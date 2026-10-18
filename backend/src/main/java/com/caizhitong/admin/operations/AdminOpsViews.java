package com.caizhitong.admin.operations;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class AdminOpsViews {
    private AdminOpsViews() {}

    public record Campus(String id, String name, String code) {}

    public record Config(String key, JsonNode value, int version, Instant updatedAt) {}

    public record Statistics(Instant from, Instant to, long newUsers, long verifiedStudents, long newOpportunities,
                             long newIssues, long newWorkOrders, long newReservations, long newMutualAidPosts,
                             long newTeams) {}

    public record AuditLog(String id, String action, String targetType, String targetId, String operator,
                           String reason, Instant createdAt) {}

    public record AuditPage(List<AuditLog> items, String nextCursor, boolean hasMore) {}

    public record Integration(String id, String type, String provider, String status, Instant lastSyncedAt,
                              String lastError, int version) {}

    public record IntegrationConfig(String type, String provider, String status, JsonNode config, int version,
                                    Instant updatedAt) {}

    public record SyncError(String id, String message, Instant createdAt) {}

    public record SyncErrorPage(List<SyncError> items, String nextCursor, boolean hasMore) {}
}
