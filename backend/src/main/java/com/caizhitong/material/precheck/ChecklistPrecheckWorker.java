package com.caizhitong.material.precheck;

import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.integration.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
        var rows = jdbc.queryForList("SELECT checklist_id,input_snapshot FROM precheck_tasks WHERE id=?", claim.task());
        if (rows.isEmpty()) throw new IllegalStateException("task gone");
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
            String fileId = item.path("fileId").asString();
            var file = jdbc.queryForList("SELECT file_name,mime_type,size_bytes,expires_at,scan_status,object_key_encrypted FROM material_files WHERE id=? AND status='active' AND deleted_at IS NULL",
                    Long.parseLong(fileId));
            if (file.isEmpty()) {
                results.add(new Result(List.of(new MaterialPrecheckRules.Finding("required", "missing", "材料已被移除",
                        Map.of("itemName", item.path("itemName").asString("")))), itemId, "required", "missing"));
                basicComplete = false;
                continue;
            }
            var row = file.getFirst();
            byte[] magic = magic((byte[]) row.get("object_key_encrypted"));
            List<MaterialPrecheckRules.Finding> findings = MaterialPrecheckRules.check(
                    (String) row.get("file_name"), (String) row.get("mime_type"), ((Number) row.get("size_bytes")).longValue(),
                    row.get("expires_at") == null ? null : ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC),
                    (String) row.get("scan_status"), magic, now);
            if (magic == null) {
                findings = new ArrayList<>(findings);
                findings.add(new MaterialPrecheckRules.Finding("content", "confirm", "无法读取文件内容，请人工确认", Map.of()));
            }
            String level = MaterialPrecheckRules.basicComplete(findings) ? "passed" : findings.stream().anyMatch(f -> "missing".equals(f.level())) ? "missing" : "confirm";
            if (!"passed".equals(level)) basicComplete = false;
            results.add(new Result(findings, itemId, "material", level));
        }
        Map<String, Object> summary = Map.of("basicComplete", basicComplete, "resultCount", results.size(), "checkedAt", now.toString());
        return new Run(results, basicComplete, mapper.writeValueAsString(summary));
    }

    /** 读取对象前 16 字节做文件头识别；对象不存在返回 null（不把存储故障伪装成通过）。 */
    private byte[] magic(byte[] encryptedKey) {
        if (encryptedKey == null) return null;
        try (InputStream input = storage.open(crypto.decrypt("file-object-key", encryptedKey))) {
            return input.readNBytes(16);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void complete(Claim claim, Run run) {
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
            jdbc.update("UPDATE material_checklist_items SET status=?,updated_at=? WHERE id=?", itemStatus, now, result.checklistItemId());
        }
        var task = jdbc.queryForList("SELECT checklist_id FROM precheck_tasks WHERE id=? FOR UPDATE", claim.task());
        if (task.isEmpty()) return;
        long checklistId = ((Number) task.getFirst().get("checklist_id")).longValue();
        jdbc.update("UPDATE material_checklist_items SET status='missing',updated_at=? WHERE checklist_id=? AND status='prechecking'", now, checklistId);
        jdbc.update("UPDATE precheck_tasks SET status='succeeded',result_json=?,claim_token=NULL,updated_at=? WHERE id=? AND claim_token=?",
                run.summaryJson(), now, claim.task(), claim.token());
        jdbc.update("UPDATE async_jobs SET status='succeeded',result_json=?,error=NULL,updated_at=? WHERE id IN (SELECT async_job_id FROM precheck_tasks WHERE id=? AND async_job_id IS NOT NULL)",
                run.summaryJson(), now, claim.task());
    }

    private void fail(Claim claim) {
        if (!owns(claim)) return;
        LocalDateTime now = now();
        jdbc.update("UPDATE precheck_tasks SET status=?,next_retry_at=?,claim_token=NULL,updated_at=? WHERE id=?",
                claim.attempt() >= claim.max() ? "failed" : "pending", now.plusSeconds(30), now, claim.task());
    }

    private boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT id FROM precheck_tasks WHERE id=? AND status='running' AND claim_token=? FOR UPDATE",
                Long.class, claim.task(), claim.token()).isEmpty();
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
