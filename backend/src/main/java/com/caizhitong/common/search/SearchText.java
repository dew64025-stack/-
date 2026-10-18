package com.caizhitong.common.search;

/** Shared LIKE escaping. Queries must use ESCAPE '!'. */
public final class SearchText {
    private SearchText() {}

    public static String pattern(String query) {
        String text = query == null ? "" : query.trim();
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public static String sourceName(String sourceType) {
        if (sourceType == null) return null;
        return switch (sourceType) {
            case "demo" -> "校园演示提供器";
            case "manual" -> "平台处理";
            case "official" -> "官方同步";
            default -> sourceType;
        };
    }
}
