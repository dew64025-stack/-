package com.caizhitong.community;

import java.time.Instant;
import java.util.List;

public final class CommunityViews {
    private CommunityViews() {}
    public record Post(String id, String title, String content, String pseudonym, String status, int version, boolean canEdit, Instant createdAt) {}
    public record Page(List<Post> items, String nextCursor, boolean hasMore) {}
    public record Comment(String id, String pseudonym, String content, String status, Instant createdAt) {}
    public record CommentPage(List<Comment> items, String nextCursor, boolean hasMore) {}
    public record Deleted(boolean deleted) {}
}
