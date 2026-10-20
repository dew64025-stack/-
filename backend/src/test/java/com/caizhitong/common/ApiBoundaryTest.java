package com.caizhitong.common;

import com.caizhitong.config.JsonConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiBoundaryTest {
    private MockMvc mvc;

    @BeforeEach void setup() {
        JsonMapper mapper = new JsonConfiguration().jsonMapper();
        mvc = MockMvcBuilders.standaloneSetup(new BoundaryController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(new RequestIdFilter()).build();
    }

    @Test void responseOmitsNullBranchesAndReplacesUntrustedRequestId() throws Exception {
        mvc.perform(get("/fixture/success").header("X-Request-Id", "req_attacker"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.count").value(2)).andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.requestId").value(startsWith("req_")))
                .andExpect(jsonPath("$.requestId").value(not("req_attacker")))
                .andExpect(jsonPath("$.timestamp").value(endsWith("Z")))
                .andExpect(header().string("X-Request-Id", startsWith("req_")));
    }

    @Test void beanValidationReturnsFieldErrorWithoutRejectedValue() throws Exception {
        mvc.perform(post("/fixture/input").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.error.fieldErrors[0].code").value("NotBlank"))
                .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test void rejectsUnknownDtoFieldsAndWrongTypes() throws Exception {
        mvc.perform(post("/fixture/input").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"safe\",\"role\":\"admin\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/fixture/type").param("count", "sensitive-input"))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("sensitive-input"))));
    }

    @Test void exceptionsUseStableCodesAndNeverExposeInternalMessages() throws Exception {
        mvc.perform(get("/fixture/error/version")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/fixture/error/denied")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));
        mvc.perform(get("/fixture/error/auth")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/fixture/error/internal")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
                .andExpect(content().string(not(containsString("SELECT password"))));
        mvc.perform(get("/fixture/error/business")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_BUSY"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @RestController static class BoundaryController {
        @GetMapping("/fixture/success") ApiResponse<?> success(jakarta.servlet.http.HttpServletRequest request) {
            return ApiResponse.ok(Map.of("id", "9007199254740993", "count", 2), RequestIdFilter.requestId(request));
        }
        @PostMapping("/fixture/input") ApiResponse<?> input(@Valid @RequestBody Input input, jakarta.servlet.http.HttpServletRequest request) {
            return ApiResponse.ok(input, RequestIdFilter.requestId(request));
        }
        @GetMapping("/fixture/type") void type(@RequestParam int count) { }
        @GetMapping("/fixture/error/{kind}") void error(@PathVariable String kind) {
            switch (kind) {
                case "version" -> throw new OptimisticLockingFailureException("SELECT password");
                case "denied" -> throw new AccessDeniedException("SELECT password");
                case "auth" -> throw new BadCredentialsException("SELECT password");
                case "business" -> throw new BusinessException(409, "RESOURCE_BUSY", "资源暂不可用", java.util.List.of(), true);
                default -> throw new IllegalStateException("SELECT password FROM secrets");
            }
        }
    }
    record Input(@NotBlank String name) { }
}
