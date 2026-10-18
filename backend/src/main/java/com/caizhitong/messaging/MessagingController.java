package com.caizhitong.messaging;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MessagingController {
    private final MessagingService messaging;

    public MessagingController(MessagingService messaging) {
        this.messaging = messaging;
    }

    public record CreateConversation(String contextType, String contextId) {}
    public record SendMessage(String content, String clientMessageId) {}
    public record ReadMessages(String lastReadMessageId) {}
    public record ReadAll(String beforeId) {}
    public record Subscribe(String templateId, String scene, String consentResult) {}

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessagingViews.Conversation> createConversation(@RequestBody CreateConversation input,
            HttpServletRequest request) {
        return ok(messaging.createConversation(input.contextType(), input.contextId()), request);
    }

    @GetMapping("/conversations")
    public ApiResponse<MessagingViews.ConversationPage> conversations(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return ok(messaging.conversations(cursor, limit), request);
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<MessagingViews.MessagePage> messages(@PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ok(messaging.messages(id, cursor, limit), request);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessagingViews.Message> send(@PathVariable String id, @RequestBody SendMessage input,
            HttpServletRequest request) {
        return ok(messaging.send(id, input.content(), input.clientMessageId()), request);
    }

    @PostMapping("/conversations/{id}/read")
    public ApiResponse<MessagingViews.ReadResult> read(@PathVariable String id, @RequestBody ReadMessages input,
            HttpServletRequest request) {
        return ok(messaging.read(id, input.lastReadMessageId()), request);
    }

    @GetMapping("/me/notifications")
    public ApiResponse<MessagingViews.NotificationPage> notifications(
            @RequestParam(required = false) Boolean unreadOnly, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return ok(messaging.notifications(Boolean.TRUE.equals(unreadOnly), cursor, limit), request);
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<MessagingViews.Notification> readNotification(@PathVariable String id,
            @RequestBody(required = false) Object ignored, HttpServletRequest request) {
        return ok(messaging.readNotification(id), request);
    }

    @PostMapping("/notifications/read-all")
    public ApiResponse<MessagingViews.ReadAll> readAll(@RequestBody ReadAll input, HttpServletRequest request) {
        return ok(messaging.readAllNotifications(input.beforeId()), request);
    }

    @PostMapping("/subscriptions")
    public ApiResponse<MessagingViews.Subscription> subscribe(@RequestBody Subscribe input, HttpServletRequest request) {
        return ok(messaging.subscribe(input.templateId(), input.scene(), input.consentResult()), request);
    }

    @DeleteMapping("/subscriptions/{id}")
    public ApiResponse<MessagingViews.Revoked> revoke(@PathVariable String id, HttpServletRequest request) {
        return ok(messaging.revokeSubscription(id), request);
    }

    @PostMapping("/realtime/tickets")
    public ApiResponse<MessagingViews.RealtimeTicket> realtimeTicket(HttpServletRequest request) {
        return ok(messaging.realtimeTicket(), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, RequestIdFilter.requestId(request));
    }
}
