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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(SessionHttpBoundaryTest.Fixtures.class)
class SessionHttpBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired SessionTokenService sessions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @BeforeEach void database() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_idempotency_records (id BIGINT PRIMARY KEY,campus_id BIGINT,user_id BIGINT,scope_digest CHAR(64),key_digest CHAR(64),request_digest CHAR(64),response_encrypted BLOB,response_type VARCHAR(255),status VARCHAR(16),created_at TIMESTAMP,expires_at TIMESTAMP,UNIQUE(scope_digest,key_digest))");
        jdbc.execute("DELETE FROM api_idempotency_records");
    }
    String bearer(long user, SessionKind kind) { return "Bearer " + sessions.issue(user, 1, kind).accessToken(); }
    @Test void onlyDocumentedPublicGetsReach404AndOtherMethodsRemainClosed() throws Exception {
        mvc.perform(get("/api/v1/campuses")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/opportunities/10001")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/campus-issues/10001")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mutual-aid/posts/10001")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mutual-aid/posts/10001/ratings")).andExpect(status().isNotFound());
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
        mvc.perform(post("/api/v1/admin/probe").header("Authorization", bearer).header("Idempotency-Key", "csrf-test-key").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/probe").header("Authorization", bearer).header("Idempotency-Key", "csrf-test-key").with(csrf()))
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
        mvc.perform(post("/api/v1/auth/wechat/login")).andExpect(status().isNotFound());
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
    @Test void uploadBearerIsNotATemporaryUploadCredentialAndAdminAuthIsClosed() throws Exception {
        mvc.perform(post("/api/v1/files/uploads/10001").header("Authorization", bearer(10001, SessionKind.STUDENT))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/auth/login").with(csrf())).andExpect(status().isUnauthorized());
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
        @GetMapping("/api/v1/me/penalties") public Output own() { current.requireAuthenticatedIdentity(); return new Output(true); }
        @PostMapping("/api/v1/me/account-deletion") public Output delete() { return service.deleteAccount(); }
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary ActorLoader testActorLoader() { return (user, campus, kind) -> {
            if (campus != 1 || user < 10001 || user > 10003 || (user == 10002) != (kind == SessionKind.ADMIN)) return Optional.empty();
            return Optional.of(new Actor(user, campus, user == 10002 ? Set.of("content.review") : Set.of(), true, true, user == 10003 ? "banned" : "active", kind));
        }; }
        @Bean @Primary SessionStore testSessionStore() { return new OpaqueSessionTest.MemorySessionStore(java.time.Clock.systemUTC()); }
        @Bean ProbeService probeService() { return new ProbeService(); }
        @Bean ProbeController probeController(CurrentActor current, ProbeService service) { return new ProbeController(current, service); }
    }
}
