package com.caizhitong.moderation;

import java.time.Instant;
import java.util.List;

public final class GovernanceViews {
    private GovernanceViews() {}
    public record Report(String id, String targetType, String targetId, String reason, String status, String resolution, Instant createdAt, Instant updatedAt) {}
    public record ReportPage(List<Report> items, String nextCursor, boolean hasMore) {}
    public record Blocked(boolean blocked) {}
    public record Penalty(String id, String type, String reason, String status, Instant expiresAt) {}
    public record PenaltyPage(List<Penalty> items, String nextCursor, boolean hasMore) {}
    public record Appeal(String id, String penaltyId, String reason, String status, Instant reviewedAt, String decisionReason, Instant createdAt) {}
    public record AppealPage(List<Appeal> items, String nextCursor, boolean hasMore) {}
    public record ModerationTask(String id, String targetType, String targetId, String status, String result,
                                 String content, Instant createdAt) {}
    public record ModerationPage(List<ModerationTask> items, String nextCursor, boolean hasMore) {}
    public record CreditDispute(String id, String userId, String creditEventId, String reason, String status, Instant createdAt) {}
    public record CreditDisputePage(List<CreditDispute> items, String nextCursor, boolean hasMore) {}
    public record Investigation(String investigationId, Subject subject, boolean audited) {}
    public record InvestigationReceipt(String investigationId) {}
    public record Subject(String userId) {}
}
