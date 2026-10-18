package com.caizhitong.admin.operations;

import com.caizhitong.campus.CampusAdminBridge;
import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.identity.IdentityReadBridge;
import com.caizhitong.issue.IssueReadBridge;
import com.caizhitong.mutualaid.MutualAidReadBridge;
import com.caizhitong.opportunity.OpportunityReadBridge;
import com.caizhitong.opportunity.OpportunityViews;
import com.caizhitong.resource.ResourceReadBridge;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import com.caizhitong.team.TeamReadBridge;
import com.caizhitong.workorder.WorkOrderReadBridge;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.text;

/**
 * Campus administration, whitelisted configuration, aggregate statistics, desensitized audit
 * reading and the explicit-failure integration control plane.
 */
@Service
public class AdminOpsService {
    private static final Set<String> INTEGRATION_TYPES = Set.of("notice", "library", "classroom", "repair", "crowd");
    private static final Set<String> INTEGRATION_CONFIG_KEYS =
            Set.of("endpoint", "secretRef", "timeoutMs", "schoolAuthorized");
    private static final Set<String> DOCUMENT_TYPES =
            Set.of("privacy", "phone", "subscription", "location", "material_ai");
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final JsonMapper mapper;
    private final CampusAdminBridge campuses;
    private final IdentityReadBridge identities;
    private final OpportunityReadBridge opportunities;
    private final IssueReadBridge issues;
    private final WorkOrderReadBridge workOrders;
    private final ResourceReadBridge resources;
    private final MutualAidReadBridge mutualAid;
    private final TeamReadBridge teams;

    public AdminOpsService(JdbcTemplate jdbc, CurrentActor current, SnowflakeIds ids, Clock clock, CursorCodec cursors,
                           JsonMapper mapper, CampusAdminBridge campuses, IdentityReadBridge identities,
                           OpportunityReadBridge opportunities, IssueReadBridge issues,
                           WorkOrderReadBridge workOrders, ResourceReadBridge resources,
                           MutualAidReadBridge mutualAid, TeamReadBridge teams) {
        this.jdbc = jdbc;
        this.current = current;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.mapper = mapper;
        this.campuses = campuses;
        this.identities = identities;
        this.opportunities = opportunities;
        this.issues = issues;
        this.workOrders = workOrders;
        this.resources = resources;
        this.mutualAid = mutualAid;
        this.teams = teams;
    }

    @BusinessWrite(permission = "platform.campus.manage")
    public AdminOpsViews.Campus createCampus(AdminOpsInputs.CampusCreate input) {
        Actor actor = current.requirePermission("platform.campus.manage");
        String name = text(input.name(), 128, false);
        String code = text(input.code(), 64, false);
        var created = campuses.create(name, code, now());
        long campus = Long.parseLong(created.id());
        audit(actor, campus, "campus.create", "campus", campus, "创建校园", "{}");
        return new AdminOpsViews.Campus(created.id(), created.name(), created.code());
    }

    public List<AdminOpsViews.Config> configs() {
        Actor actor = current.requirePermission("campus.config.read");
        return campuses.configs(actor.campusId()).stream()
                .map(row -> new AdminOpsViews.Config(row.key(), parse(row.value()), row.version(), row.updatedAt()))
                .toList();
    }

    @BusinessWrite(permission = "campus.config.write")
    public AdminOpsViews.Config updateConfig(String key, AdminOpsInputs.ConfigUpdate input) {
        Actor actor = current.requirePermission("campus.config.write");
        String configKey = text(mapper.valueToTree(key), 128, false);
        JsonNode value = validateConfigValue(configKey, input.value());
        int version = input.version().asInt();
        String reason = text(input.reason(), 512, false);
        String json = mapper.writeValueAsString(value);
        if (json.length() > 4096) throw invalid();
        var row = campuses.updateConfig(actor.campusId(), configKey, json, version, now());
        audit(actor, actor.campusId(), "campus.config.update", "campus_config", nullKey(), reason,
                mapper.writeValueAsString(Map.of("key", configKey)));
        return new AdminOpsViews.Config(row.key(), parse(row.value()), row.version(), row.updatedAt());
    }

    public AdminOpsViews.Statistics statistics(String fromParam, String toParam) {
        Actor actor = current.requirePermission("statistics.read");
        Instant to = instant(toParam);
        Instant from = instant(fromParam);
        if (to == null) to = clock.instant();
        if (from == null) from = to.minus(Duration.ofDays(30));
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BusinessException(400, "VALIDATION_FAILED", "统计区间不合法");
        }
        long campus = actor.campusId();
        return new AdminOpsViews.Statistics(from, to, identities.newUsers(campus, from, to),
                identities.verifiedStudents(campus), opportunities.count(campus, from, to),
                issues.count(campus, from, to), workOrders.count(campus, from, to),
                resources.reservationCount(campus, from, to), mutualAid.count(campus, from, to),
                teams.count(campus, from, to));
    }

    public AdminOpsViews.AuditPage auditLogs(String action, String fromParam, String toParam, String cursor,
                                             Integer limit) {
        Actor actor = current.requirePermission("audit.read");
        String actionFilter = action == null || action.isBlank() ? null : text(mapper.valueToTree(action), 64, false);
        Instant from = instant(fromParam);
        Instant to = instant(toParam);
        if (from != null && to != null && !from.isBefore(to)) throw invalid();
        int size = PageLimits.resolve(limit);
        String scope = "audit-logs:" + actor.campusId() + ":" + (actionFilter == null ? "" : actionFilter)
                + ":" + (from == null ? "" : from) + ":" + (to == null ? "" : to);
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scope);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        String where = " WHERE campus_id=? AND id<?";
        List<Object> args = new ArrayList<>(List.of(actor.campusId(), before));
        if (actionFilter != null) {
            where += " AND action=?";
            args.add(actionFilter);
        }
        if (from != null) {
            where += " AND created_at>=?";
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where += " AND created_at<?";
            args.add(Timestamp.from(to));
        }
        var found = jdbc.query("SELECT id,operator_id,action,target_type,target_id,reason,created_at FROM admin_audit_logs"
                        + where + " ORDER BY id DESC LIMIT ?", (rs, i) -> auditRow(rs), append(args, size + 1));
        boolean more = found.size() > size;
        List<AdminOpsViews.AuditLog> items = found.stream().limit(size).toList();
        String next = more && !items.isEmpty()
                ? cursors.encode(new CursorPayload(scope, Map.of("id", items.getLast().id()))) : null;
        return new AdminOpsViews.AuditPage(items, next, more);
    }

    public List<AdminOpsViews.Integration> integrations() {
        Actor actor = current.requirePermission("integration.read");
        return jdbc.query("SELECT e.id,e.type,e.provider,e.status,e.version,"
                        + " (SELECT c.last_synced_at FROM sync_cursors c WHERE c.external_system_id=e.id) last_synced_at,"
                        + " (SELECT COUNT(*) FROM sync_errors s WHERE s.external_system_id=e.id) error_count"
                        + " FROM external_systems e WHERE e.campus_id=? ORDER BY e.type",
                (rs, i) -> new AdminOpsViews.Integration(Long.toString(rs.getLong("id")), rs.getString("type"),
                        rs.getString("provider"), rs.getString("status"), instant(rs, "last_synced_at"),
                        rs.getLong("error_count") == 0 ? null : "同步失败，请查看错误列表", rs.getInt("version")),
                actor.campusId());
    }

    @BusinessWrite(permission = "integration.write")
    public AdminOpsViews.IntegrationConfig updateIntegration(String type, AdminOpsInputs.IntegrationUpdate input) {
        Actor actor = current.requirePermission("integration.write");
        String kind = integrationType(type);
        String provider = text(input.provider(), 16, false);
        String reason = text(input.reason(), 512, false);
        int version = input.version().asInt();
        JsonNode config = validateIntegrationConfig(input.config());
        String json = mapper.writeValueAsString(config);
        var existing = jdbc.query("SELECT id,provider,version,updated_at FROM external_systems"
                        + " WHERE campus_id=? AND type=? FOR UPDATE",
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("provider"), rs.getInt("version"),
                        rs.getObject("updated_at", LocalDateTime.class)}, actor.campusId(), kind);
        long system;
        int nextVersion;
        LocalDateTime now = now();
        if (existing.isEmpty()) {
            if (version != 0) throw conflict();
            if ("official".equals(provider)) requireOfficialReady(actor.campusId(), kind, config, null);
            system = ids.nextId();
            jdbc.update("INSERT INTO external_systems(id,campus_id,type,provider,config_json,status,version,"
                            + "created_at,updated_at) VALUES(?,?,?,?,?,'active',1,?,?)",
                    system, actor.campusId(), kind, provider, json, now, now);
            nextVersion = 1;
        } else {
            Object[] row = existing.getFirst();
            if (((Number) row[2]).intValue() != version) throw conflict();
            if ("official".equals(provider)) {
                requireOfficialReady(actor.campusId(), kind, config, (LocalDateTime) row[3]);
            }
            system = ((Number) row[0]).longValue();
            jdbc.update("UPDATE external_systems SET provider=?,config_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?", provider, json, now, system);
            nextVersion = version + 1;
        }
        audit(actor, actor.campusId(), "integration.update", "external_system", system, reason,
                mapper.writeValueAsString(Map.of("type", kind)));
        return new AdminOpsViews.IntegrationConfig(kind, provider, "active", sanitized(config), nextVersion,
                now.toInstant(ZoneOffset.UTC));
    }

    @BusinessWrite(permission = "integration.write")
    public OpportunityViews.AsyncJob testIntegration(String type) {
        Actor actor = current.requirePermission("integration.write");
        String kind = integrationType(type);
        integration(kind, actor.campusId());
        return enqueue(actor, kind, "integration_test");
    }

    @BusinessWrite(permission = "integration.sync")
    public OpportunityViews.AsyncJob syncIntegration(String type) {
        Actor actor = current.requirePermission("integration.sync");
        String kind = integrationType(type);
        integration(kind, actor.campusId());
        return enqueue(actor, kind, "integration_sync");
    }

    public AdminOpsViews.SyncErrorPage syncErrors(String type, String cursor, Integer limit) {
        Actor actor = current.requirePermission("integration.read");
        String kind = integrationType(type);
        long system = integration(kind, actor.campusId());
        int size = PageLimits.resolve(limit);
        String scope = "sync-errors:" + actor.campusId() + ":" + kind;
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scope);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        var found = jdbc.query("SELECT id,error,created_at FROM sync_errors WHERE external_system_id=? AND id<?"
                        + " ORDER BY id DESC LIMIT ?", (rs, i) -> new AdminOpsViews.SyncError(Long.toString(rs.getLong("id")),
                        desensitizeError(rs.getString("error")), instant(rs, "created_at")), system, before, size + 1);
        boolean more = found.size() > size;
        List<AdminOpsViews.SyncError> items = found.stream().limit(size).toList();
        String next = more && !items.isEmpty()
                ? cursors.encode(new CursorPayload(scope, Map.of("id", items.getLast().id()))) : null;
        return new AdminOpsViews.SyncErrorPage(items, next, more);
    }

    /** Used by the worker to decide an explicit, non-fake terminal error. */
    public String integrationFailure(long campus, String type) {
        var rows = jdbc.query("SELECT provider,config_json FROM external_systems WHERE campus_id=? AND type=?",
                (rs, i) -> new Object[]{rs.getString("provider"), rs.getString("config_json")}, campus, type);
        if (rows.isEmpty()) return "INTEGRATION_NOT_CONFIGURED";
        String provider = (String) rows.getFirst()[0];
        JsonNode config = parse((String) rows.getFirst()[1]);
        if ("official".equals(provider)
                && (!config.hasNonNull("endpoint") || !config.hasNonNull("secretRef"))) {
            return "INTEGRATION_CONFIG_INCOMPLETE";
        }
        return "INTEGRATION_PROVIDER_UNAVAILABLE";
    }

    private void requireOfficialReady(long campus, String type, JsonNode config, LocalDateTime since) {
        if (!config.path("schoolAuthorized").asBoolean(false)) {
            throw new BusinessException(409, "INTEGRATION_AUTHORIZATION_REQUIRED", "切换官方提供器需要确认学校授权");
        }
        String sql = "SELECT COUNT(*) FROM async_jobs WHERE job_type='integration_test' AND campus_id=?"
                + " AND access_domain='admin' AND status='succeeded' AND result_json LIKE ?";
        List<Object> args = new ArrayList<>(List.of(campus, "%\"type\":\"" + type + "\"%"));
        if (since != null) {
            sql += " AND created_at>=?";
            args.add(Timestamp.valueOf(since));
        }
        Integer count = jdbc.queryForObject(sql, Integer.class, args.toArray());
        if (count == null || count == 0) {
            throw new BusinessException(409, "INTEGRATION_TEST_REQUIRED", "切换官方提供器前必须先通过连接测试");
        }
    }

    private OpportunityViews.AsyncJob enqueue(Actor actor, String type, String jobType) {
        long job = ids.nextId();
        LocalDateTime now = now();
        jdbc.update("INSERT INTO async_jobs(id,job_type,payload_json,status,campus_id,owner_user_id,access_domain,"
                        + "retry_count,max_retries,created_at,updated_at) VALUES(?,?,?,'pending',?,?,'admin',0,1,?,?)",
                job, jobType, mapper.writeValueAsString(Map.of("type", type)), actor.campusId(), actor.userId(), now, now);
        audit(actor, actor.campusId(), "integration." + ("integration_test".equals(jobType) ? "test" : "sync"),
                "async_job", job, null, mapper.writeValueAsString(Map.of("type", type)));
        Instant nowInstant = now.toInstant(ZoneOffset.UTC);
        return new OpportunityViews.AsyncJob(Long.toString(job), jobType, "pending", null, null, nowInstant, nowInstant);
    }

    private long integration(String type, long campus) {
        var rows = jdbc.queryForList("SELECT id FROM external_systems WHERE campus_id=? AND type=?", Long.class, campus, type);
        if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "未找到该集成");
        return rows.getFirst();
    }

    private String integrationType(String type) {
        String value = type == null ? "" : type.trim();
        if (!INTEGRATION_TYPES.contains(value)) throw new BusinessException(404, "NOT_FOUND", "未找到该集成");
        return value;
    }

    /** Only approved keys are written; values are shape-checked, never treated as free text blobs. */
    private JsonNode validateConfigValue(String key, JsonNode value) {
        if ("profile.allowedGrades".equals(key)) {
            if (IdentityInputs.strings(value).isEmpty()) throw invalid();
            return value;
        }
        if ("privacy.documentVersion".equals(key)) {
            text(value, 64, false);
            return value;
        }
        if ("privacy.deletionCoolingOffDays".equals(key)) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()
                    || value.asInt() < 1 || value.asInt() > 30) throw invalid();
            return value;
        }
        if ("privacy.documentVersions".equals(key)) {
            if (!value.isObject()) throw invalid();
            var fields = value.properties();
            if (fields.isEmpty() || fields.size() > DOCUMENT_TYPES.size()) throw invalid();
            for (var field : fields) {
                if (!DOCUMENT_TYPES.contains(field.getKey())) throw invalid();
                text(field.getValue(), 64, false);
            }
            return value;
        }
        if (key.matches("privacy\\.documentVersion\\.(privacy|phone|subscription|location|material_ai)")) {
            text(value, 64, false);
            return value;
        }
        throw new BusinessException(400, "VALIDATION_FAILED", "配置键不在允许范围内");
    }

    /** Secrets never arrive here: only a secret reference is accepted and never echoed. */
    private JsonNode validateIntegrationConfig(JsonNode config) {
        for (var field : config.properties()) {
            if (!INTEGRATION_CONFIG_KEYS.contains(field.getKey())) throw invalid();
        }
        JsonNode endpoint = config.path("endpoint");
        if (config.has("endpoint")) {
            String value = text(endpoint, 512, false);
            try {
                URI uri = new URI(value);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null) throw invalid();
            } catch (URISyntaxException error) {
                throw invalid();
            }
        }
        if (config.has("secretRef")) {
            String value = text(config.path("secretRef"), 128, false);
            if (!value.matches("[A-Za-z0-9_./:#-]{1,128}")) throw invalid();
        }
        if (config.has("timeoutMs")) {
            JsonNode value = config.path("timeoutMs");
            if (!value.isIntegralNumber() || !value.canConvertToInt()
                    || value.asInt() < 500 || value.asInt() > 30000) throw invalid();
        }
        if (config.has("schoolAuthorized") && !config.path("schoolAuthorized").isBoolean()) throw invalid();
        return config;
    }

    private JsonNode sanitized(JsonNode config) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (config.has("endpoint")) safe.put("endpoint", config.path("endpoint").asString());
        if (config.has("timeoutMs")) safe.put("timeoutMs", config.path("timeoutMs").asInt());
        if (config.has("schoolAuthorized")) safe.put("schoolAuthorized", config.path("schoolAuthorized").asBoolean());
        if (config.has("secretRef")) safe.put("secretRef", "***");
        return mapper.valueToTree(safe);
    }

    private AdminOpsViews.AuditLog auditRow(ResultSet rs) throws SQLException {
        long operator = rs.getLong("operator_id");
        String code = "admin-" + String.format("%04d", operator % 10000);
        long target = rs.getLong("target_id");
        boolean hasTarget = !rs.wasNull();
        String reason = rs.getString("reason");
        if (reason != null && reason.length() > 200) reason = reason.substring(0, 200);
        return new AdminOpsViews.AuditLog(Long.toString(rs.getLong("id")), rs.getString("action"),
                rs.getString("target_type"), hasTarget ? Long.toString(target) : null, code, reason,
                instant(rs, "created_at"));
    }

    private void audit(Actor actor, long campus, String action, String targetType, Long targetId, String reason,
                       String detailJson) {
        jdbc.update("INSERT INTO admin_audit_logs(id,operator_id,campus_id,action,target_type,target_id,reason,"
                        + "detail_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                ids.nextId(), actor.userId(), campus, action, targetType, targetId, reason, detailJson, now());
    }

    private JsonNode parse(String raw) {
        if (raw == null) return null;
        try {
            JsonNode node = mapper.readTree(raw);
            if (node.isString() && node.asString().startsWith("{")) node = mapper.readTree(node.asString());
            return node;
        } catch (RuntimeException error) {
            return mapper.valueToTree(raw);
        }
    }

    private Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException error) {
            throw invalid();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static Object[] append(List<Object> args, Object last) {
        args.add(last);
        return args.toArray();
    }

    private static String desensitizeError(String error) {
        if (error == null) return "同步失败";
        String value = error.replaceAll("[0-9]{4,}", "***").replaceAll("\\s+", " ").trim();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private static Long nullKey() {
        return null;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private BusinessException conflict() {
        return new BusinessException(409, "VERSION_CONFLICT", "信息已更新，请刷新后重试");
    }
}
