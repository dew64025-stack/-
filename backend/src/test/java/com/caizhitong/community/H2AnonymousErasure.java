package com.caizhitong.community;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2-only substitute for the MySQL definer routine; never included in production. */
@TestConfiguration
public class H2AnonymousErasure {
    @Bean @Primary AnonymousErasure testErasure(JdbcTemplate jdbc) {
        return (userId, searchHex) -> {
            List<Long> identities = jdbc.queryForList(
                    "SELECT id FROM pseudo_identities WHERE user_id=? OR user_id_search=?",
                    Long.class, userId, searchHex);
            if (identities.isEmpty()) return 0;
            String placeholders = String.join(",", java.util.Collections.nCopies(identities.size(), "?"));
            Object[] args = identities.toArray();
            jdbc.update("UPDATE community_comments SET content='[已删除]',status='deleted'"
                    + " WHERE pseudo_identity_id IN (" + placeholders + ")", args);
            jdbc.update("UPDATE community_posts SET title='[已删除]',content='[已删除]',status='deleted',"
                    + " deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,version=version+1"
                    + " WHERE pseudo_identity_id IN (" + placeholders + ")", args);
            jdbc.update("DELETE FROM pseudo_identities WHERE id IN (" + placeholders + ")", args);
            return identities.size();
        };
    }
}
