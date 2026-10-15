package com.caizhitong.material.precheck;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.material.checklist.ChecklistService;
import com.caizhitong.opportunity.OpportunityViews;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.version;

/**
 * 预检：单材料基础预检走 async_jobs（jobType=material_precheck）；
 * 整清单预检走 precheck_tasks/results，保存不可变 inputSnapshot/checklistVersion/rulesVersion 快照，
 * 替换材料后旧任务仍可查看但 stale=true。AI 处理材料必须先取得专门授权（aiConsentId）。
 */
@Service
public class PrecheckService {
    public record PrecheckTaskView(String id, String status, int checklistVersion, boolean stale,
            List<ResultItem> results, Instant checkedAt) {}
    public record ResultItem(String checklistItemId, String itemKey, String level, String message, JsonNode evidence) {}

    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final ChecklistService checklists;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    public PrecheckService(JdbcTemplate jdbc, CurrentActor current, ChecklistService checklists, SnowflakeIds ids,
                           Clock clock, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.checklists = checklists;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** 单材料基础预检：202 AsyncJob（async_jobs 收据），仅本人材料版本。 */
    @BusinessWrite
    public OpportunityViews.AsyncJob single(String materialId, JsonNode input) {
        Actor actor = current.require();
        long material = id(materialId);
        int expectedVersion = version(input.get("version"));
        var files = jdbc.queryForList("SELECT version FROM material_files WHERE id=? AND user_id=? AND campus_id=? AND status='active' AND deleted_at IS NULL FOR UPDATE",
                material, actor.userId(), actor.campusId());
        if (files.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "未找到该材料");
        if (((Number) files.getFirst().get("version")).intValue() != expectedVersion)
            throw new BusinessException(409, "VERSION_CONFLICT", "材料已更新，请刷新后重试");
        long versionId = id(com.caizhitong.identity.IdentityInputs.text(input.get("materialVersionId"), 20, false));
        int bound = jdbc.queryForObject("SELECT COUNT(*) FROM material_versions WHERE id=? AND file_id=?", Integer.class, versionId, material);
        if (bound != 1) throw new BusinessException(404, "NOT_FOUND", "未找到该材料版本");
        consentId(input, actor);
        LocalDateTime now = LocalDateTime.now(clock);
        long job = ids.nextId();
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,retry_count,max_retries,created_at,updated_at) VALUES(?,'material_precheck',?,'pending',?,?,'student',0,3,?,?)",
                job, mapper.writeValueAsString(Map.of("materialId", Long.toString(material), "materialVersionId", Long.toString(versionId))),
                actor.campusId(), actor.userId(), now, now);
        return new OpportunityViews.AsyncJob(Long.toString(job), "material_precheck", "pending", null, null, clock.instant(), clock.instant());
    }

    /** 整清单预检：202 AsyncJob（async_jobs 收据）+ precheck_tasks 执行记录，原子提交。 */
    @BusinessWrite
    public OpportunityViews.AsyncJob checklist(String checklistId, JsonNode input) {
        Actor actor = current.require();
        long checklist = checklists.owned(checklistId, actor);
        int expectedVersion = version(input.get("version"));
        var locked = jdbc.queryForList("SELECT version,opportunity_id FROM material_checklists WHERE id=? FOR UPDATE", checklist);
        if (((Number) locked.getFirst().get("version")).intValue() != expectedVersion)
            throw new BusinessException(409, "VERSION_CONFLICT", "清单已更新，请刷新后重试");
        long opportunity = ((Number) locked.getFirst().get("opportunity_id")).longValue();
        consentId(input, actor);
        int rulesVersion = jdbc.queryForObject("SELECT rules_version FROM opportunities WHERE id=?", Integer.class, opportunity);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var item : jdbc.queryForList("SELECT id,item_name,required,material_version_id FROM material_checklist_items WHERE checklist_id=? ORDER BY id", checklist)) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("checklistItemId", ((Number) item.get("id")).toString());
            snapshot.put("itemName", item.get("item_name"));
            Object requiredRaw = item.get("required");
            snapshot.put("required", requiredRaw instanceof Boolean b ? b : ((Number) requiredRaw).intValue() == 1);
            Long versionId = item.get("material_version_id") == null ? null : ((Number) item.get("material_version_id")).longValue();
            snapshot.put("materialVersionId", versionId == null ? null : versionId.toString());
            if (versionId != null) {
                var file = jdbc.queryForList("SELECT f.id,f.file_name,f.mime_type,f.size_bytes,f.expires_at,f.scan_status FROM material_versions v JOIN material_files f ON f.id=v.file_id WHERE v.id=?",
                        versionId).stream().findFirst();
                if (file.isPresent()) {
                    snapshot.put("fileId", ((Number) file.get().get("id")).toString());
                    snapshot.put("fileName", file.get().get("file_name"));
                    snapshot.put("mimeType", file.get().get("mime_type"));
                    snapshot.put("sizeBytes", file.get().get("size_bytes"));
                    snapshot.put("expiresAt", file.get().get("expires_at") == null ? null
                            : ((LocalDateTime) file.get().get("expires_at")).toInstant(ZoneOffset.UTC).toString());
                    snapshot.put("scanStatus", file.get().get("scan_status"));
                }
            }
            items.add(snapshot);
        }
        Map<String, Object> snapshot = Map.of("checklistId", Long.toString(checklist), "opportunityId", Long.toString(opportunity),
                "items", items, "rulesVersion", rulesVersion);
        String snapshotJson = mapper.writeValueAsString(snapshot);
        String digest = sha256(snapshotJson);
        long task = ids.nextId();
        long job = ids.nextId();
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,retry_count,max_retries,created_at,updated_at) VALUES(?,'checklist_precheck',?,'pending',?,?,'student',0,3,?,?)",
                job, mapper.writeValueAsString(Map.of("precheckTaskId", Long.toString(task), "checklistId", Long.toString(checklist))),
                actor.campusId(), actor.userId(), now, now);
        jdbc.update("INSERT INTO precheck_tasks(id,checklist_id,user_id,async_job_id,status,checklist_version,rules_version,input_digest,input_snapshot,model_config_version,ai_consent_id,created_at,updated_at) VALUES(?,?,?,?,'pending',?,?,?,?,'material-precheck:v1',?,?,?)",
                task, checklist, actor.userId(), job, expectedVersion, rulesVersion, digest, snapshotJson,
                consentId(input, actor), now, now);
        jdbc.update("UPDATE material_checklist_items SET status='prechecking',updated_at=? WHERE checklist_id=? AND material_version_id IS NOT NULL", now, checklist);
        return new OpportunityViews.AsyncJob(Long.toString(job), "checklist_precheck", "pending", null, null, clock.instant(), clock.instant());
    }

    /** 最新预检结果：旧版本冲突（stale）不回写，只标记。 */
    public PrecheckTaskView latest(String checklistId) {
        Actor actor = current.require();
        long checklist = checklists.owned(checklistId, actor);
        int currentVersion = jdbc.queryForObject("SELECT version FROM material_checklists WHERE id=?", Integer.class, checklist);
        var tasks = jdbc.query("SELECT id,status,checklist_version,updated_at FROM precheck_tasks WHERE checklist_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                (r, i) -> new Object[] { r.getLong("id"), r.getString("status"), r.getInt("checklist_version"),
                        r.getObject("updated_at", LocalDateTime.class) }, checklist);
        if (tasks.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "暂无预检记录");
        Object[] task = tasks.getFirst();
        long taskId = (Long) task[0];
        List<ResultItem> results = new ArrayList<>();
        if ("succeeded".equals(task[1])) {
            for (var result : jdbc.queryForList("SELECT checklist_item_id,item_key,level,message,evidence_json FROM precheck_results WHERE precheck_task_id=? ORDER BY id", taskId)) {
                results.add(new ResultItem(((Number) result.get("checklist_item_id")).toString(), (String) result.get("item_key"),
                        (String) result.get("level"), (String) result.get("message"),
                        result.get("evidence_json") == null ? null : mapper.readTree((String) result.get("evidence_json"))));
            }
        }
        boolean stale = ((Integer) task[2]) != currentVersion;
        Instant checkedAt = "succeeded".equals(task[1]) && task[3] != null
                ? ((LocalDateTime) task[3]).toInstant(ZoneOffset.UTC) : null;
        return new PrecheckTaskView(Long.toString(taskId), (String) task[1], (Integer) task[2], stale, List.copyOf(results), checkedAt);
    }

    private Long consentId(JsonNode input, Actor actor) {
        long consent = id(com.caizhitong.identity.IdentityInputs.text(input.get("aiConsentId"), 20, false));
        int granted = jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE id=? AND user_id=? AND type='ai' AND status='granted'",
                Integer.class, consent, actor.userId());
        if (granted != 1) throw new BusinessException(403, "FORBIDDEN", "缺少 AI 处理授权");
        return consent;
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
