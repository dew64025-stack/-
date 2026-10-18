package com.caizhitong.admin.opportunity;

import com.caizhitong.ai.notice.NoticeParseSchema;
import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.integration.notice.SafeUrlReader;
import com.caizhitong.material.files.AttachmentService;
import com.caizhitong.opportunity.OpportunityInputs;
import com.caizhitong.opportunity.OpportunityService;
import com.caizhitong.opportunity.OpportunityViews;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.version;

/**
 * 通知管理：创建（至少一种原文来源）、AI 解析登记（原子+审计+202）、
 * 逐字段人工复核（只接受固定 Schema 键，approve 只生成 draft，不直接公开）。
 */
@Service
public class AdminNoticeService {
    private static final Set<String> NOTICE_STATUSES = Set.of("pending", "parsing", "reviewing", "published", "archived");
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final AttachmentService files;
    private final SafeUrlReader reader;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final JsonMapper mapper;
    public AdminNoticeService(JdbcTemplate jdbc, CurrentActor current, AttachmentService files, SafeUrlReader reader,
                              SnowflakeIds ids, Clock clock, CursorCodec cursors, SensitiveDataCrypto crypto, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.files = files;
        this.reader = reader;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public record NoticeInput(String title, String publisher, String content, String rawUrl, String attachmentId) {}
    public record ParseInput(JsonNode version) {
        public ParseInput { com.caizhitong.identity.IdentityInputs.version(version); }
    }
    public record ReviewInput(List<Map<String, String>> fields, String decision, String reason) {}
    public record ParseTaskView(String id, String status, List<Map<String, Object>> fields, String model, String error) {}
    public record ReviewResult(boolean reviewed, String opportunityDraftId) {}
    public record NoticeView(String id, String title, String publisher, Instant publishedAt, String status, int version,
            String sourceType, String sourceName, Instant updatedAt, boolean isStale, String officialReference,
            String externalId, String content, String rawUrl, String attachmentId) {}
    public record NoticePage(List<NoticeView> items, String nextCursor, boolean hasMore) {}

    public NoticePage list(String status, String q, String cursor, Integer limit) {
        Actor actor = current.requirePermission("notice.read");
        int size = PageLimits.resolve(limit);
        if (status != null && !NOTICE_STATUSES.contains(status)) throw invalid();
        if (q != null && (q.isBlank() || q.length() > 100)) throw invalid();
        String scopeToken = "admin-notices:" + actor.campusId() + ":"
                + java.util.HexFormat.of().formatHex(crypto.searchHash("admin-notices-list", mapper.writeValueAsString(java.util.Arrays.asList(status, q))));
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scopeToken);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        StringBuilder sql = new StringBuilder("SELECT id,title,publisher,published_at,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id,content,raw_url,attachment_id FROM notices WHERE campus_id=? AND deleted_at IS NULL AND id<?");
        List<Object> args = new ArrayList<>(List.of(actor.campusId(), before));
        if (status != null) { sql.append(" AND status=?"); args.add(status); }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (title LIKE ? ESCAPE '!' OR publisher LIKE ? ESCAPE '!')");
            String like = "%" + escape(q) + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1);
        var rows = jdbc.query(sql.toString(), (r, i) -> new NoticeView(r.getString("id"), r.getString("title"), r.getString("publisher"),
                r.getObject("published_at", LocalDateTime.class) == null ? null : r.getObject("published_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                r.getString("status"), r.getInt("version"), r.getString("source_type"), r.getString("source_name"),
                r.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), r.getBoolean("is_stale"),
                r.getString("official_reference"), r.getString("external_id"), r.getString("content"), r.getString("raw_url"),
                r.getString("attachment_id") == null ? null : r.getString("attachment_id")), args.toArray());
        boolean more = rows.size() > size;
        var page = rows.stream().limit(size).toList();
        String next = null;
        if (more && !page.isEmpty()) next = cursors.encode(new CursorPayload(scopeToken, Map.of("id", page.getLast().id())));
        return new NoticePage(page, next, more);
    }

    /** 创建通知：至少一种原文来源；不接收 sourceType/状态/校园/创建人；rawUrl 创建时即做 SSRF 结构校验。 */
    @BusinessWrite(permission = "notice.write")
    public NoticeView create(NoticeInput input) {
        Actor actor = current.requirePermission("notice.write");
        String title = OpportunityInputs.text(mapper.valueToTree(input.title()), 256, false);
        String publisher = input.publisher() == null ? null : OpportunityInputs.text(mapper.valueToTree(input.publisher()), 128, false);
        String content = input.content() == null ? null : OpportunityInputs.text(mapper.valueToTree(input.content()), 100000, false);
        String rawUrl = input.rawUrl() == null ? null : OpportunityInputs.text(mapper.valueToTree(input.rawUrl()), 1024, false);
        Long attachmentId = input.attachmentId() == null ? null : id(input.attachmentId());
        if ((content == null || content.isBlank()) && (rawUrl == null || rawUrl.isBlank()) && attachmentId == null)
            throw new BusinessException(400, "VALIDATION_FAILED", "至少需要一种通知原文来源");
        if (rawUrl != null) {
            try {
                reader.validate(rawUrl);
            } catch (SafeUrlReader.FetchFailure e) {
                throw new BusinessException(400, "VALIDATION_FAILED", "原文链接不合法");
            }
        }
        if (attachmentId != null) files.owner(actor, attachmentId, "notice_document", true, false);
        LocalDateTime now = LocalDateTime.now(clock);
        long notice = ids.nextId();
        jdbc.update("INSERT INTO notices(id,campus_id,title,content,raw_url,publisher,source_type,status,version,attachment_id,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,'manual','pending',0,?,?,?,?)",
                notice, actor.campusId(), title, content, rawUrl, publisher, attachmentId, actor.userId(), now, now);
        audit(actor, "notice.create", "notice", notice, null);
        return view(notice);
    }

    /** 解析登记与审计原子提交后 202；同通知仅一个进行中的解析代。 */
    @BusinessWrite(permission = "notice.parse")
    public OpportunityViews.AsyncJob parse(String noticeId, ParseInput input) {
        Actor actor = current.requirePermission("notice.parse");
        long notice = lockNotice(noticeId, version(input.version()));
        String status = jdbc.queryForObject("SELECT status FROM notices WHERE id=?", String.class, notice);
        if (!Set.of("pending", "failed").contains(status)) throw OpportunityInputs.stateConflict("当前通知状态不可发起解析");
        int active = jdbc.queryForObject("SELECT COUNT(*) FROM ai_parse_tasks WHERE notice_id=? AND status IN ('pending','processing')", Integer.class, notice);
        if (active > 0) throw OpportunityInputs.stateConflict("该通知已有进行中的解析任务");
        LocalDateTime now = LocalDateTime.now(clock);
        long task = ids.nextId();
        long job = ids.nextId();
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,retry_count,max_retries,created_at,updated_at) VALUES(?,'notice_parse',?,'pending',?,?,'admin',0,3,?,?)",
                job, mapper.writeValueAsString(Map.of("parseTaskId", Long.toString(task), "noticeId", noticeId)), actor.campusId(), actor.userId(), now, now);
        jdbc.update("INSERT INTO ai_parse_tasks(id,notice_id,async_job_id,status,retry_count,created_at,updated_at) VALUES(?,?,?,'pending',0,?,?)",
                task, notice, job, now, now);
        jdbc.update("UPDATE notices SET status='parsing',version=version+1,updated_at=? WHERE id=?", now, notice);
        audit(actor, "notice.parse", "notice", notice, Map.of("parseTaskId", Long.toString(task)));
        // 收据 id 必须是 async_jobs 的 id（文档第 5 节 /jobs/{jobId}），解析任务 id 在任务结果里返回
        return new OpportunityViews.AsyncJob(Long.toString(job), "notice_parse", "pending", null, null, clock.instant(), clock.instant());
    }

    /** 字段级解析结果；跨校 404；不含内部 payload/对象键。 */
    public ParseTaskView parseTask(String taskId) {
        Actor actor = current.requirePermission("notice.read");
        long task = id(taskId);
        var rows = jdbc.queryForList("SELECT t.id,t.status,t.model,t.error FROM ai_parse_tasks t JOIN notices n ON n.id=t.notice_id WHERE t.id=? AND n.campus_id=? AND n.deleted_at IS NULL",
                task, actor.campusId());
        if (rows.isEmpty()) throw missing();
        var row = rows.getFirst();
        List<Map<String, Object>> fields = jdbc.queryForList(
                "SELECT field_key,field_value,evidence_location,confidence,human_confirmed,human_value FROM ai_parse_fields WHERE parse_task_id=? ORDER BY id", task)
                .stream().map(f -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("key", f.get("field_key"));
                    view.put("value", f.get("field_value"));
                    view.put("evidenceLocation", f.get("evidence_location"));
                    view.put("confidence", f.get("confidence"));
                    view.put("humanConfirmed", Boolean.TRUE.equals(f.get("human_confirmed")));
                    view.put("humanValue", f.get("human_value"));
                    return view;
                }).toList();
        return new ParseTaskView(Long.toString(task), (String) row.get("status"), fields, (String) row.get("model"), (String) row.get("error"));
    }

    /** 逐字段 review：只接受固定 Schema 键，approve 只生成 draft；仅当前解析代可审核。 */
    @BusinessWrite(permission = "notice.review")
    public ReviewResult review(String taskId, ReviewInput input) {
        Actor actor = current.requirePermission("notice.review");
        long task = id(taskId);
        String decision = input.decision();
        if (!Set.of("approve", "reject").contains(decision)) throw invalid();
        String reason = OpportunityInputs.text(mapper.valueToTree(input.reason()), 512, false);
        var rows = jdbc.queryForList("SELECT t.id,t.notice_id,t.status,t.review_decision,n.campus_id,n.status AS notice_status,n.title,n.source_type,n.version FROM ai_parse_tasks t JOIN notices n ON n.id=t.notice_id WHERE t.id=? AND n.campus_id=? AND n.deleted_at IS NULL FOR UPDATE",
                task, actor.campusId());
        if (rows.isEmpty()) throw missing();
        var row = rows.getFirst();
        if (!"succeeded".equals(row.get("status"))) throw OpportunityInputs.stateConflict("解析任务尚未完成");
        if (row.get("review_decision") != null) throw OpportunityInputs.stateConflict("该解析结果已复核");
        long noticeId = ((Number) row.get("notice_id")).longValue();
        long latest = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM ai_parse_tasks WHERE notice_id=?", Long.class, noticeId);
        if (latest != task) throw OpportunityInputs.stateConflict("只能复核该通知最新的解析结果");
        if (!"reviewing".equals(row.get("notice_status"))) throw OpportunityInputs.stateConflict("通知当前状态不可复核");
        LocalDateTime now = LocalDateTime.now(clock);
        // 逐字段复核：approve 只把管理员确认过的字段写入草稿，未确认的抽取字段不进入草稿
        Map<String, String> fields = NoticeParseSchema.reviewFields(input.fields());
        if (decision.equals("approve")) {
            if (!fields.containsKey("title") || !fields.containsKey("category"))
                throw new BusinessException(400, "VALIDATION_FAILED", "发布草稿需要确认标题与类别");
            for (Map.Entry<String, String> field : fields.entrySet()) {
                int updated = jdbc.update("UPDATE ai_parse_fields SET human_confirmed=1,human_value=? WHERE parse_task_id=? AND field_key=?",
                        field.getValue(), task, field.getKey());
                if (updated == 0) jdbc.update("INSERT INTO ai_parse_fields(id,parse_task_id,field_key,field_value,human_confirmed,human_value,created_at) VALUES(?,?,?,NULL,1,?,?)",
                        ids.nextId(), task, field.getKey(), field.getValue(), now);
            }
            long draft = createDraft(actor, noticeId, (String) row.get("title"), (String) row.get("source_type"), fields, now);
            jdbc.update("UPDATE ai_parse_tasks SET review_decision='approved',reviewed_by=?,review_reason=?,opportunity_draft_id=?,updated_at=? WHERE id=?",
                    actor.userId(), reason, draft, now, task);
            audit(actor, "notice.review.approve", "notice", noticeId, Map.of("parseTaskId", taskId, "opportunityDraftId", Long.toString(draft)));
            return new ReviewResult(true, Long.toString(draft));
        }
        jdbc.update("UPDATE ai_parse_tasks SET review_decision='rejected',reviewed_by=?,review_reason=?,updated_at=? WHERE id=?", actor.userId(), reason, now, task);
        jdbc.update("UPDATE notices SET status='pending',updated_at=? WHERE id=?", now, noticeId);
        audit(actor, "notice.review.reject", "notice", noticeId, Map.of("parseTaskId", taskId));
        return new ReviewResult(true, null);
    }

    /** approve 只生成 draft 机会（explicit draft，永不自动公开）。 */
    private long createDraft(Actor actor, long noticeId, String noticeTitle, String sourceType, Map<String, String> fields, LocalDateTime now) {
        long opportunity = ids.nextId();
        Instant deadline = fields.containsKey("deadline") ? OpportunityInputs.rfc3339(fields.get("deadline")) : null;
        // Carry the notice's official source metadata so published opportunities keep their origin.
        var sources = jdbc.query("SELECT source_name,external_id,official_reference,is_stale FROM notices WHERE id=?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)}, noticeId);
        if (sources.isEmpty()) throw missing();
        Object[] source = sources.getFirst();
        jdbc.update("INSERT INTO opportunities(id,campus_id,notice_id,title,category,organizer,summary,deadline,status,"
                        + "source_type,source_name,external_id,official_reference,is_stale,rules_version,version,"
                        + "created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'draft',?,?,?,?,?,0,0,?,?,?)",
                opportunity, actor.campusId(), noticeId, fields.get("title"), fields.get("category"), fields.get("organizer"),
                fields.get("summary"), deadline == null ? null : OpportunityService.utc(deadline), sourceType,
                source[0], source[1], source[2], source[3], actor.userId(), now, now);
        jdbc.update("INSERT INTO opportunity_rule_snapshots(id,opportunity_id,rules_version,rules_json,material_requirements_json,created_by,created_at) VALUES(?,?,0,'[]','[]',?,?)",
                ids.nextId(), opportunity, actor.userId(), now);
        return opportunity;
    }

    private long lockNotice(String noticeId, int expectedVersion) {
        long notice = id(noticeId);
        Actor actor = current.requirePermission("notice.parse");
        var rows = jdbc.queryForList("SELECT version FROM notices WHERE id=? AND campus_id=? AND deleted_at IS NULL FOR UPDATE", notice, actor.campusId());
        if (rows.isEmpty()) throw missing();
        if (((Number) rows.getFirst().get("version")).intValue() != expectedVersion)
            throw new BusinessException(409, "VERSION_CONFLICT", "信息已更新，请刷新后重试");
        return notice;
    }

    private NoticeView view(long noticeId) {
        return jdbc.query("SELECT id,title,publisher,published_at,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id,content,raw_url,attachment_id FROM notices WHERE id=?",
                (r, i) -> new NoticeView(r.getString("id"), r.getString("title"), r.getString("publisher"),
                        r.getObject("published_at", LocalDateTime.class) == null ? null : r.getObject("published_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        r.getString("status"), r.getInt("version"), r.getString("source_type"), r.getString("source_name"),
                        r.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), r.getBoolean("is_stale"),
                        r.getString("official_reference"), r.getString("external_id"), r.getString("content"), r.getString("raw_url"),
                        r.getString("attachment_id") == null ? null : r.getString("attachment_id")), noticeId).getFirst();
    }

    private void audit(Actor actor, String action, String targetType, long targetId, Map<String, Object> detail) {
        jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,campus_id,action,target_type,target_id,reason,detail_json,created_at) VALUES(?,?,?,?,?,?,NULL,?,?)",
                ids.nextId(), actor.userId(), actor.campusId(), action, targetType, targetId,
                detail == null ? "{}" : mapper.writeValueAsString(detail), LocalDateTime.now(clock));
    }

    private String escape(String text) { return text.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }

    private BusinessException missing() { return new BusinessException(404, "NOT_FOUND", "未找到该通知"); }
}
