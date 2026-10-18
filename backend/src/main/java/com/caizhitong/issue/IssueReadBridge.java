package com.caizhitong.issue;

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

/** Read-only public issue projection. Private issues never leave the issue module. */
@Component
public class IssueReadBridge {
    private static final String BASE = """
            SELECT i.id,i.title,i.category,i.description,i.vote_count,i.source_type,i.updated_at,
                   (SELECT l.name FROM campus_issue_locations l WHERE l.issue_id=i.id ORDER BY l.id LIMIT 1) location_name
            FROM campus_issues i
            WHERE i.campus_id=? AND i.visibility='public'
              AND i.status IN ('published','processing','completed') AND i.deleted_at IS NULL""";

    private final JdbcTemplate jdbc;

    public IssueReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<HomeCard> hot(long campus, int limit) {
        return jdbc.query(BASE + " ORDER BY i.vote_count DESC,i.id DESC LIMIT ?",
                (rs, i) -> card(rs), campus, limit);
    }

    public List<HomeCard> nearby(long campus, long regionId, int limit) {
        return jdbc.query(BASE + " AND i.region_id=? ORDER BY i.id DESC LIMIT ?",
                (rs, i) -> card(rs), campus, regionId, limit);
    }

    public List<SearchItem> search(long campus, String query, long beforeId, int limit) {
        String pattern = SearchText.pattern(query);
        return jdbc.query(BASE + " AND i.id<? AND (i.title LIKE ? ESCAPE '!' OR i.description LIKE ? ESCAPE '!')"
                        + " ORDER BY i.id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, beforeId, pattern, pattern, limit);
    }

    public List<SearchItem> context(long campus, int limit) {
        return jdbc.query(BASE + " ORDER BY i.vote_count DESC,i.id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, limit);
    }

    public Optional<SearchItem> visible(long campus, long id) {
        return jdbc.query(BASE + " AND i.id=?", (rs, i) -> item(rs), campus, id).stream().findFirst();
    }

    public long count(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM campus_issues WHERE campus_id=? AND deleted_at IS NULL"
                + " AND created_at>=? AND created_at<?", Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    /** Own issues, including non-public visibility; never other users' submissions. */
    public List<Map<String, Object>> exportOwn(long userId, long campus) {
        return jdbc.query("SELECT id,title,category,description,status,visibility,created_at,updated_at FROM campus_issues"
                        + " WHERE user_id=? AND campus_id=? AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("title", rs.getString("title"));
                    row.put("category", rs.getString("category"));
                    row.put("description", rs.getString("description"));
                    row.put("status", rs.getString("status"));
                    row.put("visibility", rs.getString("visibility"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    row.put("updatedAt", instantText(rs, "updated_at"));
                    return row;
                }, userId, campus);
    }

    private static String instantText(ResultSet rs, String column) throws SQLException {
        Instant value = instant(rs, column);
        return value == null ? null : value.toString();
    }

    private HomeCard card(ResultSet rs) throws SQLException {
        return new HomeCard("issue", Long.toString(rs.getLong("id")), rs.getString("title"),
                rs.getString("description"), rs.getString("source_type"), SearchText.sourceName(rs.getString("source_type")),
                instant(rs, "updated_at"), false, null, null, null, rs.getString("category"), rs.getString("location_name"));
    }

    private SearchItem item(ResultSet rs) throws SQLException {
        return new SearchItem("issue", Long.toString(rs.getLong("id")), rs.getString("title"),
                rs.getString("description"), rs.getString("source_type"), SearchText.sourceName(rs.getString("source_type")),
                instant(rs, "updated_at"), false, null, null);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
