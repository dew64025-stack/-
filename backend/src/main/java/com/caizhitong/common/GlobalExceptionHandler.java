package com.caizhitong.common;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException error, HttpServletRequest request) {
        return response(error.getStatus(), new ApiError(error.getCode(), error.getMessage(), error.getFieldErrors(), error.isRetryable()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        var fields = error.getBindingResult().getFieldErrors().stream()
                .map(field -> new ApiFieldError(field.getField(), field.getCode(), "字段不符合要求")).toList();
        return response(400, new ApiError("VALIDATION_FAILED", "请检查填写的信息", fields, false), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> invalidInput(Exception error, HttpServletRequest request) {
        return response(400, error("VALIDATION_FAILED", "请检查填写的信息", false), request);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiResponse<Void>> version(Exception error, HttpServletRequest request) {
        return response(409, error("VERSION_CONFLICT", "信息已更新，请刷新后重试", false), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException error, HttpServletRequest request) {
        return response(403, error("FORBIDDEN", "无权执行此操作", false), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> unauthenticated(AuthenticationException error, HttpServletRequest request) {
        return response(401, error("UNAUTHENTICATED", "请先登录", false), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException error, HttpServletRequest request) {
        return response(404, error("NOT_FOUND", "未找到请求的资源", false), request);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception error, HttpServletRequest request) {
        log.warn("Unexpected error requestId={} type={}", RequestIdFilter.requestId(request), error.getClass().getSimpleName());
        return response(500, error("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", true), request);
    }

    private ApiError error(String code, String message, boolean retryable) {
        return new ApiError(code, message, List.of(), retryable);
    }

    private ResponseEntity<ApiResponse<Void>> response(int status, ApiError error, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.failure(error, RequestIdFilter.requestId(request)));
    }
}
