package com.caizhitong.material.precheck;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.integration.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 单材料基础预检 worker：领取 async_jobs(job_type=material_precheck)，
 * 检查格式/大小/命名/有效期/安全扫描/文件头基础识别；不宣称机会材料已完整。
 */
@Component("materialPrecheckWorker")
public class MaterialPrecheckWorker implements Runnable {
    private record Claim(long job, String token, int attempt, int max) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper mapper;
    private final SensitiveDataCrypto crypto;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;
    public MaterialPrecheckWorker(JdbcTemplate jdbc, Clock clock, JsonMapper mapper, SensitiveDataCrypto crypto,
                                  ObjectStorage storage, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
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
            Map<String, Object> result = process(claim);
            tx.executeWithoutResult(s -> complete(claim, result));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> fail(claim));
        }
    }

    private Claim claim() {
        LocalDateTime now = now();
        jdbc.update("UPDATE async_jobs SET status=CASE WHEN retry_count>=max_retries THEN 'failed' ELSE 'pending' END,claim_token=NULL,next_retry_at=?,updated_at=?,error='MATERIAL_PRECHECK_LEASE_EXPIRED' WHERE job_type='material_precheck' AND status='running' AND picked_at<?",
                now, now, now.minusMinutes(5));
        var rows = jdbc.query("SELECT id,retry_count,max_retries FROM async_jobs WHERE job_type='material_precheck' AND status='pending' AND retry_count<max_retries AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (r, i) -> new Claim(r.getLong(1), UUID.randomUUID().toString(), r.getInt(2) + 1, r.getInt(3)), now);
        if (rows.isEmpty()) return null;
        Claim claim = rows.getFirst();
        jdbc.update("UPDATE async_jobs SET status='running',picked_at=?,claim_token=?,retry_count=retry_count+1,updated_at=? WHERE id=?",
                now, claim.token(), now, claim.job());
        return claim;
    }

    private Map<String, Object> process(Claim claim) {
        var rows = jdbc.queryForList("SELECT payload_json,campus_id,owner_user_id,access_domain FROM async_jobs WHERE id=?", claim.job());
        if (rows.isEmpty()) throw new IllegalStateException("job gone");
        var row = rows.getFirst();
        if (!"student".equals(row.get("access_domain")) || row.get("campus_id") == null || row.get("owner_user_id") == null)
            throw new IllegalStateException("job scope invalid");
        var payload = mapper.readTree((String) row.get("payload_json"));
        long materialId = Long.parseLong(payload.path("materialId").asString());
        long versionId = Long.parseLong(payload.path("materialVersionId").asString());
        long campus = ((Number) row.get("campus_id")).longValue();
        long owner = ((Number) row.get("owner_user_id")).longValue();
        var files = jdbc.queryForList("SELECT f.id,f.file_name,f.mime_type,f.size_bytes,f.expires_at,f.scan_status,f.object_key_encrypted FROM material_versions v JOIN material_files f ON f.id=v.file_id WHERE v.id=? AND v.file_id=? AND f.user_id=? AND f.campus_id=? AND f.status='active' AND f.deleted_at IS NULL",
                versionId, materialId, owner, campus);
        if (files.isEmpty()) {
            return summary(materialId, versionId, false, List.of(new MaterialPrecheckRules.Finding("required", "missing",
                    "材料已被移除或不属于本人", Map.of())));
        }
        var file = files.getFirst();
        byte[] magic = magic((byte[]) file.get("object_key_encrypted"));
        List<MaterialPrecheckRules.Finding> findings = MaterialPrecheckRules.check(
                (String) file.get("file_name"), (String) file.get("mime_type"), ((Number) file.get("size_bytes")).longValue(),
                file.get("expires_at") == null ? null : ((LocalDateTime) file.get("expires_at")).toInstant(ZoneOffset.UTC),
                (String) file.get("scan_status"), magic, clock.instant());
        return summary(materialId, versionId, MaterialPrecheckRules.basicComplete(findings), findings);
    }

    private Map<String, Object> summary(long materialId, long versionId, boolean basicComplete, List<MaterialPrecheckRules.Finding> findings) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("materialId", Long.toString(materialId));
        summary.put("materialVersionId", Long.toString(versionId));
        summary.put("basicComplete", basicComplete);
        summary.put("results", findings);
        summary.put("checkedAt", clock.instant().toString());
        return summary;
    }

    private byte[] magic(byte[] encryptedKey) {
        if (encryptedKey == null) return null;
        try (InputStream input = storage.open(crypto.decrypt("file-object-key", encryptedKey))) {
            return input.readNBytes(16);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void complete(Claim claim, Map<String, Object> result) {
        if (!owns(claim)) return;
        jdbc.update("UPDATE async_jobs SET status='succeeded',result_json=?,error=NULL,claim_token=NULL,updated_at=? WHERE id=? AND claim_token=?",
                mapper.writeValueAsString(result), now(), claim.job(), claim.token());
    }

    private void fail(Claim claim) {
        if (!owns(claim)) return;
        LocalDateTime now = now();
        jdbc.update("UPDATE async_jobs SET status=?,next_retry_at=?,claim_token=NULL,error='MATERIAL_PRECHECK_FAILED',updated_at=? WHERE id=?",
                claim.attempt() >= claim.max() ? "failed" : "pending", now.plusSeconds(30), now, claim.job());
    }

    private boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT id FROM async_jobs WHERE id=? AND status='running' AND claim_token=? FOR UPDATE",
                Long.class, claim.job(), claim.token()).isEmpty();
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
