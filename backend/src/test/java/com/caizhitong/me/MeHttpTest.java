package com.caizhitong.me;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.security.SessionKind;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:me_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(MeHttpTest.Fixtures.class)
@Sql(scripts = {"/identity-schema.sql", "/community-schema.sql", "/me-schema.sql", "/me-http-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class MeHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired SensitiveDataCrypto crypto;
    private final JsonMapper mapper = new JsonMapper();
    private String student;
    private String other;

    @BeforeEach
    void seed() {
        for (String table : List.of("reminders", "reservations", "work_orders", "conversation_members",
                "conversations", "notifications", "team_applications", "teams", "borrow_requests",
                "mutual_aid_posts", "credit_disputes", "credit_events", "browse_history", "growth_records",
                "data_requests", "privacy_settings", "api_idempotency_records", "admin_audit_logs")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM user_phones");
        jdbc.update("DELETE FROM authorizations");
        jdbc.update("DELETE FROM student_verifications");
        jdbc.update("DELETE FROM user_profiles");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM campuses");
        jdbc.update("INSERT INTO campuses VALUES(1,'大学','one','active',NULL)");
        for (long user : new long[]{101, 102}) {
            jdbc.update("INSERT INTO users(id,campus_id,nickname,status,version,created_at,updated_at)"
                    + " VALUES(?,1,?,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user, "用户" + user);
            jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at)"
                            + " VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)", user + 1000, user,
                    crypto.encrypt("phone", "1380000" + user), "ph" + user);
            jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,version,granted_at,revoked_at,created_at)"
                    + " VALUES(?,?,'phone','*','granted',0,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP)", user + 2000, user);
            jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,"
                            + "status,version,created_at,updated_at) VALUES(?,?,1,?,?,'verified',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    user + 3000, user, crypto.encrypt("student", "2026000" + user), "sv" + user);
        }
        student = bearer(101);
        other = bearer(102);
    }

    @Test
    void dashboardAggregatesOnlyOwnPendingWork() throws Exception {
        jdbc.update("INSERT INTO reminders(id,user_id,target_type,target_id,remind_at,status,created_at)"
                + " VALUES(1,101,'opportunity',1,CURRENT_TIMESTAMP,'pending',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO reminders(id,user_id,target_type,target_id,remind_at,status,created_at)"
                + " VALUES(2,102,'opportunity',1,CURRENT_TIMESTAMP,'pending',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO resources(id,campus_id,type,name,capacity,status,source_type,version,created_at,updated_at)"
                + " VALUES(20,1,'study_room','自习室',1,'active','user',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO resource_slots(id,resource_id,slot_date,start_time,end_time,capacity,status,version,created_at,updated_at)"
                + " VALUES(30,20,DATE '2026-10-20',TIME '09:00:00',TIME '10:00:00',1,'open',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,status,deleted_at,created_at) VALUES(3,1,101,30,'pending',NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,slot_id,status,deleted_at,created_at) VALUES(4,1,102,30,'pending',NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO work_orders(id,campus_id,user_id,status,deleted_at,created_at) VALUES(5,1,101,'processing',NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,title,status,source_type,version,created_at,updated_at)"
                + " VALUES(6,1,102,'借用','published','user',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO borrow_requests(id,post_id,borrower_id,status,created_at,updated_at)"
                + " VALUES(7,6,101,'pending',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO teams(id,campus_id,leader_id,title,status,version,created_at,updated_at)"
                + " VALUES(8,1,102,'组队','recruiting',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO team_applications(id,team_id,user_id,status,created_at,updated_at)"
                + " VALUES(9,8,101,'pending',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO conversations(id,campus_id,type,title,created_at,updated_at)"
                + " VALUES(10,1,'direct','会话',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO conversation_members(id,conversation_id,user_id,unread_count,created_at)"
                + " VALUES(11,10,101,3,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notifications(id,campus_id,user_id,title,read_at,created_at)"
                + " VALUES(12,1,101,'提醒',NULL,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/me/dashboard").header("Authorization", student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todoCount").value(5))
                .andExpect(jsonPath("$.data.unreadCount").value(4))
                .andExpect(jsonPath("$.data.reservationCount").value(1))
                .andExpect(jsonPath("$.data.workOrderCount").value(1));
        mvc.perform(get("/api/v1/me/todos").header("Authorization", student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void historyIsOwnerScopedAndClearable() throws Exception {
        jdbc.update("INSERT INTO browse_history(id,campus_id,user_id,target_type,target_id,title,viewed_at)"
                + " VALUES(1,1,101,'opportunity',1,'自己的',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO browse_history(id,campus_id,user_id,target_type,target_id,title,viewed_at)"
                + " VALUES(2,1,102,'opportunity',1,'别人的',CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/me/history").header("Authorization", student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("自己的"));
        mvc.perform(delete("/api/v1/me/history").header("Authorization", student)
                        .header("Idempotency-Key", "clear-history"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cleared").value(true));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM browse_history", Integer.class));
    }

    @Test
    void growthRecordsSupportVersionedUpdateAndSoftDelete() throws Exception {
        JsonNode created = mapper.readTree(command("/me/growth-records", student,
                "{\"type\":\"competition\",\"title\":\"程序设计竞赛\",\"description\":\"校赛\","
                        + "\"occurredAt\":\"2026-10-01T08:00:00Z\",\"confirmed\":true}", "growth-create")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
        String id = created.path("id").asString();
        command("/me/growth-records/" + id + "/update", student, "{\"title\":\"改标题\",\"version\":0}", "growth-update")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        command("/me/growth-records/" + id + "/update", student, "{\"title\":\"再次\",\"version\":0}", "growth-stale")
                .andExpect(status().isConflict());
        command("/me/growth-records/" + id + "/update", other, "{\"title\":\"越权\",\"version\":1}", "growth-foreign")
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/growth-records?type=competition").header("Authorization", student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(delete("/api/v1/me/growth-records/" + id + "?version=1").header("Authorization", student)
                        .header("Idempotency-Key", "growth-delete"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cleared").value(true));
        mvc.perform(get("/api/v1/me/growth-records").header("Authorization", student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void creditEventsAndDisputesAreOwnerScoped() throws Exception {
        jdbc.update("INSERT INTO credit_events(id,campus_id,user_id,event_type,reason,points,created_at)"
                + " VALUES(1,1,101,'volunteer','志愿服务',5,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO credit_events(id,campus_id,user_id,event_type,reason,points,created_at)"
                + " VALUES(2,1,102,'volunteer','他人',5,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/me/credit-events").header("Authorization", student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].points").value(5));
        command("/me/credit-events/2/disputes", student, "{\"reason\":\"不是我的\"}", "credit-foreign")
                .andExpect(status().isNotFound());
        command("/me/credit-events/1/disputes", student, "{\"reason\":\"时间不对\"}", "credit-dispute")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("pending"));
        command("/me/credit-events/1/disputes", student, "{\"reason\":\"重复\"}", "credit-duplicate")
                .andExpect(status().isConflict());
    }

    private String bearer(long user) {
        return "Bearer " + sessions.issue(user, 1, SessionKind.STUDENT).accessToken();
    }

    private ResultActions command(String path, String token, String body, String key) throws Exception {
        return mvc.perform(post("/api/v1" + path).header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }

    @TestConfiguration static class Fixtures {
        @Bean @Primary com.caizhitong.security.SessionStore sessionStore() {
            return new InMemorySessionStore();
        }
    }
}
