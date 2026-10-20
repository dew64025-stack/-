package com.caizhitong.common;

/**
 * Public error-code contract. Internal modules keep their stable codes; this boundary maps the
 * legacy generic codes to the codes the mini-program and admin client are documented to handle
 * (campus-backend-api-v1.1-reviewed.md section 17).
 */
public final class ContractErrorCodes {
    private ContractErrorCodes() {}

    public static String map(String code) {
        if (code == null) return "INTERNAL_ERROR";
        return switch (code) {
            case "UNAUTHENTICATED" -> "AUTH_REQUIRED";
            case "FORBIDDEN" -> "PERMISSION_DENIED";
            case "NOT_FOUND" -> "RESOURCE_NOT_FOUND";
            case "INVALID_IDEMPOTENCY_KEY" -> "INVALID_REQUEST";
            case "IDEMPOTENCY_CONFLICT" -> "IDEMPOTENCY_KEY_REUSED";
            default -> code;
        };
    }
}
