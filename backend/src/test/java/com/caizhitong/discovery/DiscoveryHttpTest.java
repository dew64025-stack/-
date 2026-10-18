package com.caizhitong.discovery;

import com.caizhitong.common.ratelimit.RateCounter;
import com.caizhitong.messaging.ReminderNotificationWorker;
import com.caizhitong.security.SessionKind;
import com.caizhitong.security.SessionStore;
import com.caizhitong.security.SessionTokenService;
import com.caizhitong.testsupport.InMemorySessionStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:discovery_http;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DiscoveryHttpTest.Fixtures.class)
@Sql(scripts = {"/identity-schema.sql", "/community-schema.sql", "/service-schema.sql", "/discovery-http-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class DiscoveryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired ReminderNotificationWorker reminderWorker;
    private final JsonMapper mapper = new JsonMapper();
    private String student;
    private String unverified;

    @BeforeEach
    void seed() {
        for (String table : List.of("async_jobs", "conversation_members", "conversations", "notifications", "reminders",
                "privacy_settings", "campus_issue_locations", "campus_issues", "mutual_aid_posts", "teams",
                "opportunities", "resources", "resource_slots", "reservations", "work_orders", "campus_regions",
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
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at)"
                + " VALUES(101,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at)"
                + " VALUES(102,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at)"
                + " VALUES(201,2,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        studentIdentity(101, 1);
        unverifiedIdentity(102, 1);
        studentIdentity(201, 2);
        jdbc.update("INSERT INTO campus_regions VALUES(31,1,NULL,'东区','area','active',NULL)");
        jdbc.update("INSERT INTO campus_regions VALUES(32,2,NULL,'西区','area','active',NULL)");

        jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,description,status,vote_count,"
                + "source_type,visibility,created_at,updated_at) VALUES(10,1,101,'图书馆座位不足','facility',"
                + "'图书馆三楼座位紧张','published',5,'user','public',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,description,status,vote_count,"
                + "source_type,visibility,created_at,updated_at) VALUES(12,1,101,'私有问题','facility',"
                + "'图书馆内部草稿','published',9,'user','private',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO campus_issues(id,campus_id,user_id,title,category,description,status,vote_count,"
                + "source_type,visibility,created_at,updated_at) VALUES(11,2,201,'图书馆座位不足','facility',"
                + "'另一校图书馆问题','published',7,'user','public',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO campus_issue_locations(id,issue_id,name,created_at)"
                + " VALUES(41,10,'图书馆三楼',CURRENT_TIMESTAMP)");

        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,content,exchange_area,status,"
                + "source_type,created_at,updated_at) VALUES(20,1,101,'secondhand','转让教材','九成新 教材',"
                + "'东区食堂','published','user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,category,title,content,exchange_area,status,"
                + "source_type,created_at,updated_at) VALUES(21,2,201,'secondhand','转让教材','另一校教材',"
                + "'西区门口','published','user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,organizer,summary,deadline,status,"
                + "source_type,created_at,updated_at) VALUES(501,1,'奖学金申请','scholarship','学生处','奖学金材料提交',"
                + "TIMESTAMP '2030-01-01 00:00:00','published','manual',TIMESTAMP '2026-01-01 00:00:00',"
                + "TIMESTAMP '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,organizer,summary,deadline,status,"
                + "source_type,created_at,updated_at) VALUES(502,1,'图书馆志愿招募','volunteer','图书馆','整理书架',"
                + "NULL,'published','manual',TIMESTAMP '2026-02-01 00:00:00',TIMESTAMP '2026-02-01 00:00:00')");
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,category,organizer,summary,deadline,status,"
                + "source_type,created_at,updated_at) VALUES(503,2,'另一校奖学金','scholarship','外校','不应出现',"
                + "NULL,'published','manual',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        jdbc.update("INSERT INTO resources(id,campus_id,region_id,type,name,capacity,status,source_type,"
                + "created_at,updated_at) VALUES(40,1,31,'study_room','图书馆研讨室',8,'active','manual',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO teams(id,campus_id,leader_id,title,goal,status,created_at,updated_at)"
                + " VALUES(50,1,101,'图书馆学习小组','一起自习','recruiting',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO teams(id,campus_id,leader_id,title,goal,status,created_at,updated_at)"
                + " VALUES(51,2,201,'图书馆学习小组','另一校','recruiting',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        student = bearer(101, 1);
        unverified = bearer(102, 1);
    }

    @Test
    void publicOverviewScopesCampusAndNeverFabricatesDistance() throws Exception {
        mvc.perform(get("/api/v1/home/overview").param("campusCode", "one"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hotIssues.length()").value(1))
                .andExpect(jsonPath("$.data.hotIssues[0].id").value("10"))
                .andExpect(jsonPath("$.data.nearbyMode").value("unavailable"))
                .andExpect(jsonPath("$.data.nearbyPosts.length()").value(0))
                .andExpect(jsonPath("$.data.deadlines.length()").value(0))
                .andExpect(jsonPath("$.data.unreadCount").value(0));

        mvc.perform(get("/api/v1/home/overview").param("campusCode", "one").param("regionId", "31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nearbyMode").value("region"))
                .andExpect(jsonPath("$.data.nearbyPosts.length()").value(1))
                .andExpect(jsonPath("$.data.nearbyPosts[0].id").value("20"));

        mvc.perform(get("/api/v1/home/overview").param("campusCode", "missing"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CAMPUS_UNAVAILABLE"));
        mvc.perform(get("/api/v1/home/overview").param("campusCode", "one").param("regionId", "32"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/home/overview").param("campusCode", "one").param("latitude", "31.2"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/home/overview").param("campusCode", "one").param("radiusMeters", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedOverviewAggregatesPersonalSectionsAndConsent() throws Exception {
        jdbc.update("INSERT INTO reminders(id,user_id,target_type,target_id,remind_at,status,created_at)"
                + " VALUES(70,101,'opportunity',501,TIMESTAMP '2026-12-01 00:00:00','pending',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO conversations(id,campus_id,type,title,created_by,created_at,updated_at)"
                + " VALUES(601,1,'direct','会话',101,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO conversation_members(id,conversation_id,user_id,unread_count,created_at)"
                + " VALUES(602,601,101,3,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notifications(id,campus_id,user_id,type,title,content,created_at)"
                + " VALUES(603,1,101,'deadline','未读','内容',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notifications(id,campus_id,user_id,type,title,content,created_at)"
                + " VALUES(604,2,101,'deadline','他校未读','内容',CURRENT_TIMESTAMP)");

        mvc.perform(get("/api/v1/home/overview").header("Authorization", student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadlines.length()").value(1))
                .andExpect(jsonPath("$.data.deadlines[0].id").value("501"))
                .andExpect(jsonPath("$.data.recommendations.length()").value(0))
                .andExpect(jsonPath("$.data.unreadCount").value(4));

        jdbc.update("INSERT INTO privacy_settings(user_id,personalization_enabled,nearby_enabled,created_at,updated_at)"
                + " VALUES(101,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/home/overview").header("Authorization", student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendations.length()").value(1))
                .andExpect(jsonPath("$.data.recommendations[0].id").value("502"));
    }

    @Test
    void searchFiltersTypesScopesCampusAndPagesByStableOrder() throws Exception {
        mvc.perform(get("/api/v1/search").param("campusCode", "one").param("q", "图书馆"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].type").value("issue"))
                .andExpect(jsonPath("$.data.items[0].id").value("10"))
                .andExpect(jsonPath("$.data.items[1].type").value("opportunity"))
                .andExpect(jsonPath("$.data.items[1].id").value("502"))
                .andExpect(jsonPath("$.data.items[2].type").value("resource"))
                .andExpect(jsonPath("$.data.items[2].id").value("40"))
                .andExpect(jsonPath("$.data.items[3].type").value("team"))
                .andExpect(jsonPath("$.data.items[3].id").value("50"));

        mvc.perform(get("/api/v1/search").param("campusCode", "two").param("q", "图书馆"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value("11"))
                .andExpect(jsonPath("$.data.items[1].id").value("51"));

        String firstPage = mvc.perform(get("/api/v1/search").param("campusCode", "one").param("q", "图书馆")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode page = mapper.readTree(firstPage).path("data");
        String cursor = page.path("nextCursor").asString();
        mvc.perform(get("/api/v1/search").param("campusCode", "one").param("q", "图书馆")
                        .param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value("40"))
                .andExpect(jsonPath("$.data.items[1].id").value("50"))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        mvc.perform(get("/api/v1/search").param("campusCode", "one").param("q", "图书馆")
                        .param("types", "issue,resource"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(get("/api/v1/search").param("campusCode", "one").param("q", " "))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("campusCode", "one").param("q", "图书馆")
                        .param("types", "anonymous"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("campusCode", "one").param("q", "图书馆")
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("campusCode", "one").param("types", "mutual_aid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assistantQueuesExplicitPendingJobWithoutFabricatingAnswer() throws Exception {
        mvc.perform(post("/api/v1/ai/assistant/messages").header("Idempotency-Key", "assistant-anon")
                        .contentType("application/json").content("{\"question\":\"图书馆几点开门\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ai/assistant/messages").header("Authorization", unverified)
                        .header("Idempotency-Key", "assistant-verify")
                        .contentType("application/json").content("{\"question\":\"图书馆几点开门\"}"))
                .andExpect(status().isForbidden());

        JsonNode job = mapper.readTree(mvc.perform(post("/api/v1/ai/assistant/messages")
                        .header("Authorization", student).header("Idempotency-Key", "assistant-ask-1")
                        .contentType("application/json")
                        .content("{\"question\":\"图书馆几点开门\",\"scope\":\"campus\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobType").value("ai_assistant"))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andReturn().getResponse().getContentAsString()).path("data");
        long id = Long.parseLong(job.path("id").asString());
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM async_jobs WHERE id=?", String.class, id));
        assertEquals("ai_assistant", jdbc.queryForObject("SELECT job_type FROM async_jobs WHERE id=?", String.class, id));
        assertEquals(101L, jdbc.queryForObject("SELECT owner_user_id FROM async_jobs WHERE id=?", Long.class, id));
        assertNull(jdbc.queryForObject("SELECT result_json FROM async_jobs WHERE id=?", String.class, id));

        mvc.perform(post("/api/v1/ai/assistant/messages").header("Authorization", student)
                        .header("Idempotency-Key", "assistant-ask-2").contentType("application/json")
                        .content("{\"question\":\"" + "问".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ai/assistant/messages").header("Authorization", student)
                        .header("Idempotency-Key", "assistant-ask-3").contentType("application/json")
                        .content("{\"question\":\"测试\",\"scope\":\"private\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reminderWorkerDrainsBacklogAndSkipsInactiveUsers() {
        jdbc.update("INSERT INTO reminders(id,user_id,target_type,target_id,remind_at,status,created_at)"
                + " VALUES(9001,102,'opportunity',501,DATEADD('DAY',-2,CURRENT_TIMESTAMP),'pending',CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE users SET status='disabled' WHERE id=102");
        for (int i = 0; i < 55; i++) {
            jdbc.update("INSERT INTO reminders(id,user_id,target_type,target_id,remind_at,status,created_at)"
                    + " VALUES(?,101,'opportunity',501,DATEADD('SECOND',? ,CURRENT_TIMESTAMP),'pending',CURRENT_TIMESTAMP)",
                    9002 + i, -3600 + i);
        }

        reminderWorker.run();
        assertEquals(50, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id=101 AND type='deadline'", Integer.class));
        assertEquals(50, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reminders WHERE user_id=101 AND status='sent'", Integer.class));

        reminderWorker.run();
        assertEquals(55, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id=101 AND type='deadline'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reminders WHERE user_id=101 AND status='pending'", Integer.class));
        assertEquals("pending", jdbc.queryForObject(
                "SELECT status FROM reminders WHERE id=9001", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id=102", Integer.class));
    }

    private void studentIdentity(long user, long campus) {
        verifiedPhone(user, campus);
        jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,"
                + "status,version,created_at,updated_at) VALUES(?,?,?,?,?,'verified',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                user + 3000, user, campus, new byte[]{1}, "sv" + user);
    }

    private void unverifiedIdentity(long user, long campus) {
        verifiedPhone(user, campus);
    }

    private void verifiedPhone(long user, long campus) {
        jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at)"
                + " VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)", user + 1000, user, new byte[]{1}, "ph" + user);
        jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,version,granted_at,revoked_at,created_at)"
                + " VALUES(?,?,'phone','*','granted',0,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP)", user + 2000, user);
    }

    private String bearer(long user, long campus) {
        return "Bearer " + sessions.issue(user, campus, SessionKind.STUDENT).accessToken();
    }

    private ResultActions command(String path, String token, String body, String key) throws Exception {
        return mvc.perform(post("/api/v1" + path).header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }

    @TestConfiguration static class Fixtures {
        @Bean @Primary SessionStore sessionStore() {
            return new InMemorySessionStore();
        }

        @Bean @Primary RateCounter rateCounter() {
            return (bucket, window) -> 1L;
        }
    }
}
