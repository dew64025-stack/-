package com.caizhitong.opportunity;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 截止状态持久化：仅 published 且已过截止时间 → expired，不能把未发布草稿按时间自动公开。 */
@Component("opportunityExpiryWorker")
public class OpportunityExpiryWorker implements Runnable {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public OpportunityExpiryWorker(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run() {
        jdbc.update("UPDATE opportunities SET status='expired',updated_at=? WHERE status='published' AND deadline IS NOT NULL AND deadline<=?",
                LocalDateTime.now(clock), LocalDateTime.now(clock));
    }
}
