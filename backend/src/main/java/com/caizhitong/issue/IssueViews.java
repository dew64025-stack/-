package com.caizhitong.issue;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.caizhitong.resource.ServiceSources;

public final class IssueViews {
    private IssueViews() {}
    public record Location(String name,double latitude,double longitude) {}
    public record Issue(String id,String title,String category,String description,String status,int followCount,int voteCount,Location location,int version,Instant createdAt,@JsonUnwrapped ServiceSources.Source sourceFields,String displayPolicy,List<ServiceSources.Attachment> images) {}
    public record Page(List<Issue> items, String nextCursor, boolean hasMore) {}
    public record Point(String id, String title, String category, String status, double latitude, double longitude) {}
    public record Map(List<Point> points, boolean truncated) {}
    public record Follow(boolean following, int followCount) {}
    public record Vote(Integer value, int voteCount) {}
    public record Evidence(String id, String reviewStatus) {}
    public record Crowd(String id,String locationType,String locationName,String crowdLevel,Instant observedAt,@JsonUnwrapped ServiceSources.Source sourceFields) {}
    public record CrowdPage(List<Crowd> items, String nextCursor, boolean hasMore) {}
}
