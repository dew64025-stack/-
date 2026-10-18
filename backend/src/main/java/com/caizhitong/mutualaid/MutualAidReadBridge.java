package com.caizhitong.mutualaid;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.messaging.ConversationContext;
import com.caizhitong.common.search.HomeCard;
import com.caizhitong.common.search.SearchItem;
import com.caizhitong.common.search.SearchText;
import com.caizhitong.common.todos.TodoItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only published-post projection plus participant resolution for conversations. */
@Component
public class MutualAidReadBridge {
    private static final String BASE = """
            SELECT id,title,content,category,exchange_area,source_type,updated_at
            FROM mutual_aid_posts
            WHERE campus_id=? AND status='published' AND deleted_at IS NULL""";

    private final JdbcTemplate jdbc;

    public MutualAidReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<HomeCard> latest(long campus, int limit) {
        return jdbc.query(BASE + " ORDER BY id DESC LIMIT ?", (rs, i) -> card(rs), campus, limit);
    }

    public List<HomeCard> nearby(long campus, long regionId, int limit) {
        List<String> regions = jdbc.queryForList("SELECT name FROM campus_regions"
                + " WHERE id=? AND campus_id=? AND status='active' AND deleted_at IS NULL", String.class, regionId, campus);
        if (regions.isEmpty()) return List.of();
        return jdbc.query(BASE + " AND exchange_area LIKE ? ESCAPE '!' ORDER BY id DESC LIMIT ?",
                (rs, i) -> card(rs), campus, SearchText.pattern(regions.getFirst()), limit);
    }

    public List<SearchItem> search(long campus, String query, long beforeId, int limit) {
        String pattern = SearchText.pattern(query);
        return jdbc.query(BASE + " AND id<? AND (title LIKE ? ESCAPE '!' OR content LIKE ? ESCAPE '!')"
                        + " ORDER BY id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, beforeId, pattern, pattern, limit);
    }

    public List<SearchItem> context(long campus, int limit) {
        return jdbc.query(BASE + " ORDER BY updated_at DESC,id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, limit);
    }

    public Optional<SearchItem> visible(long campus, long id) {
        return jdbc.query(BASE + " AND id=?", (rs, i) -> item(rs), campus, id).stream().findFirst();
    }

    public long userPosts(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM mutual_aid_posts WHERE user_id=? AND campus_id=?"
                + " AND deleted_at IS NULL", Long.class, userId, campus);
        return value == null ? 0 : value;
    }

    /** Open borrow requests where the actor is the borrower or the post owner. */
    public List<TodoItem> pendingBorrowTodos(long userId, long campus) {
        return jdbc.query("SELECT b.id,b.start_time,b.status,p.title,p.user_id FROM borrow_requests b"
                        + " JOIN mutual_aid_posts p ON p.id=b.post_id"
                        + " WHERE p.campus_id=? AND (b.borrower_id=? OR p.user_id=?)"
                        + " AND b.status IN ('pending','confirmed','handed_over') AND p.deleted_at IS NULL"
                        + " ORDER BY b.id",
                (rs, i) -> {
                    long owner = rs.getLong("user_id");
                    String action = owner == userId ? "处理借用申请" : "查看借用进度";
                    var start = rs.getObject("start_time", LocalDateTime.class);
                    return new TodoItem("borrow", Long.toString(rs.getLong("id")), rs.getString("title"),
                            start == null ? null : start.toInstant(ZoneOffset.UTC), rs.getString("status"), action);
                }, campus, userId, userId);
    }

    public long pendingBorrowCount(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM borrow_requests b JOIN mutual_aid_posts p ON p.id=b.post_id"
                + " WHERE p.campus_id=? AND (b.borrower_id=? OR p.user_id=?)"
                + " AND b.status IN ('pending','confirmed','handed_over') AND p.deleted_at IS NULL",
                Long.class, campus, userId, userId);
        return value == null ? 0 : value;
    }

    public long count(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM mutual_aid_posts WHERE campus_id=? AND deleted_at IS NULL"
                + " AND created_at>=? AND created_at<?", Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    /** Own posts including unpublished/deleted states; internal image object keys are excluded. */
    public List<Map<String, Object>> exportPosts(long userId, long campus) {
        return jdbc.query("SELECT id,category,title,content,price,exchange_area,status,created_at,updated_at,deleted_at"
                        + " FROM mutual_aid_posts WHERE user_id=? AND campus_id=? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("category", rs.getString("category"));
                    row.put("title", rs.getString("title"));
                    row.put("content", rs.getString("content"));
                    row.put("price", rs.getBigDecimal("price"));
                    row.put("exchangeArea", rs.getString("exchange_area"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    row.put("updatedAt", instantText(rs, "updated_at"));
                    row.put("deletedAt", instantText(rs, "deleted_at"));
                    return row;
                }, userId, campus);
    }

    /** Borrow requests where the user is borrower or post owner; no counterpart contact data. */
    public List<Map<String, Object>> exportBorrows(long userId, long campus) {
        return jdbc.query("SELECT b.id,p.title,b.borrower_id,b.start_time,b.end_time,b.status,b.created_at"
                        + " FROM borrow_requests b JOIN mutual_aid_posts p ON p.id=b.post_id"
                        + " WHERE p.campus_id=? AND (b.borrower_id=? OR p.user_id=?) ORDER BY b.id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("postTitle", rs.getString("title"));
                    row.put("role", rs.getLong("borrower_id") == userId ? "borrower" : "owner");
                    row.put("startTime", instantText(rs, "start_time"));
                    row.put("endTime", instantText(rs, "end_time"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, campus, userId, userId);
    }

    private static String instantText(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    /** Only a publishable post's owner can be contacted; the caller is rejected for self-chat. */
    public ConversationContext postContext(long postId, long campus, long actorId) {
        var rows = jdbc.query("SELECT user_id,title FROM mutual_aid_posts WHERE id=? AND campus_id=?"
                        + " AND status='published' AND deleted_at IS NULL",
                (rs, i) -> new Object[]{rs.getLong("user_id"), rs.getString("title")}, postId, campus);
        if (rows.isEmpty()) throw missing();
        long owner = ((Number) rows.getFirst()[0]).longValue();
        if (owner == actorId) throw new BusinessException(409, "STATE_CONFLICT", "不能与自己发起会话");
        return new ConversationContext("mutual_aid_post", Long.toString(postId), title((String) rows.getFirst()[1]),
                List.of(owner, actorId));
    }

    /** Both sides of an active borrow can talk; completed/returned history stays readable. */
    public ConversationContext borrowContext(long borrowId, long campus, long actorId) {
        var rows = jdbc.query("SELECT b.borrower_id,p.user_id,p.title,b.status FROM borrow_requests b"
                        + " JOIN mutual_aid_posts p ON p.id=b.post_id"
                        + " WHERE b.id=? AND p.campus_id=? AND p.deleted_at IS NULL",
                (rs, i) -> new Object[]{rs.getLong("borrower_id"), rs.getLong("user_id"), rs.getString("title"), rs.getString("status")},
                borrowId, campus);
        if (rows.isEmpty()) throw missing();
        long borrower = ((Number) rows.getFirst()[0]).longValue();
        long owner = ((Number) rows.getFirst()[1]).longValue();
        String status = (String) rows.getFirst()[3];
        if (actorId != borrower && actorId != owner) throw missing();
        if (Set.of("cancelled", "rejected").contains(status)) throw new BusinessException(409, "STATE_CONFLICT", "该借用申请已结束");
        Set<Long> participants = new LinkedHashSet<>(List.of(borrower, owner));
        return new ConversationContext("borrow_request", Long.toString(borrowId), title((String) rows.getFirst()[2]),
                List.copyOf(participants));
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "借用沟通";
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private HomeCard card(ResultSet rs) throws SQLException {
        return new HomeCard("mutual_aid", Long.toString(rs.getLong("id")), rs.getString("title"),
                rs.getString("content"), rs.getString("source_type"), SearchText.sourceName(rs.getString("source_type")),
                instant(rs), false, null, null, null, rs.getString("category"), rs.getString("exchange_area"));
    }

    private SearchItem item(ResultSet rs) throws SQLException {
        return new SearchItem("mutual_aid", Long.toString(rs.getLong("id")), rs.getString("title"),
                rs.getString("content"), rs.getString("source_type"), SearchText.sourceName(rs.getString("source_type")),
                instant(rs), false, null, null);
    }

    private static Instant instant(ResultSet rs) throws SQLException {
        LocalDateTime value = rs.getObject("updated_at", LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static BusinessException missing() {
        return new BusinessException(404, "NOT_FOUND", "未找到请求的资源");
    }
}
