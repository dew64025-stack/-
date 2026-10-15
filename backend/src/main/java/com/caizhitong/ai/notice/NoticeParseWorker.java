package com.caizhitong.ai.notice;

import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.integration.ai.DeepSeekClient;
import com.caizhitong.integration.notice.SafeUrlReader;
import com.caizhitong.material.files.AttachmentService;
import com.caizhitong.security.Actor;
import com.caizhitong.security.ActorLoader;
import com.caizhitong.security.SessionKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通知解析 worker：领取 ai_parse_tasks（租约/fencing/有限重试），原文提取（content/rawUrl/PDF 附件）、
 * DeepSeek 调用与 Schema 校验全部在事务外；扫描件无 OCR 能力明确转人工，不编造文字。
 * 失败回到可人工处理状态（notice→pending）并保留安全错误与原文。
 */
@Component("noticeParseWorker")
public class NoticeParseWorker implements Runnable {
    private static final int MAX_SOURCE_CHARS = 100000;
    private record Claim(long task, String token, int attempt, int max) {}
    private final JdbcTemplate jdbc;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final DeepSeekClient ai;
    private final SafeUrlReader reader;
    private final AttachmentService files;
    private final ActorLoader actors;
    private final TransactionTemplate tx;
    public NoticeParseWorker(JdbcTemplate jdbc, SnowflakeIds ids, Clock clock, JsonMapper mapper, DeepSeekClient ai,
                             SafeUrlReader reader, AttachmentService files, ActorLoader actors, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
        this.ai = ai;
        this.reader = reader;
        this.files = files;
        this.actors = actors;
        this.tx = new TransactionTemplate(tm);
        this.tx.setTimeout(10);
    }

    @Override
    public void run() {
        Claim claim = tx.execute(s -> claim());
        if (claim == null) return;
        try {
            Result result = process(claim);
            tx.executeWithoutResult(s -> complete(claim, result));
        } catch (SourceFailure e) {
            tx.executeWithoutResult(s -> fail(claim, e.code, e.retryable));
        } catch (DeepSeekClient.AiCallException e) {
            tx.executeWithoutResult(s -> fail(claim, e.code(), e.retryable()));
        } catch (SafeUrlReader.FetchFailure e) {
            tx.executeWithoutResult(s -> fail(claim, "FETCH_" + e.code(), e.retryable()));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> fail(claim, "PARSE_FAILED", false));
        }
    }

    /** 原文提取结果或明确的失败原因（code + 是否可重试）。 */
    private record Source(String text) {}
    private static final class SourceFailure extends RuntimeException {
        final String code;
        final boolean retryable;
        SourceFailure(String code, boolean retryable) { super(code); this.code = code; this.retryable = retryable; }
    }

    private Claim claim() {
        LocalDateTime now = now();
        var expired = jdbc.queryForList("SELECT id,notice_id FROM ai_parse_tasks WHERE status='processing' AND picked_at<? AND retry_count>=3", now.minusMinutes(5));
        for (var task : expired) {
            long notice = ((Number) task.get("notice_id")).longValue();
            jdbc.queryForList("SELECT id FROM notices WHERE id=? FOR UPDATE", notice);
            if (latest(notice) == ((Number) task.get("id")).longValue())
                jdbc.update("UPDATE notices SET status='pending',updated_at=? WHERE id=? AND status='parsing' AND EXISTS (SELECT 1 FROM ai_parse_tasks WHERE id=? AND status='processing' AND retry_count>=3 AND picked_at<?)",
                        now, notice, task.get("id"), now.minusMinutes(5));
        }
        jdbc.update("UPDATE ai_parse_tasks SET status=CASE WHEN retry_count>=3 THEN 'failed' ELSE 'pending' END,claim_token=NULL,next_retry_at=?,updated_at=?,error='PARSE_LEASE_EXPIRED' WHERE status='processing' AND picked_at<?",
                now, now, now.minusMinutes(5));
        // 租约恢复同 fail() 一样要终结收据:重试耗尽的恢复任务,其 async_jobs 不能永远停在中途
        jdbc.update("UPDATE async_jobs SET status='failed',error='PARSE_LEASE_EXPIRED',updated_at=? WHERE status NOT IN ('succeeded','failed') AND id IN (SELECT async_job_id FROM ai_parse_tasks WHERE status='failed' AND error='PARSE_LEASE_EXPIRED' AND async_job_id IS NOT NULL)",
                now);
        var rows = jdbc.query("SELECT id,retry_count FROM ai_parse_tasks WHERE status='pending' AND retry_count<3 AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (r, i) -> new Claim(r.getLong(1), UUID.randomUUID().toString(), r.getInt(2) + 1, 3), now);
        if (rows.isEmpty()) return null;
        Claim claim = rows.getFirst();
        jdbc.update("UPDATE ai_parse_tasks SET status='processing',picked_at=?,claim_token=?,retry_count=retry_count+1,updated_at=? WHERE id=?",
                now, claim.token(), now, claim.task());
        return claim;
    }

    private record Result(String model, String promptTemplateVersion, List<NoticeParseSchema.ParsedField> fields,
            String inputDigest, long durationMs, boolean succeeded, String error) {}

    private Result process(Claim claim) {
        long start = System.nanoTime();
        var rows = jdbc.queryForList("SELECT t.notice_id,t.id AS task_id,n.campus_id,n.content,n.raw_url,n.attachment_id,n.created_by FROM ai_parse_tasks t JOIN notices n ON n.id=t.notice_id WHERE t.id=?", claim.task());
        if (rows.isEmpty()) throw new SourceFailure("NOTICE_GONE", false);
        var row = rows.getFirst();
        String content = (String) row.get("content");
        String rawUrl = (String) row.get("raw_url");
        Long attachmentId = row.get("attachment_id") == null ? null : ((Number) row.get("attachment_id")).longValue();
        Long createdBy = row.get("created_by") == null ? null : ((Number) row.get("created_by")).longValue();
        String text;
        if (content != null && !content.isBlank()) {
            text = cap(content);
        } else if (rawUrl != null && !rawUrl.isBlank()) {
            text = cap(reader.fetch(rawUrl).body());
        } else if (attachmentId != null) {
            text = extractAttachment(createdBy, ((Number) row.get("campus_id")).longValue(), attachmentId);
        } else {
            throw new SourceFailure("NO_SOURCE", false);
        }
        String inputDigest = sha256(text);
        DeepSeekClient.Completion completion = ai.complete(NoticeParseSchema.systemPrompt(), NoticeParseSchema.userPrompt(text), 8192);
        NoticeParseSchema.ParseResult parsed = NoticeParseSchema.parse(mapper.readTree(completion.content()), text, completion.model());
        return new Result(completion.model(), NoticeParseSchema.PROMPT_TEMPLATE_VERSION, parsed.fields(), inputDigest,
                (System.nanoTime() - start) / 1_000_000, true, null);
    }

    private String extractAttachment(Long createdBy, long campus, long attachmentId) {
        if (createdBy == null) throw new SourceFailure("ATTACHMENT_OWNER_MISSING", false);
        Actor actor = actors.load(createdBy, campus, SessionKind.ADMIN).orElseThrow(() -> new SourceFailure("ATTACHMENT_OWNER_MISSING", false));
        var attachment = files.owner(actor, attachmentId, "notice_document", true, false);
        if (attachment.mime().equals("application/pdf")) {
            try {
                String text = new PDFTextStripper().getText(Loader.loadPDF(attachmentBytes(actor, attachmentId)));
                if (text == null || text.isBlank()) throw new SourceFailure("ATTACHMENT_NO_TEXT", false);
                return cap(text);
            } catch (SourceFailure e) {
                throw e;
            } catch (RuntimeException | java.io.IOException e) {
                throw new SourceFailure("ATTACHMENT_UNREADABLE", false);
            }
        }
        throw new SourceFailure("ATTACHMENT_NO_OCR", false); // 图片扫描件无 OCR 能力，明确转人工
    }

    private byte[] attachmentBytes(Actor actor, long attachmentId) {
        return files.readCleanOwner(actor, attachmentId, "notice_document", 20 * 1024 * 1024);
    }

    private void complete(Claim claim, Result result) {
        if (!owns(claim)) return;
        LocalDateTime now = now();
        var row = jdbc.queryForList("SELECT notice_id FROM ai_parse_tasks WHERE id=? FOR UPDATE", claim.task());
        if (row.isEmpty()) return;
        long noticeId = ((Number) row.getFirst().get("notice_id")).longValue();
        for (NoticeParseSchema.ParsedField field : result.fields()) {
            jdbc.update("INSERT INTO ai_parse_fields(id,parse_task_id,field_key,field_value,evidence_location,confidence,human_confirmed,created_at) VALUES(?,?,?,?,?,?,0,?)",
                    ids.nextId(), claim.task(), field.key(), field.value(), field.evidenceLocation(), field.confidence(), now);
        }
        var summary = java.util.Map.of("parseTaskId", Long.toString(claim.task()), "fieldCount", result.fields().size(),
                "model", result.model());
        jdbc.update("UPDATE ai_parse_tasks SET status='succeeded',model=?,result_json=?,input_digest=?,prompt_template_version=?,duration_ms=?,error=NULL,claim_token=NULL,updated_at=? WHERE id=? AND claim_token=?",
                result.model(), mapper.writeValueAsString(summary), result.inputDigest(), result.promptTemplateVersion(), result.durationMs(), now, claim.task(), claim.token());
        if (latest(noticeId) == claim.task()) {
            jdbc.update("UPDATE notices SET status='reviewing',updated_at=? WHERE id=?", now, noticeId);
        }
        finishAsyncJob(claim, "succeeded", mapper.writeValueAsString(summary), null);
    }

    private void fail(Claim claim, String code, boolean retryable) {
        if (!owns(claim)) return;
        LocalDateTime now = now();
        var row = jdbc.queryForList("SELECT notice_id FROM ai_parse_tasks WHERE id=? FOR UPDATE", claim.task());
        if (row.isEmpty()) return;
        long noticeId = ((Number) row.getFirst().get("notice_id")).longValue();
        boolean exhausted = !retryable || claim.attempt() >= claim.max();
        String status = exhausted ? "failed" : "pending";
        jdbc.update("UPDATE ai_parse_tasks SET status=?,next_retry_at=?,error=?,claim_token=NULL,updated_at=? WHERE id=?",
                status, retryable && !exhausted ? now.plusSeconds(30) : null, code, now, claim.task());
        if (exhausted && latest(noticeId) == claim.task()) {
            jdbc.update("UPDATE notices SET status='pending',updated_at=? WHERE id=?", now, noticeId);
        }
        // 收据与任务状态一致：可重试时保持 pending，耗尽才 failed
        finishAsyncJob(claim, exhausted ? "failed" : "pending", null, code);
    }

    private void finishAsyncJob(Claim claim, String status, String resultJson, String error) {
        jdbc.update("UPDATE async_jobs SET status=?,result_json=?,error=?,updated_at=? WHERE id IN (SELECT async_job_id FROM ai_parse_tasks WHERE id=? AND async_job_id IS NOT NULL)",
                status, resultJson, error, now(), claim.task());
    }

    private long latest(long noticeId) {
        var rows = jdbc.queryForList("SELECT id FROM ai_parse_tasks WHERE notice_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE", Long.class, noticeId);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT id FROM ai_parse_tasks WHERE id=? AND status='processing' AND claim_token=? FOR UPDATE",
                Long.class, claim.task(), claim.token()).isEmpty();
    }

    private String cap(String text) {
        if (text.length() <= MAX_SOURCE_CHARS) return text;
        return text.substring(0, MAX_SOURCE_CHARS);
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
