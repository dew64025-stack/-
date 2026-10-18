package com.caizhitong.messaging;

import com.caizhitong.opportunity.ReminderReadBridge;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns due reminders into in-app notifications once. Reminder status stays owned by A;
 * the notifications target+dedup key is the delivery receipt, so no A row is written here.
 */
@Component
public class ReminderNotificationWorker implements Runnable {
    private static final int BATCH = 50;
    private static final int MAX_TITLE = 128;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReminderNotificationWorker.class);

    private final ReminderReadBridge reminders;
    private final MessagingService messaging;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ReminderNotificationWorker(ReminderReadBridge reminders, MessagingService messaging, JdbcTemplate jdbc,
                                      Clock clock, PlatformTransactionManager manager) {
        this.reminders = reminders;
        this.messaging = messaging;
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
    }

    @Override
    public void run() {
        List<ReminderReadBridge.DueReminder> due = reminders.due(clock.instant(), BATCH);
        for (ReminderReadBridge.DueReminder reminder : due) {
            try {
                tx.executeWithoutResult(status -> deliver(reminder));
            } catch (RuntimeException failure) {
                // One malformed row must not starve the rest of the due queue.
                log.warn("Reminder delivery failed for reminder {}", reminder.id(), failure);
            }
        }
    }

    private void deliver(ReminderReadBridge.DueReminder reminder) {
        // The batch was read before this transaction. Re-read and lock the reminder so a
        // cancellation committed in between wins instead of still producing a notification.
        var current = jdbc.queryForList("SELECT status FROM reminders WHERE id=? AND user_id=? FOR UPDATE",
                reminder.id(), reminder.userId());
        if (current.isEmpty() || !"pending".equals(current.getFirst().get("status"))) return;
        Integer delivered = jdbc.queryForObject("SELECT COUNT(*) FROM notifications"
                        + " WHERE user_id=? AND type='deadline' AND target_type='reminder' AND target_id=?",
                Integer.class, reminder.userId(), reminder.id());
        if (delivered != null && delivered > 0) {
            reminders.markSent(reminder.id());
            return;
        }
        var users = jdbc.queryForList("SELECT campus_id FROM users WHERE id=? AND status='active' AND deleted_at IS NULL",
                Long.class, reminder.userId());
        if (users.isEmpty()) return;
        String title = reminder.title() == null || reminder.title().isBlank() ? "事项提醒" : reminder.title();
        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        String content = "你设置的事项即将到期：" + title;
        if (content.length() > 512) content = content.substring(0, 512);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        messaging.issueNotification(users.getFirst(), reminder.userId(), "deadline", title, content,
                "reminder", reminder.id(), "reminder:" + reminder.id(), now);
        reminders.markSent(reminder.id());
    }
}
