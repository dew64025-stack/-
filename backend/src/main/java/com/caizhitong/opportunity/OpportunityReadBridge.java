package com.caizhitong.opportunity;

import com.caizhitong.common.search.HomeCard;
import com.caizhitong.common.search.SearchItem;
import com.caizhitong.common.search.SearchText;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only, desensitized view of published opportunities for home/search/AI. */
@Component
public class OpportunityReadBridge {
    private static final String BASE = """
            SELECT id,title,category,organizer,summary,deadline,source_type,updated_at,is_stale,official_reference,external_id
            FROM opportunities
            WHERE campus_id=? AND status='published' AND deleted_at IS NULL""";

    private final JdbcTemplate jdbc;

    public OpportunityReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<HomeCard> deadlines(long campus, Instant now, int limit) {
        return jdbc.query(BASE + " AND deadline IS NOT NULL AND deadline>=? ORDER BY deadline ASC,id ASC LIMIT ?",
                (rs, i) -> card(rs), campus, Timestamp.from(now), limit);
    }

    public List<HomeCard> recommendations(long campus, int limit) {
        return jdbc.query(BASE + " ORDER BY created_at DESC,id DESC LIMIT ?", (rs, i) -> card(rs), campus, limit);
    }

    public List<SearchItem> search(long campus, String query, long beforeId, int limit) {
        String pattern = SearchText.pattern(query);
        return jdbc.query(BASE + " AND id<? AND (title LIKE ? ESCAPE '!' OR organizer LIKE ? ESCAPE '!' OR summary LIKE ? ESCAPE '!')"
                        + " ORDER BY id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, beforeId, pattern, pattern, pattern, limit);
    }

    public List<SearchItem> context(long campus, int limit) {
        return jdbc.query(BASE + " AND deadline IS NOT NULL AND deadline>=CURRENT_TIMESTAMP ORDER BY deadline ASC,id ASC LIMIT ?",
                (rs, i) -> item(rs), campus, limit);
    }

    public Optional<SearchItem> visible(long campus, long id) {
        return jdbc.query(BASE + " AND id=?", (rs, i) -> item(rs), campus, id).stream().findFirst();
    }

    public long count(long campus, Instant from, Instant to) {
        return count("opportunities", campus, from, to);
    }

    /** Personal export: own submissions to same-campus opportunities, without other users' data. */
    public List<Map<String, Object>> exportSubmissions(long user, long campus) {
        return jdbc.query("SELECT s.id,s.opportunity_id,o.title,s.status,s.submitted_at FROM user_opportunity_submissions s"
                        + " JOIN opportunities o ON o.id=s.opportunity_id"
                        + " WHERE s.user_id=? AND o.campus_id=? ORDER BY s.id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("opportunityId", Long.toString(rs.getLong("opportunity_id")));
                    row.put("opportunityTitle", rs.getString("title"));
                    row.put("status", rs.getString("status"));
                    row.put("submittedAt", instantText(rs, "submitted_at"));
                    return row;
                }, user, campus);
    }

    public List<Map<String, Object>> exportFavorites(long user) {
        return jdbc.query("SELECT id,target_type,target_id,created_at FROM favorites WHERE user_id=? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("targetType", rs.getString("target_type"));
                    row.put("targetId", Long.toString(rs.getLong("target_id")));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, user);
    }

    public List<Map<String, Object>> exportReminders(long user) {
        return jdbc.query("SELECT id,target_type,target_id,remind_at,status FROM reminders WHERE user_id=? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("targetType", rs.getString("target_type"));
                    row.put("targetId", Long.toString(rs.getLong("target_id")));
                    row.put("remindAt", instantText(rs, "remind_at"));
                    row.put("status", rs.getString("status"));
                    return row;
                }, user);
    }

    private long count(String table, long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE campus_id=? AND deleted_at IS NULL AND created_at>=? AND created_at<?",
                Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    private HomeCard card(ResultSet rs) throws SQLException {
        return new HomeCard("opportunity", Long.toString(rs.getLong("id")), rs.getString("title"),
                rs.getString("summary"), rs.getString("source_type"), SearchText.sourceName(rs.getString("source_type")),
                instant(rs, "updated_at"), rs.getBoolean("is_stale"), rs.getString("official_reference"),
                rs.getString("external_id"), instant(rs, "deadline"), rs.getString("category"), null);
    }

    private SearchItem item(ResultSet rs) throws SQLException {
        return new SearchItem("opportunity", Long.toString(rs.getLong("id")), rs.getString("title"),
                rs.getString("summary"), rs.getString("source_type"), SearchText.sourceName(rs.getString("source_type")),
                instant(rs, "updated_at"), rs.getBoolean("is_stale"), rs.getString("official_reference"),
                rs.getString("external_id"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String instantText(ResultSet rs, String column) throws SQLException {
        Instant value = instant(rs, column);
        return value == null ? null : value.toString();
    }
}
