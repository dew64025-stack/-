package com.caizhitong.messaging;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.messaging.ConversationContext;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.common.ratelimit.RateLimiter;
import com.caizhitong.identity.OneTimeSecrets;
import com.caizhitong.mutualaid.MutualAidReadBridge;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import com.caizhitong.team.TeamReadBridge;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MessagingService {
    private static final Set<String> CONTEXT_TYPES = Set.of("mutual_aid_post", "borrow_request", "team");
    private static final Set<String> SCENES = Set.of("deadline", "workorder", "reservation", "borrow");
    private static final Duration REALTIME_TTL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final RateLimiter rates;
    private final OneTimeSecrets secrets;
    private final MutualAidReadBridge mutualAid;
    private final TeamReadBridge teams;
    private final SecureRandom random = new SecureRandom();
    private final String websocketUrl;

    public MessagingService(JdbcTemplate jdbc, CurrentActor current, SnowflakeIds ids, Clock clock, CursorCodec cursors,
                            SensitiveDataCrypto crypto, RateLimiter rates, OneTimeSecrets secrets,
                            MutualAidReadBridge mutualAid, TeamReadBridge teams,
                            @Value("${app.realtime.websocket-url:}") String websocketUrl) {
        this.jdbc = jdbc;
        this.current = current;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.crypto = crypto;
        this.rates = rates;
        this.secrets = secrets;
        this.mutualAid = mutualAid;
        this.teams = teams;
        this.websocketUrl = websocketUrl == null ? "" : websocketUrl.trim();
    }

    // ---- conversations ----

    @BusinessWrite(student = true)
    public MessagingViews.Conversation createConversation(String contextType, String contextId) {
        Actor actor = current.requireStudent();
        String type = MessagingInputs.text(contextType, 32);
        if (!CONTEXT_TYPES.contains(type)) throw MessagingInputs.invalid();
        long context = MessagingInputs.id(contextId);
        ConversationContext resolved = switch (type) {
            case "mutual_aid_post" -> mutualAid.postContext(context, actor.campusId(), actor.userId());
            case "borrow_request" -> mutualAid.borrowContext(context, actor.campusId(), actor.userId());
            case "team" -> teams.teamContext(context, actor.campusId(), actor.userId());
            default -> throw MessagingInputs.invalid();
        };
        List<Long> participants = new ArrayList<>(new LinkedHashSet<>(resolved.participantIds()));
        if (participants.size() < 2 || !participants.contains(actor.userId())) throw MessagingInputs.missing();
        requireParticipants(actor.campusId(), participants);
        if (!"team".equals(type) && blockedBetween(actor.userId(), participants)) {
            throw new BusinessException(403, "MESSAGE_BLOCKED", "无法向已拉黑的对象发起会话");
        }

        var existing = jdbc.queryForList("SELECT id FROM conversations WHERE campus_id=? AND context_type=? AND context_id=?",
                Long.class, actor.campusId(), type, context);
        if (!existing.isEmpty()) {
            long conversation = existing.getFirst();
            ensureMember(conversation, actor.userId());
            return conversation(actor, conversation);
        }

        long id = ids.nextId();
        LocalDateTime now = now();
        String conversationType = "team".equals(type) ? "team" : participants.size() == 2 ? "direct" : "group";
        String title = resolved.title() == null || resolved.title().isBlank() ? "会话" : resolved.title();
        try {
            jdbc.update("INSERT INTO conversations(id,campus_id,type,title,context_type,context_id,created_by,version,created_at,updated_at)"
                            + " VALUES(?,?,?,?,?,?,?,0,?,?)",
                    id, actor.campusId(), conversationType, title, type, context, actor.userId(), now, now);
        } catch (DuplicateKeyException race) {
            long conversation = jdbc.queryForObject("SELECT id FROM conversations WHERE campus_id=? AND context_type=? AND context_id=?",
                    Long.class, actor.campusId(), type, context);
            ensureMember(conversation, actor.userId());
            return conversation(actor, conversation);
        }
        for (long participant : participants) {
            jdbc.update("INSERT INTO conversation_members(id,conversation_id,user_id,unread_count,created_at) VALUES(?,?,?,0,?)",
                    ids.nextId(), id, participant, now);
        }
        return conversation(actor, id);
    }

    public MessagingViews.ConversationPage conversations(String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        String scope = "conversations:" + actor.campusId() + ":" + actor.userId();
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            var keys = cursors.decode(cursor, scope).sortKeys();
            if (!keys.keySet().equals(Set.of("id"))) throw MessagingInputs.invalid();
            before = MessagingInputs.id(keys.get("id"));
        }
        var found = jdbc.queryForList("SELECT c.id FROM conversations c JOIN conversation_members m ON m.conversation_id=c.id"
                        + " WHERE m.user_id=? AND c.campus_id=? AND c.id<? ORDER BY c.id DESC LIMIT ?",
                Long.class, actor.userId(), actor.campusId(), before, size + 1);
        boolean more = found.size() > size;
        var page = found.stream().limit(size).map(id -> conversation(actor, id)).toList();
        String next = more && !page.isEmpty()
                ? cursors.encode(new CursorPayload(scope, Map.of("id", page.getLast().id()))) : null;
        return new MessagingViews.ConversationPage(page, next, more);
    }

    public MessagingViews.MessagePage messages(String conversationId, String cursor, Integer limit) {
        Actor actor = current.require();
        long conversation = MessagingInputs.id(conversationId);
        requireMember(actor, conversation);
        int size = PageLimits.resolve(limit);
        String scope = "messages:" + conversation + ":" + actor.userId();
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            var keys = cursors.decode(cursor, scope).sortKeys();
            if (!keys.keySet().equals(Set.of("id"))) throw MessagingInputs.invalid();
            before = MessagingInputs.id(keys.get("id"));
        }
        var found = jdbc.query("SELECT id,conversation_id,sender_id,content,created_at FROM messages"
                        + " WHERE conversation_id=? AND status='sent' AND id<? ORDER BY id DESC LIMIT ?",
                (rs, i) -> message(rs), conversation, before, size + 1);
        boolean more = found.size() > size;
        var page = found.stream().limit(size).toList();
        String next = more && !page.isEmpty()
                ? cursors.encode(new CursorPayload(scope, Map.of("id", page.getLast().id()))) : null;
        return new MessagingViews.MessagePage(page, next, more);
    }

    @BusinessWrite(student = true)
    public MessagingViews.Message send(String conversationId, String contentValue, String clientMessageId) {
        Actor actor = current.requireStudent();
        long conversation = MessagingInputs.id(conversationId);
        requireMember(actor, conversation);
        String content = MessagingInputs.text(contentValue, 2000);
        String clientId = MessagingInputs.text(clientMessageId, 64);
        if (!clientId.matches("[\\x20-\\x7E]{1,64}")) throw MessagingInputs.invalid();
        rates.check("message-send:" + actor.userId(), 120, Duration.ofMinutes(1));

        var existing = jdbc.query("SELECT id,conversation_id,sender_id,content,created_at FROM messages"
                        + " WHERE conversation_id=? AND sender_id=? AND client_message_id=?",
                (rs, i) -> message(rs), conversation, actor.userId(), clientId);
        if (!existing.isEmpty()) return existing.getFirst();

        requireNotMuted(actor);
        List<Long> recipients = jdbc.queryForList("SELECT user_id FROM conversation_members"
                + " WHERE conversation_id=? AND user_id<>? ORDER BY id", Long.class, conversation, actor.userId());
        if (blockedBetween(actor.userId(), recipients)) {
            throw new BusinessException(403, "MESSAGE_BLOCKED", "无法向已拉黑的对象发送消息");
        }
        rejectBlockedContent(content);

        long id = ids.nextId();
        LocalDateTime now = now();
        try {
            jdbc.update("INSERT INTO messages(id,conversation_id,sender_id,content,status,client_message_id,created_at)"
                    + " VALUES(?,?,?,?,'sent',?,?)", id, conversation, actor.userId(), content, clientId, now);
        } catch (DuplicateKeyException race) {
            var replay = jdbc.query("SELECT id,conversation_id,sender_id,content,created_at FROM messages"
                            + " WHERE conversation_id=? AND sender_id=? AND client_message_id=?",
                    (rs, i) -> message(rs), conversation, actor.userId(), clientId);
            if (!replay.isEmpty()) return replay.getFirst();
            throw race;
        }
        jdbc.update("UPDATE conversations SET updated_at=?,version=version+1 WHERE id=?", now, conversation);
        jdbc.update("UPDATE conversation_members SET unread_count=unread_count+1"
                + " WHERE conversation_id=? AND user_id<>?", conversation, actor.userId());
        return new MessagingViews.Message(Long.toString(id), Long.toString(conversation), Long.toString(actor.userId()),
                content, now.toInstant(ZoneOffset.UTC));
    }

    @BusinessWrite(student = true)
    public MessagingViews.ReadResult read(String conversationId, String lastReadMessageId) {
        Actor actor = current.requireStudent();
        long conversation = MessagingInputs.id(conversationId);
        long lastRead = MessagingInputs.id(lastReadMessageId);
        requireMember(actor, conversation);
        int exists = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE id=? AND conversation_id=? AND status='sent'",
                Integer.class, lastRead, conversation);
        if (exists == 0) throw MessagingInputs.missing();
        var rows = jdbc.queryForList("SELECT last_read_message_id FROM conversation_members"
                + " WHERE conversation_id=? AND user_id=? FOR UPDATE", conversation, actor.userId());
        if (rows.isEmpty()) throw MessagingInputs.missing();
        Object previous = rows.getFirst().get("last_read_message_id");
        long effective = previous == null ? lastRead : Math.max(((Number) previous).longValue(), lastRead);
        LocalDateTime now = now();
        jdbc.update("UPDATE conversation_members SET last_read_message_id=?,last_read_at=?,unread_count=?"
                        + " WHERE conversation_id=? AND user_id=?",
                effective, now, countUnread(conversation, actor.userId(), effective), conversation, actor.userId());
        return new MessagingViews.ReadResult(countUnread(conversation, actor.userId(), effective));
    }

    // ---- notifications ----

    public MessagingViews.NotificationPage notifications(boolean unreadOnly, String cursor, Integer limit) {
        Actor actor = current.require();
        int size = PageLimits.resolve(limit);
        String scope = "notifications:" + actor.campusId() + ":" + actor.userId() + ":" + unreadOnly;
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            var keys = cursors.decode(cursor, scope).sortKeys();
            if (!keys.keySet().equals(Set.of("id"))) throw MessagingInputs.invalid();
            before = MessagingInputs.id(keys.get("id"));
        }
        String sql = "SELECT id,type,title,content,target_type,target_id,read_at,created_at FROM notifications"
                + " WHERE user_id=? AND campus_id=? AND id<?" + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY id DESC LIMIT ?";
        var found = jdbc.query(sql, (rs, i) -> notification(rs), actor.userId(), actor.campusId(), before, size + 1);
        boolean more = found.size() > size;
        var page = found.stream().limit(size).toList();
        String next = more && !page.isEmpty()
                ? cursors.encode(new CursorPayload(scope, Map.of("id", page.getLast().id()))) : null;
        return new MessagingViews.NotificationPage(page, next, more);
    }

    @BusinessWrite
    public MessagingViews.Notification readNotification(String notificationId) {
        Actor actor = current.require();
        long id = MessagingInputs.id(notificationId);
        var rows = jdbc.queryForList("SELECT read_at FROM notifications WHERE id=? AND user_id=? AND campus_id=? FOR UPDATE",
                id, actor.userId(), actor.campusId());
        if (rows.isEmpty()) throw MessagingInputs.missing();
        if (rows.getFirst().get("read_at") == null) {
            jdbc.update("UPDATE notifications SET read_at=? WHERE id=?", now(), id);
        }
        return notificationById(actor, id);
    }

    @BusinessWrite
    public MessagingViews.ReadAll readAllNotifications(String beforeId) {
        Actor actor = current.require();
        long before = MessagingInputs.id(beforeId);
        int updated = jdbc.update("UPDATE notifications SET read_at=?"
                        + " WHERE user_id=? AND campus_id=? AND read_at IS NULL AND id<=?",
                now(), actor.userId(), actor.campusId(), before);
        return new MessagingViews.ReadAll(updated);
    }

    public int unreadTotal(long userId, long campus) {
        Integer conversations = jdbc.queryForObject("SELECT COALESCE(SUM(m.unread_count),0) FROM conversation_members m"
                + " JOIN conversations c ON c.id=m.conversation_id WHERE m.user_id=? AND c.campus_id=?",
                Integer.class, userId, campus);
        Integer notifications = jdbc.queryForObject("SELECT COUNT(*) FROM notifications"
                + " WHERE user_id=? AND campus_id=? AND read_at IS NULL", Integer.class, userId, campus);
        return (conversations == null ? 0 : conversations) + (notifications == null ? 0 : notifications);
    }

    /** Called by workers inside their own transaction. Duplicate dedup keys are a no-op. */
    public long issueNotification(long campus, long userId, String type, String title, String content,
                                  String targetType, Long targetId, String dedupMaterial, LocalDateTime now) {
        long id = ids.nextId();
        String dedup = HexFormat.of().formatHex(crypto.searchHash("notification-dedup", dedupMaterial));
        try {
            jdbc.update("INSERT INTO notifications(id,campus_id,user_id,type,title,content,target_type,target_id,dedup_key,created_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, campus, userId, type, title, content, targetType, targetId, dedup, now);
        } catch (DuplicateKeyException duplicate) {
            return 0;
        }
        return id;
    }

    // ---- subscriptions ----

    @BusinessWrite(student = true)
    public MessagingViews.Subscription subscribe(String templateId, String scene, String consentResult) {
        Actor actor = current.requireStudent();
        String template = MessagingInputs.text(templateId, 128);
        String targetScene = MessagingInputs.text(scene, 64);
        if (!SCENES.contains(targetScene)) throw MessagingInputs.invalid();
        String consent = MessagingInputs.text(consentResult, 16);
        if (!Set.of("accept", "reject").contains(consent)) throw MessagingInputs.invalid();
        boolean accepted = "accept".equals(consent);
        LocalDateTime now = now();
        var rows = jdbc.queryForList("SELECT id,version FROM subscription_authorizations"
                        + " WHERE user_id=? AND template_id=? AND scene=? FOR UPDATE",
                actor.userId(), template, targetScene);
        long id;
        if (rows.isEmpty()) {
            id = ids.nextId();
            jdbc.update("INSERT INTO subscription_authorizations(id,user_id,template_id,scene,status,version,granted_at,revoked_at,created_at)"
                            + " VALUES(?,?,?,?,?,0,?,?,?)",
                    id, actor.userId(), template, targetScene, accepted ? "granted" : "revoked", now,
                    accepted ? null : now, now);
        } else {
            id = ((Number) rows.getFirst().get("id")).longValue();
            jdbc.update("UPDATE subscription_authorizations SET status=?,granted_at=?,revoked_at=?,version=version+1 WHERE id=?",
                    accepted ? "granted" : "revoked", accepted ? now : null, accepted ? null : now, id);
        }
        return subscription(id, actor.userId());
    }

    @BusinessWrite(student = true)
    public MessagingViews.Revoked revokeSubscription(String subscriptionId) {
        Actor actor = current.requireStudent();
        long id = MessagingInputs.id(subscriptionId);
        int updated = jdbc.update("UPDATE subscription_authorizations SET status='revoked',revoked_at=COALESCE(revoked_at,?),"
                + "version=version+1 WHERE id=? AND user_id=?", now(), id, actor.userId());
        if (updated == 0) throw MessagingInputs.missing();
        return new MessagingViews.Revoked(true);
    }

    // ---- realtime ----

    @BusinessWrite(student = true)
    public MessagingViews.RealtimeTicket realtimeTicket() {
        Actor actor = current.requireStudent();
        if (websocketUrl.isEmpty()) {
            throw new BusinessException(503, "REALTIME_UNAVAILABLE", "实时通道尚未配置");
        }
        rates.check("realtime-ticket:" + actor.userId(), 10, Duration.ofMinutes(1));
        byte[] material = new byte[32];
        random.nextBytes(material);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        Instant expiresAt = clock.instant().plus(REALTIME_TTL);
        String digest = HexFormat.of().formatHex(crypto.searchHash("realtime-ticket", ticket));
        boolean claimed = secrets.claim("realtime", digest,
                actor.userId() + ":" + actor.campusId() + ":" + expiresAt.getEpochSecond(), REALTIME_TTL);
        if (!claimed) throw new BusinessException(503, "REALTIME_TICKET_UNAVAILABLE", "实时票证服务暂时不可用", null, true);
        return new MessagingViews.RealtimeTicket(ticket, expiresAt, websocketUrl);
    }

    // ---- helpers ----

    private MessagingViews.Conversation conversation(Actor actor, long id) {
        var rows = jdbc.query("SELECT c.id,c.type,c.title,c.context_type,c.context_id,c.created_at,c.updated_at,m.unread_count"
                        + " FROM conversations c JOIN conversation_members m ON m.conversation_id=c.id"
                        + " WHERE c.id=? AND c.campus_id=? AND m.user_id=?",
                (rs, i) -> new ConversationRow(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                        rs.getString("context_type"), rs.getObject("context_id") == null ? null : rs.getLong("context_id"),
                        rs.getInt("unread_count"), local(rs, "created_at"), local(rs, "updated_at")),
                id, actor.campusId(), actor.userId());
        if (rows.isEmpty()) throw MessagingInputs.missing();
        ConversationRow row = rows.getFirst();
        int memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM conversation_members WHERE conversation_id=?",
                Integer.class, id);
        var messages = jdbc.query("SELECT id,conversation_id,sender_id,content,created_at FROM messages"
                + " WHERE conversation_id=? AND status='sent' ORDER BY id DESC LIMIT 1", (rs, i) -> message(rs), id);
        return new MessagingViews.Conversation(Long.toString(row.id()), row.type(), row.title(),
                row.contextType() == null ? null : new MessagingViews.ConversationContext(row.contextType(),
                        row.contextId() == null ? null : Long.toString(row.contextId())),
                memberCount, row.unreadCount(), messages.isEmpty() ? null : messages.getFirst(),
                row.createdAt().toInstant(ZoneOffset.UTC), row.updatedAt().toInstant(ZoneOffset.UTC));
    }

    private void requireMember(Actor actor, long conversation) {
        int member = jdbc.queryForObject("SELECT COUNT(*) FROM conversations c JOIN conversation_members m"
                        + " ON m.conversation_id=c.id WHERE c.id=? AND c.campus_id=? AND m.user_id=?",
                Integer.class, conversation, actor.campusId(), actor.userId());
        if (member != 1) throw MessagingInputs.missing();
    }

    private void ensureMember(long conversation, long userId) {
        int member = jdbc.queryForObject("SELECT COUNT(*) FROM conversation_members WHERE conversation_id=? AND user_id=?",
                Integer.class, conversation, userId);
        if (member == 0) {
            jdbc.update("INSERT INTO conversation_members(id,conversation_id,user_id,unread_count,created_at) VALUES(?,?,?,0,?)",
                    ids.nextId(), conversation, userId, now());
        }
    }

    private void requireParticipants(long campus, List<Long> participants) {
        String placeholders = String.join(",", participants.stream().map(ignored -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(campus);
        args.addAll(participants);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE campus_id=? AND status='active'"
                + " AND deleted_at IS NULL AND id IN (" + placeholders + ")", Integer.class, args.toArray());
        if (count == null || count != participants.size()) throw MessagingInputs.missing();
    }

    private boolean blockedBetween(long actor, List<Long> others) {
        if (others.isEmpty()) return false;
        String placeholders = String.join(",", others.stream().map(ignored -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(actor);
        args.addAll(others);
        args.addAll(others);
        args.add(actor);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM blocks WHERE (blocker_id=? AND target_type='user'"
                        + " AND target_id IN (" + placeholders + ")) OR (blocker_id IN (" + placeholders
                        + ") AND target_type='user' AND target_id=?)",
                Integer.class, args.toArray());
        return count != null && count > 0;
    }

    private void requireNotMuted(Actor actor) {
        Integer muted = jdbc.queryForObject("SELECT COUNT(*) FROM penalties WHERE user_id=? AND status='active'"
                        + " AND penalty_type IN ('mute','ban') AND (expires_at IS NULL OR expires_at>?)",
                Integer.class, actor.userId(), now());
        if (muted != null && muted > 0) {
            throw new BusinessException(403, "MESSAGE_RESTRICTED", "当前账号已被限制发送消息");
        }
    }

    private void rejectBlockedContent(String content) {
        var rules = jdbc.query("SELECT rule_type,pattern FROM moderation_rules WHERE enabled=TRUE AND action='block'",
                (rs, i) -> new String[]{rs.getString("rule_type"), rs.getString("pattern")});
        for (String[] rule : rules) {
            String ruleType = rule[0];
            String pattern = rule[1];
            if (pattern == null || pattern.isBlank()) continue;
            boolean hit = switch (ruleType) {
                case "keyword" -> content.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
                case "regex" -> matchesRegex(content, pattern);
                default -> false;
            };
            if (hit) throw new BusinessException(422, "CONTENT_BLOCKED", "消息包含不允许的内容");
        }
    }

    private static boolean matchesRegex(String content, String pattern) {
        try {
            return Pattern.compile(pattern).matcher(content).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private int countUnread(long conversation, long userId, long lastRead) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND id>?"
                        + " AND sender_id<>? AND status='sent'", Integer.class, conversation, lastRead, userId);
        return value == null ? 0 : value;
    }

    private MessagingViews.Notification notificationById(Actor actor, long id) {
        var rows = jdbc.query("SELECT id,type,title,content,target_type,target_id,read_at,created_at FROM notifications"
                        + " WHERE id=? AND user_id=? AND campus_id=?", (rs, i) -> notification(rs),
                id, actor.userId(), actor.campusId());
        if (rows.isEmpty()) throw MessagingInputs.missing();
        return rows.getFirst();
    }

    private MessagingViews.Notification notification(ResultSet rs) throws SQLException {
        Long targetId = rs.getObject("target_id") == null ? null : rs.getLong("target_id");
        String targetType = rs.getString("target_type");
        return new MessagingViews.Notification(Long.toString(rs.getLong("id")), rs.getString("type"),
                rs.getString("title"), rs.getString("content"),
                targetType == null ? null : new MessagingViews.NotificationTarget(targetType,
                        targetId == null ? null : Long.toString(targetId)),
                instant(rs, "read_at"), instant(rs, "created_at"));
    }

    private MessagingViews.Message message(ResultSet rs) throws SQLException {
        return new MessagingViews.Message(Long.toString(rs.getLong("id")), Long.toString(rs.getLong("conversation_id")),
                Long.toString(rs.getLong("sender_id")), rs.getString("content"), instant(rs, "created_at"));
    }

    private MessagingViews.Subscription subscription(long id, long userId) {
        return jdbc.queryForObject("SELECT id,template_id,scene,status,granted_at,revoked_at,version"
                        + " FROM subscription_authorizations WHERE id=? AND user_id=?",
                (rs, i) -> new MessagingViews.Subscription(Long.toString(rs.getLong("id")), rs.getString("template_id"),
                        rs.getString("scene"), rs.getString("status"), instant(rs, "granted_at"),
                        instant(rs, "revoked_at"), rs.getInt("version")), id, userId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = local(rs, column);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime local(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record ConversationRow(long id, String type, String title, String contextType, Long contextId,
                                   int unreadCount, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
