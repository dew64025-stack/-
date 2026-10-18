package com.caizhitong.workorder;

import com.caizhitong.common.todos.TodoItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only work-order counters for the personal dashboard. */
@Component
public class WorkOrderReadBridge {
    private final JdbcTemplate jdbc;

    public WorkOrderReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long userOrders(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM work_orders WHERE user_id=? AND campus_id=? AND deleted_at IS NULL",
                Long.class, userId, campus);
        return value == null ? 0 : value;
    }

    public long userTodos(long userId, long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM work_orders WHERE user_id=? AND campus_id=?"
                + " AND status IN ('pending','reviewing','accepted','processing') AND deleted_at IS NULL", Long.class, userId, campus);
        return value == null ? 0 : value;
    }

    public List<TodoItem> pendingTodos(long userId, long campus) {
        return jdbc.query("SELECT id,category,location,description,status FROM work_orders"
                        + " WHERE user_id=? AND campus_id=? AND status IN ('pending','reviewing','accepted','processing')"
                        + " AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> {
                    String title = rs.getString("description");
                    String location = rs.getString("location");
                    if (location != null && !location.isBlank()) title = title + " · " + location;
                    if (title != null && title.length() > 120) title = title.substring(0, 120);
                    return new TodoItem("work_order", Long.toString(rs.getLong("id")), title, null,
                            rs.getString("status"), "查看工单");
                }, userId, campus);
    }

    public long count(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM work_orders WHERE campus_id=? AND deleted_at IS NULL"
                + " AND created_at>=? AND created_at<?", Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    public List<Map<String, Object>> exportOrders(long userId, long campus) {
        return jdbc.query("SELECT id,category,location,description,urgency,status,created_at,updated_at FROM work_orders"
                        + " WHERE user_id=? AND campus_id=? AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("category", rs.getString("category"));
                    row.put("location", rs.getString("location"));
                    row.put("description", rs.getString("description"));
                    row.put("urgency", rs.getString("urgency"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    row.put("updatedAt", instantText(rs, "updated_at"));
                    return row;
                }, userId, campus);
    }

    private static String instantText(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
