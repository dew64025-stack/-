package com.caizhitong.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

public record ApiResponse<T>(boolean success,
        @JsonInclude(JsonInclude.Include.NON_NULL) T data,
        @JsonInclude(JsonInclude.Include.NON_NULL) ApiError error,
        String requestId, Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId, Instant.now());
    }

    public static ApiResponse<Void> failure(ApiError error, String requestId) {
        return new ApiResponse<>(false, null, error, requestId, Instant.now());
    }
}
