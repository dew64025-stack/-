package com.caizhitong.privacy;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class PrivacyViews {
    private PrivacyViews() {}

    public record Authorization(String id, String type, String scope, String status, String documentVersion,
                                Instant grantedAt, Instant revokedAt) {}

    public record PrivacySettings(boolean personalization, boolean historyEnabled, boolean nearbyEnabled, int version) {}

    public record DataRequest(String id, String type, String status, Instant requestedAt, Instant processedAt,
                              String retentionBasis, JsonNode result, Instant cancelledAt, Instant coolingOffUntil,
                              int version) {}

    public record DataRequestPage(List<DataRequest> items, String nextCursor, boolean hasMore) {}
}
