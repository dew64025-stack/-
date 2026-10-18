package com.caizhitong.privacy;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:privacy_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(PrivacyHttpTest.Fixtures.class)
@Sql(scripts = {"/identity-schema.sql", "/community-schema.sql", "/me-schema.sql", "/me-http-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PrivacyHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokenService sessions;
    @Autowired DataRightsWorker worker;
    private final JsonMapper mapper = new JsonMapper();
    private String student;
    private String other;

    @BeforeEach
    void seed() {
        for (String table : List.of("api_idempotency_records", "admin_audit_logs", "data_requests",
                "privacy_settings", "browse_history", "borrow_requests", "mutual_aid_posts", "reservations",
                "work_orders", "campus_configs")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM user_phones");
        jdbc.update("DELETE FROM authorizations");
        jdbc.update("DELETE FROM student_verifications");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM campuses");
        jdbc.update("INSERT INTO campuses VALUES(1,'大学','one','active',NULL)");
        for (long user : new long[]{101, 102}) {
            jdbc.update("INSERT INTO users(id,campus_id,status,version,created_at,updated_at)"
                    + " VALUES(?,1,'active',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
            jdbc.update("INSERT INTO user_phones(id,user_id,phone_encrypted,phone_search,verified,created_at)"
                    + " VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)", user + 1000, user, new byte[]{1}, "ph" + user);
            jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,status,version,granted_at,revoked_at,created_at,"
                    + "document_version) VALUES(?,?,'phone','*','granted',0,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP,'v1')",
                    user + 2000, user);
            jdbc.update("INSERT INTO student_verifications(id,user_id,campus_id,student_id_encrypted,student_id_search,"
                    + "status,version,created_at,updated_at) VALUES(?,?,1,?,?,'verified',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    user + 3000, user, new byte[]{1}, "sv" + user);
        }
        jdbc.update("INSERT INTO campus_configs(id,campus_id,config_key,config_value)"
                + " VALUES(1,1,'privacy.documentVersion','\"v1\"')");
        student = bearer(101);
        other = bearer(102);
    }

    @Test
    void authorizationRequiresPublishedVersionAndIsOwnerScoped() throws Exception {
        command("/me/authorizations", student,
                "{\"type\":\"location\",\"scope\":\"campus\",\"documentVersion\":\"old\"}", "auth-old")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRIVACY_DOCUMENT_CHANGED"));
        JsonNode granted = mapper.readTree(command("/me/authorizations", student,
                "{\"type\":\"location\",\"scope\":\"campus\",\"documentVersion\":\"v1\"}", "auth-new")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("granted"))
                .andReturn().getResponse().getContentAsString()).path("data");
        String id = granted.path("id").asString();
        command("/me/authorizations/" + id + "/revoke", other, "{}", "auth-foreign")
                .andExpect(status().isNotFound());
        command("/me/authorizations/" + id + "/revoke", student, "{}", "auth-revoke")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("revoked"));
        assertEquals("revoked", jdbc.queryForObject("SELECT status FROM authorizations WHERE id=?",
                String.class, Long.parseLong(id)));
    }

    @Test
    void authorizationWithoutConfiguredDocumentFailsExplicitly() throws Exception {
        jdbc.update("DELETE FROM campus_configs");
        command("/me/authorizations", student,
                "{\"type\":\"privacy\",\"scope\":\"*\",\"documentVersion\":\"v1\"}", "auth-missing")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("PRIVACY_DOCUMENT_UNAVAILABLE"));
    }

    @Test
    void privacySettingsAreDefaultOffAndDisablingHistoryDeletesIt() throws Exception {
        mvc.perform(get("/api/v1/me/privacy-settings").header("Authorization", student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalization").value(false))
                .andExpect(jsonPath("$.data.historyEnabled").value(false));
        jdbc.update("INSERT INTO browse_history(id,campus_id,user_id,target_type,target_id,title,viewed_at)"
                + " VALUES(1,1,101,'opportunity',1,'旧记录',CURRENT_TIMESTAMP)");
        command("/me/privacy-settings/update", student,
                "{\"personalization\":true,\"historyEnabled\":false,\"version\":0}", "privacy-1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.personalization").value(true));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM browse_history WHERE user_id=101", Integer.class));
        command("/me/privacy-settings/update", student,
                "{\"personalization\":false,\"version\":0}", "privacy-stale")
                .andExpect(status().isConflict());
    }

    @Test
    void exportRequestIsIdempotentPerUserAndDetailHasNoUrlBeforeCompletion() throws Exception {
        JsonNode export = mapper.readTree(command("/me/data-exports", student, "{}", "export-1")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");
        String id = export.path("id").asString();
        assertEquals("pending", export.path("status").asString());
        assertNull(export.path("result").get("downloadUrl"));
        command("/me/data-exports", student, "{}", "export-2").andExpect(status().isConflict());
        mvc.perform(get("/api/v1/me/data-requests/" + id).header("Authorization", other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/data-requests").header("Authorization", student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void exportWorkerRecordsExplicitFailureWhenOutputCannotBeProduced() throws Exception {
        String id = mapper.readTree(command("/me/data-exports", student, "{}", "export-worker")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asString();
        worker.run();
        assertEquals("failed", jdbc.queryForObject("SELECT status FROM data_requests WHERE id=?",
                String.class, Long.parseLong(id)));
        assertNull(jdbc.queryForObject("SELECT export_object_key_encrypted FROM data_requests WHERE id=?",
                byte[].class, Long.parseLong(id)));
    }

    @Test
    void deletionCoolingOffCanBeCancelledAndOpenObligationRejectsExecution() throws Exception {
        JsonNode deletion = mapper.readTree(command("/me/account-deletion", student, "{\"confirm\":true}", "deletion-1")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");
        String id = deletion.path("id").asString();
        assertEquals("pending", deletion.path("status").asString());
        command("/me/data-requests/" + id + "/cancel", student, "{}", "del-cancel")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("cancelled"));
        command("/me/account-deletion", student, "{\"confirm\":false}", "del-invalid")
                .andExpect(status().isBadRequest());

        String second = mapper.readTree(command("/me/account-deletion", student, "{\"confirm\":true}", "deletion-2")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asString();
        jdbc.update("INSERT INTO mutual_aid_posts(id,campus_id,user_id,title,status,source_type,version,created_at,updated_at)"
                + " VALUES(501,1,102,'借用','published','user',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO borrow_requests(id,post_id,borrower_id,status,created_at,updated_at)"
                + " VALUES(601,501,101,'confirmed',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE data_requests SET cooling_off_until=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=?",
                Long.parseLong(second));
        worker.run();
        assertEquals("rejected", jdbc.queryForObject("SELECT status FROM data_requests WHERE id=?",
                String.class, Long.parseLong(second)));
        assertEquals("active", jdbc.queryForObject("SELECT status FROM users WHERE id=101", String.class));
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
