package com.caizhitong.common.search;

import java.time.Instant;

/** Home aggregation card. Only public fields of campus-visible objects are copied. */
public record HomeCard(String type, String id, String title, String summary,
        String sourceType, String sourceName, Instant updatedAt, boolean isStale,
        String officialReference, String externalId, Instant deadline, String category,
        String locationName) {}
