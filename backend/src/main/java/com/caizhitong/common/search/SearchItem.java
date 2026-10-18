package com.caizhitong.common.search;

import java.time.Instant;

/** Desensitized cross-module card. Source metadata is flattened per the API contract. */
public record SearchItem(String type, String id, String title, String summary,
        String sourceType, String sourceName, Instant updatedAt, boolean isStale,
        String officialReference, String externalId) {}
