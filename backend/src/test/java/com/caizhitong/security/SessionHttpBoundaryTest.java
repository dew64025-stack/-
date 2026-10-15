package com.caizhitong.security;

import com.caizhitong.common.*;
import com.caizhitong.common.idempotency.BusinessWrite;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:session_http;MODE=MySQL;DB_CLOSE_DELAY=-1") @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(SessionHttpBoundaryTest.Fixtures.class)
class SessionHttpBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired SessionTokenService sessions;
    @Autowired SessionStore sessionStore;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @BeforeEach void database() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS campuses(id BIGINT PRIMARY KEY,name VARCHAR(128),code VARCHAR(64),status VARCHAR(32),deleted_at TIMESTAMP)");
        jdbc.execute("DELETE FROM campuses");
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_idempotency_records (id BIGINT PRIMARY KEY,campus_id BIGINT,user_id BIGINT,scope_digest CHAR(64),key_digest CHAR(64),request_digest CHAR(64),response_encrypted BLOB,response_type VARCHAR(255),status VARCHAR(16),created_at TIMESTAMP,expires_at TIMESTAMP,UNIQUE(scope_digest,key_digest))");
        jdbc.execute("DELETE FROM api_idempotency_records");
        jdbc.execute("INSERT INTO campuses VALUES(1,'大学','one','active',NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mutual_aid_posts(id BIGINT PRIMARY KEY,campus_id BIGINT,user_id BIGINT,category VARCHAR(32),title VARCHAR(256),content VARCHAR(2048),detail_json VARCHAR(4000),images_json VARCHAR(4000),price DECIMAL(10,2),exchange_area VARCHAR(128),status VARCHAR(32),source_type VARCHAR(16),version INT DEFAULT 0,created_at TIMESTAMP,updated_at TIMESTAMP,deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mutual_aid_ratings(id BIGINT PRIMARY KEY,post_id BIGINT,rater_id BIGINT,score INT,comment VARCHAR(512),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS penalties(id BIGINT PRIMARY KEY,user_id BIGINT,penalty_type VARCHAR(32),reason VARCHAR(512),expires_at TIMESTAMP,status VARCHAR(32),created_by BIGINT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS opportunities(id BIGINT PRIMARY KEY,campus_id BIGINT,notice_id BIGINT,title VARCHAR(256),category VARCHAR(32),organizer VARCHAR(128),summary VARCHAR(1000),deadline TIMESTAMP,status VARCHAR(32),source_type VARCHAR(16),source_name VARCHAR(128),external_id VARCHAR(128),official_reference VARCHAR(512),is_stale BOOLEAN DEFAULT FALSE,rules_version INT DEFAULT 0,version INT DEFAULT 0,created_at TIMESTAMP,updated_at TIMESTAMP,deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS notices(id BIGINT PRIMARY KEY,campus_id BIGINT,title VARCHAR(256),content VARCHAR(1000),raw_url VARCHAR(1024),publisher VARCHAR(128),published_at TIMESTAMP,source_type VARCHAR(16),source_name VARCHAR(128),external_id VARCHAR(128),official_reference VARCHAR(512),is_stale BOOLEAN DEFAULT FALSE,status VARCHAR(32),version INT DEFAULT 0,attachment_id BIGINT,created_at TIMESTAMP,updated_at TIMESTAMP,deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS opportunity_eligibility_rules(id BIGINT PRIMARY KEY,opportunity_id BIGINT,field_key VARCHAR(64),operator VARCHAR(16),value_json VARCHAR(1000),severity VARCHAR(16),sort_order INT DEFAULT 0,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS opportunity_material_items(id BIGINT PRIMARY KEY,opportunity_id BIGINT,name VARCHAR(128),description VARCHAR(512),required BOOLEAN DEFAULT TRUE,sort_order INT DEFAULT 0,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS teams(id BIGINT PRIMARY KEY,opportunity_id BIGINT)");
        jdbc.execute("DELETE FROM opportunities");
        jdbc.execute("DELETE FROM notices");
        jdbc.execute("DELETE FROM teams");
    }
    String bearer(long user, SessionKind kind) { return "Bearer " + sessions.issue(user, 1, kind).accessToken(); }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminCsrf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,boolean valid)throws Exception{
        var bootstrap=mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        String token=new tools.jackson.databind.json.JsonMapper().readTree(bootstrap.getResponse().getContentAsString()).path("data").path("token").asString();
        return request.secure(true).cookie(bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN")).header("X-ADMIN-CSRF",valid?token:"invalid");
    }
    @Test void onlyDocumentedPublicGetsReach404AndOtherMethodsRemainClosed() throws Exception {
        mvc.perform(get("/api/v1/campuses")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mvc.perform(get("/api/v1/opportunities/10001")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/campus-issues/10001")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mutual-aid/posts/10001?campusCode=one")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mutual-aid/posts/10001/ratings?campusCode=one")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mutual-aid/posts/10001/ratings/private")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mutual-aid/posts/10001/ratings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/campus-issues/10001/evidences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/mutual-aid/posts/10001/private")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/campus-issues/10001")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mutual-aid/posts/10001")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/opportunities/10001/private")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/campuses")).andExpect(status().isUnauthorized());
    }
    @Test void forgedOrWrongNamespaceBearerIs401AndClientIdentityCannotAuthenticate() throws Exception {
        mvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer(10001, SessionKind.STUDENT))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/probe").header("userId", "10001").header("roles", "platform_admin")).andExpect(status().isUnauthorized());
    }
    @Test void adminCsrfRejectsMissingAndInvalidButAcceptsValidToken() throws Exception {
        String bearer = bearer(10002, SessionKind.ADMIN);
        mvc.perform(post("/api/v1/admin/probe").header("Authorization", bearer).header("Idempotency-Key", "csrf-test-key"))
                .andExpect(status().isForbidden());
        mvc.perform(adminCsrf(post("/api/v1/admin/probe").header("Authorization", bearer).header("Idempotency-Key", "csrf-test-key"),false))
                .andExpect(status().isForbidden());
        mvc.perform(adminCsrf(post("/api/v1/admin/probe").header("Authorization", bearer).header("Idempotency-Key", "csrf-test-key"),true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
    }
    @Test void studentBusinessWriteRequiresBoundedAsciiKeyAndNormalizesJsonBeforeReplay() throws Exception {
        String bearer = bearer(10001, SessionKind.STUDENT);
        mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer).contentType("application/json").content("{\"value\":1}"))
                .andExpect(status().isBadRequest());
        for (String key : new String[]{"short", "x".repeat(129), "非ascii-key"})
            mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer).header("Idempotency-Key", key).contentType("application/json").content("{\"value\":1}"))
                    .andExpect(status().isBadRequest());
        var first = mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer).header("Idempotency-Key", "json-key-123").contentType("application/json").content("{\"value\":1}"))
                .andExpect(status().isOk()).andReturn();
        var replay = mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer).header("Idempotency-Key", "json-key-123").contentType("application/json").content("{ \"value\" : 1 }"))
                .andExpect(status().isOk()).andReturn();
        Assertions.assertNotEquals(first.getResponse().getHeader("X-Request-Id"), replay.getResponse().getHeader("X-Request-Id"));
        mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer).header("Idempotency-Key", "json-key-123").contentType("application/json").content("{\"value\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer).header("Idempotency-Key", "json-key-456").contentType("application/json").content("{\"value\":1,\"userId\":10002}"))
                .andExpect(status().isBadRequest());
    }
    @Test void bannedIdentityOnlyEntersExactOwnRightsRoutes() throws Exception {
        String bearer = bearer(10003, SessionKind.STUDENT);
        mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer).header("Idempotency-Key", "banned-key").contentType("application/json").content("{\"value\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/penalties").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/dashboard").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/account-deletion").header("Authorization", bearer).header("Idempotency-Key", "delete-key"))
                .andExpect(status().isOk());
    }
    @Test void exactCredentialExceptionsDoNotBecomeClientSelectedBypasses() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat/login")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/wechat/login/evil")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/probe").header("Authorization", bearer(10001, SessionKind.STUDENT)).header("X-Idempotency-Exempt", "true"))
                .andExpect(status().isBadRequest());
    }
    @Test void actualAdminCsrfCookieIsSecureAndRequiresItsMatchingMaskedHeader() throws Exception {
        String bearer = bearer(10002, SessionKind.ADMIN);
        var bootstrap = mvc.perform(get("/api/v1/admin/csrf-probe").secure(true).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn();
        String token = bootstrap.getResponse().getContentAsString();
        jakarta.servlet.http.Cookie csrfCookie = bootstrap.getResponse().getCookie("ADMIN-XSRF-TOKEN");
        Assertions.assertTrue(csrfCookie.getSecure()); Assertions.assertTrue(csrfCookie.isHttpOnly());
        Assertions.assertEquals("Strict", csrfCookie.getAttribute("SameSite"));
        mvc.perform(post("/api/v1/admin/probe").secure(true).header("Authorization", bearer).header("Idempotency-Key", "cookie-key").cookie(csrfCookie).header("X-ADMIN-CSRF", token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/probe").secure(true).header("Authorization", bearer).header("Idempotency-Key", "cookie-key").cookie(csrfCookie).header("X-ADMIN-CSRF", "invalid"))
                .andExpect(status().isForbidden());
    }
    @Test void uploadBearerIsNotATemporaryUploadCredentialAndAdminLoginValidatesInput() throws Exception {
        mvc.perform(post("/api/v1/files/uploads/10001").header("Authorization", bearer(10001, SessionKind.STUDENT)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_UPLOAD_CREDENTIAL"));
        mvc.perform(adminCsrf(post("/api/v1/admin/auth/login"),true)).andExpect(status().isBadRequest());
    }
    @Test void interleavedRefreshAndLogoutReturns401AndCurrentCredentialsCanLogoutNormally() throws Exception {
        SessionTokens first = sessions.issue(10001,1,SessionKind.STUDENT);
        var next = new SessionTokens[1];
        ((OpaqueSessionTest.RotateBeforeRevokeStore) sessionStore).beforeRevoke =
                () -> next[0] = sessions.refresh(first.refreshToken(), SessionKind.STUDENT);
        mvc.perform(post("/api/v1/auth/logout").contentType("application/json").content("{\"refreshToken\":\"" + first.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + next[0].accessToken())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").contentType("application/json").content("{\"refreshToken\":\"" + next[0].refreshToken() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revoked").value(true));
        mvc.perform(get("/api/v1/me/probe").header("Authorization", "Bearer " + next[0].accessToken())).andExpect(status().isUnauthorized());
        Assertions.assertEquals(401, Assertions.assertThrows(BusinessException.class,
                () -> sessions.refresh(next[0].refreshToken(), SessionKind.STUDENT)).getStatus());
    }
    @Test void expiredAccessHeaderDoesNotPreemptValidStudentRefreshOrLogout()throws Exception{
        SessionTokens old=sessions.issue(10001,1,SessionKind.STUDENT),next=sessions.refresh(old.refreshToken(),SessionKind.STUDENT);String expired="Bearer "+old.accessToken();
        var refreshed=mvc.perform(post("/api/v1/auth/token/refresh").header("Authorization",expired).contentType("application/json").content("{\"refreshToken\":\""+next.refreshToken()+"\"}")).andExpect(status().isOk()).andReturn();
        String refresh=new tools.jackson.databind.json.JsonMapper().readTree(refreshed.getResponse().getContentAsString()).path("data").path("refreshToken").asString();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization",expired).contentType("application/json").content("{\"refreshToken\":\""+refresh+"\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/token/refresh").header("Authorization",expired).contentType("application/json").content("{\"refreshToken\":\""+refresh+"\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/probe").header("Authorization",expired)).andExpect(status().isUnauthorized());
    }
    record Input(int value) {}
    record Output(boolean accepted) {}
    @Service static class ProbeService {
        @BusinessWrite public Output write(Input input) { return new Output(true); }
        @BusinessWrite(ownRights = true) public Output deleteAccount() { return new Output(true); }
    }
    @RestController static class ProbeController {
        final CurrentActor current; final ProbeService service;
        ProbeController(CurrentActor current, ProbeService service) { this.current = current; this.service = service; }
        @GetMapping("/api/v1/me/probe") public Output me() { current.require(); return new Output(true); }
        @GetMapping("/api/v1/admin/probe") public Output admin() { current.requirePermission("content.review"); return new Output(true); }
        @GetMapping(value="/api/v1/admin/csrf-probe", produces="text/plain") public String csrf(HttpServletRequest r) {
            return ((org.springframework.security.web.csrf.CsrfToken) r.getAttribute("_csrf")).getToken();
        }
        @PostMapping("/api/v1/admin/probe") public ApiResponse<Output> csrfProbe(HttpServletRequest r) { current.requirePermission("content.review"); return ApiResponse.ok(new Output(true), RequestIdFilter.requestId(r)); }
        @PostMapping("/api/v1/me/probe") public ApiResponse<Output> write(@RequestBody Input input, HttpServletRequest r) { return ApiResponse.ok(service.write(input), RequestIdFilter.requestId(r)); }
        @PostMapping("/api/v1/me/account-deletion") public Output delete() { return service.deleteAccount(); }
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary ActorLoader testActorLoader() { return (user, campus, kind) -> {
            if (campus != 1 || user < 10001 || user > 10003 || (user == 10002) != (kind == SessionKind.ADMIN)) return Optional.empty();
            return Optional.of(new Actor(user, campus, user == 10002 ? Set.of("content.review") : Set.of(), true, true, user == 10003 ? "banned" : "active", kind));
        }; }
        @Bean @Primary SessionStore testSessionStore() { return new OpaqueSessionTest.RotateBeforeRevokeStore(java.time.Clock.systemUTC()); }
        @Bean ProbeService probeService() { return new ProbeService(); }
        @Bean ProbeController probeController(CurrentActor current, ProbeService service) { return new ProbeController(current, service); }
    }
}
