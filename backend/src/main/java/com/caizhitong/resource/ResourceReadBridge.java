package com.caizhitong.resource;

import com.caizhitong.common.search.SearchItem;
import com.caizhitong.common.search.SearchText;
import com.caizhitong.common.todos.TodoItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only active-resource projection and reservation counters. */
@Component
public class ResourceReadBridge {
    private static final String BASE = """
            SELECT r.id,r.name,r.type,r.capacity,r.source_type,r.updated_at,
                   (SELECT g.name FROM campus_regions g WHERE g.id=r.region_id) region_name
            FROM resources r
            WHERE r.campus_id=? AND r.status='active' AND r.deleted_at IS NULL""";

    private final JdbcTemplate jdbc;

    public ResourceReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchItem> search(long campus, String query, long beforeId, int limit) {
        String pattern = SearchText.pattern(query);
        return jdbc.query(BASE + " AND r.id<? AND (r.name LIKE ? ESCAPE '!' OR r.type LIKE ? ESCAPE '!')"
                        + " ORDER BY r.id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, beforeId, pattern, pattern, limit);
    }

    public List<SearchItem> context(long campus, int limit) {
        return jdbc.query(BASE + " ORDER BY r.updated_at DESC,r.id DESC LIMIT ?",
                (rs, i) -> item(rs), campus, limit);
    }

    public Optional<SearchItem> visible(long campus, long id) {
        return jdbc.query(BASE + " AND r.id=?", (rs, i) -> item(rs), campus, id).stream().findFirst();
    }

    public long userReservations(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE user_id=? AND campus_id=?"
                + " AND status IN ('pending','confirmed','checked_in') AND deleted_at IS NULL", Long.class, userId, campus);
        return value == null ? 0 : value;
    }

    public long userTodos(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE user_id=? AND campus_id=?"
                + " AND status='pending' AND deleted_at IS NULL", Long.class, userId, campus);
        return value == null ? 0 : value;
    }

    /** Pending reservations in the campus's local slot time, as personal to-dos. */
    public List<TodoItem> pendingTodos(long userId, long campus) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        return jdbc.query("SELECT r.id,r.name,s.slot_date,s.start_time,r.status FROM reservations v"
                        + " JOIN resource_slots s ON s.id=v.slot_id JOIN resources r ON r.id=s.resource_id"
                        + " WHERE v.user_id=? AND v.campus_id=? AND v.status='pending' AND v.deleted_at IS NULL"
                        + " ORDER BY s.slot_date,s.start_time,v.id",
                (rs, i) -> {
                    LocalDateTime due = LocalDateTime.of(rs.getObject("slot_date", java.time.LocalDate.class),
                            rs.getObject("start_time", LocalTime.class));
                    return new TodoItem("reservation", Long.toString(rs.getLong("id")), rs.getString("name"),
                            due.toInstant(zone.getRules().getOffset(due)), rs.getString("status"), "查看预约");
                }, userId, campus);
    }

    public long count(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM resources WHERE campus_id=? AND deleted_at IS NULL"
                + " AND created_at>=? AND created_at<?", Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    public long reservationCount(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE campus_id=? AND deleted_at IS NULL"
                + " AND created_at>=? AND created_at<?", Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    /** Personal reservations with slot local date/time, without other users' occupancy. */
    public List<Map<String, Object>> exportReservations(long userId, long campus) {
        return jdbc.query("SELECT v.id,r.name,s.slot_date,s.start_time,s.end_time,v.seat_no,v.status,v.created_at"
                        + " FROM reservations v JOIN resource_slots s ON s.id=v.slot_id"
                        + " JOIN resources r ON r.id=s.resource_id"
                        + " WHERE v.user_id=? AND v.campus_id=? AND v.deleted_at IS NULL ORDER BY v.id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("resourceName", rs.getString("name"));
                    row.put("slotDate", rs.getObject("slot_date", java.time.LocalDate.class).toString());
                    row.put("startTime", rs.getObject("start_time", LocalTime.class).toString());
                    row.put("endTime", rs.getObject("end_time", LocalTime.class).toString());
                    row.put("seatNo", rs.getInt("seat_no"));
                    row.put("status", rs.getString("status"));
                    LocalDateTime created = rs.getObject("created_at", LocalDateTime.class);
                    row.put("createdAt", created.toInstant(ZoneOffset.UTC).toString());
                    return row;
                }, userId, campus);
    }

    private SearchItem item(ResultSet rs) throws SQLException {
        String summary = rs.getString("type");
        String region = rs.getString("region_name");
        int capacity = rs.getInt("capacity");
        if (region != null) summary += " · " + region;
        if (capacity > 0) summary += " · 可容纳 " + capacity + " 人";
        LocalDateTime updated = rs.getObject("updated_at", LocalDateTime.class);
        return new SearchItem("resource", Long.toString(rs.getLong("id")), rs.getString("name"), summary,
                rs.getString("source_type"), SearchText.sourceName(rs.getString("source_type")),
                updated == null ? null : updated.toInstant(ZoneOffset.UTC), false, null, null);
    }
}
