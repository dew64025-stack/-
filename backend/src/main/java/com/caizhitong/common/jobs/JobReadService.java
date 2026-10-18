package com.caizhitong.common.jobs;

import com.caizhitong.common.BusinessException;
import com.caizhitong.opportunity.OpportunityViews;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import com.caizhitong.security.SessionKind;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;

/**
 * 异步任务受控读取（Task 5 提前实现，Task 12 复用）：
 * 学生域任务仅本人可读；未知归属的旧任务不开放；payload/租约/存储键永不返回。
 */
@Service
public class JobReadService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final JsonMapper mapper;
    public JobReadService(JdbcTemplate jdbc, CurrentActor current, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.mapper = mapper;
    }

    public OpportunityViews.AsyncJob read(String jobId) {
        long value = id(jobId);
        Actor actor = current.require();
        var rows = jdbc.query(
                "SELECT id,job_type,status,result_json,error,created_at,updated_at,campus_id,owner_user_id,access_domain FROM async_jobs WHERE id=?",
                (r, i) -> new Object[] { r.getLong("id"), r.getString("job_type"), r.getString("status"), r.getString("result_json"),
                        r.getString("error"), r.getObject("created_at", LocalDateTime.class), r.getObject("updated_at", LocalDateTime.class),
                        r.getObject("campus_id", Long.class), r.getObject("owner_user_id", Long.class), r.getString("access_domain") },
                value);
        if (rows.isEmpty()) throw missing();
        Object[] row = rows.getFirst();
        if (row[7] == null || row[8] == null || row[9] == null) throw missing();
        long campus = (Long) row[7];
        long owner = (Long) row[8];
        String domain = (String) row[9];
        boolean allowed = switch (domain) {
            case "student" -> actor.kind() == SessionKind.STUDENT && actor.userId() == owner && actor.campusId() == campus;
            case "admin" -> actor.kind() == SessionKind.ADMIN && actor.campusId() == campus;
            default -> false;
        };
        if (!allowed) throw missing();
        String result = (String) row[3];
        String errorCode = (String) row[4];
        return new OpportunityViews.AsyncJob(Long.toString((Long) row[0]), (String) row[1], (String) row[2],
                result == null ? null : mapper.readTree(result),
                errorCode == null ? null : new OpportunityViews.JobError(errorCode, describe(errorCode)),
                row[5] == null ? null : ((LocalDateTime) row[5]).toInstant(ZoneOffset.UTC),
                row[6] == null ? null : ((LocalDateTime) row[6]).toInstant(ZoneOffset.UTC));
    }

    /** 错误码 → 面向客户端的中文说明；未知码不泄露内部细节。 */
    private String describe(String code) {
        return switch (code) {
            case "ELIGIBILITY_LEASE_EXPIRED", "ELIGIBILITY_FAILED" -> "资格检查执行失败";
            case "PARSE_FAILED", "PARSE_LEASE_EXPIRED", "AI_KEY_MISSING", "AI_TIMEOUT", "AI_UPSTREAM", "AI_UNAUTHORIZED",
                    "AI_PAYMENT", "AI_REQUEST", "AI_RATE_LIMITED", "AI_BAD_RESPONSE", "AI_RESPONSE_TOO_LARGE" -> "通知解析失败";
            case "FETCH_URL_INVALID", "FETCH_URL_SCHEME", "FETCH_URL_PORT", "FETCH_URL_BLOCKED", "FETCH_TLS_HOST",
                    "FETCH_TLS_FAILED", "FETCH_REDIRECT_INVALID", "FETCH_TOO_MANY_REDIRECTS", "FETCH_UPSTREAM_STATUS",
                    "FETCH_UPSTREAM_IO", "FETCH_TIMEOUT", "FETCH_HEADER_TOO_LARGE", "FETCH_BODY_TOO_LARGE",
                    "FETCH_DNS_FAILED", "FETCH_CONTENT_TYPE", "FETCH_PROTOCOL" -> "通知原文读取失败";
            case "MATERIAL_PRECHECK_LEASE_EXPIRED", "MATERIAL_PRECHECK_FAILED",
                    "CHECKLIST_PRECHECK_FAILED", "CHECKLIST_PRECHECK_LEASE_EXPIRED" -> "材料预检执行失败";
            case "ASSISTANT_LEASE_EXPIRED", "ASSISTANT_FAILED", "ASSISTANT_AI_BAD_RESPONSE", "ASSISTANT_AI_KEY_MISSING",
                    "ASSISTANT_AI_TIMEOUT", "ASSISTANT_AI_UPSTREAM", "ASSISTANT_AI_UNAUTHORIZED", "ASSISTANT_AI_PAYMENT",
                    "ASSISTANT_AI_REQUEST", "ASSISTANT_AI_RATE_LIMITED", "ASSISTANT_AI_RESPONSE_TOO_LARGE" -> "AI 助手执行失败";
            case "INTEGRATION_NOT_CONFIGURED", "INTEGRATION_CONFIG_INCOMPLETE", "INTEGRATION_PROVIDER_UNAVAILABLE",
                    "INTEGRATION_TIMEOUT", "INTEGRATION_FAILED" -> "学校系统集成执行失败";
            case "JOB_SCOPE_INVALID", "OPPORTUNITY_GONE", "RULES_SNAPSHOT_MISSING", "NOTICE_GONE", "NO_SOURCE",
                    "ATTACHMENT_NO_TEXT", "ATTACHMENT_UNREADABLE", "ATTACHMENT_NO_OCR", "ATTACHMENT_OWNER_MISSING" -> "任务输入已不可用";
            default -> "任务执行失败";
        };
    }

    private BusinessException missing() { return new BusinessException(404, "NOT_FOUND", "未找到该任务"); }
}
