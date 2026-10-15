package com.caizhitong.opportunity;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class OpportunityViews {
    private OpportunityViews() {}

    /** 公开机会（六个来源字段在顶层；BIGINT 一律十进制字符串）。 */
    public record Opportunity(String id, String title, String category, String organizer, String summary,
            Instant deadline, String status, int version,
            String sourceType, String sourceName, Instant updatedAt, boolean isStale,
            String officialReference, String externalId) {}

    public record Notice(String id, String title, String publisher, Instant publishedAt, String status, int version,
            String sourceType, String sourceName, Instant updatedAt, boolean isStale,
            String officialReference, String externalId, String content, String rawUrl, String attachmentId) {}

    public record Rule(String field, String operator, JsonNode value, String severity, int sortOrder) {}

    public record MaterialRequirement(String name, String description, boolean required, int sortOrder, JsonNode validationRules) {}

    /** 机会详情：Opportunity 六个来源字段在内的全部基础字段平铺 + 五个详情扩展字段（文档第 6 节）。 */
    public record Detail(String id, String title, String category, String organizer, String summary, Instant deadline,
            String status, int version, String sourceType, String sourceName, Instant updatedAt, boolean isStale,
            String officialReference, String externalId, Notice notice, List<Rule> eligibilityRules,
            List<MaterialRequirement> materialRequirements, long teamCount, boolean isFavorited) {}

    public record Page(List<Opportunity> items, String nextCursor, boolean hasMore) {}

    public record FavoriteItem(String id, String targetType, String targetId, Instant createdAt,
            boolean available, Opportunity target) {}

    public record FavoritePage(List<FavoriteItem> items, String nextCursor, boolean hasMore) {}

    public record Favorited(boolean favorited) {}

    public record Reminder(String id, String targetType, String targetId, Instant remindAt, String status) {}

    public record ReminderPage(List<Reminder> items, String nextCursor, boolean hasMore) {}

    public record Cancelled(boolean cancelled) {}

    public record Submission(String id, String opportunityId, String status, Instant submittedAt) {}

    public record EligibilityResult(String id, String opportunityId, String conclusion, List<EligibilityEngine.Reason> reasons,
            Integer rulesVersion, Integer profileVersion, Instant checkedAt) {}

    /** 错误为 {code,message} 对象（文档第 3 节），不返回裸错误码字符串。 */
    public record JobError(String code, String message) {}

    public record AsyncJob(String id, String jobType, String status, JsonNode result, JobError error,
            Instant createdAt, Instant updatedAt) {}
}
