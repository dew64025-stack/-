package com.caizhitong.common.pagination;
public final class PageLimits {
    private PageLimits() {}
    public static int resolve(Integer limit) {
        if (limit == null) return 20;
        if (limit < 1 || limit > 100) throw new com.caizhitong.common.BusinessException(400, "INVALID_LIMIT", "分页数量必须为1至100");
        return limit;
    }
}
