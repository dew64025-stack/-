package com.caizhitong.material.checklist;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.opportunity.OpportunityService;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.version;

/**
 * 材料清单：某机会对应用户的保险箱视图；创建时按当时的机会材料要求复制条目；
 * 清单项绑定/解绑不可变的材料版本，父清单版本提升；材料使用留痕，删除材料前受引用保护。
 */
@Service
public class ChecklistService {
    public record Checklist(String id, String opportunityId, String status, int version, List<Item> items) {}
    public record Item(String id, String itemName, boolean required, String materialVersionId, String status) {}
    public record ItemUpdate(JsonNode input) {}

    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final OpportunityService opportunities;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final JsonMapper mapper;
    public ChecklistService(JdbcTemplate jdbc, CurrentActor current, OpportunityService opportunities, SnowflakeIds ids,
                            Clock clock, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.opportunities = opportunities;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** 创建或取得本人清单；并发创建由 (user_id, opportunity_id) 唯一约束收口。 */
    @BusinessWrite(student = true)
    public Checklist getOrCreate(String opportunityId) {
        long opportunity = opportunities.visible(opportunityId);
        Actor actor = current.requireStudent();
        var existing = jdbc.queryForList("SELECT id FROM material_checklists WHERE user_id=? AND opportunity_id=?",
                Long.class, actor.userId(), opportunity);
        if (!existing.isEmpty()) return view(existing.getFirst());
        LocalDateTime now = LocalDateTime.now(clock);
        long checklist = ids.nextId();
        try {
            jdbc.update("INSERT INTO material_checklists(id,user_id,opportunity_id,status,version,created_at,updated_at) VALUES(?,?,?,'draft',0,?,?)",
                    checklist, actor.userId(), opportunity, now, now);
            for (var requirement : jdbc.query("SELECT name,description,required FROM opportunity_material_items WHERE opportunity_id=? ORDER BY sort_order,id",
                    (r, i) -> new Object[] { r.getString("name"), r.getString("description"), r.getBoolean("required") }, opportunity)) {
                jdbc.update("INSERT INTO material_checklist_items(id,checklist_id,item_name,required,material_version_id,status,created_at,updated_at) VALUES(?,?,?,?,NULL,'missing',?,?)",
                        ids.nextId(), checklist, requirement[0], requirement[2], now, now);
            }
        } catch (DuplicateKeyException e) {
            var created = jdbc.queryForList("SELECT id FROM material_checklists WHERE user_id=? AND opportunity_id=?",
                    Long.class, actor.userId(), opportunity);
            return view(created.getFirst());
        }
        return view(checklist);
    }

    public Checklist get(String checklistId) {
        Actor actor = current.require();
        long checklist = owned(checklistId, actor);
        return view(checklist);
    }

    /** 绑定/解绑本人材料版本（不可变版本）；锁父清单并增版本。 */
    @BusinessWrite
    public Checklist updateItem(String checklistId, String itemId, JsonNode input) {
        Actor actor = current.require();
        long checklist = owned(checklistId, actor);
        int expectedVersion = version(input.get("version"));
        var locked = jdbc.queryForList("SELECT version FROM material_checklists WHERE id=? FOR UPDATE", checklist);
        if (((Number) locked.getFirst().get("version")).intValue() != expectedVersion)
            throw new BusinessException(409, "VERSION_CONFLICT", "清单已更新，请刷新后重试");
        long item = id(itemId);
        var items = jdbc.queryForList("SELECT material_version_id FROM material_checklist_items WHERE id=? AND checklist_id=? FOR UPDATE", item, checklist);
        if (items.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "未找到该清单项");
        String materialVersionId = input.has("materialVersionId") && !input.get("materialVersionId").isNull()
                ? com.caizhitong.identity.IdentityInputs.text(input.get("materialVersionId"), 20, false) : null;
        LocalDateTime now = LocalDateTime.now(clock);
        Long oldVersion = items.getFirst().get("material_version_id") == null ? null : ((Number) items.getFirst().get("material_version_id")).longValue();
        if (materialVersionId == null) {
            jdbc.update("UPDATE material_checklist_items SET material_version_id=NULL,status='missing',updated_at=? WHERE id=?", now, item);
            if (oldVersion != null) {
                Long oldFile = jdbc.queryForObject("SELECT file_id FROM material_versions WHERE id=?", Long.class, oldVersion);
                jdbc.update("DELETE FROM material_usage_records WHERE file_id=? AND used_in_type='checklist_item' AND used_in_id=?", oldFile, item);
            }
        } else {
            long versionId = id(materialVersionId);
            var bound = jdbc.queryForList("SELECT v.file_id,f.scan_status FROM material_versions v JOIN material_files f ON f.id=v.file_id WHERE v.id=? AND f.user_id=? AND f.campus_id=? AND f.status='active' AND f.deleted_at IS NULL",
                    versionId, actor.userId(), actor.campusId());
            if (bound.isEmpty()) throw new BusinessException(422, "MATERIAL_NOT_READY", "材料版本不可用");
            if (!"clean".equals(bound.getFirst().get("scan_status")))
                throw new BusinessException(422, "MATERIAL_NOT_READY", "材料尚未通过安全检查");
            long fileId = ((Number) bound.getFirst().get("file_id")).longValue();
            if (oldVersion != null && !oldVersion.equals(versionId)) {
                Long oldFile = jdbc.queryForObject("SELECT file_id FROM material_versions WHERE id=?", Long.class, oldVersion);
                jdbc.update("DELETE FROM material_usage_records WHERE file_id=? AND used_in_type='checklist_item' AND used_in_id=?", oldFile, item);
            }
            jdbc.update("UPDATE material_checklist_items SET material_version_id=?,status='uploaded',updated_at=? WHERE id=?", versionId, now, item);
            int usage = jdbc.queryForObject("SELECT COUNT(*) FROM material_usage_records WHERE file_id=? AND used_in_type='checklist_item' AND used_in_id=?",
                    Integer.class, fileId, item);
            if (usage == 0) {
                jdbc.update("INSERT INTO material_usage_records(id,file_id,used_in_type,used_in_id,created_at) VALUES(?,'checklist_item',?,?)",
                        ids.nextId(), fileId, item, now);
            }
        }
        jdbc.update("UPDATE material_checklists SET version=version+1,updated_at=? WHERE id=?", now, checklist);
        syncStatus(checklist);
        return view(checklist);
    }

    /** 必交项全部绑定即 ready，否则 draft。 */
    private void syncStatus(long checklist) {
        int missing = jdbc.queryForObject("SELECT COUNT(*) FROM material_checklist_items WHERE checklist_id=? AND required=TRUE AND material_version_id IS NULL",
                Integer.class, checklist);
        jdbc.update("UPDATE material_checklists SET status=? WHERE id=?", missing == 0 ? "ready" : "draft", checklist);
    }

    public long owned(String checklistId, Actor actor) {
        long checklist = id(checklistId);
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM material_checklists WHERE id=? AND user_id=?",
                Integer.class, checklist, actor.userId());
        if (count != 1) throw new BusinessException(404, "NOT_FOUND", "未找到该清单");
        return checklist;
    }

    private Checklist view(long checklist) {
        var row = jdbc.queryForList("SELECT opportunity_id,status,version FROM material_checklists WHERE id=?", checklist).getFirst();
        List<Item> items = new ArrayList<>();
        for (var item : jdbc.queryForList("SELECT id,item_name,required,material_version_id,status FROM material_checklist_items WHERE checklist_id=? ORDER BY id", checklist)) {
            items.add(new Item(((Number) item.get("id")).toString(), (String) item.get("item_name"),
                    Boolean.TRUE.equals(((Number) item.get("required")).intValue() == 1),
                    item.get("material_version_id") == null ? null : item.get("material_version_id").toString(),
                    (String) item.get("status")));
        }
        return new Checklist(Long.toString(checklist), ((Number) row.get("opportunity_id")).toString(),
                (String) row.get("status"), ((Number) row.get("version")).intValue(), List.copyOf(items));
    }
}
