package com.caizhitong.assistant;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.ratelimit.RateLimiter;
import com.caizhitong.opportunity.OpportunityViews;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AssistantService {
    private static final Set<String> SCOPES = Set.of("campus", "opportunity", "service");

    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final RateLimiter rates;

    public AssistantService(JdbcTemplate jdbc, CurrentActor current, SnowflakeIds ids, Clock clock, JsonMapper mapper,
                            RateLimiter rates) {
        this.jdbc = jdbc;
        this.current = current;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
        this.rates = rates;
    }

    @BusinessWrite
    public OpportunityViews.AsyncJob enqueue(String questionValue, String scopeValue) {
        Actor actor = current.require();
        String question = questionValue == null ? "" : questionValue.trim();
        if (question.isEmpty() || question.length() > 2000) {
            throw new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
        }
        String scope = scopeValue == null || scopeValue.isBlank() ? "campus" : scopeValue.trim();
        if (!SCOPES.contains(scope)) throw new BusinessException(400, "VALIDATION_FAILED", "请检查填写的信息");
        rates.check("assistant:" + actor.userId(), 5, Duration.ofMinutes(1));

        long job = ids.nextId();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String payload = mapper.writeValueAsString(Map.of("question", question, "scope", scope));
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,"
                        + "retry_count,max_retries,created_at,updated_at)"
                        + " VALUES(?,'ai_assistant',?,'pending',?,?,'student',0,2,?,?)",
                job, payload, actor.campusId(), actor.userId(), now, now);
        return new OpportunityViews.AsyncJob(Long.toString(job), "ai_assistant", "pending", null, null,
                clock.instant(), clock.instant());
    }
}
