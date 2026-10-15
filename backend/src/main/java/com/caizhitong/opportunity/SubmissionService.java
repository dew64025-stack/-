package com.caizhitong.opportunity;

import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户自行提交：本人待办记录，不代表平台代交。
 * 用户＋机会最多一份当前记录，由 V10 唯一约束 + 事务保证，并发重复提交明确失败而不是静默删历史。
 */
@Service
public class SubmissionService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final OpportunityService opportunities;
    private final SnowflakeIds ids;
    private final Clock clock;
    public SubmissionService(JdbcTemplate jdbc, CurrentActor current, OpportunityService opportunities, SnowflakeIds ids, Clock clock) {
        this.jdbc = jdbc;
        this.current = current;
        this.opportunities = opportunities;
        this.ids = ids;
        this.clock = clock;
    }

    @BusinessWrite(student = true)
    public OpportunityViews.Submission submit(String opportunityId, Instant submittedAt) {
        long opportunity = opportunities.visible(opportunityId);
        if (submittedAt == null) throw com.caizhitong.identity.IdentityInputs.invalid();
        Actor actor = current.requireStudent();
        LocalDateTime now = LocalDateTime.now(clock);
        var existing = jdbc.queryForList("SELECT id,submitted_at FROM user_opportunity_submissions WHERE user_id=? AND opportunity_id=? FOR UPDATE",
                Long.class, actor.userId(), opportunity);
        LocalDateTime submitted = OpportunityService.utc(submittedAt);
        if (existing.isEmpty()) {
            long record = ids.nextId();
            try {
                jdbc.update("INSERT INTO user_opportunity_submissions(id,user_id,opportunity_id,status,submitted_at,created_at) VALUES(?,?,?,'submitted',?,?)",
                        record, actor.userId(), opportunity, submitted, now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new com.caizhitong.common.BusinessException(409, "ALREADY_EXISTS", "已存在提交记录，请刷新后重试");
            }
            return new OpportunityViews.Submission(Long.toString(record), opportunityId, "submitted", submittedAt);
        }
        long record = existing.getFirst();
        jdbc.update("UPDATE user_opportunity_submissions SET submitted_at=? WHERE id=?", submitted, record);
        return new OpportunityViews.Submission(Long.toString(record), opportunityId, "submitted", submittedAt);
    }
}
