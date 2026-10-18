package com.caizhitong.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** D 模块后台任务：到期提醒转站内通知。测试中关闭。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.messaging.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class MessagingScheduling {
    private final ReminderNotificationWorker reminders;

    public MessagingScheduling(ReminderNotificationWorker reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${app.messaging.reminder-poll-ms:60000}")
    public void reminders() {
        reminders.run();
    }
}
