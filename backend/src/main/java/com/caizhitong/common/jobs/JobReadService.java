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
        return new OpportunityViews.AsyncJob(Long.toString((Long) row[0]), (String) row[1], (String) row[2],
                result == null ? null : mapper.readTree(result), (String) row[4],
                row[5] == null ? null : ((LocalDateTime) row[5]).toInstant(ZoneOffset.UTC),
                row[6] == null ? null : ((LocalDateTime) row[6]).toInstant(ZoneOffset.UTC));
    }

    private BusinessException missing() { return new BusinessException(404, "NOT_FOUND", "未找到该任务"); }
}
