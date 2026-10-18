package com.caizhitong.me;

import com.caizhitong.identity.IdentityViews;
import java.time.Instant;
import java.util.List;

public final class MeViews {
    private MeViews() {}

    public record Dashboard(IdentityViews.User user, long todoCount, long unreadCount, long postCount,
                            long reservationCount, long workOrderCount) {}

    public record Todo(String type, String targetId, String title, Instant dueAt, String status, String action) {}
    public record TodoPage(List<Todo> items, String nextCursor, boolean hasMore) {}

    public record HistoryItem(String id, String targetType, String targetId, String title, String summary,
                              Instant viewedAt) {}
    public record HistoryPage(List<HistoryItem> items, String nextCursor, boolean hasMore) {}

    public record Cleared(boolean cleared) {}
    public record Deleted(boolean deleted) {}

    public record GrowthRecord(String id, String type, String title, String description, String proofFileId,
                               boolean confirmed, Instant occurredAt, int version, Instant createdAt, Instant updatedAt) {}
    public record GrowthPage(List<GrowthRecord> items, String nextCursor, boolean hasMore) {}

    public record CreditEvent(String id, String eventType, String reason, int points, String relatedType,
                              String relatedId, Instant createdAt) {}
    public record CreditPage(List<CreditEvent> items, String nextCursor, boolean hasMore) {}

    public record Dispute(String id, String status) {}
}
