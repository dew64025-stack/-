package com.caizhitong.opportunity;

import com.caizhitong.common.SnowflakeIds;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 资格检查 worker：领取 async_jobs(job_type=eligibility_check)，按入队时的规则版本快照评估，
 * 结果只属于本人；画像快照只落库不进入任务结果。租约/fencing 与文件扫描 worker 同模式。
 */
@Component("eligibilityWorker")
public class EligibilityWorker implements Runnable {
    private record Claim(long job, String token, int attempt, int max) {}
    private final JdbcTemplate jdbc;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;
    public EligibilityWorker(JdbcTemplate jdbc, SnowflakeIds ids, Clock clock, JsonMapper mapper, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
        this.tx.setTimeout(10);
    }

    @Override
    public void run() {
        Claim claim = tx.execute(s -> claim());
        if (claim == null) return;
        try {
            tx.executeWithoutResult(s -> process(claim));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> fail(claim, e));
        }
    }

    private Claim claim() {
        LocalDateTime now = now();
        jdbc.update("UPDATE async_jobs SET status=CASE WHEN retry_count>=max_retries THEN 'failed' ELSE 'pending' END,claim_token=NULL,next_retry_at=?,updated_at=?,error='ELIGIBILITY_LEASE_EXPIRED' WHERE job_type='eligibility_check' AND status='running' AND picked_at<?",
                now, now, now.minusMinutes(5));
        var rows = jdbc.query("SELECT id,retry_count,max_retries FROM async_jobs WHERE job_type='eligibility_check' AND status='pending' AND retry_count<max_retries AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (r, i) -> new Claim(r.getLong(1), UUID.randomUUID().toString(), r.getInt(2) + 1, r.getInt(3)), now);
        if (rows.isEmpty()) return null;
        Claim claim = rows.getFirst();
        jdbc.update("UPDATE async_jobs SET status='running',picked_at=?,claim_token=?,retry_count=retry_count+1,updated_at=? WHERE id=?",
                now, claim.token(), now, claim.job());
        return claim;
    }

    private void process(Claim claim) {
        if (!owns(claim)) return;
        var row = jdbc.queryForList("SELECT payload_json,campus_id,owner_user_id,access_domain FROM async_jobs WHERE id=? FOR UPDATE", claim.job());
        if (row.isEmpty()) return;
        String payloadText = (String) row.getFirst().get("payload_json");
        long campus = ((Number) row.getFirst().get("campus_id")).longValue();
        long owner = ((Number) row.getFirst().get("owner_user_id")).longValue();
        String domain = (String) row.getFirst().get("access_domain");
        if (!"student".equals(domain) || campus == 0 || owner == 0) { terminal(claim, "JOB_SCOPE_INVALID"); return; }
        JsonNode payload = mapper.readTree(payloadText);
        long opportunityId = Long.parseLong(payload.path("opportunityId").asString());
        int rulesVersion = payload.path("rulesVersion").asInt(0);
        JsonNode profile = payload.path("profile");
        Integer profileVersion = payload.has("profileVersion") && !payload.get("profileVersion").isNull() ? payload.get("profileVersion").asInt() : null;
        int exists = jdbc.queryForObject("SELECT COUNT(*) FROM opportunities WHERE id=? AND campus_id=? AND deleted_at IS NULL",
                Integer.class, opportunityId, campus);
        if (exists != 1) { terminal(claim, "OPPORTUNITY_GONE"); return; }
        var snapshots = jdbc.queryForList("SELECT rules_json FROM opportunity_rule_snapshots WHERE opportunity_id=? AND rules_version=?",
                String.class, opportunityId, rulesVersion);
        if (snapshots.isEmpty()) { terminal(claim, "RULES_SNAPSHOT_MISSING"); return; }
        List<EligibilityEngine.Rule> rules = rules(snapshots.getFirst());
        EligibilityEngine.Profile profileValue = new EligibilityEngine.Profile(
                profile.path("college").asString(""), profile.path("grade").asString(""),
                strings(profile.path("skills")), strings(profile.path("interests")), profileVersion);
        EligibilityEngine.Verdict verdict = EligibilityEngine.evaluate(rules, profileValue);
        long checkId = ids.nextId();
        LocalDateTime now = now();
        jdbc.update("INSERT INTO opportunity_eligibility_checks(id,opportunity_id,user_id,conclusion,reason_json,rules_version,profile_snapshot,profile_version,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                checkId, opportunityId, owner, verdict.conclusion(), mapper.writeValueAsString(verdict.reasons()), rulesVersion,
                mapper.writeValueAsString(profile), profileVersion, now);
        JsonNode result = mapper.readTree(mapper.writeValueAsString(java.util.Map.of(
                "checkId", Long.toString(checkId), "opportunityId", Long.toString(opportunityId),
                "conclusion", verdict.conclusion(), "reasons", mapper.readTree(mapper.writeValueAsString(verdict.reasons())),
                "rulesVersion", rulesVersion, "profileVersion", profileVersion == null ? 0 : profileVersion,
                "checkedAt", now.toInstant(java.time.ZoneOffset.UTC).toString())));
        jdbc.update("UPDATE async_jobs SET status='succeeded',claim_token=NULL,error=NULL,result_json=?,updated_at=? WHERE id=? AND claim_token=?",
                mapper.writeValueAsString(result), now, claim.job(), claim.token());
    }

    private void fail(Claim claim, RuntimeException e) {
        if (!owns(claim)) return;
        LocalDateTime now = now();
        jdbc.update("UPDATE async_jobs SET status=?,next_retry_at=?,claim_token=NULL,error='ELIGIBILITY_FAILED',updated_at=? WHERE id=?",
                claim.attempt() >= claim.max() ? "failed" : "pending", now.plusSeconds(30), now, claim.job());
    }

    private void terminal(Claim claim, String code) {
        if (!owns(claim)) return;
        jdbc.update("UPDATE async_jobs SET status='failed',error=?,claim_token=NULL,updated_at=? WHERE id=?", code, now(), claim.job());
    }

    private boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT id FROM async_jobs WHERE id=? AND status='running' AND claim_token=? FOR UPDATE",
                Long.class, claim.job(), claim.token()).isEmpty();
    }

    private List<EligibilityEngine.Rule> rules(String json) {
        List<EligibilityEngine.Rule> rules = new java.util.ArrayList<>();
        for (JsonNode item : mapper.readTree(json)) {
            rules.add(EligibilityEngine.validateRule(item.path("field").asString(), item.path("operator").asString(),
                    item.path("value"), item.path("severity").asString(), item.path("sortOrder").asInt(0)));
        }
        rules.sort(java.util.Comparator.comparingInt(EligibilityEngine.Rule::sortOrder));
        return List.copyOf(rules);
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new java.util.ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> values.add(item.asString()));
        return List.copyOf(values);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
