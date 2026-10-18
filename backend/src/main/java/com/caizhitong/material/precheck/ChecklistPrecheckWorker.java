package com.caizhitong.material.precheck;

import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.integration.storage.ObjectStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 整清单预检 worker：领取 precheck_tasks（租约/fencing/有限重试），按不可变输入快照逐项检查；
 * 缺必交项即 missing；替换材料后旧任务不覆盖当前状态（stale 由读取端标记）。
 * 对象存储读取在事务外。
 */
@Component("checklistPrecheckWorker")
public class ChecklistPrecheckWorker implements Runnable {
    private record Claim(long task, String token, int attempt, int max) {}
    private final JdbcTemplate jdbc;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    private final SensitiveDataCrypto crypto;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;
    public ChecklistPrecheckWorker(JdbcTemplate jdbc, SnowflakeIds ids, Clock clock, JsonMapper mapper,
                                   SensitiveDataCrypto crypto, ObjectStorage storage, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
        this.crypto = crypto;
        this.storage = storage;
        this.tx = new TransactionTemplate(tm);
        this.tx.setTimeout(10);
    }

    @Override
    public void run() {
        Claim claim = tx.execute(s -> claim());
        if (claim == null) return;
        try {
            Run run = process(claim);
            tx.executeWithoutResult(s -> complete(claim, run));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> fail(claim));
        }
    }

    private Claim claim() {
        LocalDateTime now = now();
        jdbc.update("UPDATE precheck_tasks SET status=CASE WHEN retry_count>=3 THEN 'failed' ELSE 'pending' END,claim_token=NULL,next_retry_at=?,updated_at=?,result_json=NULL WHERE status='running' AND picked_at<?",
                now, now, now.minusMinutes(5));
        // 租约恢复同 fail() 一样要终结收据:重试耗尽的恢复任务,其 async_jobs 不能永远停在中途
        jdbc.update("UPDATE async_jobs SET status='failed',error='CHECKLIST_PRECHECK_LEASE_EXPIRED',updated_at=? WHERE status NOT IN ('succeeded','failed') AND id IN (SELECT async_job_id FROM precheck_tasks WHERE status='failed' AND retry_count>=3 AND claim_token IS NULL AND async_job_id IS NOT NULL)",
                now);
        for (long failed : jdbc.queryForList("SELECT id FROM precheck_tasks WHERE status='failed' AND retry_count>=3", Long.class)) restoreItems(failed);
        var rows = jdbc.query("SELECT id,retry_count FROM precheck_tasks WHERE status='pending' AND retry_count<3 AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (r, i) -> new Claim(r.getLong(1), UUID.randomUUID().toString(), r.getInt(2) + 1, 3), now);
        if (rows.isEmpty()) return null;
        Claim claim = rows.getFirst();
        jdbc.update("UPDATE precheck_tasks SET status='running',picked_at=?,claim_token=?,retry_count=retry_count+1,updated_at=? WHERE id=?",
                now, claim.token(), now, claim.task());
        return claim;
    }

    private record Result(List<MaterialPrecheckRules.Finding> findings, long checklistItemId, String itemKey, String level) {}
    private record Run(List<Result> results, boolean basicComplete, String summaryJson) {}

    private Run process(Claim claim) {
        var rows = jdbc.queryForList("SELECT t.checklist_id,t.input_snapshot,t.user_id,t.ai_consent_id,j.campus_id FROM precheck_tasks t JOIN async_jobs j ON j.id=t.async_job_id WHERE t.id=?", claim.task());
        if (rows.isEmpty()) throw new IllegalStateException("task gone");
        var task = rows.getFirst();
        long owner = ((Number) task.get("user_id")).longValue();
        if (task.get("ai_consent_id") == null || task.get("campus_id") == null
                || jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE id=? AND user_id=? AND type IN ('ai','material_ai') AND status='granted'", Integer.class, task.get("ai_consent_id"), owner) != 1)
            throw new IllegalStateException("consent revoked");
        long campus = ((Number) task.get("campus_id")).longValue();
        String snapshotText = (String) rows.getFirst().get("input_snapshot");
        JsonNode snapshot = mapper.readTree(snapshotText);
        Instant now = clock.instant();
        List<Result> results = new ArrayList<>();
        boolean basicComplete = true;
        for (JsonNode item : snapshot.path("items")) {
            long itemId = Long.parseLong(item.path("checklistItemId").asString());
            boolean required = item.path("required").asBoolean(true);
            if (!item.has("materialVersionId") || item.get("materialVersionId").isNull()) {
                if (required) {
                    results.add(new Result(List.of(new MaterialPrecheckRules.Finding("required", "missing", "缺少必交材料",
                            Map.of("itemName", item.path("itemName").asString("")))), itemId, "required", "missing"));
                    basicComplete = false;
                }
                continue;
            }
            var row = VersionPrecheckFiles.metadata(jdbc, Long.parseLong(item.path("materialVersionId").asString()), owner, campus);
            if (row == null) {
                results.add(new Result(List.of(new MaterialPrecheckRules.Finding("required", "missing", "材料已被移除",
                        Map.of("itemName", item.path("itemName").asString("")))), itemId, "required", "missing"));
                basicComplete = false;
                continue;
            }
            if (!item.path("fileHash").asString().equals(row.get("file_hash"))
                    || !item.path("attachmentId").asString().equals(row.get("attachment_id").toString())) throw new IllegalStateException("version changed");
            byte[] magic = VersionPrecheckFiles.magic(row, crypto, storage);
            List<MaterialPrecheckRules.Finding> findings = MaterialPrecheckRules.check(
                    item.path("fileName").asString(), item.path("mimeType").asString(), item.path("sizeBytes").asLong(),
                    item.path("expiresAt").isNull() ? null : Instant.parse(item.path("expiresAt").asString()),
                    "clean", magic, now, item.get("validationRules"));
            String level = MaterialPrecheckRules.basicComplete(findings) ? "passed" : findings.stream().anyMatch(f -> "missing".equals(f.level())) ? "missing" : "confirm";
            if (!"passed".equals(level)) basicComplete = false;
            results.add(new Result(findings, itemId, "material", level));
        }
        Map<String, Object> summary = Map.of("basicComplete", basicComplete, "resultCount", results.size(), "checkedAt", now.toString());
        return new Run(results, basicComplete, mapper.writeValueAsString(summary));
    }

    private void complete(Claim claim, Run run) {
        boolean current = currentTask(claim.task());
        if (!owns(claim)) return;
        LocalDateTime now = now();
        for (Result result : run.results()) {
            for (MaterialPrecheckRules.Finding finding : result.findings()) {
                jdbc.update("INSERT INTO precheck_results(id,precheck_task_id,checklist_item_id,item_key,level,message,evidence_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                        ids.nextId(), claim.task(), result.checklistItemId(), finding.itemKey(), finding.level(), finding.message(),
                        mapper.writeValueAsString(finding.evidence()), now);
            }
            String itemStatus = switch (result.level()) {
                case "passed" -> "basic_complete";
                case "missing", "confirm" -> "needs_fix";
                default -> "missing";
            };
            if (current) jdbc.update("UPDATE material_checklist_items SET status=?,updated_at=? WHERE id=?", itemStatus, now, result.checklistItemId());
        }
        var task = jdbc.queryForList("SELECT checklist_id FROM precheck_tasks WHERE id=? FOR UPDATE", claim.task());
        if (task.isEmpty()) return;
        long checklistId = ((Number) task.getFirst().get("checklist_id")).longValue();
        if (current) jdbc.update("UPDATE material_checklist_items SET status=CASE WHEN material_version_id IS NULL THEN 'missing' ELSE 'uploaded' END,updated_at=? WHERE checklist_id=? AND status='prechecking'", now, checklistId);
        jdbc.update("UPDATE precheck_tasks SET status='succeeded',result_json=?,claim_token=NULL,updated_at=? WHERE id=? AND claim_token=?",
                run.summaryJson(), now, claim.task(), claim.token());
        jdbc.update("UPDATE async_jobs SET status='succeeded',result_json=?,error=NULL,updated_at=? WHERE id IN (SELECT async_job_id FROM precheck_tasks WHERE id=? AND async_job_id IS NOT NULL)",
                run.summaryJson(), now, claim.task());
    }

    private void fail(Claim claim) {
        currentTask(claim.task());
        if (!owns(claim)) return;
        LocalDateTime now = now();
        boolean exhausted = claim.attempt() >= claim.max();
        jdbc.update("UPDATE precheck_tasks SET status=?,next_retry_at=?,claim_token=NULL,updated_at=? WHERE id=?",
                exhausted ? "failed" : "pending", now.plusSeconds(30), now, claim.task());
        // 收据与任务状态一致：重试期间保持 pending，耗尽才 failed，不能让 /jobs/{jobId} 永远停在中途
        if (exhausted) {
            jdbc.update("UPDATE async_jobs SET status='failed',error='CHECKLIST_PRECHECK_FAILED',updated_at=? WHERE id IN (SELECT async_job_id FROM precheck_tasks WHERE id=? AND async_job_id IS NOT NULL)",
                    now, claim.task());
            restoreItems(claim.task());
        }
    }

    /** Lock the parent first, matching checklist edits; only the latest task for this version may publish item state. */
    private boolean currentTask(long task) {
        var rows = jdbc.queryForList("SELECT checklist_id,checklist_version FROM precheck_tasks WHERE id=?", task);
        if (rows.isEmpty()) return false;
        long checklist = ((Number) rows.getFirst().get("checklist_id")).longValue();
        int version = jdbc.queryForObject("SELECT version FROM material_checklists WHERE id=? FOR UPDATE", Integer.class, checklist);
        var latestRows = jdbc.queryForList("SELECT id FROM precheck_tasks WHERE checklist_id=? AND checklist_version=? ORDER BY id DESC LIMIT 1 FOR UPDATE", Long.class, checklist, version);
        Long latest = latestRows.isEmpty() ? null : latestRows.getFirst();
        return version == ((Number) rows.getFirst().get("checklist_version")).intValue() && latest != null && latest == task;
    }

    private void restoreItems(long task) {
        if (currentTask(task)) jdbc.update("UPDATE material_checklist_items SET status=CASE WHEN material_version_id IS NULL THEN 'missing' ELSE 'uploaded' END,updated_at=? WHERE status='prechecking' AND checklist_id IN (SELECT checklist_id FROM precheck_tasks WHERE id=?)", now(), task);
    }

    private boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT id FROM precheck_tasks WHERE id=? AND status='running' AND claim_token=? FOR UPDATE",
                Long.class, claim.task(), claim.token()).isEmpty();
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
