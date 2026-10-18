package com.caizhitong.me;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.common.search.SearchItem;
import com.caizhitong.common.todos.TodoItem;
import com.caizhitong.identity.IdentityService;
import com.caizhitong.issue.IssueReadBridge;
import com.caizhitong.material.files.MaterialProofBridge;
import com.caizhitong.messaging.MessagingService;
import com.caizhitong.mutualaid.MutualAidReadBridge;
import com.caizhitong.opportunity.OpportunityReadBridge;
import com.caizhitong.opportunity.ReminderReadBridge;
import com.caizhitong.resource.ResourceReadBridge;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import com.caizhitong.security.SessionKind;
import com.caizhitong.team.TeamReadBridge;
import com.caizhitong.workorder.WorkOrderReadBridge;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;

/** 个人中心、成长档案与信用事件；不读取匿名社区身份，成长记录只能由本人主动提交。 */
@Service
public class MeService {
    private static final Comparator<TodoItem> TODO_ORDER = (a, b) -> {
        int due;
        if (a.dueAt() == null && b.dueAt() == null) due = 0;
        else if (a.dueAt() == null) due = 1;
        else if (b.dueAt() == null) due = -1;
        else due = a.dueAt().compareTo(b.dueAt());
        if (due != 0) return due;
        int type = a.type().compareTo(b.type());
        if (type != 0) return type;
        return Long.compare(id(a.targetId()), id(b.targetId()));
    };

    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final IdentityService identity;
    private final MessagingService messaging;
    private final ReminderReadBridge reminders;
    private final ResourceReadBridge resources;
    private final WorkOrderReadBridge workOrders;
    private final MutualAidReadBridge mutualAid;
    private final TeamReadBridge teams;
    private final OpportunityReadBridge opportunities;
    private final IssueReadBridge issues;
    private final MaterialProofBridge materials;

    public MeService(JdbcTemplate jdbc, CurrentActor current, SnowflakeIds ids, Clock clock, CursorCodec cursors,
                     IdentityService identity, MessagingService messaging, ReminderReadBridge reminders,
                     ResourceReadBridge resources, WorkOrderReadBridge workOrders, MutualAidReadBridge mutualAid,
                     TeamReadBridge teams, OpportunityReadBridge opportunities, IssueReadBridge issues,
                     MaterialProofBridge materials) {
        this.jdbc = jdbc;
        this.current = current;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.identity = identity;
        this.messaging = messaging;
        this.reminders = reminders;
        this.resources = resources;
        this.workOrders = workOrders;
        this.mutualAid = mutualAid;
        this.teams = teams;
        this.opportunities = opportunities;
        this.issues = issues;
        this.materials = materials;
    }

    public MeViews.Dashboard dashboard() {
        Actor actor = current.require();
        var user = identity.userResponse(identity.me());
        long todo = reminders.pendingCount(actor.userId()) + resources.userTodos(actor.userId(), actor.campusId())
                + workOrders.userTodos(actor.userId(), actor.campusId())
                + mutualAid.pendingBorrowCount(actor.userId(), actor.campusId())
                + teams.pendingApplicationCount(actor.userId(), actor.campusId());
        return new MeViews.Dashboard(user, todo, messaging.unreadTotal(actor.userId(), actor.campusId()),
                mutualAid.userPosts(actor.userId(), actor.campusId()),
                resources.userReservations(actor.userId(), actor.campusId()),
                workOrders.userOrders(actor.userId(), actor.campusId()));
    }

    public MeViews.TodoPage todos(String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        String scope = "todos:" + actor.campusId() + ":" + actor.userId();
        List<TodoItem> all = new ArrayList<>();
        all.addAll(reminders.pendingTodos(actor.userId()));
        all.addAll(resources.pendingTodos(actor.userId(), actor.campusId()));
        all.addAll(workOrders.pendingTodos(actor.userId(), actor.campusId()));
        all.addAll(mutualAid.pendingBorrowTodos(actor.userId(), actor.campusId()));
        all.addAll(teams.pendingApplicationTodos(actor.userId(), actor.campusId()));
        all.sort(TODO_ORDER);
        TodoItem after = null;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scope);
            if (!keys.sortKeys().keySet().equals(Set.of("due", "type", "target"))) throw invalid();
            String due = keys.sortKeys().get("due");
            String type = keys.sortKeys().get("type");
            String target = keys.sortKeys().get("target");
            if (!due.isEmpty() && !due.matches("[0-9]{1,19}")) throw invalid();
            if (!type.matches("[a-z_]{1,32}")) throw invalid();
            id(target);
            after = new TodoItem(type, target, null, due.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(due)),
                    null, null);
        }
        int start = 0;
        if (after != null) {
            while (start < all.size() && TODO_ORDER.compare(all.get(start), after) <= 0) start++;
        }
        int end = Math.min(start + size, all.size());
        List<TodoItem> pageRows = all.subList(start, end);
        boolean more = end < all.size();
        List<MeViews.Todo> items = pageRows.stream()
                .map(item -> new MeViews.Todo(item.type(), item.targetId(), item.title(), item.dueAt(),
                        item.status(), item.action()))
                .toList();
        String next = null;
        if (more && !pageRows.isEmpty()) {
            TodoItem last = pageRows.getLast();
            next = cursors.encode(new CursorPayload(scope, Map.of(
                    "due", last.dueAt() == null ? "" : Long.toString(last.dueAt().toEpochMilli()),
                    "type", last.type(),
                    "target", last.targetId())));
        }
        return new MeViews.TodoPage(items, next, more);
    }

    public MeViews.HistoryPage history(String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        String scope = "history:" + actor.campusId() + ":" + actor.userId();
        Instant beforeViewed = null;
        long beforeId = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scope);
            if (!keys.sortKeys().keySet().equals(Set.of("viewed", "id"))) throw invalid();
            try {
                beforeViewed = Instant.parse(keys.sortKeys().get("viewed"));
            } catch (RuntimeException badCursor) {
                throw invalid();
            }
            beforeId = id(keys.sortKeys().get("id"));
        }
        // Re-opening an item refreshes viewed_at, so recency and the cursor must both follow it.
        String sql = "SELECT id,target_type,target_id,title,summary,viewed_at FROM browse_history"
                + " WHERE user_id=? AND campus_id=?"
                + (beforeViewed == null ? "" : " AND (viewed_at<? OR (viewed_at=? AND id<?))")
                + " ORDER BY viewed_at DESC,id DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(actor.userId());
        args.add(actor.campusId());
        if (beforeViewed != null) {
            Timestamp viewed = Timestamp.from(beforeViewed);
            args.add(viewed);
            args.add(viewed);
            args.add(beforeId);
        }
        args.add(size + 1);
        var rows = jdbc.query(sql,
                (rs, i) -> new MeViews.HistoryItem(Long.toString(rs.getLong("id")), rs.getString("target_type"),
                        Long.toString(rs.getLong("target_id")), rs.getString("title"), rs.getString("summary"),
                        rs.getObject("viewed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                args.toArray());
        boolean more = rows.size() > size;
        List<MeViews.HistoryItem> items = rows.stream().limit(size).toList();
        String next = null;
        if (more && !items.isEmpty()) {
            MeViews.HistoryItem last = items.getLast();
            next = cursors.encode(new CursorPayload(scope, Map.of(
                    "viewed", last.viewedAt().toString(), "id", last.id())));
        }
        return new MeViews.HistoryPage(items, next, more);
    }

    @BusinessWrite
    public MeViews.Cleared clearHistory() {
        Actor actor = current.require();
        jdbc.update("DELETE FROM browse_history WHERE user_id=? AND campus_id=?", actor.userId(), actor.campusId());
        return new MeViews.Cleared(true);
    }

    /** Best-effort detail-view recording; callers must ignore failures. */
    public void recordView(Actor actor, String targetType, long targetId) {
        if (actor.kind() != SessionKind.STUDENT) return;
        var enabled = jdbc.queryForList("SELECT history_enabled FROM privacy_settings WHERE user_id=?",
                Boolean.class, actor.userId());
        if (enabled.isEmpty() || !Boolean.TRUE.equals(enabled.getFirst())) return;
        Optional<SearchItem> found = switch (targetType) {
            case "opportunity" -> opportunities.visible(actor.campusId(), targetId);
            case "resource" -> resources.visible(actor.campusId(), targetId);
            case "issue" -> issues.visible(actor.campusId(), targetId);
            case "mutual_aid" -> mutualAid.visible(actor.campusId(), targetId);
            case "team" -> teams.visible(actor.campusId(), targetId);
            default -> Optional.empty();
        };
        if (found.isEmpty()) return;
        SearchItem item = found.get();
        String title = item.title() == null ? "" : truncate(item.title(), 256);
        String summary = item.summary() == null ? null : truncate(item.summary(), 512);
        LocalDateTime now = now();
        jdbc.update("INSERT INTO browse_history(id,campus_id,user_id,target_type,target_id,title,summary,viewed_at)"
                        + " VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE campus_id=?,title=?,summary=?,viewed_at=?",
                ids.nextId(), actor.campusId(), actor.userId(), targetType, targetId, title, summary, now,
                actor.campusId(), title, summary, now);
    }

    public MeViews.GrowthPage growthRecords(String type, String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        if (type != null && !MeInputs.GROWTH_TYPES.contains(type)) throw invalid();
        String scope = "growth:" + actor.campusId() + ":" + actor.userId() + ":" + (type == null ? "" : type);
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scope);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        String sql = "SELECT id,type,title,description,proof_file_id,confirmed,occurred_at,version,created_at,updated_at"
                + " FROM growth_records WHERE user_id=? AND deleted_at IS NULL AND id<?"
                + (type == null ? "" : " AND type=?") + " ORDER BY id DESC LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(actor.userId(), before));
        if (type != null) args.add(type);
        args.add(size + 1);
        var rows = jdbc.query(sql, (rs, i) -> growthRow(rs), args.toArray());
        boolean more = rows.size() > size;
        List<MeViews.GrowthRecord> items = rows.stream().limit(size).toList();
        String next = null;
        if (more && !items.isEmpty()) {
            next = cursors.encode(new CursorPayload(scope, Map.of("id", items.getLast().id())));
        }
        return new MeViews.GrowthPage(items, next, more);
    }

    @BusinessWrite(student = true)
    public MeViews.GrowthRecord createGrowth(MeInputs.GrowthCreate input) {
        Actor actor = current.requireStudent();
        String type = MeInputs.growthType(input.type());
        String title = com.caizhitong.identity.IdentityInputs.text(input.title(), 256, false);
        String description = MeInputs.optionalText(input.description(), 1024);
        Long proof = MeInputs.optionalId(input.proofFileId());
        requireProof(actor, proof);
        Instant occurred = MeInputs.instant(input.occurredAt());
        boolean confirmed = MeInputs.bool(input.confirmed());
        long record = ids.nextId();
        LocalDateTime now = now();
        jdbc.update("INSERT INTO growth_records(id,user_id,type,title,description,proof_file_id,confirmed,occurred_at,"
                        + "version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,0,?,?)",
                record, actor.userId(), type, title, description, proof, confirmed,
                occurred == null ? null : LocalDateTime.ofInstant(occurred, ZoneOffset.UTC), now, now);
        return growthRecord(record, actor.userId());
    }

    @BusinessWrite
    public MeViews.GrowthRecord updateGrowth(String growthId, MeInputs.GrowthUpdate input) {
        Actor actor = current.require();
        long record = id(growthId);
        int version = input.version().asInt();
        var rows = jdbc.query("SELECT user_id,type,title,description,proof_file_id,confirmed,occurred_at,version"
                        + " FROM growth_records WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                (rs, i) -> new Object[]{rs.getLong("user_id"), rs.getString("type"), rs.getString("title"),
                        rs.getString("description"), rs.getObject("proof_file_id", Long.class),
                        rs.getBoolean("confirmed"), rs.getObject("occurred_at", LocalDateTime.class),
                        rs.getInt("version")}, record);
        if (rows.isEmpty() || ((Long) rows.getFirst()[0]) != actor.userId()) throw missing();
        Object[] row = rows.getFirst();
        if (((Integer) row[7]) != version) throw MeInputs.conflict();
        String type = input.type() == null ? (String) row[1] : MeInputs.growthType(input.type());
        String title = input.title() == null ? (String) row[2]
                : com.caizhitong.identity.IdentityInputs.text(input.title(), 256, false);
        String description = input.description() == null ? (String) row[3] : MeInputs.optionalText(input.description(), 1024);
        Long proof = input.proofFileId() == null ? (Long) row[4] : MeInputs.optionalId(input.proofFileId());
        requireProof(actor, proof);
        boolean confirmed = input.confirmed() == null ? (Boolean) row[5] : MeInputs.bool(input.confirmed());
        Instant occurred = input.occurredAt() == null ? toInstant((LocalDateTime) row[6])
                : MeInputs.instant(input.occurredAt());
        int changed = jdbc.update("UPDATE growth_records SET type=?,title=?,description=?,proof_file_id=?,confirmed=?,"
                        + "occurred_at=?,version=version+1,updated_at=? WHERE id=? AND version=?",
                type, title, description, proof, confirmed,
                occurred == null ? null : LocalDateTime.ofInstant(occurred, ZoneOffset.UTC), now(), record, version);
        if (changed != 1) throw MeInputs.conflict();
        return growthRecord(record, actor.userId());
    }

    @BusinessWrite
    public MeViews.Cleared deleteGrowth(String growthId, int version) {
        Actor actor = current.require();
        MeInputs.nonNegativeVersion(version);
        long record = id(growthId);
        var owners = jdbc.queryForList("SELECT user_id FROM growth_records WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                Long.class, record);
        if (owners.isEmpty() || owners.getFirst() != actor.userId()) throw missing();
        int changed = jdbc.update("UPDATE growth_records SET deleted_at=?,version=version+1,updated_at=?"
                + " WHERE id=? AND version=?", now(), now(), record, version);
        if (changed != 1) throw MeInputs.conflict();
        return new MeViews.Cleared(true);
    }

    public MeViews.CreditPage creditEvents(String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        String scope = "credit:" + actor.campusId() + ":" + actor.userId();
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scope);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        var rows = jdbc.query("SELECT id,event_type,reason,points,related_type,related_id,created_at FROM credit_events"
                        + " WHERE user_id=? AND campus_id=? AND id<? ORDER BY id DESC LIMIT ?",
                (rs, i) -> new MeViews.CreditEvent(Long.toString(rs.getLong("id")), rs.getString("event_type"),
                        rs.getString("reason"), rs.getInt("points"), rs.getString("related_type"),
                        rs.getObject("related_id", Long.class) == null ? null : Long.toString(rs.getLong("related_id")),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                actor.userId(), actor.campusId(), before, size + 1);
        boolean more = rows.size() > size;
        List<MeViews.CreditEvent> items = rows.stream().limit(size).toList();
        String next = null;
        if (more && !items.isEmpty()) {
            next = cursors.encode(new CursorPayload(scope, Map.of("id", items.getLast().id())));
        }
        return new MeViews.CreditPage(items, next, more);
    }

    @BusinessWrite
    public MeViews.Dispute dispute(String eventId, MeInputs.CreditDispute input) {
        Actor actor = current.require();
        long event = id(eventId);
        String reason = com.caizhitong.identity.IdentityInputs.text(input.reason(), 512, false);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM credit_events WHERE id=? AND user_id=? AND campus_id=?",
                Integer.class, event, actor.userId(), actor.campusId()) != 1) throw missing();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM credit_disputes WHERE credit_event_id=? AND user_id=? AND status='pending'",
                Integer.class, event, actor.userId()) > 0) {
            throw new BusinessException(409, "STATE_CONFLICT", "该信用事件已有待处理的纠错申请");
        }
        long dispute = ids.nextId();
        try {
            jdbc.update("INSERT INTO credit_disputes(id,campus_id,user_id,credit_event_id,reason,status,created_at)"
                            + " VALUES(?,?,?,?,?,'pending',?)",
                    dispute, actor.campusId(), actor.userId(), event, reason, now());
        } catch (DuplicateKeyException concurrent) {
            throw new BusinessException(409, "STATE_CONFLICT", "该信用事件已有待处理的纠错申请");
        }
        return new MeViews.Dispute(Long.toString(dispute), "pending");
    }

    private void requireProof(Actor actor, Long proof) {
        if (proof != null && !materials.usable(actor.campusId(), actor.userId(), proof)) {
            throw new BusinessException(404, "NOT_FOUND", "未找到证明材料");
        }
    }

    private MeViews.GrowthRecord growthRecord(long id, long user) {
        var rows = jdbc.query("SELECT id,type,title,description,proof_file_id,confirmed,occurred_at,version,created_at,updated_at"
                        + " FROM growth_records WHERE id=? AND user_id=? AND deleted_at IS NULL",
                (rs, i) -> growthRow(rs), id, user);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private MeViews.GrowthRecord growthRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long proof = rs.getObject("proof_file_id", Long.class);
        return new MeViews.GrowthRecord(Long.toString(rs.getLong("id")), rs.getString("type"), rs.getString("title"),
                rs.getString("description"), proof == null ? null : Long.toString(proof), rs.getBoolean("confirmed"),
                toInstant(rs.getObject("occurred_at", LocalDateTime.class)), rs.getInt("version"),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private BusinessException missing() {
        return new BusinessException(404, "NOT_FOUND", "未找到该记录");
    }
}
