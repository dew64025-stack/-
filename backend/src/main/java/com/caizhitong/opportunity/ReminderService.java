package com.caizhitong.opportunity;

import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;

@Service
public class ReminderService {
    private static final Set<String> STATUSES = Set.of("pending", "sent", "cancelled");
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final JsonMapper mapper;
    public ReminderService(JdbcTemplate jdbc, CurrentActor current, SnowflakeIds ids, Clock clock, CursorCodec cursors,
                           SensitiveDataCrypto crypto, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    /** 提醒不得早于当前时间；目标先验证校园与可见性/本人归属。opportunity/material 已实现，borrow/reservation 待 Task7 接入。 */
    @BusinessWrite
    public OpportunityViews.Reminder create(String targetType, String targetId, Instant remindAt) {
        String type = OpportunityInputs.targetType(targetType, OpportunityInputs.REMINDER_TARGETS);
        long target = id(targetId);
        Actor actor = current.require();
        if (remindAt == null || !remindAt.isAfter(clock.instant())) throw invalid();
        boolean visible = switch (type) {
            case "opportunity" -> jdbc.queryForObject("SELECT COUNT(*) FROM opportunities WHERE id=? AND campus_id=? AND status IN ('published','expired') AND deleted_at IS NULL",
                    Integer.class, target, actor.campusId()) == 1;
            case "material" -> jdbc.queryForObject("SELECT COUNT(*) FROM material_files WHERE id=? AND user_id=? AND campus_id=? AND status='active' AND deleted_at IS NULL",
                    Integer.class, target, actor.userId(), actor.campusId()) == 1;
            default -> throw new com.caizhitong.common.BusinessException(400, "TARGET_NOT_CONNECTED", "该提醒目标类型尚未接入，敬请期待");
        };
        if (!visible) throw new com.caizhitong.common.BusinessException(404, "NOT_FOUND", "提醒目标不可见");
        LocalDateTime now = now();
        long reminder = ids.nextId();
        jdbc.update("INSERT INTO reminders(id,user_id,target_type,target_id,remind_at,status,created_at) VALUES(?,?,?,?,?,'pending',?)",
                reminder, actor.userId(), type, target, OpportunityService.utc(remindAt), now);
        return new OpportunityViews.Reminder(Long.toString(reminder), type, targetId, remindAt, "pending");
    }

    /** 仅 pending→cancelled，不伪造 sent。 */
    @BusinessWrite
    public OpportunityViews.Cancelled cancel(String reminderId) {
        long value = id(reminderId);
        Actor actor = current.require();
        var rows = jdbc.queryForList("SELECT status FROM reminders WHERE id=? AND user_id=? FOR UPDATE", String.class, value, actor.userId());
        if (rows.isEmpty()) throw new com.caizhitong.common.BusinessException(404, "NOT_FOUND", "未找到该提醒");
        if (!"pending".equals(rows.getFirst())) throw OpportunityInputs.stateConflict("该提醒已不可取消");
        jdbc.update("UPDATE reminders SET status='cancelled' WHERE id=?", value);
        return new OpportunityViews.Cancelled(true);
    }

    public OpportunityViews.ReminderPage list(String status, String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        if (status != null && !STATUSES.contains(status)) throw invalid();
        String scopeToken = "reminders:" + actor.campusId() + ":" + actor.userId() + ":"
                + java.util.HexFormat.of().formatHex(crypto.searchHash("reminders-list", mapper.writeValueAsString(List.of(status))));
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scopeToken);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        StringBuilder sql = new StringBuilder("SELECT id,target_type,target_id,remind_at,status FROM reminders WHERE user_id=? AND id<?");
        List<Object> args = new ArrayList<>(List.of(actor.userId(), before));
        if (status != null) { sql.append(" AND status=?"); args.add(status); }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1);
        var rows = jdbc.query(sql.toString(), (r, i) -> new OpportunityViews.Reminder(r.getString("id"), r.getString("target_type"),
                r.getString("target_id"), r.getObject("remind_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), r.getString("status")), args.toArray());
        boolean more = rows.size() > size;
        var page = rows.stream().limit(size).toList();
        String next = null;
        if (more && !page.isEmpty()) {
            next = cursors.encode(new CursorPayload(scopeToken, Map.of("id", page.getLast().id())));
        }
        return new OpportunityViews.ReminderPage(page, next, more);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
