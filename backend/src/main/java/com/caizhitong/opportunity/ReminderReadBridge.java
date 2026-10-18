package com.caizhitong.opportunity;

import com.caizhitong.common.todos.TodoItem;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only due reminders. Delivery bookkeeping is owned by the messaging module (dedup keys). */
@Component
public class ReminderReadBridge {
    public record DueReminder(long id, long userId, String targetType, long targetId, String title, Instant remindAt) {}

    private final JdbcTemplate jdbc;

    public ReminderReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DueReminder> due(Instant now, int limit) {
        return jdbc.query("SELECT r.id,r.user_id,r.target_type,r.target_id,o.title,r.remind_at"
                        + " FROM reminders r LEFT JOIN opportunities o ON r.target_type='opportunity' AND o.id=r.target_id"
                        + " WHERE r.status='pending' AND r.remind_at<=? ORDER BY r.remind_at,r.id LIMIT ?",
                (rs, i) -> new DueReminder(rs.getLong("id"), rs.getLong("user_id"), rs.getString("target_type"),
                        rs.getLong("target_id"), rs.getString("title"),
                        rs.getObject("remind_at", java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)),
                Timestamp.from(now), limit);
    }

    /** All pending reminders for the personal to-do feed (not only ones already due). */
    public List<TodoItem> pendingTodos(long userId) {
        return jdbc.query("SELECT r.id,r.target_type,r.target_id,r.remind_at,o.title"
                        + " FROM reminders r LEFT JOIN opportunities o ON r.target_type='opportunity' AND o.id=r.target_id"
                        + " WHERE r.user_id=? AND r.status='pending' ORDER BY r.remind_at,r.id",
                (rs, i) -> {
                    String title = rs.getString("title");
                    if (title == null || title.isBlank()) title = "提醒：" + rs.getString("target_type");
                    LocalDateTime due = rs.getObject("remind_at", LocalDateTime.class);
                    return new TodoItem("reminder", Long.toString(rs.getLong("id")), title,
                            due == null ? null : due.toInstant(ZoneOffset.UTC), "pending", "查看提醒");
                }, userId);
    }

    public long pendingCount(long userId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE user_id=? AND status='pending'",
                Long.class, userId);
        return value == null ? 0 : value;
    }

    /** Personal upcoming deadlines, sourced only from the user's own pending reminders. */
    public List<com.caizhitong.common.search.HomeCard> upcoming(long userId, Instant now, int limit) {
        return jdbc.query("SELECT o.id,o.title,o.summary,o.deadline,o.source_type,o.updated_at,o.is_stale,o.official_reference,o.external_id"
                        + " FROM reminders r JOIN opportunities o ON r.target_type='opportunity' AND o.id=r.target_id"
                        + " WHERE r.user_id=? AND r.status='pending' AND o.status='published' AND o.deleted_at IS NULL"
                        + " AND o.deadline IS NOT NULL AND o.deadline>=? ORDER BY o.deadline,o.id LIMIT ?",
                (rs, i) -> {
                    var updated = rs.getObject("updated_at", java.time.LocalDateTime.class);
                    var deadline = rs.getObject("deadline", java.time.LocalDateTime.class);
                    return new com.caizhitong.common.search.HomeCard("opportunity", Long.toString(rs.getLong("id")),
                            rs.getString("title"), rs.getString("summary"), rs.getString("source_type"),
                            com.caizhitong.common.search.SearchText.sourceName(rs.getString("source_type")),
                            updated == null ? null : updated.toInstant(java.time.ZoneOffset.UTC),
                            rs.getBoolean("is_stale"), rs.getString("official_reference"), rs.getString("external_id"),
                            deadline == null ? null : deadline.toInstant(java.time.ZoneOffset.UTC), null, null);
                }, userId, Timestamp.from(now), limit);
    }
}
