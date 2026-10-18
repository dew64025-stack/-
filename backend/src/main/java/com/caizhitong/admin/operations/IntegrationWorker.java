package com.caizhitong.admin.operations;

import com.caizhitong.common.SnowflakeIds;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration jobs finish explicitly: there is no provider adapter, so no job is ever marked
 * succeeded and no school data is fabricated. Each failure is recorded with a stable code.
 */
@Component("integrationWorker")
public class IntegrationWorker implements Runnable {
    private static final Set<String> TYPES = Set.of("notice", "library", "classroom", "repair", "crowd");

    private record Claim(long job, String token, String jobType, String type, long campus) {}

    private final JdbcTemplate jdbc;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final AdminOpsService ops;
    private final TransactionTemplate tx;

    public IntegrationWorker(JdbcTemplate jdbc, SnowflakeIds ids, Clock clock, JsonMapper mapper,
                             AdminOpsService ops, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
        this.ops = ops;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
    }

    @Override
    public void run() {
        Claim claim = tx.execute(status -> claim());
        if (claim == null) return;
        String failure;
        try {
            failure = ops.integrationFailure(claim.campus(), claim.type());
        } catch (RuntimeException error) {
            failure = "INTEGRATION_FAILED";
        }
        String code = failure;
        tx.executeWithoutResult(status -> fail(claim, code));
    }

    private Claim claim() {
        LocalDateTime now = now();
        LocalDateTime stale = now.minusMinutes(5);
        jdbc.update("UPDATE async_jobs SET status='failed',error='INTEGRATION_TIMEOUT',claim_token=NULL,updated_at=?"
                        + " WHERE job_type IN ('integration_test','integration_sync') AND status='running'"
                        + " AND picked_at<? AND retry_count>=max_retries", now, stale);
        jdbc.update("UPDATE async_jobs SET status='pending',error=NULL,next_retry_at=?,claim_token=NULL,updated_at=?"
                        + " WHERE job_type IN ('integration_test','integration_sync') AND status='running'"
                        + " AND picked_at<? AND retry_count<max_retries", now, now, stale);
        var rows = jdbc.query("SELECT id,job_type,payload_json,campus_id FROM async_jobs"
                        + " WHERE job_type IN ('integration_test','integration_sync') AND status='pending'"
                        + " AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("job_type"), rs.getString("payload_json"),
                        rs.getObject("campus_id", Long.class)}, now);
        if (rows.isEmpty()) return null;
        Object[] row = rows.getFirst();
        Long campus = (Long) row[3];
        if (campus == null || row[2] == null) {
            jdbc.update("UPDATE async_jobs SET status='failed',error='JOB_SCOPE_INVALID',updated_at=? WHERE id=?",
                    now, row[0]);
            return null;
        }
        String type;
        try {
            JsonNode payload = mapper.readTree((String) row[2]);
            type = payload.path("type").isTextual() ? payload.path("type").asString() : "";
        } catch (RuntimeException error) {
            type = "";
        }
        if (!TYPES.contains(type)) {
            jdbc.update("UPDATE async_jobs SET status='failed',error='JOB_SCOPE_INVALID',updated_at=? WHERE id=?",
                    now, row[0]);
            return null;
        }
        Claim claim = new Claim(((Long) row[0]), UUID.randomUUID().toString(), (String) row[1], type, campus);
        jdbc.update("UPDATE async_jobs SET status='running',picked_at=?,claim_token=?,retry_count=retry_count+1,"
                + "updated_at=? WHERE id=? AND status='pending'", now, claim.token(), now, claim.job());
        return claim;
    }

    private void fail(Claim claim, String code) {
        LocalDateTime now = now();
        int updated = jdbc.update("UPDATE async_jobs SET status='failed',error=?,claim_token=NULL,updated_at=?"
                + " WHERE id=? AND status='running' AND claim_token=?", code, now, claim.job(), claim.token());
        if (updated != 1 || !"integration_sync".equals(claim.jobType())) return;
        var systems = jdbc.queryForList("SELECT id FROM external_systems WHERE campus_id=? AND type=?",
                Long.class, claim.campus(), claim.type());
        if (systems.isEmpty()) return;
        jdbc.update("INSERT INTO sync_errors(id,external_system_id,external_id,error,created_at) VALUES(?,?,?,?,?)",
                ids.nextId(), systems.getFirst(), null, safeError(code), now);
    }

    private static String safeError(String code) {
        return switch (code) {
            case "INTEGRATION_NOT_CONFIGURED" -> "集成尚未配置";
            case "INTEGRATION_CONFIG_INCOMPLETE" -> "集成配置不完整";
            case "INTEGRATION_PROVIDER_UNAVAILABLE" -> "学校系统提供器不可用";
            case "INTEGRATION_TIMEOUT" -> "集成任务超时";
            default -> "集成任务失败";
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
