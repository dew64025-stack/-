package com.caizhitong.opportunity;

import com.caizhitong.campus.CampusService;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.security.CurrentActor;
import com.caizhitong.security.Actor;
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
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.id;

@Service
public class OpportunityService {
    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final CampusService campuses;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final JsonMapper mapper;
    public OpportunityService(JdbcTemplate jdbc, CurrentActor current, CampusService campuses, CursorCodec cursors,
                              SensitiveDataCrypto crypto, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.current = current;
        this.campuses = campuses;
        this.cursors = cursors;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    /** 登录用户用其校园；游客带 campusCode，省略时按文档第 2.8 节默认首发开放校园（最早创建）。 */
    public long scope(String campusCode) {
        return current.optional().map(Actor::campusId).orElseGet(() -> {
            if (campusCode != null) return campuses.open(campusCode);
            var rows = jdbc.queryForList("SELECT id FROM campuses WHERE status='active' AND deleted_at IS NULL ORDER BY id LIMIT 1", Long.class);
            if (rows.isEmpty()) throw invalid();
            return rows.getFirst();
        });
    }

    /** 公开列表：仅 published/expired；deadline ASC,id ASC，NULL deadline 最后。 */
    public OpportunityViews.Page list(String campusCode, String q, String category, String deadlineFrom, String deadlineTo,
                                      String cursor, Integer limit) {
        long campus = scope(campusCode);
        int size = PageLimits.resolve(limit);
        if (q != null && (q.isBlank() || q.length() > 100)) throw invalid();
        OpportunityInputs.category(category);
        Instant from = deadlineFrom == null ? null : OpportunityInputs.rfc3339(deadlineFrom);
        Instant to = deadlineTo == null ? null : OpportunityInputs.rfc3339(deadlineTo);
        if (from != null && to != null && from.isAfter(to)) throw invalid();
        String scopeToken = "opportunities:" + campus + ":"
                + java.util.HexFormat.of().formatHex(crypto.searchHash("opportunity-list", mapper.writeValueAsString(java.util.Arrays.asList(q, category, deadlineFrom, deadlineTo))));
        CursorPayload keys = cursor == null ? null : cursors.decode(cursor, scopeToken);
        if (keys != null && !keys.sortKeys().keySet().equals(Set.of("deadline", "id"))) throw invalid();
        String cursorDeadline = keys == null ? null : keys.sortKeys().get("deadline");
        long cursorId = keys == null ? 0 : id(keys.sortKeys().get("id"));
        StringBuilder sql = new StringBuilder(
                "SELECT id,title,category,organizer,summary,deadline,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id FROM opportunities WHERE campus_id=? AND status IN ('published','expired') AND deleted_at IS NULL");
        List<Object> args = new ArrayList<>(List.of(campus));
        if (q != null && !q.isBlank()) {
            sql.append(" AND (title LIKE ? ESCAPE '!' OR summary LIKE ? ESCAPE '!')");
            String like = "%" + escape(q) + "%";
            args.add(like);
            args.add(like);
        }
        if (category != null) { sql.append(" AND category=?"); args.add(category); }
        if (from != null) { sql.append(" AND (deadline IS NULL OR deadline>=?)"); args.add(utc(from)); }
        if (to != null) { sql.append(" AND (deadline IS NULL OR deadline<=?)"); args.add(utc(to)); }
        if (cursorDeadline == null) {
            if (keys != null) { sql.append(" AND deadline IS NULL AND id>?"); args.add(cursorId); }
        } else {
            if (!"null".equals(cursorDeadline)) {
                sql.append(" AND (deadline IS NULL OR deadline>? OR (deadline=? AND id>?))");
                args.add(LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(cursorDeadline)), ZoneOffset.UTC));
                args.add(LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(cursorDeadline)), ZoneOffset.UTC));
                args.add(cursorId);
            } else {
                sql.append(" AND deadline IS NULL AND id>?");
                args.add(cursorId);
            }
        }
        sql.append(" ORDER BY deadline IS NULL, deadline ASC, id ASC LIMIT ?");
        args.add(size + 1);
        var rows = jdbc.query(sql.toString(), (r, i) -> opportunity(r.getString("id"), r.getString("title"), r.getString("category"),
                r.getString("organizer"), r.getString("summary"), r.getObject("deadline", LocalDateTime.class), r.getString("status"),
                r.getInt("version"), r.getString("source_type"), r.getString("source_name"), r.getObject("updated_at", LocalDateTime.class),
                r.getBoolean("is_stale"), r.getString("official_reference"), r.getString("external_id")), args.toArray());
        boolean more = rows.size() > size;
        var page = rows.stream().limit(size).toList();
        String next = null;
        if (more && !page.isEmpty()) {
            var last = page.getLast();
            String deadlineKey = last.deadline() == null ? "null" : Long.toString(last.deadline().toEpochMilli());
            next = cursors.encode(new CursorPayload(scopeToken, Map.of("deadline", deadlineKey, "id", last.id())));
        }
        return new OpportunityViews.Page(page, next, more);
    }

    /** 公开详情：published/expired；跨校/未发布 404。 */
    public OpportunityViews.Detail detail(String opportunityId, String campusCode) {
        long campus = scope(campusCode);
        long idValue = id(opportunityId);
        var rows = jdbc.query(
                "SELECT id,title,category,organizer,summary,deadline,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id,notice_id FROM opportunities WHERE id=? AND campus_id=? AND status IN ('published','expired') AND deleted_at IS NULL",
                (r, i) -> new Object[] { opportunity(r.getString("id"), r.getString("title"), r.getString("category"), r.getString("organizer"),
                        r.getString("summary"), r.getObject("deadline", LocalDateTime.class), r.getString("status"), r.getInt("version"),
                        r.getString("source_type"), r.getString("source_name"), r.getObject("updated_at", LocalDateTime.class),
                        r.getBoolean("is_stale"), r.getString("official_reference"), r.getString("external_id")), r.getObject("notice_id", Long.class) },
                idValue, campus);
        if (rows.isEmpty()) throw missing();
        Object[] row = rows.getFirst();
        OpportunityViews.Opportunity opportunity = (OpportunityViews.Opportunity) row[0];
        Long noticeId = (Long) row[1];
        OpportunityViews.Notice notice = noticeId == null ? null : publicNotice(noticeId, campus);
        var rules = jdbc.query("SELECT field_key,operator,value_json,severity,sort_order FROM opportunity_eligibility_rules WHERE opportunity_id=? ORDER BY sort_order,id",
                (r, i) -> new OpportunityViews.Rule(r.getString("field_key"), r.getString("operator"), mapper.readTree(r.getString("value_json")),
                        r.getString("severity"), r.getInt("sort_order")), idValue);
        var requirements = jdbc.query("SELECT name,description,required,sort_order,validation_rules_json FROM opportunity_material_items WHERE opportunity_id=? ORDER BY sort_order,id",
                (r, i) -> new OpportunityViews.MaterialRequirement(r.getString("name"), r.getString("description"), r.getBoolean("required"), r.getInt("sort_order"),
                        r.getString("validation_rules_json") == null ? null : mapper.readTree(r.getString("validation_rules_json"))), idValue);
        long teamCount = jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE opportunity_id=?", Long.class, idValue);
        boolean isFavorited = current.optional()
                .map(actor -> jdbc.queryForObject("SELECT COUNT(*) FROM favorites WHERE user_id=? AND target_type='opportunity' AND target_id=?", Integer.class, actor.userId(), idValue) > 0)
                .orElse(false);
        return new OpportunityViews.Detail(opportunity.id(), opportunity.title(), opportunity.category(), opportunity.organizer(),
                opportunity.summary(), opportunity.deadline(), opportunity.status(), opportunity.version(),
                opportunity.sourceType(), opportunity.sourceName(), opportunity.updatedAt(), opportunity.isStale(),
                opportunity.officialReference(), opportunity.externalId(),
                notice, rules, requirements, teamCount, isFavorited);
    }

    /** 通知只带已获发布依据的安全原文与来源，不含任何管理员字段。 */
    private OpportunityViews.Notice publicNotice(long noticeId, long campus) {
        var rows = jdbc.query(
                "SELECT id,title,publisher,published_at,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id,content,raw_url,attachment_id FROM notices WHERE id=? AND campus_id=? AND deleted_at IS NULL",
                (r, i) -> new OpportunityViews.Notice(r.getString("id"), r.getString("title"), r.getString("publisher"),
                        r.getObject("published_at", LocalDateTime.class) == null ? null : r.getObject("published_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        r.getString("status"), r.getInt("version"), r.getString("source_type"), r.getString("source_name"),
                        r.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), r.getBoolean("is_stale"),
                        r.getString("official_reference"), r.getString("external_id"), r.getString("content"), r.getString("raw_url"),
                        r.getString("attachment_id") == null ? null : r.getString("attachment_id")),
                noticeId, campus);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 当前用户可见（published/expired 本校）才返回 id，否则 404。 */
    public long visible(String opportunityId) {
        long value = id(opportunityId);
        Actor actor = current.require();
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM opportunities WHERE id=? AND campus_id=? AND status IN ('published','expired') AND deleted_at IS NULL",
                Integer.class, value, actor.campusId());
        if (count != 1) throw missing();
        return value;
    }

    /** 收藏/提醒等窄读取：机会摘要或不可用。 */
    public OpportunityViews.Opportunity available(long opportunityId, long campus) {
        var rows = jdbc.query("SELECT id,title,category,organizer,summary,deadline,status,version,source_type,source_name,updated_at,is_stale,official_reference,external_id FROM opportunities WHERE id=? AND campus_id=? AND status IN ('published','expired') AND deleted_at IS NULL",
                (r, i) -> opportunity(r.getString("id"), r.getString("title"), r.getString("category"), r.getString("organizer"),
                        r.getString("summary"), r.getObject("deadline", LocalDateTime.class), r.getString("status"), r.getInt("version"),
                        r.getString("source_type"), r.getString("source_name"), r.getObject("updated_at", LocalDateTime.class),
                        r.getBoolean("is_stale"), r.getString("official_reference"), r.getString("external_id")), opportunityId, campus);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private OpportunityViews.Opportunity opportunity(String idValue, String title, String category, String organizer, String summary,
            LocalDateTime deadline, String status, int version, String sourceType, String sourceName, LocalDateTime updatedAt,
            boolean isStale, String officialReference, String externalId) {
        return new OpportunityViews.Opportunity(idValue, title, category, organizer, summary,
                deadline == null ? null : deadline.toInstant(ZoneOffset.UTC), status, version, sourceType, sourceName,
                updatedAt == null ? null : updatedAt.toInstant(ZoneOffset.UTC), isStale, officialReference, externalId);
    }

    public static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }

    private String escape(String text) { return text.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }

    private com.caizhitong.common.BusinessException missing() { return new com.caizhitong.common.BusinessException(404, "NOT_FOUND", "未找到该机会"); }
}
