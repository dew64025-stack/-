package com.caizhitong.security;

import com.caizhitong.common.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

public final class FilterErrors {
    private final JsonMapper mapper;
    public FilterErrors(JsonMapper mapper) { this.mapper = mapper; }
    public void write(HttpServletRequest request, HttpServletResponse response, BusinessException error) throws IOException {
        response.setStatus(error.getStatus()); response.setContentType("application/json"); response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), ApiResponse.failure(new ApiError(error.getCode(), error.getMessage(),
                error.getFieldErrors(), error.isRetryable()), RequestIdFilter.requestId(request)));
    }
}
