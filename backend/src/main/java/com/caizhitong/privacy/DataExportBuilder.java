package com.caizhitong.privacy;

import com.caizhitong.issue.IssueReadBridge;
import com.caizhitong.material.files.MaterialProofBridge;
import com.caizhitong.mutualaid.MutualAidReadBridge;
import com.caizhitong.opportunity.OpportunityReadBridge;
import com.caizhitong.resource.ResourceReadBridge;
import com.caizhitong.team.TeamReadBridge;
import com.caizhitong.workorder.WorkOrderReadBridge;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the export body in memory. It contains only the requesting user's own data:
 * no other users' content, anonymous mappings, investigation data, phone/student numbers,
 * object keys or signed URLs. The body is never stored in the database.
 */
@Component
public class DataExportBuilder {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final OpportunityReadBridge opportunities;
    private final MaterialProofBridge materials;
    private final ResourceReadBridge resources;
    private final WorkOrderReadBridge workOrders;
    private final IssueReadBridge issues;
    private final MutualAidReadBridge mutualAid;
    private final TeamReadBridge teams;

    public DataExportBuilder(JdbcTemplate jdbc, JsonMapper mapper, OpportunityReadBridge opportunities,
                             MaterialProofBridge materials, ResourceReadBridge resources,
                             WorkOrderReadBridge workOrders, IssueReadBridge issues, MutualAidReadBridge mutualAid,
                             TeamReadBridge teams) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.opportunities = opportunities;
        this.materials = materials;
        this.resources = resources;
        this.workOrders = workOrders;
        this.issues = issues;
        this.mutualAid = mutualAid;
        this.teams = teams;
    }

    public byte[] build(long user, long campus, String requestedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("formatVersion", 1);
        data.put("requestedAt", requestedAt);
        data.put("notice", "匿名社区内容、匿名身份映射、安全调查资料及他人隐私不在本导出范围内。");
        data.put("identity", identity(user));
        data.put("authorizations", authorizations(user));
        data.put("privacySettings", privacySettings(user));
        data.put("growthRecords", growthRecords(user));
        data.put("creditEvents", creditEvents(user, campus));
        data.put("creditDisputes", creditDisputes(user, campus));
        data.put("favorites", opportunities.exportFavorites(user));
        data.put("reminders", opportunities.exportReminders(user));
        data.put("browseHistory", browseHistory(user));
        data.put("dataRequests", dataRequests(user));
        data.put("opportunitySubmissions", opportunities.exportSubmissions(user, campus));
        data.put("materials", materials.exportMaterials(user));
        data.put("reservations", resources.exportReservations(user, campus));
        data.put("workOrders", workOrders.exportOrders(user, campus));
        data.put("issues", issues.exportOwn(user, campus));
        data.put("mutualAidPosts", mutualAid.exportPosts(user, campus));
        data.put("borrowRequests", mutualAid.exportBorrows(user, campus));
        data.put("teams", teams.exportTeams(user, campus));
        data.put("teamMemberships", teams.exportMemberships(user, campus));
        data.put("teamApplications", teams.exportApplications(user, campus));
        data.put("sentMessages", sentMessages(user));
        data.put("notifications", notifications(user));
        return mapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> identity(long user) {
        Map<String, Object> result = new LinkedHashMap<>();
        jdbc.query("SELECT id,campus_id,nickname,status,created_at,updated_at FROM users WHERE id=?",
                rs -> {
                    result.put("userId", Long.toString(rs.getLong("id")));
                    result.put("campusId", Long.toString(rs.getLong("campus_id")));
                    result.put("nickname", rs.getString("nickname"));
                    result.put("status", rs.getString("status"));
                    result.put("createdAt", instantText(rs, "created_at"));
                    result.put("updatedAt", instantText(rs, "updated_at"));
                    return null;
                }, user);
        result.put("phoneBound", jdbc.queryForObject("SELECT COUNT(*) FROM user_phones WHERE user_id=?",
                Integer.class, user) > 0);
        var verification = jdbc.query("SELECT status,verified_at FROM student_verifications WHERE user_id=?"
                        + " ORDER BY id DESC LIMIT 1",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("verifiedAt", instantText(rs, "verified_at"));
                    return row;
                }, user);
        result.put("studentVerification", verification.isEmpty() ? null : verification.getFirst());
        var profiles = jdbc.query("SELECT college,grade,skills,interests FROM user_profiles WHERE user_id=?",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("college", rs.getString("college"));
                    row.put("grade", rs.getString("grade"));
                    row.put("skills", json(rs.getString("skills")));
                    row.put("interests", json(rs.getString("interests")));
                    return row;
                }, user);
        result.put("profile", profiles.isEmpty() ? null : profiles.getFirst());
        return result;
    }

    private List<Map<String, Object>> authorizations(long user) {
        return jdbc.query("SELECT id,type,scope,document_version,status,granted_at,revoked_at FROM authorizations"
                        + " WHERE user_id=? ORDER BY id", (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("type", rs.getString("type"));
                    row.put("scope", rs.getString("scope"));
                    row.put("documentVersion", rs.getString("document_version"));
                    row.put("status", rs.getString("status"));
                    row.put("grantedAt", instantText(rs, "granted_at"));
                    row.put("revokedAt", instantText(rs, "revoked_at"));
                    return row;
                }, user);
    }

    private Map<String, Object> privacySettings(long user) {
        var rows = jdbc.query("SELECT personalization_enabled,history_enabled,nearby_enabled,updated_at"
                + " FROM privacy_settings WHERE user_id=?", (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("personalization", rs.getBoolean("personalization_enabled"));
            row.put("historyEnabled", rs.getBoolean("history_enabled"));
            row.put("nearbyEnabled", rs.getBoolean("nearby_enabled"));
            row.put("updatedAt", instantText(rs, "updated_at"));
            return row;
        }, user);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> growthRecords(long user) {
        return jdbc.query("SELECT id,type,title,description,proof_file_id,confirmed,occurred_at,created_at,updated_at"
                        + " FROM growth_records WHERE user_id=? AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("type", rs.getString("type"));
                    row.put("title", rs.getString("title"));
                    row.put("description", rs.getString("description"));
                    long proof = rs.getLong("proof_file_id");
                    row.put("proofFileId", rs.wasNull() ? null : Long.toString(proof));
                    row.put("confirmed", rs.getBoolean("confirmed"));
                    row.put("occurredAt", instantText(rs, "occurred_at"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    row.put("updatedAt", instantText(rs, "updated_at"));
                    return row;
                }, user);
    }

    private List<Map<String, Object>> creditEvents(long user, long campus) {
        return jdbc.query("SELECT id,event_type,reason,points,related_type,related_id,created_at FROM credit_events"
                        + " WHERE user_id=? AND campus_id=? ORDER BY id", (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("eventType", rs.getString("event_type"));
                    row.put("reason", rs.getString("reason"));
                    row.put("points", rs.getInt("points"));
                    row.put("relatedType", rs.getString("related_type"));
                    long related = rs.getLong("related_id");
                    row.put("relatedId", rs.wasNull() ? null : Long.toString(related));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, user, campus);
    }

    private List<Map<String, Object>> creditDisputes(long user, long campus) {
        return jdbc.query("SELECT id,credit_event_id,reason,status,reviewed_at,created_at FROM credit_disputes"
                        + " WHERE user_id=? AND campus_id=? ORDER BY id", (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("creditEventId", Long.toString(rs.getLong("credit_event_id")));
                    row.put("reason", rs.getString("reason"));
                    row.put("status", rs.getString("status"));
                    row.put("reviewedAt", instantText(rs, "reviewed_at"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, user, campus);
    }

    private List<Map<String, Object>> browseHistory(long user) {
        return jdbc.query("SELECT id,target_type,target_id,title,summary,viewed_at FROM browse_history"
                        + " WHERE user_id=? ORDER BY viewed_at DESC,id DESC", (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("targetType", rs.getString("target_type"));
                    row.put("targetId", Long.toString(rs.getLong("target_id")));
                    row.put("title", rs.getString("title"));
                    row.put("summary", rs.getString("summary"));
                    row.put("viewedAt", instantText(rs, "viewed_at"));
                    return row;
                }, user);
    }

    private List<Map<String, Object>> dataRequests(long user) {
        return jdbc.query("SELECT id,type,status,requested_at,processed_at,cancelled_at,cooling_off_until,failure_reason"
                        + " FROM data_requests WHERE user_id=? ORDER BY id", (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("type", rs.getString("type"));
                    row.put("status", rs.getString("status"));
                    row.put("requestedAt", instantText(rs, "requested_at"));
                    row.put("processedAt", instantText(rs, "processed_at"));
                    row.put("cancelledAt", instantText(rs, "cancelled_at"));
                    row.put("coolingOffUntil", instantText(rs, "cooling_off_until"));
                    row.put("failureReason", rs.getString("failure_reason"));
                    return row;
                }, user);
    }

    private List<Map<String, Object>> sentMessages(long user) {
        return jdbc.query("SELECT id,conversation_id,content,status,created_at FROM messages"
                        + " WHERE sender_id=? ORDER BY id", (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("conversationId", Long.toString(rs.getLong("conversation_id")));
                    row.put("content", rs.getString("content"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, user);
    }

    private List<Map<String, Object>> notifications(long user) {
        return jdbc.query("SELECT id,type,title,content,target_type,target_id,read_at,created_at FROM notifications"
                        + " WHERE user_id=? ORDER BY id", (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("type", rs.getString("type"));
                    row.put("title", rs.getString("title"));
                    row.put("content", rs.getString("content"));
                    row.put("targetType", rs.getString("target_type"));
                    long target = rs.getLong("target_id");
                    row.put("targetId", rs.wasNull() ? null : Long.toString(target));
                    row.put("readAt", instantText(rs, "read_at"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, user);
    }

    private JsonNode json(String raw) {
        if (raw == null) return null;
        try {
            return mapper.readTree(raw);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String instantText(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
