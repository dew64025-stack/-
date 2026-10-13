package com.caizhitong.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityBoundaryTest {
    @Autowired MockMvc mvc;

    @Test void anonymousRequestIsRejectedWithSafeEnvelope() throws Exception {
        mvc.perform(get("/api/v1/me").header("X-Request-Id", "spoofed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.requestId").value(startsWith("req_")))
                .andExpect(header().string("X-Request-Id", startsWith("req_")));
    }

    @Test void authenticatedRequestIsStillClosedUntilSessionModuleExists() throws Exception {
        mvc.perform(get("/api/v1/me").with(user("test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test void csrfDenialUsesTheSameEnvelope() throws Exception {
        mvc.perform(post("/api/v1/me/update").with(user("test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
