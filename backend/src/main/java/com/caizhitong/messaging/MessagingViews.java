package com.caizhitong.messaging;

import java.time.Instant;
import java.util.List;

public final class MessagingViews {
    private MessagingViews() {}

    public record ConversationContext(String type, String id) {}
    public record Sender(String id, String nickname) {}
    public record Message(String id, String conversationId, Sender sender, String content, String status,
            String clientMessageId, Instant createdAt) {}
    public record Conversation(String id, String type, String title, ConversationContext context, int memberCount,
            int unreadCount, Message lastMessage, Instant createdAt, Instant updatedAt) {}
    public record ConversationPage(List<Conversation> items, String nextCursor, boolean hasMore) {}
    public record MessagePage(List<Message> items, String nextCursor, boolean hasMore) {}
    public record ReadResult(int unreadCount) {}

    public record Notification(String id, String type, String title, String content, String targetType,
            String targetId, Instant readAt, Instant createdAt) {}
    public record NotificationPage(List<Notification> items, String nextCursor, boolean hasMore) {}
    public record ReadAll(int updatedCount) {}

    public record Subscription(String id, String templateId, String scene, String status,
            Instant grantedAt, Instant revokedAt, int version) {}
    public record Revoked(boolean revoked) {}

    public record RealtimeTicket(String ticket, Instant expiresAt, String websocketUrl) {}
}
