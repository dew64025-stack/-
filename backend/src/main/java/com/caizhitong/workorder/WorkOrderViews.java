package com.caizhitong.workorder;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.caizhitong.resource.ServiceSources;

public final class WorkOrderViews {
    private WorkOrderViews() {}
    public record WorkOrder(String id,String category,String location,String description,String urgency,String status,String serviceStage,List<ServiceSources.Attachment> images,String assigneeDisplayName,int version,Instant createdAt,@JsonUnwrapped ServiceSources.Source sourceFields) {}
    public record Event(String id,String fromStatus,String toStatus,String comment,String operatorDisplayName,Instant createdAt) {}
    public record Detail(@JsonUnwrapped WorkOrder workOrder,List<Event> events) {}
    public record Page(List<WorkOrder> items, String nextCursor, boolean hasMore) {}
    public record Rating(String id, int score, String comment, Instant createdAt) {}
}
