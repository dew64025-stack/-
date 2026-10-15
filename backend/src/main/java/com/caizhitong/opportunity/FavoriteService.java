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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;

@Service
public class FavoriteService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final OpportunityService opportunities;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final JsonMapper mapper;
    public FavoriteService(JdbcTemplate jdbc, CurrentActor current, OpportunityService opportunities, SnowflakeIds ids,
                           Clock clock, CursorCodec cursors, SensitiveDataCrypto crypto, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.opportunities = opportunities;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    /** 收藏幂等；目标必须真实可见。当前实现 opportunity 可见性策略，其余类型明确拒绝（待 Task7/9/11 接入）。 */
    @BusinessWrite
    public OpportunityViews.Favorited put(String targetType, String targetId) {
        String type = OpportunityInputs.targetType(targetType, OpportunityInputs.FAVORITE_TARGETS);
        long target = id(targetId);
        Actor actor = current.require();
        if (type.equals("opportunity")) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM opportunities WHERE id=? AND campus_id=? AND status IN ('published','expired') AND deleted_at IS NULL",
                    Integer.class, target, actor.campusId()) != 1) throw missing();
        } else {
            throw new com.caizhitong.common.BusinessException(400, "TARGET_NOT_CONNECTED", "该收藏目标类型尚未接入，敬请期待");
        }
        LocalDateTime now = now();
        jdbc.update("INSERT INTO favorites(id,user_id,target_type,target_id,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                ids.nextId(), actor.userId(), type, target, now);
        return new OpportunityViews.Favorited(true);
    }

    @BusinessWrite
    public OpportunityViews.Favorited delete(String targetType, String targetId) {
        String type = OpportunityInputs.targetType(targetType, OpportunityInputs.FAVORITE_TARGETS);
        long target = id(targetId);
        Actor actor = current.require();
        jdbc.update("DELETE FROM favorites WHERE user_id=? AND target_type=? AND target_id=?", actor.userId(), type, target);
        return new OpportunityViews.Favorited(false);
    }

    /** 分页收藏；目标已下架只返回不可用标识。 */
    public OpportunityViews.FavoritePage list(String targetType, String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        if (targetType != null && !OpportunityInputs.FAVORITE_TARGETS.contains(targetType)) throw invalid();
        String scopeToken = "favorites:" + actor.campusId() + ":" + actor.userId() + ":"
                + java.util.HexFormat.of().formatHex(crypto.searchHash("favorites-list", mapper.writeValueAsString(List.of(targetType))));
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scopeToken);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        StringBuilder sql = new StringBuilder("SELECT id,target_type,target_id,created_at FROM favorites WHERE user_id=? AND id<?");
        List<Object> args = new java.util.ArrayList<>(List.of(actor.userId(), before));
        if (targetType != null) { sql.append(" AND target_type=?"); args.add(targetType); }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1);
        var rows = jdbc.query(sql.toString(), (r, i) -> new Object[] { r.getLong("id"), r.getString("target_type"), r.getLong("target_id"),
                r.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC) }, args.toArray());
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(row -> {
            long rowId = (Long) row[0];
            String type = (String) row[1];
            long target = (Long) row[2];
            Instant createdAt = (Instant) row[3];
            OpportunityViews.Opportunity available = type.equals("opportunity") ? opportunities.available(target, actor.campusId()) : null;
            return new OpportunityViews.FavoriteItem(Long.toString(rowId), type, Long.toString(target), createdAt, available != null, available);
        }).toList();
        String next = null;
        if (more && !items.isEmpty()) {
            next = cursors.encode(new CursorPayload(scopeToken, Map.of("id", items.getLast().id())));
        }
        return new OpportunityViews.FavoritePage(items, next, more);
    }

    public boolean isFavorited(Actor actor, long opportunityId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM favorites WHERE user_id=? AND target_type='opportunity' AND target_id=?",
                Integer.class, actor.userId(), opportunityId) > 0;
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private com.caizhitong.common.BusinessException missing() { return new com.caizhitong.common.BusinessException(404, "NOT_FOUND", "未找到该机会"); }
}
