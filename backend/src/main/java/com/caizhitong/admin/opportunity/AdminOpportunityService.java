package com.caizhitong.admin.opportunity;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.opportunity.EligibilityEngine;
import com.caizhitong.opportunity.OpportunityInputs;
import com.caizhitong.opportunity.OpportunityService;
import com.caizhitong.opportunity.OpportunityViews;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;

/**
 * 机会管理：创建始终显式 draft；规则与材料要求变更增加独立 rulesVersion 并保存不可变快照；
 * 发布校验版本/规则快照/通知确认依据并记录不可变审计；已发布规则必须撤回后才能修改。
 */
@Service
public class AdminOpportunityService {
    private static final Set<String> OPPORTUNITY_STATUSES = Set.of("draft", "parsing", "reviewing", "published", "expired", "archived");
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final JsonMapper mapper;
    public AdminOpportunityService(JdbcTemplate jdbc, CurrentActor current, SnowflakeIds ids, Clock clock,
                                   CursorCodec cursors, SensitiveDataCrypto crypto, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public record DraftInput(String title, String category, String organizer, String summary, String deadline,
            String noticeId, JsonNode eligibilityRules, JsonNode materialRequirements) {}

    public record PublishInput(int version, String reason) {}

    public record OpportunityPage(List<OpportunityViews.Opportunity> items, String nextCursor, boolean hasMore) {}

    public OpportunityPage list(String status, String category, String cursor, Integer limit) {
        Actor actor = current.requirePermission("opportunity.read");
        int size = PageLimits.resolve(limit);
        if (status != null && !OPPORTUNITY_STATUSES.contains(status)) throw invalid();
        OpportunityInputs.category(category);
        String scopeToken = "admin-opportunities:" + actor.campusId() + ":"
                + java.util.HexFormat.of().formatHex(crypto.searchHash("admin-opportunities-list", mapper.writeValueAsString(List.of(status, category))));
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scopeToken);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        StringBuilder sql = new StringBuilder("SELECT id,title,category,organizer,summary,deadline,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id FROM opportunities WHERE campus_id=? AND deleted_at IS NULL AND id<?");
        List<Object> args = new ArrayList<>(List.of(actor.campusId(), before));
        if (status != null) { sql.append(" AND status=?"); args.add(status); }
        if (category != null) { sql.append(" AND category=?"); args.add(category); }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1);
        var rows = jdbc.query(sql.toString(), (r, i) -> view(r.getString("id"), r.getString("title"), r.getString("category"),
                r.getString("organizer"), r.getString("summary"), r.getObject("deadline", LocalDateTime.class), r.getString("status"),
                r.getInt("version"), r.getString("source_type"), r.getString("source_name"), r.getObject("updated_at", LocalDateTime.class),
                r.getBoolean("is_stale"), r.getString("official_reference"), r.getString("external_id")), args.toArray());
        boolean more = rows.size() > size;
        var page = rows.stream().limit(size).toList();
        String next = null;
        if (more && !page.isEmpty()) next = cursors.encode(new CursorPayload(scopeToken, Map.of("id", page.getLast().id())));
        return new OpportunityPage(page, next, more);
    }

    /** 创建机会：显式 draft；校验规则/材料要求结构并落库初始快照。 */
    @BusinessWrite(permission = "opportunity.write")
    public OpportunityViews.Opportunity create(DraftInput input) {
        Actor actor = current.requirePermission("opportunity.write");
        String title = OpportunityInputs.text(mapper.valueToTree(input.title()), 256, false);
        OpportunityInputs.category(input.category());
        String organizer = input.organizer() == null ? null : OpportunityInputs.text(mapper.valueToTree(input.organizer()), 128, false);
        String summary = input.summary() == null ? null : OpportunityInputs.text(mapper.valueToTree(input.summary()), 100000, false);
        Instant deadline = OpportunityInputs.optionalRfc3339(mapper.valueToTree(input.deadline()));
        Long noticeId = input.noticeId() == null ? null : id(input.noticeId());
        if (noticeId != null) {
            int exists = jdbc.queryForObject("SELECT COUNT(*) FROM notices WHERE id=? AND campus_id=? AND deleted_at IS NULL",
                    Integer.class, noticeId, actor.campusId());
            if (exists != 1) throw new BusinessException(404, "NOT_FOUND", "未找到该通知");
        }
        List<EligibilityEngine.Rule> rules = input.eligibilityRules() == null ? List.of() : OpportunityInputs.rules(input.eligibilityRules());
        List<OpportunityViews.MaterialRequirement> requirements = input.materialRequirements() == null ? List.of()
                : OpportunityInputs.materialRequirements(input.materialRequirements());
        LocalDateTime now = LocalDateTime.now(clock);
        long opportunity = ids.nextId();
        jdbc.update("INSERT INTO opportunities(id,campus_id,notice_id,title,category,organizer,summary,deadline,status,source_type,rules_version,version,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'draft','manual',0,0,?,?,?)",
                opportunity, actor.campusId(), noticeId, title, input.category(), organizer, summary, deadline == null ? null : OpportunityService.utc(deadline),
                actor.userId(), now, now);
        saveRulesAndItems(opportunity, rules, requirements, actor.userId(), now);
        jdbc.update("INSERT INTO opportunity_rule_snapshots(id,opportunity_id,rules_version,rules_json,material_requirements_json,created_by,created_at) VALUES(?,?,0,?,?,?,?)",
                ids.nextId(), opportunity, mapper.writeValueAsString(rules), mapper.writeValueAsString(requirements), actor.userId(), now);
        return view(opportunity);
    }

    /** 更新：白名单字段（title/organizer/summary/deadline/eligibilityRules/materialRequirements）；
     *  已发布/已截止先撤回；规则/材料变化独立 rulesVersion + 新快照；至少修改一个白名单字段。 */
    @BusinessWrite(permission = "opportunity.write")
    public OpportunityViews.Opportunity update(String opportunityId, JsonNode input) {
        Actor actor = current.requirePermission("opportunity.write");
        long opportunity = id(opportunityId);
        int expectedVersion = com.caizhitong.identity.IdentityInputs.version(input.get("version"));
        var locked = lock(opportunity, actor.campusId(), expectedVersion);
        String status = (String) locked.get("status");
        if (Set.of("published", "expired").contains(status))
            throw OpportunityInputs.stateConflict("已发布机会需先撤回才能修改");
        if (Set.of("parsing", "reviewing").contains(status))
            throw OpportunityInputs.stateConflict("机会解析/复核中不可修改");
        String title = (String) locked.get("title");
        String organizer = (String) locked.get("organizer");
        String summary = (String) locked.get("summary");
        Instant deadline = locked.get("deadline") == null ? null : ((LocalDateTime) locked.get("deadline")).toInstant(ZoneOffset.UTC);
        boolean changed = false;
        if (input.has("title")) { title = OpportunityInputs.text(input.get("title"), 256, false); changed = true; }
        if (input.has("organizer")) {
            organizer = input.get("organizer").isNull() ? null : OpportunityInputs.text(input.get("organizer"), 128, false);
            changed = true;
        }
        if (input.has("summary")) {
            summary = input.get("summary").isNull() ? null : OpportunityInputs.text(input.get("summary"), 100000, false);
            changed = true;
        }
        if (input.has("deadline")) { deadline = OpportunityInputs.optionalRfc3339(input.get("deadline")); changed = true; }
        List<EligibilityEngine.Rule> rules = null;
        List<OpportunityViews.MaterialRequirement> requirements = null;
        if (input.has("eligibilityRules")) { rules = OpportunityInputs.rules(input.get("eligibilityRules")); changed = true; }
        if (input.has("materialRequirements")) { requirements = OpportunityInputs.materialRequirements(input.get("materialRequirements")); changed = true; }
        if (!changed) throw invalid();
        boolean rulesChanged = rules != null || requirements != null;
        if (rules == null) rules = currentRules(opportunity);
        if (requirements == null) requirements = currentRequirements(opportunity);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE opportunities SET title=?,organizer=?,summary=?,deadline=?,version=version+1,rules_version=?,updated_by=?,updated_at=? WHERE id=?",
                title, organizer, summary, deadline == null ? null : OpportunityService.utc(deadline),
                rulesChanged ? ((Number) locked.get("rules_version")).intValue() + 1 : locked.get("rules_version"),
                actor.userId(), now, opportunity);
        if (rulesChanged) {
            jdbc.update("DELETE FROM opportunity_eligibility_rules WHERE opportunity_id=?", opportunity);
            jdbc.update("DELETE FROM opportunity_material_items WHERE opportunity_id=?", opportunity);
            saveRulesAndItems(opportunity, rules, requirements, actor.userId(), now);
            int nextRulesVersion = ((Number) locked.get("rules_version")).intValue() + 1;
            jdbc.update("INSERT INTO opportunity_rule_snapshots(id,opportunity_id,rules_version,rules_json,material_requirements_json,created_by,created_at) VALUES(?,?,?,?,?,?,?)",
                    ids.nextId(), opportunity, nextRulesVersion, mapper.writeValueAsString(rules), mapper.writeValueAsString(requirements), actor.userId(), now);
        }
        return view(opportunity);
    }

    private List<EligibilityEngine.Rule> currentRules(long opportunity) {
        return jdbc.query("SELECT field_key,operator,value_json,severity,sort_order FROM opportunity_eligibility_rules WHERE opportunity_id=? ORDER BY sort_order,id",
                (r, i) -> new EligibilityEngine.Rule(r.getString("field_key"), r.getString("operator"), mapper.readTree(r.getString("value_json")),
                        r.getString("severity"), r.getInt("sort_order")), opportunity);
    }

    private List<OpportunityViews.MaterialRequirement> currentRequirements(long opportunity) {
        return jdbc.query("SELECT name,description,required,sort_order FROM opportunity_material_items WHERE opportunity_id=? ORDER BY sort_order,id",
                (r, i) -> new OpportunityViews.MaterialRequirement(r.getString("name"), r.getString("description"), r.getBoolean("required"), r.getInt("sort_order")), opportunity);
    }

    @BusinessWrite(permission = "opportunity.publish")
    public OpportunityViews.Opportunity publish(String opportunityId, PublishInput input) {
        Actor actor = current.requirePermission("opportunity.publish");
        long opportunity = id(opportunityId);
        var locked = lock(opportunity, actor.campusId(), input.version());
        String status = (String) locked.get("status");
        if (!Set.of("draft", "reviewing").contains(status)) throw OpportunityInputs.stateConflict("当前状态不可发布");
        String reason = OpportunityInputs.text(mapper.valueToTree(input.reason()), 512, false);
        Long noticeId = locked.get("notice_id") == null ? null : ((Number) locked.get("notice_id")).longValue();
        if (noticeId != null) {
            int confirmed = jdbc.queryForObject("SELECT COUNT(*) FROM ai_parse_tasks t JOIN notices n ON n.id=t.notice_id WHERE n.id=? AND t.review_decision='approved' AND n.deleted_at IS NULL",
                    Integer.class, noticeId);
            if (confirmed == 0) throw new BusinessException(422, "MATERIAL_NOT_READY", "关联通知缺少人工确认依据");
        }
        int rulesVersion = ((Number) locked.get("rules_version")).intValue();
        int snapshot = jdbc.queryForObject("SELECT COUNT(*) FROM opportunity_rule_snapshots WHERE opportunity_id=? AND rules_version=?",
                Integer.class, opportunity, rulesVersion);
        if (snapshot == 0) throw new BusinessException(422, "MATERIAL_NOT_READY", "缺少当前规则版本快照");
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE opportunities SET status='published',version=version+1,updated_by=?,updated_at=? WHERE id=?", actor.userId(), now, opportunity);
        if (noticeId != null) jdbc.update("UPDATE notices SET status='published',updated_at=? WHERE id=? AND status='reviewing'", now, noticeId);
        audit(actor, "opportunity.publish", opportunity, reason, Map.of("version", input.version()));
        return view(opportunity);
    }

    @BusinessWrite(permission = "opportunity.publish")
    public OpportunityViews.Opportunity unpublish(String opportunityId, PublishInput input) {
        Actor actor = current.requirePermission("opportunity.publish");
        long opportunity = id(opportunityId);
        var locked = lock(opportunity, actor.campusId(), input.version());
        String status = (String) locked.get("status");
        if (!Set.of("published", "expired").contains(status)) throw OpportunityInputs.stateConflict("当前状态不可撤回");
        String reason = OpportunityInputs.text(mapper.valueToTree(input.reason()), 512, false);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE opportunities SET status='archived',version=version+1,updated_by=?,updated_at=? WHERE id=?", actor.userId(), now, opportunity);
        audit(actor, "opportunity.unpublish", opportunity, reason, Map.of("version", input.version()));
        return view(opportunity);
    }

    private Map<String, Object> lock(long opportunity, long campus, int expectedVersion) {
        var rows = jdbc.queryForList("SELECT * FROM opportunities WHERE id=? AND campus_id=? AND deleted_at IS NULL FOR UPDATE", opportunity, campus);
        if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "未找到该机会");
        if (((Number) rows.getFirst().get("version")).intValue() != expectedVersion)
            throw new BusinessException(409, "VERSION_CONFLICT", "信息已更新，请刷新后重试");
        return rows.getFirst();
    }

    private void saveRulesAndItems(long opportunity, List<EligibilityEngine.Rule> rules,
            List<OpportunityViews.MaterialRequirement> requirements, long by, LocalDateTime now) {
        for (EligibilityEngine.Rule rule : rules) {
            jdbc.update("INSERT INTO opportunity_eligibility_rules(id,opportunity_id,field_key,operator,value_json,severity,sort_order,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    ids.nextId(), opportunity, rule.field(), rule.operator(), mapper.writeValueAsString(rule.value()), rule.severity(), rule.sortOrder(), now);
        }
        for (OpportunityViews.MaterialRequirement requirement : requirements) {
            jdbc.update("INSERT INTO opportunity_material_items(id,opportunity_id,name,description,required,sort_order,created_at) VALUES(?,?,?,?,?,?,?)",
                    ids.nextId(), opportunity, requirement.name(), requirement.description(), requirement.required(), requirement.sortOrder(), now);
        }
    }

    private OpportunityViews.Opportunity view(long opportunity) {
        return jdbc.query("SELECT id,title,category,organizer,summary,deadline,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id FROM opportunities WHERE id=?",
                (r, i) -> view(r.getString("id"), r.getString("title"), r.getString("category"), r.getString("organizer"),
                        r.getString("summary"), r.getObject("deadline", LocalDateTime.class), r.getString("status"), r.getInt("version"),
                        r.getString("source_type"), r.getString("source_name"), r.getObject("updated_at", LocalDateTime.class),
                        r.getBoolean("is_stale"), r.getString("official_reference"), r.getString("external_id")), opportunity).getFirst();
    }

    private OpportunityViews.Opportunity view(String idValue, String title, String category, String organizer, String summary,
            LocalDateTime deadline, String status, int version, String sourceType, String sourceName, LocalDateTime updatedAt,
            boolean isStale, String officialReference, String externalId) {
        return new OpportunityViews.Opportunity(idValue, title, category, organizer, summary,
                deadline == null ? null : deadline.toInstant(ZoneOffset.UTC), status, version, sourceType, sourceName,
                updatedAt == null ? null : updatedAt.toInstant(ZoneOffset.UTC), isStale, officialReference, externalId);
    }

    private void audit(Actor actor, String action, long targetId, String reason, Map<String, Object> detail) {
        jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,action,target_type,target_id,reason,detail_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                ids.nextId(), actor.userId(), action, "opportunity", targetId, reason,
                detail == null ? "{}" : mapper.writeValueAsString(detail), LocalDateTime.now(clock));
    }
}
