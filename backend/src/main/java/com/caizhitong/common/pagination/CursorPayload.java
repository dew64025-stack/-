package com.caizhitong.common.pagination;
import java.util.Map;
public record CursorPayload(String scope, Map<String, String> sortKeys) {
    public CursorPayload { sortKeys = Map.copyOf(sortKeys); }
}
