package com.caizhitong.common.idempotency;
public record IdempotencyContext(String method, String route, String key, String requestDigest) {
    public static final String REQUEST_ATTRIBUTE = IdempotencyContext.class.getName();
    public IdempotencyContext {
        if (method == null || !java.util.Set.of("POST", "PUT", "DELETE", "PATCH").contains(method)
                || route == null || !route.startsWith("/api/v1/") || route.contains("?")
                || key == null || !key.matches("[\\x20-\\x7E]{8,128}")
                || requestDigest == null || !requestDigest.matches("[0-9a-f]{64}"))
            throw new com.caizhitong.common.BusinessException(400, "INVALID_IDEMPOTENCY_KEY", "业务写入需要有效的Idempotency-Key");
    }
    @Override public String toString() { return "IdempotencyContext[redacted]"; }
}
