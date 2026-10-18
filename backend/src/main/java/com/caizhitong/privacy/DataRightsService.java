package com.caizhitong.privacy;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.common.idempotency.BusinessWrite;
import com.caizhitong.common.pagination.CursorCodec;
import com.caizhitong.common.pagination.CursorPayload;
import com.caizhitong.common.pagination.PageLimits;
import com.caizhitong.integration.storage.ObjectStorage;
import com.caizhitong.security.Actor;
import com.caizhitong.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.caizhitong.identity.IdentityInputs.id;
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.text;

/** 授权、隐私设置与数据权利申请；下载凭证只在本人读取详情时短时签发，不入库。 */
@Service
public class DataRightsService {
    private static final int DOWNLOAD_SECONDS = 120;
    private static final Set<String> AUTHORIZATION_TYPES =
            Set.of("privacy", "phone", "subscription", "location", "material_ai");

    private final JdbcTemplate jdbc;
    private final CurrentActor current;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final CursorCodec cursors;
    private final SensitiveDataCrypto crypto;
    private final ObjectStorage storage;
    private final JsonMapper mapper;
    private final String defaultDocumentVersion;
    private final int configuredCoolingOffDays;

    public DataRightsService(JdbcTemplate jdbc, CurrentActor current, SnowflakeIds ids, Clock clock,
                             CursorCodec cursors, SensitiveDataCrypto crypto, ObjectStorage storage, JsonMapper mapper,
                             @Value("${app.privacy.document-version:}") String defaultDocumentVersion,
                             @Value("${app.privacy.deletion-cooling-off-days:7}") int configuredCoolingOffDays) {
        this.jdbc = jdbc;
        this.current = current;
        this.ids = ids;
        this.clock = clock;
        this.cursors = cursors;
        this.crypto = crypto;
        this.storage = storage;
        this.mapper = mapper;
        this.defaultDocumentVersion = defaultDocumentVersion == null ? "" : defaultDocumentVersion.trim();
        this.configuredCoolingOffDays = configuredCoolingOffDays;
    }

    public List<PrivacyViews.Authorization> authorizations() {
        Actor actor = current.require();
        return jdbc.query("SELECT id,type,scope,status,document_version,granted_at,revoked_at FROM authorizations"
                        + " WHERE user_id=? ORDER BY id DESC", (rs, i) -> {
                    var revoked = rs.getObject("revoked_at", LocalDateTime.class);
                    return new PrivacyViews.Authorization(Long.toString(rs.getLong("id")), rs.getString("type"),
                            rs.getString("scope"), rs.getString("status"), rs.getString("document_version"),
                            rs.getObject("granted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                            revoked == null ? null : revoked.toInstant(ZoneOffset.UTC));
                }, actor.userId());
    }

    @BusinessWrite
    public PrivacyViews.Authorization grant(PrivacyInputs.Grant input) {
        Actor actor = current.require();
        String type = text(input.type(), 32, false);
        if (!AUTHORIZATION_TYPES.contains(type)) throw invalid();
        String scope = text(input.scope(), 128, false);
        String version = text(input.documentVersion(), 64, false);
        String published = publishedVersion(actor.campusId(), type);
        if (published == null || published.isBlank()) {
            throw new BusinessException(503, "PRIVACY_DOCUMENT_UNAVAILABLE", "隐私协议版本尚未配置，暂时无法授权", null, true);
        }
        if (!published.equals(version)) {
            throw new BusinessException(409, "PRIVACY_DOCUMENT_CHANGED", "协议版本已更新，请阅读后重试");
        }
        long authorization = ids.nextId();
        LocalDateTime now = now();
        jdbc.update("INSERT INTO authorizations(id,user_id,type,scope,document_version,status,version,granted_at,created_at)"
                        + " VALUES(?,?,?,?,?,'granted',0,?,?)",
                authorization, actor.userId(), type, scope, version, now, now);
        return authorization(authorization, actor.userId());
    }

    @BusinessWrite
    public PrivacyViews.Authorization revoke(String authorizationId) {
        Actor actor = current.require();
        long authorization = id(authorizationId);
        var rows = jdbc.queryForList("SELECT status FROM authorizations WHERE id=? AND user_id=? FOR UPDATE",
                authorization, actor.userId());
        if (rows.isEmpty()) throw missing("未找到该授权");
        if ("granted".equals(rows.getFirst().get("status"))) {
            LocalDateTime now = now();
            jdbc.update("UPDATE authorizations SET status='revoked',revoked_at=?,version=version+1 WHERE id=?",
                    now, authorization);
        }
        return authorization(authorization, actor.userId());
    }

    public PrivacyViews.PrivacySettings privacySettings() {
        Actor actor = current.require();
        var rows = jdbc.query("SELECT personalization_enabled,history_enabled,nearby_enabled,version FROM privacy_settings"
                        + " WHERE user_id=?",
                (rs, i) -> new PrivacyViews.PrivacySettings(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
                        rs.getInt(4)), actor.userId());
        return rows.isEmpty() ? new PrivacyViews.PrivacySettings(false, false, false, 0) : rows.getFirst();
    }

    @BusinessWrite
    public PrivacyViews.PrivacySettings updatePrivacy(PrivacyInputs.PrivacyUpdate input) {
        Actor actor = current.require();
        int version = input.version().asInt();
        var rows = jdbc.queryForList("SELECT personalization_enabled,history_enabled,nearby_enabled,version"
                + " FROM privacy_settings WHERE user_id=? FOR UPDATE", actor.userId());
        boolean personalization;
        boolean history;
        boolean nearby;
        LocalDateTime now = now();
        if (rows.isEmpty()) {
            if (version != 0) throw conflict();
            personalization = input.personalization() != null && input.personalization().asBoolean();
            history = input.historyEnabled() != null && input.historyEnabled().asBoolean();
            nearby = input.nearbyEnabled() != null && input.nearbyEnabled().asBoolean();
            jdbc.update("INSERT INTO privacy_settings(user_id,personalization_enabled,history_enabled,nearby_enabled,"
                            + "version,created_at,updated_at) VALUES(?,?,?,?,1,?,?)",
                    actor.userId(), personalization, history, nearby, now, now);
        } else {
            Map<String, Object> old = rows.getFirst();
            if (((Number) old.get("version")).intValue() != version) throw conflict();
            personalization = input.personalization() == null
                    ? truth(old.get("personalization_enabled")) : input.personalization().asBoolean();
            history = input.historyEnabled() == null
                    ? truth(old.get("history_enabled")) : input.historyEnabled().asBoolean();
            nearby = input.nearbyEnabled() == null
                    ? truth(old.get("nearby_enabled")) : input.nearbyEnabled().asBoolean();
            jdbc.update("UPDATE privacy_settings SET personalization_enabled=?,history_enabled=?,nearby_enabled=?,"
                            + "version=version+1,updated_at=? WHERE user_id=? AND version=?",
                    personalization, history, nearby, now, actor.userId(), version);
        }
        if (!history) {
            jdbc.update("DELETE FROM browse_history WHERE user_id=?", actor.userId());
        }
        return privacySettings();
    }

    @BusinessWrite(ownRights = true)
    public PrivacyViews.DataRequest exportData() {
        Actor actor = current.requireAuthenticatedIdentity();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM data_requests WHERE user_id=? AND type='export'"
                        + " AND status IN ('pending','processing')", Integer.class, actor.userId()) > 0) {
            throw new BusinessException(409, "STATE_CONFLICT", "已有正在处理的数据导出申请");
        }
        long request = ids.nextId();
        LocalDateTime now = now();
        try {
            jdbc.update("INSERT INTO data_requests(id,user_id,campus_id,type,status,requested_at,version,created_at,updated_at)"
                            + " VALUES(?,?,?,'export','pending',?,0,?,?)",
                    request, actor.userId(), actor.campusId(), now, now, now);
        } catch (DuplicateKeyException concurrent) {
            throw new BusinessException(409, "STATE_CONFLICT", "已有正在处理的数据导出申请");
        }
        return dataRequest(request, actor, true);
    }

    @BusinessWrite(ownRights = true)
    public PrivacyViews.DataRequest deleteAccount(PrivacyInputs.Deletion input) {
        Actor actor = current.requireAuthenticatedIdentity();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM data_requests WHERE user_id=? AND type='deletion'"
                        + " AND status IN ('pending','processing')", Integer.class, actor.userId()) > 0) {
            throw new BusinessException(409, "STATE_CONFLICT", "已有正在处理的注销申请");
        }
        int days = coolingOffDays(actor.campusId());
        long request = ids.nextId();
        LocalDateTime now = now();
        LocalDateTime coolingOff = now.plusDays(days);
        try {
            jdbc.update("INSERT INTO data_requests(id,user_id,campus_id,type,status,requested_at,cooling_off_until,"
                            + "retention_basis,version,created_at,updated_at)"
                            + " VALUES(?,?,?,'deletion','pending',?,?,?,0,?,?)",
                    request, actor.userId(), actor.campusId(), now, coolingOff,
                    "冷静期内可撤销；执行前复核未完成借用、预约与工单，依法保留的信用与审计记录不含联系方式。", now, now);
        } catch (DuplicateKeyException concurrent) {
            throw new BusinessException(409, "STATE_CONFLICT", "已有正在处理的注销申请");
        }
        return dataRequest(request, actor, true);
    }

    public PrivacyViews.DataRequestPage dataRequests(String cursor, Integer limit) {
        Actor actor = current.requireAuthenticatedIdentity();
        int size = PageLimits.resolve(limit);
        String scope = "data-requests:" + actor.campusId() + ":" + actor.userId();
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            CursorPayload keys = cursors.decode(cursor, scope);
            if (!keys.sortKeys().keySet().equals(Set.of("id"))) throw invalid();
            before = id(keys.sortKeys().get("id"));
        }
        var found = jdbc.queryForList("SELECT id FROM data_requests WHERE user_id=? AND campus_id=? AND id<?"
                + " ORDER BY id DESC LIMIT ?", Long.class, actor.userId(), actor.campusId(), before, size + 1);
        boolean more = found.size() > size;
        List<PrivacyViews.DataRequest> items = found.stream().limit(size)
                .map(value -> dataRequest(value, actor, false)).toList();
        String next = more && !items.isEmpty()
                ? cursors.encode(new CursorPayload(scope, Map.of("id", items.getLast().id()))) : null;
        return new PrivacyViews.DataRequestPage(items, next, more);
    }

    public PrivacyViews.DataRequest dataRequest(String requestId) {
        Actor actor = current.requireAuthenticatedIdentity();
        return dataRequest(id(requestId), actor, true);
    }

    @BusinessWrite(ownRights = true)
    public PrivacyViews.DataRequest cancel(String requestId) {
        Actor actor = current.requireAuthenticatedIdentity();
        long request = id(requestId);
        var rows = jdbc.query("SELECT type,status,cooling_off_until,version FROM data_requests"
                        + " WHERE id=? AND user_id=? AND campus_id=? FOR UPDATE",
                (rs, i) -> new Object[]{rs.getString("type"), rs.getString("status"),
                        rs.getObject("cooling_off_until", LocalDateTime.class), rs.getInt("version")},
                request, actor.userId(), actor.campusId());
        if (rows.isEmpty()) throw missing("未找到该数据请求");
        Object[] row = rows.getFirst();
        if (!"deletion".equals(row[0])) {
            throw new BusinessException(409, "STATE_CONFLICT", "数据导出申请不支持撤销");
        }
        if (!"pending".equals(row[1])) {
            throw new BusinessException(409, "STATE_CONFLICT", "该注销申请已进入处理阶段，无法撤销");
        }
        LocalDateTime coolingOff = (LocalDateTime) row[2];
        LocalDateTime now = now();
        if (coolingOff == null || !coolingOff.isAfter(now)) {
            throw new BusinessException(409, "STATE_CONFLICT", "注销冷静期已结束，无法撤销");
        }
        jdbc.update("UPDATE data_requests SET status='cancelled',cancelled_at=?,processed_at=?,version=version+1,"
                + "updated_at=? WHERE id=?", now, now, now, request);
        return dataRequest(request, actor, true);
    }

    private PrivacyViews.DataRequest dataRequest(long request, Actor actor, boolean detail) {
        var rows = jdbc.query("SELECT id,type,status,requested_at,processed_at,retention_basis,result_json,"
                        + "cancelled_at,cooling_off_until,failure_reason,export_object_key_encrypted,export_sha256,version"
                        + " FROM data_requests WHERE id=? AND user_id=? AND campus_id=?",
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                        rs.getObject("requested_at", LocalDateTime.class),
                        rs.getObject("processed_at", LocalDateTime.class), rs.getString("retention_basis"),
                        rs.getObject("cancelled_at", LocalDateTime.class),
                        rs.getObject("cooling_off_until", LocalDateTime.class), rs.getString("failure_reason"),
                        rs.getBytes("export_object_key_encrypted"), rs.getString("export_sha256"), rs.getInt("version")},
                request, actor.userId(), actor.campusId());
        if (rows.isEmpty()) throw missing("未找到该数据请求");
        Object[] row = rows.getFirst();
        String type = (String) row[1];
        String status = (String) row[2];
        JsonNode result = null;
        if (detail) {
            if ("completed".equals(status) && "export".equals(type) && row[9] != null) {
                String key = crypto.decrypt("data-export-key", (byte[]) row[9]);
                String url;
                try {
                    url = storage.sign(key, Duration.ofSeconds(DOWNLOAD_SECONDS));
                } catch (RuntimeException error) {
                    throw new BusinessException(503, "EXPORT_DOWNLOAD_UNAVAILABLE", "导出文件暂不可用", null, true);
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("downloadUrl", url);
                payload.put("expiresAt", clock.instant().plusSeconds(DOWNLOAD_SECONDS).toString());
                payload.put("sha256", row[10]);
                payload.put("fileName", "data-export-" + request + ".json");
                result = mapper.valueToTree(payload);
            } else if (row[8] != null && ("failed".equals(status) || "rejected".equals(status))) {
                result = mapper.valueToTree(Map.of("failureReason", row[8]));
            }
        }
        LocalDateTime processed = (LocalDateTime) row[4];
        LocalDateTime cancelled = (LocalDateTime) row[6];
        LocalDateTime cooling = (LocalDateTime) row[7];
        return new PrivacyViews.DataRequest(Long.toString((Long) row[0]), type, status,
                ((LocalDateTime) row[3]).toInstant(ZoneOffset.UTC),
                processed == null ? null : processed.toInstant(ZoneOffset.UTC), (String) row[5], result,
                cancelled == null ? null : cancelled.toInstant(ZoneOffset.UTC),
                cooling == null ? null : cooling.toInstant(ZoneOffset.UTC), (Integer) row[11]);
    }

    private PrivacyViews.Authorization authorization(long id, long user) {
        var rows = jdbc.query("SELECT id,type,scope,status,document_version,granted_at,revoked_at FROM authorizations"
                        + " WHERE id=? AND user_id=?", (rs, i) -> {
                    var revoked = rs.getObject("revoked_at", LocalDateTime.class);
                    return new PrivacyViews.Authorization(Long.toString(rs.getLong("id")), rs.getString("type"),
                            rs.getString("scope"), rs.getString("status"), rs.getString("document_version"),
                            rs.getObject("granted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                            revoked == null ? null : revoked.toInstant(ZoneOffset.UTC));
                }, id, user);
        if (rows.isEmpty()) throw missing("未找到该授权");
        return rows.getFirst();
    }

    /** Per-campus published version, then the deployment default; never the optimistic-lock version. */
    private String publishedVersion(long campus, String type) {
        List<String> direct = jdbc.queryForList("SELECT config_value FROM campus_configs WHERE campus_id=?"
                + " AND config_key=?", String.class, campus, "privacy.documentVersion." + type);
        if (!direct.isEmpty()) return configText(direct.getFirst());
        List<String> generic = jdbc.queryForList("SELECT config_value FROM campus_configs WHERE campus_id=?"
                + " AND config_key='privacy.documentVersion'", String.class, campus);
        if (!generic.isEmpty()) return configText(generic.getFirst());
        List<String> documents = jdbc.queryForList("SELECT config_value FROM campus_configs WHERE campus_id=?"
                + " AND config_key='privacy.documentVersions'", String.class, campus);
        if (!documents.isEmpty()) {
            JsonNode node = parseConfig(documents.getFirst());
            if (node != null && node.isObject()) {
                JsonNode value = node.path(type);
                if (value.isString() && !value.asString().isBlank()) return value.asString();
            }
        }
        return defaultDocumentVersion;
    }

    private String configText(String raw) {
        JsonNode node = parseConfig(raw);
        if (node == null) return null;
        return node.isString() ? node.asString() : null;
    }

    private JsonNode parseConfig(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (node.isString() && node.asString().startsWith("{")) node = mapper.readTree(node.asString());
            return node;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private int coolingOffDays(long campus) {
        List<String> values = jdbc.queryForList("SELECT config_value FROM campus_configs WHERE campus_id=?"
                + " AND config_key='privacy.deletionCoolingOffDays'", String.class, campus);
        if (!values.isEmpty()) {
            JsonNode node = parseConfig(values.getFirst());
            if (node != null && node.isIntegralNumber()) {
                return Math.max(1, Math.min(30, node.asInt()));
            }
        }
        return Math.max(1, Math.min(30, configuredCoolingOffDays));
    }

    private static boolean truth(Object value) {
        return value instanceof Boolean bool ? bool : value instanceof Number number && number.intValue() != 0;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private BusinessException missing(String message) {
        return new BusinessException(404, "NOT_FOUND", message);
    }

    private BusinessException conflict() {
        return new BusinessException(409, "VERSION_CONFLICT", "信息已更新，请刷新后重试");
    }
}
