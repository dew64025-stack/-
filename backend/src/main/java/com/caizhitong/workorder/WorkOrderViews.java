package com.caizhitong.workorder;

import java.time.Instant;
import java.util.List;

public final class WorkOrderViews {
    private WorkOrderViews() {}
    public record WorkOrder(String id, String category, String location, String description, String urgency, String status, String serviceStage, int version, Instant createdAt, Instant updatedAt) {}
    public record Event(String id, String fromStatus, String toStatus, String comment, Instant createdAt) {}
    public record Detail(WorkOrder workOrder, List<Event> events) {}
    public record Page(List<WorkOrder> items, String nextCursor, boolean hasMore) {}
    public record Rating(String id, int score, String comment, Instant createdAt) {}
}
