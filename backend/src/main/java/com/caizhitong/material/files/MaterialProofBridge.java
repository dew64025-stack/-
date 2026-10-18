package com.caizhitong.material.files;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only proof-file ownership check for the growth archive. */
@Component
public class MaterialProofBridge {
    private final JdbcTemplate jdbc;

    public MaterialProofBridge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A proof must be the actor's own active, clean material in the same campus. */
    public boolean usable(long campus, long user, long materialId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM material_files WHERE id=? AND campus_id=? AND user_id=?"
                + " AND status='active' AND scan_status='clean' AND deleted_at IS NULL", Integer.class,
                materialId, campus, user);
        return count != null && count == 1;
    }

    /** Metadata only: object keys and hashes are never part of a personal export. */
    public List<Map<String, Object>> exportMaterials(long user) {
        return jdbc.query("SELECT id,file_name,mime_type,size_bytes,expires_at,scan_status,status,created_at"
                        + " FROM material_files WHERE user_id=? AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", Long.toString(rs.getLong("id")));
                    row.put("fileName", rs.getString("file_name"));
                    row.put("mimeType", rs.getString("mime_type"));
                    row.put("sizeBytes", rs.getLong("size_bytes"));
                    row.put("expiresAt", instantText(rs, "expires_at"));
                    row.put("scanStatus", rs.getString("scan_status"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instantText(rs, "created_at"));
                    return row;
                }, user);
    }

    private static String instantText(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        Instant instant = value == null ? null : value.toInstant(ZoneOffset.UTC);
        return instant == null ? null : instant.toString();
    }
}
