package com.caizhitong.identity;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only, aggregate-only identity counts for campus statistics. */
@Component
public class IdentityReadBridge {
    private final JdbcTemplate jdbc;

    public IdentityReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long newUsers(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE campus_id=? AND deleted_at IS NULL"
                + " AND created_at>=? AND created_at<?", Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    public long verifiedStudents(long campus) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM student_verifications WHERE campus_id=?"
                + " AND status='verified'", Long.class, campus);
        return value == null ? 0 : value;
    }
}
