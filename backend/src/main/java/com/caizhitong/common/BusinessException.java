package com.caizhitong.common;

import java.util.List;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<ApiFieldError> fieldErrors;
    private final boolean retryable;

    public BusinessException(int status, String code, String message, List<ApiFieldError> fieldErrors, boolean retryable) {
        super(message);
        if (status < 400 || status > 599) throw new IllegalArgumentException("Business error status must be 4xx or 5xx");
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        this.retryable = retryable;
    }

    public BusinessException(int status, String code, String message) {
        this(status, code, message, List.of(), false);
    }

    public BusinessException(HttpStatus status, String code, String message) {
        this(status.value(), code, message);
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public List<ApiFieldError> getFieldErrors() { return fieldErrors; }
    public boolean isRetryable() { return retryable; }
}
