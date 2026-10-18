package com.caizhitong.admin.operations;

import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.integration.notice.SafeUrlReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration jobs finish explicitly. A connection test performs a real, SSRF-safe HTTPS fetch
 * of the configured endpoint and succeeds only for an official provider whose stored configuration
 * matches the one the job was enqueued for; the result fingerprint gates provider activation.
 * Sync has no provider adapter and always fails explicitly instead of fabricating data.
 */
@Component("integrationWorker")
public class IntegrationWorker implements Runnable {
    private static final Set<String> TYPES = Set.of("notice", "library", "classroom", "repair", "crowd");

    private static final Set<String> RETRYABLE = Set.of("INTEGRATION_TIMEOUT", "INTEGRATION_UNREACHABLE",
            "INTEGRATION_FAILED", "INTEGRATION_CIRCUIT_OPEN");
    private static final int CIRCUIT_THRESHOLD = 3;
    private static final long CIRCUIT_SECONDS = 60;

    private record Claim(long job, String token, String jobType, String type, long campus, String fingerprint,
                         int attempt, int maxRetries) {}
    private record Probe(boolean success, String code, String fingerprint) {
        static Probe succeeded(String fingerprint) { return new Probe(true, null, fingerprint); }
        static Probe failed(String code) { return new Probe(false, code, null); }
    }

    private final JdbcTemplate jdbc;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final SafeUrlReader reader;
    private final TransactionTemplate tx;

    public IntegrationWorker(JdbcTemplate jdbc, SnowflakeIds ids, Clock clock, JsonMapper mapper,
                             SafeUrlReader reader, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
        this.reader = reader;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
    }

    @Override
    public void run() {
        Claim claim = tx.execute(status -> claim());
        if (claim == null) return;
        Probe probe = probe(claim);
        if (probe.success()) {
            tx.executeWithoutResult(status -> succeed(claim, probe.fingerprint()));
        } else if (RETRYABLE.contains(probe.code()) && claim.attempt() < claim.maxRetries()) {
            tx.executeWithoutResult(status -> retry(claim, probe.code()));
        } else {
            tx.executeWithoutResult(status -> fail(claim, probe.code()));
        }
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
        var rows = jdbc.query("SELECT id,job_type,payload_json,campus_id,retry_count,max_retries FROM async_jobs"
                        + " WHERE job_type IN ('integration_test','integration_sync') AND status='pending'"
                        + " AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("job_type"), rs.getString("payload_json"),
                        rs.getObject("campus_id", Long.class), rs.getInt("retry_count"), rs.getInt("max_retries")}, now);
        if (rows.isEmpty()) return null;
        Object[] row = rows.getFirst();
        Long campus = (Long) row[3];
        if (campus == null || row[2] == null) {
            jdbc.update("UPDATE async_jobs SET status='failed',error='JOB_SCOPE_INVALID',updated_at=? WHERE id=?",
                    now, row[0]);
            return null;
        }
        String type;
        String fingerprint;
        try {
            JsonNode payload = mapper.readTree((String) row[2]);
            type = payload.path("type").isTextual() ? payload.path("type").asString() : "";
            fingerprint = payload.path("fingerprint").isTextual() ? payload.path("fingerprint").asString() : null;
        } catch (RuntimeException error) {
            type = "";
            fingerprint = null;
        }
        if (!TYPES.contains(type)) {
            jdbc.update("UPDATE async_jobs SET status='failed',error='JOB_SCOPE_INVALID',updated_at=? WHERE id=?",
                    now, row[0]);
            return null;
        }
        Claim claim = new Claim(((Long) row[0]), UUID.randomUUID().toString(), (String) row[1], type, campus,
                fingerprint, ((Number) row[4]).intValue() + 1, ((Number) row[5]).intValue());
        jdbc.update("UPDATE async_jobs SET status='running',picked_at=?,claim_token=?,retry_count=retry_count+1,"
                + "updated_at=? WHERE id=? AND status='pending'", now, claim.token(), now, claim.job());
        return claim;
    }

    /** External I/O runs outside any transaction; only the claim and the terminal write are transactional. */
    private Probe probe(Claim claim) {
        var systems = jdbc.query("SELECT provider,config_json,circuit_open_until FROM external_systems"
                        + " WHERE campus_id=? AND type=?",
                (rs, i) -> new Object[]{rs.getString("provider"), rs.getString("config_json"),
                        rs.getObject("circuit_open_until", LocalDateTime.class)},
                claim.campus(), claim.type());
        if (systems.isEmpty()) return Probe.failed("INTEGRATION_NOT_CONFIGURED");
        String provider = (String) systems.getFirst()[0];
        JsonNode config = parse((String) systems.getFirst()[1]);
        LocalDateTime circuitOpenUntil = (LocalDateTime) systems.getFirst()[2];
        if (circuitOpenUntil != null && circuitOpenUntil.isAfter(now())) {
            return Probe.failed("INTEGRATION_CIRCUIT_OPEN");
        }
        if (!AdminOpsService.INTEGRATION_PROVIDERS.contains(provider)) {
            return Probe.failed("INTEGRATION_PROVIDER_UNAVAILABLE");
        }
        String current = AdminOpsService.fingerprint(provider, claim.type(), config);
        if (!"integration_test".equals(claim.jobType())) {
            // No sync adapter exists; never fabricate school data.
            return Probe.failed("INTEGRATION_PROVIDER_UNAVAILABLE");
        }
        if (claim.fingerprint() == null || !claim.fingerprint().equals(current)) {
            return Probe.failed("INTEGRATION_CONFIG_CHANGED");
        }
        if (!"official".equals(provider)) return Probe.failed("INTEGRATION_PROVIDER_UNAVAILABLE");
        String endpoint = config.path("endpoint").isTextual() ? config.path("endpoint").asString() : "";
        String secretRef = config.path("secretRef").isTextual() ? config.path("secretRef").asString() : "";
        if (endpoint.isBlank() || secretRef.isBlank() || !config.path("schoolAuthorized").asBoolean(false)) {
            return Probe.failed("INTEGRATION_CONFIG_INCOMPLETE");
        }
        long timeoutMs = config.path("timeoutMs").isNumber() ? config.path("timeoutMs").asLong() : 15_000;
        try {
            reader.fetch(endpoint, timeoutMs);
            return Probe.succeeded(current);
        } catch (SafeUrlReader.FetchFailure failure) {
            return Probe.failed("TIMEOUT".equals(failure.code())
                    ? "INTEGRATION_TIMEOUT" : "INTEGRATION_UNREACHABLE");
        } catch (RuntimeException failure) {
            return Probe.failed("INTEGRATION_FAILED");
        }
    }

    private void succeed(Claim claim, String fingerprint) {
        LocalDateTime now = now();
        String result = mapper.writeValueAsString(Map.of("type", claim.type(), "fingerprint", fingerprint));
        int updated = jdbc.update("UPDATE async_jobs SET status='succeeded',error=NULL,result_json=?,"
                        + "claim_token=NULL,updated_at=? WHERE id=? AND status='running' AND claim_token=?",
                result, now, claim.job(), claim.token());
        if (updated != 1) return;
        jdbc.update("UPDATE external_systems SET failure_count=0,circuit_open_until=NULL,updated_at=?"
                + " WHERE campus_id=? AND type=?", now, claim.campus(), claim.type());
        if (!"integration_test".equals(claim.jobType())) return;
        // Lock the row: an admin updating the configuration between the earlier read and this
        // activation must not be able to activate a configuration that was never tested.
        var systems = jdbc.query("SELECT provider,config_json FROM external_systems WHERE campus_id=? AND type=? FOR UPDATE",
                (rs, i) -> new Object[]{rs.getString("provider"), rs.getString("config_json")},
                claim.campus(), claim.type());
        if (systems.isEmpty()) return;
        String provider = (String) systems.getFirst()[0];
        JsonNode config = parse((String) systems.getFirst()[1]);
        if (!"official".equals(provider) || !config.path("schoolAuthorized").asBoolean(false)) return;
        if (!fingerprint.equals(AdminOpsService.fingerprint(provider, claim.type(), config))) return;
        jdbc.update("UPDATE external_systems SET status='active',updated_at=?"
                + " WHERE campus_id=? AND type=? AND status<>'active'", now, claim.campus(), claim.type());
    }

    /** Requeue a transient failure with exponential backoff; circuit state is persisted first. */
    private void retry(Claim claim, String code) {
        LocalDateTime now = now();
        long delay = "INTEGRATION_CIRCUIT_OPEN".equals(code)
                ? CIRCUIT_SECONDS : Math.min(60, 5L << Math.max(0, claim.attempt() - 1));
        int updated = jdbc.update("UPDATE async_jobs SET status='pending',error=?,next_retry_at=?,"
                        + "claim_token=NULL,updated_at=? WHERE id=? AND status='running' AND claim_token=?",
                code, now.plusSeconds(delay), now, claim.job(), claim.token());
        if (updated == 1) recordFailure(claim, code, now);
    }

    private void fail(Claim claim, String code) {
        LocalDateTime now = now();
        int updated = jdbc.update("UPDATE async_jobs SET status='failed',error=?,result_json=NULL,"
                        + "claim_token=NULL,updated_at=? WHERE id=? AND status='running' AND claim_token=?",
                code, now, claim.job(), claim.token());
        if (updated != 1) return;
        recordFailure(claim, code, now);
        if (!"integration_sync".equals(claim.jobType())) return;
        var systems = jdbc.queryForList("SELECT id FROM external_systems WHERE campus_id=? AND type=?",
                Long.class, claim.campus(), claim.type());
        if (systems.isEmpty()) return;
        jdbc.update("INSERT INTO sync_errors(id,external_system_id,external_id,error,created_at) VALUES(?,?,?,?,?)",
                ids.nextId(), systems.getFirst(), null, safeError(code), now);
    }

    private void recordFailure(Claim claim, String code, LocalDateTime now) {
        if (!RETRYABLE.contains(code)) return;
        var rows = jdbc.queryForList("SELECT failure_count FROM external_systems"
                + " WHERE campus_id=? AND type=? FOR UPDATE", claim.campus(), claim.type());
        if (rows.isEmpty()) return;
        int failures = ((Number) rows.getFirst().get("failure_count")).intValue() + 1;
        LocalDateTime openUntil = failures >= CIRCUIT_THRESHOLD ? now.plusSeconds(CIRCUIT_SECONDS) : null;
        jdbc.update("UPDATE external_systems SET failure_count=?,circuit_open_until=?,updated_at=?"
                        + " WHERE campus_id=? AND type=?",
                failures, openUntil, now, claim.campus(), claim.type());
    }

    private JsonNode parse(String raw) {
        try {
            return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (RuntimeException malformed) {
            return mapper.createObjectNode();
        }
    }

    private static String safeError(String code) {
        return switch (code) {
            case "INTEGRATION_NOT_CONFIGURED" -> "集成尚未配置";
            case "INTEGRATION_CONFIG_INCOMPLETE" -> "集成配置不完整";
            case "INTEGRATION_CONFIG_CHANGED" -> "集成配置已变更，请重新测试";
            case "INTEGRATION_PROVIDER_UNAVAILABLE" -> "学校系统提供器不可用";
            case "INTEGRATION_TIMEOUT" -> "集成任务超时";
            case "INTEGRATION_UNREACHABLE" -> "学校系统连接失败";
            case "INTEGRATION_CIRCUIT_OPEN" -> "学校系统暂时熔断，请稍后重试";
            default -> "集成任务失败";
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
