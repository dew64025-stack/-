package com.caizhitong.messaging;

import com.caizhitong.common.BusinessException;

final class MessagingInputs {
    private MessagingInputs() {}

    static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid();
        return value;
    }

    static String text(String value, int max, boolean nullable) {
        if (value == null) {
            if (nullable) return null;
            throw invalid();
        }
        return text(value, max);
    }

    static long id(String value) {
        return com.caizhitong.identity.IdentityInputs.id(value);
    }

    static boolean bool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    static BusinessException invalid() {
        return new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
    }

    static BusinessException missing() {
        return new BusinessException(404, "NOT_FOUND", "未找到请求的资源");
    }

    static BusinessException state() {
        return new BusinessException(409, "STATE_CONFLICT", "当前状态不允许此操作");
    }
}
