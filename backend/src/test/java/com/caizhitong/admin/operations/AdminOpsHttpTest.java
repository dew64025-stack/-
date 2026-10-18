package com.caizhitong.admin.operations;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(AdminOpsHttpTest.Fixtures.class)
@Sql(scripts = {"/identity-schema.sql", "/community-schema.sql", "/me-http-schema.sql", "/ops-schema.sql",
        "/admin-http-schema.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AdminOpsHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired IntegrationWorker worker;
    private final tools.jackson.databind.json.JsonMapper mapper = new tools.jackson.databind.json.JsonMapper();
    private String campusAdmin;
    private String platformAdmin;

    @BeforeEach
    void seed() {
        for (String table : List.of("sync_errors", "sync_cursors", "external_systems", "async_jobs",
                "admin_audit_logs", "campus_configs", "user_roles", "roles", "api_idempotency_records",
                "opportunities", "campus_issues", "work_orders", "reservations", "mutual_aid_posts", "teams")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM student_verifications");
        jdbc.update("DELETE FROM user_phones");
        jdbc.update("DELETE FROM authorizations");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM campuses");
        jdbc.update("INSERT INTO campuses(id,name,code,status,version,created_at,updated_at) VALUES(1,'大学','one','active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO campuses(id,name,code,status,version,created_at,updated_at) VALUES(2,'另一校','two','active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users(id,campus_id,nickname,status,version,created_at,updated_at)"
                + " VALUES(101,1,'学生','active',0,TIMESTAMP '2026-10-10 00:00:00',TIMESTAMP '2026-10-10 00:00:00')");
        jdbc.update("INSERT INTO users(id,campus_id,nickname,status,version,created_at,updated_at)"
                + " VALUES(900,1,'管理员','active',0,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO users(id,campus_id,nickname,status,version,created_at,updated_at)"
                + " VALUES(901,1,'平台','active',0,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,status,version,created_at,updated_at)"
                + " VALUES(951,101,1,?,?,'verified',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", new byte[]{1}, "sv-101");
        jdbc.update("INSERT INTO roles(id,code) VALUES(7,'campus_admin')");
        jdbc.update("INSERT INTO roles(id,code) VALUES(8,'platform_admin')");
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(1,900,7,1)");
        jdbc.update("INSERT INTO user_roles(id,user_id,role_id,campus_id) VALUES(2,901,8,0)");
        campusAdmin = bearer(900);
        platformAdmin = bearer(901);
    }

    @Test
    void campusCreationIsPlatformOnlyUniqueAndAudited() throws Exception {
        adminPlatformPost("/admin/campuses", "{\"name\":\"新校区\",\"code\":\"new-campus\"}", "campus-create-1")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.code").value("new-campus"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM campuses", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action='campus.create'",
                Integer.class));
        adminPlatformPost("/admin/campuses", "{\"name\":\"重复\",\"code\":\"new-campus\"}", "campus-create-2")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CAMPUS_CODE_CONFLICT"));
        adminPlatformPost("/admin/campuses", "{\"name\":\"非法\",\"code\":\"Bad_Code\"}", "campus-create-3")
                .andExpect(status().isBadRequest());
        mvc.perform(csrf(post("/api/v1/admin/campuses").header("Authorization", campusAdmin)
                        .header("Idempotency-Key", "campus-denied").contentType("application/json")
                        .content("{\"name\":\"新校区\",\"code\":\"other-campus\"}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void configurationIsWhitelistedVersionedAndCampusScoped() throws Exception {
        adminPut("/admin/campus/configs/profile.allowedGrades",
                "{\"value\":[\"2025\",\"2026\"],\"version\":0,\"reason\":\"更新年级\"}", "config-grades-1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.value[0]").value("2025"));
        adminPut("/admin/campus/configs/privacy.documentVersion",
                "{\"value\":\"v2\",\"version\":0,\"reason\":\"发布隐私政策\"}", "config-doc-1")
                .andExpect(status().isOk());
        adminPut("/admin/campus/configs/profile.allowedGrades",
                "{\"value\":[\"2024\"],\"version\":0,\"reason\":\"过期版本\"}", "config-grades-stale")
                .andExpect(status().isConflict());
        adminPut("/admin/campus/configs/feature.unknown",
                "{\"value\":true,\"version\":0,\"reason\":\"未列入白名单\"}", "config-unknown")
                .andExpect(status().isBadRequest());
        adminPut("/admin/campus/configs/privacy.deletionCoolingOffDays",
                "{\"value\":90,\"version\":0,\"reason\":\"越界\"}", "config-cooling")
                .andExpect(status().isBadRequest());
        jdbc.update("INSERT INTO campus_configs(id,campus_id,config_key,config_value,version,created_at,updated_at)"
                + " VALUES(9001,2,'privacy.documentVersion','\"foreign\"',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/v1/admin/campus/configs").header("Authorization", campusAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        adminPut("/admin/campus/configs/profile.allowedGrades",
                "{\"value\":[\"2025\",\"2026\",\"2027\"],\"version\":1,\"reason\":\"追加\"}", "config-grades-2")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void statisticsAreCampusScopedAggregates() throws Exception {
        String created = "2026-10-10 00:00:00";
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,status,created_at,updated_at) VALUES(301,1,'校内', 'published',?::TIMESTAMP,?::TIMESTAMP)", created, created);
        jdbc.update("INSERT INTO opportunities(id,campus_id,title,status,created_at,updated_at) VALUES(302,2,'校外', 'published',?::TIMESTAMP,?::TIMESTAMP)", created, created);
        jdbc.update("INSERT INTO campus_issues(id,campus_id,title,status,created_at,updated_at) VALUES(401,1,'食堂','public',?::TIMESTAMP,?::TIMESTAMP)", created, created);
        jdbc.update("INSERT INTO work_orders(id,campus_id,user_id,status,created_at,updated_at) VALUES(501,1,101,'pending',?::TIMESTAMP,?::TIMESTAMP)", created, created);
        jdbc.update("INSERT INTO reservations(id,campus_id,user_id,status,created_at,updated_at) VALUES(601,1,101,'confirmed',?::TIMESTAMP,?::TIMESTAMP)", created, created);
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,title,status,source_type,created_at,updated_at) VALUES(701,1,101,'互助','published','user',?::TIMESTAMP,?::TIMESTAMP)", created, created);
        jdbc.update("INSERT INTO teams(id,campus_id,leader_id,title,status,created_at,updated_at) VALUES(801,1,101,'组队','recruiting',?::TIMESTAMP,?::TIMESTAMP)", created, created);
        mvc.perform(get("/api/v1/admin/statistics/overview?from=2026-10-01T00:00:00Z&to=2026-10-19T00:00:00Z")
                        .header("Authorization", campusAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newUsers").value(1))
                .andExpect(jsonPath("$.data.verifiedStudents").value(1))
                .andExpect(jsonPath("$.data.newOpportunities").value(1))
                .andExpect(jsonPath("$.data.newIssues").value(1))
                .andExpect(jsonPath("$.data.newWorkOrders").value(1))
                .andExpect(jsonPath("$.data.newReservations").value(1))
                .andExpect(jsonPath("$.data.newMutualAidPosts").value(1))
                .andExpect(jsonPath("$.data.newTeams").value(1));
        mvc.perform(get("/api/v1/admin/statistics/overview?from=2026-10-19T00:00:00Z&to=2026-10-01T00:00:00Z")
                        .header("Authorization", campusAdmin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditLogsAreCampusScopedKeysetAndDesensitized() throws Exception {
        String longReason = "x".repeat(250);
        jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,campus_id,action,target_type,target_id,reason,detail_json,created_at)"
                + " VALUES(1001,900,1,'campus.config.update','campus_config',NULL,?,'{\"key\":\"secret\"}',CURRENT_TIMESTAMP)", longReason);
        jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,campus_id,action,target_type,target_id,reason,detail_json,created_at)"
                + " VALUES(1002,900,1,'campus.config.update','campus_config',NULL,'第二条','{}',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,campus_id,action,target_type,target_id,reason,detail_json,created_at)"
                + " VALUES(1003,900,2,'campus.config.update','campus_config',NULL,'外校','{}',CURRENT_TIMESTAMP)");
        String body = mvc.perform(get("/api/v1/admin/audit-logs?limit=1").header("Authorization", platformAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.items[0].operator").value("admin-0900"))
                .andExpect(jsonPath("$.data.items[0].detailJson").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertEquals("第二条", mapper.readTree(body).path("data").path("items").get(0).path("reason").asString());
        String cursor = mapper.readTree(body).path("data").path("nextCursor").asString();
        String secondPage = mvc.perform(get("/api/v1/admin/audit-logs?limit=1&cursor=" + cursor)
                        .header("Authorization", campusAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("1001"))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();
        assertEquals(200, mapper.readTree(secondPage).path("data").path("items").get(0)
                .path("reason").asString().length());
        mvc.perform(get("/api/v1/admin/audit-logs?action=no.such.action").header("Authorization", campusAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", campusAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void integrationsMaskSecretsAndRejectUnsafeConfig() throws Exception {
        adminPut("/admin/integrations/notice",
                "{\"provider\":\"manual\",\"config\":{\"endpoint\":\"https://school.example.edu/hooks/notice\","
                        + "\"secretRef\":\"secrets/notice\",\"timeoutMs\":1000,\"schoolAuthorized\":false},"
                        + "\"version\":0,\"reason\":\"配置通知系统\"}", "integration-notice-1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.config.secretRef").value("***"))
                .andExpect(jsonPath("$.data.config.endpoint").value("https://school.example.edu/hooks/notice"))
                .andExpect(jsonPath("$.data.version").value(1));
        String stored = jdbc.queryForObject("SELECT config_json FROM external_systems WHERE campus_id=1 AND type='notice'",
                String.class);
        assertFalse(stored.contains("***"));
        assertEquals(true, stored.contains("secrets/notice"));
        String list = mvc.perform(get("/api/v1/admin/integrations").header("Authorization", campusAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].provider").value("manual"))
                .andExpect(jsonPath("$.data[0].status").value("active"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("secretRef"));
        assertFalse(list.contains("secrets/notice"));
        adminPut("/admin/integrations/notice",
                "{\"provider\":\"manual\",\"config\":{\"secret\":\"plaintext\"},\"version\":1,\"reason\":\"非法字段\"}",
                "integration-notice-bad-key")
                .andExpect(status().isBadRequest());
        adminPut("/admin/integrations/notice",
                "{\"provider\":\"manual\",\"config\":{\"endpoint\":\"http://insecure.example.edu\"},"
                        + "\"version\":1,\"reason\":\"非法地址\"}", "integration-notice-bad-url")
                .andExpect(status().isBadRequest());
        adminPut("/admin/integrations/library",
                "{\"provider\":\"official\",\"config\":{\"endpoint\":\"https://school.example.edu/library\","
                        + "\"secretRef\":\"secrets/library\",\"schoolAuthorized\":false},"
                        + "\"version\":0,\"reason\":\"官方\"}", "integration-library-auth")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INTEGRATION_AUTHORIZATION_REQUIRED"));
        adminPut("/admin/integrations/library",
                "{\"provider\":\"official\",\"config\":{\"endpoint\":\"https://school.example.edu/library\","
                        + "\"secretRef\":\"secrets/library\",\"schoolAuthorized\":true},"
                        + "\"version\":0,\"reason\":\"官方\"}", "integration-library-test")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INTEGRATION_TEST_REQUIRED"));
        adminPut("/admin/integrations/notice",
                "{\"provider\":\"manual\",\"config\":{\"endpoint\":\"https://school.example.edu/hooks/notice2\"},"
                        + "\"version\":0,\"reason\":\"过期版本\"}", "integration-notice-stale")
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/admin/integrations/classroom/errors").header("Authorization", campusAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    void integrationJobsFailExplicitlyAndNeverFabricateSuccess() throws Exception {
        adminPut("/admin/integrations/notice",
                "{\"provider\":\"manual\",\"config\":{\"endpoint\":\"https://school.example.edu/hooks/notice\","
                        + "\"secretRef\":\"secrets/notice\"},\"version\":0,\"reason\":\"配置通知系统\"}",
                "integration-notice-manual")
                .andExpect(status().isOk());
        adminPost("/admin/integrations/notice/test", "{}", "integration-test-job")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("pending"));
        worker.run();
        assertEquals("failed", jdbc.queryForObject("SELECT status FROM async_jobs WHERE job_type='integration_test'",
                String.class));
        assertEquals("INTEGRATION_PROVIDER_UNAVAILABLE", jdbc.queryForObject(
                "SELECT error FROM async_jobs WHERE job_type='integration_test'", String.class));

        adminPost("/admin/integrations/notice/sync", "{}", "integration-sync-job")
                .andExpect(status().isAccepted());
        worker.run();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sync_errors", Integer.class));
        mvc.perform(get("/api/v1/admin/integrations/notice/errors").header("Authorization", campusAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].message").value("学校系统提供器不可用"))
                .andExpect(jsonPath("$.data.items[0].externalId").doesNotExist());
        mvc.perform(get("/api/v1/admin/integrations").header("Authorization", campusAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lastError").value("同步失败，请查看错误列表"));
        adminPost("/admin/integrations/repair/test", "{}", "integration-missing-job")
                .andExpect(status().isNotFound());
    }

    private String bearer(long user) {
        return "Bearer " + sessions.issue(user, 1, SessionKind.ADMIN).accessToken();
    }

    private ResultActions adminPost(String path, String body, String key) throws Exception {
        return adminCommand(post("/api/v1" + path), body, key, campusAdmin);
    }

    private ResultActions adminPlatformPost(String path, String body, String key) throws Exception {
        return adminCommand(post("/api/v1" + path), body, key, platformAdmin);
    }

    private ResultActions adminPut(String path, String body, String key) throws Exception {
        return adminCommand(put("/api/v1" + path), body, key, campusAdmin);
    }

    private ResultActions adminCommand(MockHttpServletRequestBuilder request, String body, String key,
                                       String auth) throws Exception {
        var bootstrap = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(bootstrap.getResponse().getContentAsString())
                .path("data").path("token").asString();
        var cookie = bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN");
        return mvc.perform(request.secure(true).cookie(cookie).header("X-ADMIN-CSRF", token)
                .header("Authorization", auth).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }

    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) throws Exception {
        var bootstrap = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(bootstrap.getResponse().getContentAsString())
                .path("data").path("token").asString();
        return request.secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN"))
                .header("X-ADMIN-CSRF", token);
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
