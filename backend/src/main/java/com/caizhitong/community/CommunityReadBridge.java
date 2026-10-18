package com.caizhitong.community;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Aggregate-only counter. Never resolves pseudo-identity or author data. */
@Component
public class CommunityReadBridge {
    private final JdbcTemplate jdbc;

    public CommunityReadBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long publishedPosts(long campus, Instant from, Instant to) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM community_posts WHERE campus_id=? AND status='published'"
                + " AND deleted_at IS NULL AND created_at>=? AND created_at<?",
                Long.class, campus, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }
}
