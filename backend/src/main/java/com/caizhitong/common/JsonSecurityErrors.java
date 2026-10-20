package com.caizhitong.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonSecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final JsonMapper mapper;

    public JsonSecurityErrors(JsonMapper mapper) { this.mapper = mapper; }

    @Override public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException error)
            throws IOException {
        write(request, response, 401, "AUTH_REQUIRED", "请先登录");
    }

    @Override public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException error)
            throws IOException {
        write(request, response, 403, "PERMISSION_DENIED", "无权执行此操作");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(),
                ApiResponse.failure(new ApiError(ContractErrorCodes.map(code), message, List.of(), false),
                        RequestIdFilter.requestId(request)));
    }
}
