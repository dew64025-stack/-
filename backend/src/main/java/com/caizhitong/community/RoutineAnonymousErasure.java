package com.caizhitong.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The business account executes the narrow definer routine and never reads the identity mapping. */
@Component
public class RoutineAnonymousErasure implements AnonymousErasure {
    private final JdbcTemplate jdbc;

    public RoutineAnonymousErasure(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int erase(long userId, String userSearchHex) {
        Integer erased = jdbc.queryForObject("CALL community_privacy_erase(?,?)", Integer.class,
                userId, userSearchHex);
        return erased == null ? 0 : erased;
    }
}
