package com.caizhitong.team;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.messaging.ConversationContext;
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

/** Read-only recruiting-team projection and team participant resolution. */
@Component
public class TeamReadBridge {
    private static final String BASE = """
            SELECT id,title,goal,status,updated_at
            FROM teams
            WHERE campus_id=? AND status IN ('recruiting','full') AND deleted_at IS NULL""";

    private final JdbcTemplate jdbc;

    public TeamReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchItem> search(long campus, String query, long beforeId, int limit) {
        String pattern = SearchText.pattern(query);
        return jdbc.query(BASE + " AND id<? AND (title LIKE ? ESCAPE '!' OR goal LIKE ? ESCAPE '!')"
                        + " ORDER BY id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, beforeId, pattern, pattern, limit);
    }

    public List<SearchItem> context(long campus, int limit) {
        return jdbc.query(BASE + " ORDER BY updated_at DESC,id DESC LIMIT ?", (rs, i) -> item(rs), campus, limit);
    }

    public Optional<SearchItem> visible(long campus, long id) {
        return jdbc.query(BASE + " AND id=?", (rs, i) -> item(rs), campus, id).stream().findFirst();
    }

    public long count(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE campus_id=? AND deleted_at IS NULL"
                + " AND created_at>=? AND created_at<?", Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    public List<TodoItem> pendingApplicationTodos(long userId, long campus) {
        return jdbc.query("SELECT a.id,a.status,t.title FROM team_applications a JOIN teams t ON t.id=a.team_id"
                        + " WHERE a.user_id=? AND t.campus_id=? AND a.status='pending' AND t.deleted_at IS NULL"
                        + " ORDER BY a.id",
                (rs, i) -> new TodoItem("team_application", Long.toString(rs.getLong("id")),
                        rs.getString("title"), null, rs.getString("status"), "查看组队申请"),
                userId, campus);
    }

    public long pendingApplicationCount(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM team_applications a JOIN teams t ON t.id=a.team_id"
                + " WHERE a.user_id=? AND t.campus_id=? AND a.status='pending' AND t.deleted_at IS NULL",
                Long.class, userId, campus);
        return value == null ? 0 : value;
    }

    /** Applications a team leader must review; the applicant-side feed cannot see these. */
    public List<TodoItem> pendingReviewTodos(long userId, long campus) {
        return jdbc.query("SELECT a.id,a.status,t.title FROM team_applications a"
                        + " JOIN teams t ON t.id=a.team_id WHERE t.leader_id=? AND t.campus_id=?"
                        + " AND a.status='pending' AND t.deleted_at IS NULL ORDER BY a.id",
                (rs, i) -> new TodoItem("team_application", Long.toString(rs.getLong("id")),
                        rs.getString("title"), null, rs.getString("status"), "处理入队申请"),
                userId, campus);
    }

    public long pendingReviewCount(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM team_applications a JOIN teams t ON t.id=a.team_id"
                + " WHERE t.leader_id=? AND t.campus_id=? AND a.status='pending' AND t.deleted_at IS NULL",
                Long.class, userId, campus);
        return value == null ? 0 : value;
    }

    public ConversationContext teamContext(long teamId, long campus, long actorId) {
        var teams = jdbc.query("SELECT leader_id,title FROM teams WHERE id=? AND campus_id=? AND deleted_at IS NULL",
                (rs, i) -> new Object[]{rs.getLong("leader_id"), rs.getString("title")}, teamId, campus);
        if (teams.isEmpty()) throw missing();
        long leader = ((Number) teams.getFirst()[0]).longValue();
        List<Long> members = jdbc.queryForList("SELECT user_id FROM team_members WHERE team_id=? AND status='member'"
                + " ORDER BY id", Long.class, teamId);
        Set<Long> participants = new LinkedHashSet<>();
        participants.add(leader);
        participants.addAll(members);
        if (!participants.contains(actorId)) throw missing();
        String title = (String) teams.getFirst()[1];
        if (title != null && title.length() > 120) title = title.substring(0, 120);
        return new ConversationContext("team", Long.toString(teamId), title, List.copyOf(participants));
    }

    public List<Map<String, Object>> exportTeams(long userId, long campus) {
        return jdbc.query("SELECT id,title,goal,status,created_at,updated_at,deleted_at FROM teams"
                        + " WHERE leader_id=? AND campus_id=? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("title", rs.getString("title"));
                    row.put("goal", rs.getString("goal"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    row.put("updatedAt", instantText(rs, "updated_at"));
                    row.put("deletedAt", instantText(rs, "deleted_at"));
                    return row;
                }, userId, campus);
    }

    public List<Map<String, Object>> exportMemberships(long userId, long campus) {
        return jdbc.query("SELECT m.team_id,t.title,m.role,m.status,m.created_at FROM team_members m"
                        + " JOIN teams t ON t.id=m.team_id"
                        + " WHERE m.user_id=? AND t.campus_id=? ORDER BY m.id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("teamId", Long.toString(rs.getLong("team_id")));
                    row.put("teamTitle", rs.getString("title"));
                    row.put("role", rs.getString("role"));
                    row.put("status", rs.getString("status"));
                    row.put("joinedAt", instantText(rs, "created_at"));
                    return row;
                }, userId, campus);
    }

    public List<Map<String, Object>> exportApplications(long userId, long campus) {
        return jdbc.query("SELECT a.id,a.team_id,t.title,a.status,a.created_at FROM team_applications a"
                        + " JOIN teams t ON t.id=a.team_id"
                        + " WHERE a.user_id=? AND t.campus_id=? ORDER BY a.id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("teamId", Long.toString(rs.getLong("team_id")));
                    row.put("teamTitle", rs.getString("title"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, userId, campus);
    }

    private static String instantText(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    private SearchItem item(ResultSet rs) throws SQLException {
        LocalDateTime updated = rs.getObject("updated_at", LocalDateTime.class);
        return new SearchItem("team", Long.toString(rs.getLong("id")), rs.getString("title"), rs.getString("goal"),
                "manual", SearchText.sourceName("manual"), updated == null ? null : updated.toInstant(ZoneOffset.UTC),
                false, null, null);
    }

    private static BusinessException missing() {
        return new BusinessException(404, "NOT_FOUND", "未找到请求的资源");
    }
}
