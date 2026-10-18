package com.caizhitong.messaging;

import com.caizhitong.security.SessionKind;
import com.caizhitong.security.SessionTokenService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.caizhitong.testsupport.InMemorySessionStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:messaging_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(MessagingHttpTest.Fixtures.class)
@Sql(scripts = {"/identity-schema.sql", "/community-schema.sql", "/messaging-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class MessagingHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    private final JsonMapper mapper = new JsonMapper();
    private String owner;
    private String sender;
    private String outsider;
    private String foreign;

    @BeforeEach
    void seed() {
        for (String table : List.of("message_send_records", "subscription_authorizations", "notifications", "messages",
                "conversation_members", "conversations", "blocks", "mutual_aid_posts", "team_members", "teams",
                "api_idempotency_records", "admin_audit_logs")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM user_phones");
        jdbc.update("DELETE FROM authorizations");
        jdbc.update("DELETE FROM student_verifications");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM campuses");
        jdbc.update("INSERT INTO campuses VALUES(1,'大学','one','active',NULL)");
        jdbc.update("INSERT INTO campuses VALUES(2,'另一校','two','active',NULL)");
        for (long user : new long[]{101, 102, 103}) {
            jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at)"
                    + " VALUES(?,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
            studentIdentity(user, 1);
        }
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at)"
                + " VALUES(201,2,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        studentIdentity(201, 2);
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,content,status,source_type,"
                + "version,created_at,updated_at) VALUES(501,1,101,'secondhand','转让教材','九成新','published','user',0,"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        owner = bearer(101, 1);
        sender = bearer(102, 1);
        outsider = bearer(103, 1);
        foreign = bearer(201, 2);
    }

    @Test
    void conversationMessageAndReadLifecycle() throws Exception {
        JsonNode conversation = mapper.readTree(command("/conversations", sender,
                "{\"contextType\":\"mutual_aid_post\",\"contextId\":\"501\"}", "msg-conv").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals("direct", conversation.path("type").asString());
        String id = conversation.path("id").asString();

        JsonNode sent = mapper.readTree(command("/conversations/" + id + "/messages", sender,
                "{\"content\":\"你好，教材还在吗\",\"clientMessageId\":\"client-1\"}", "msg-send")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
        String messageId = sent.path("id").asString();
        String replay = mapper.readTree(command("/conversations/" + id + "/messages", sender,
                "{\"content\":\"重复\",\"clientMessageId\":\"client-1\"}", "msg-send-2")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals(messageId, replay);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=?",
                Integer.class, Long.parseLong(id)));

        mvc.perform(get("/api/v1/conversations/" + id + "/messages").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/conversations/" + id + "/messages").header("Authorization", outsider))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/conversations").header("Authorization", foreign))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));

        command("/conversations/" + id + "/read", owner, "{\"lastReadMessageId\":\"" + messageId + "\"}", "msg-read")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(0));
        assertEquals(0, jdbc.queryForObject("SELECT unread_count FROM conversation_members WHERE conversation_id=?"
                + " AND user_id=101", Integer.class, Long.parseLong(id)));
    }

    @Test
    void selfConversationAndForeignContextAreRejected() throws Exception {
        command("/conversations", owner, "{\"contextType\":\"mutual_aid_post\",\"contextId\":\"501\"}", "msg-self")
                .andExpect(status().isConflict());
        command("/conversations", foreign, "{\"contextType\":\"mutual_aid_post\",\"contextId\":\"501\"}", "msg-foreign")
                .andExpect(status().isNotFound());
        command("/conversations", sender, "{\"contextType\":\"private\",\"contextId\":\"501\"}", "msg-type")
                .andExpect(status().isBadRequest());
    }

    @Test
    void notificationsAreOwnerScopedAndReadAllIsBounded() throws Exception {
        jdbc.update("INSERT INTO notifications(id,campus_id,user_id,type,title,content,read_at,created_at)"
                + " VALUES(801,1,101,'deadline','截止提醒','内容',NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notifications(id,campus_id,user_id,type,title,content,read_at,created_at)"
                + " VALUES(802,1,101,'deadline','第二条','内容',NULL,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/me/notifications?unreadOnly=true").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(post("/api/v1/notifications/801/read").header("Authorization", outsider)
                        .header("Idempotency-Key", "notif-foreign"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/801/read").header("Authorization", owner)
                        .header("Idempotency-Key", "notif-read"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.readAt").isNotEmpty());
        command("/notifications/read-all", owner, "{\"beforeId\":\"802\"}", "notif-all")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.updatedCount").value(1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=101 AND read_at IS NULL",
                Integer.class));
    }

    @Test
    void subscriptionConsentIsReversible() throws Exception {
        String id = mapper.readTree(command("/subscriptions", owner,
                "{\"templateId\":\"deadline_daily\",\"scene\":\"deadline\",\"consentResult\":\"accept\"}", "sub-accept")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals("granted", jdbc.queryForObject("SELECT status FROM subscription_authorizations WHERE id=?",
                String.class, Long.parseLong(id)));
        mvc.perform(delete("/api/v1/subscriptions/" + id).header("Authorization", outsider)
                        .header("Idempotency-Key", "sub-foreign"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/subscriptions/" + id).header("Authorization", owner)
                        .header("Idempotency-Key", "sub-revoke"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revoked").value(true));
    }

    @Test
    void realtimeTicketFailsExplicitlyWhenChannelIsUnconfigured() throws Exception {
        command("/realtime/tickets", owner, "{}", "rt-ticket")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("REALTIME_UNAVAILABLE"));
    }

    private void studentIdentity(long user, long campus) {
        jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at)"
                + " VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)", user + 1000, user, new byte[]{1}, "ph" + user);
        jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,version,granted_at,revoked_at,created_at)"
                + " VALUES(?,?,'phone','*','granted',0,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP)", user + 2000, user);
        jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,"
                + "status,version,created_at,updated_at) VALUES(?,?,?,?,?,'verified',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                user + 3000, user, campus, new byte[]{1}, "sv" + user);
    }

    private String bearer(long user, long campus) {
        return "Bearer " + sessions.issue(user, campus, SessionKind.STUDENT).accessToken();
    }

    private ResultActions command(String path, String token, String body, String key) throws Exception {
        return mvc.perform(post("/api/v1" + path).header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }

    @TestConfiguration static class Fixtures {
        @Bean @Primary com.caizhitong.security.SessionStore sessionStore() {
            return new InMemorySessionStore();
        }

        @Bean @Primary com.caizhitong.common.ratelimit.RateCounter rateCounter() {
            return (bucket, window) -> 1L;
        }
    }
}
