package com.caizhitong.common;

import java.util.List;

public record ApiError(String code, String message, List<ApiFieldError> fieldErrors, boolean retryable) {
    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
}
