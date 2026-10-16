package com.caizhitong.mutualaid;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class MutualAidViews {
    private MutualAidViews() {}
    public record Author(String id, String nickname) {}
    public record Post(String id, String category, String title, String content, JsonNode detail,
            List<com.caizhitong.resource.ServiceSources.Attachment> images, String price, String exchangeArea, String status, int version, Instant createdAt) {}
    public record Detail(Post post, Author author, boolean canContact, boolean canEdit) {}
    public record Page(List<Post> items, String nextCursor, boolean hasMore) {}
    public record Rating(String id, int score, String comment, Instant createdAt) {}
    public record RatingPage(List<Rating> items, String nextCursor, boolean hasMore) {}
    public record Borrow(String id, String postId, String borrowerDisplayName, Instant startTime, Instant endTime,
            String status, Instant createdAt, Instant updatedAt) {}
    public record BorrowPage(List<Borrow> items, String nextCursor, boolean hasMore) {}
    public record Match(String id, String lostPostId, String foundPostId, String similarity, String status, String confirmationStatus) {}
    public record MatchPage(List<Match> items, String nextCursor, boolean hasMore) {}
    public record Deleted(boolean deleted) {}
}
