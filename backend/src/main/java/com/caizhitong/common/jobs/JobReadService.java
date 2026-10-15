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
        var rows = jdbc.queryForList(
                "SELECT id,job_type,status,result_json,error,created_at,updated_at,campus_id,owner_user_id,access_domain FROM async_jobs WHERE id=?",
                value);
        if (rows.isEmpty()) throw missing();
        Map<String, Object> row = rows.getFirst();
        if (row.get("campus_id") == null || row.get("owner_user_id") == null || row.get("access_domain") == null) throw missing();
        long campus = ((Number) row.get("campus_id")).longValue();
        long owner = ((Number) row.get("owner_user_id")).longValue();
        String domain = (String) row.get("access_domain");
        boolean allowed = switch (domain) {
            case "student" -> actor.kind() == SessionKind.STUDENT && actor.userId() == owner && actor.campusId() == campus;
            case "admin" -> actor.kind() == SessionKind.ADMIN && actor.campusId() == campus;
            default -> false;
        };
        if (!allowed) throw missing();
        String result = (String) row.get("result_json");
        return new OpportunityViews.AsyncJob(Long.toString(value), (String) row.get("job_type"), (String) row.get("status"),
                result == null ? null : mapper.readTree(result), (String) row.get("error"),
                toInstant(row.get("created_at")), toInstant(row.get("updated_at")));
    }

    private java.time.Instant toInstant(Object value) {
        if (value == null) return null;
        return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private BusinessException missing() { return new BusinessException(404, "NOT_FOUND", "未找到该任务"); }
}
