package com.caizhitong.material.precheck;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.integration.storage.ObjectStorage;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves only the selected immutable version; a latest material object is never a fallback. */
public final class VersionPrecheckFiles {
    private VersionPrecheckFiles() {}

    public static Map<String, Object> metadata(JdbcTemplate jdbc, long version, long owner, long campus) {
        var rows = jdbc.query("SELECT v.file_id,v.attachment_id,v.file_hash,v.object_key_encrypted,a.file_name,a.mime_type,a.size_bytes,a.scan_status,f.expires_at FROM material_versions v JOIN material_files f ON f.id=v.file_id JOIN file_attachments a ON a.id=v.attachment_id WHERE v.id=? AND f.user_id=? AND f.campus_id=? AND f.status='active' AND f.deleted_at IS NULL AND a.owner_user_id=f.user_id AND a.campus_id=f.campus_id AND a.access_domain='student' AND a.purpose='material' AND a.state='active' AND a.scan_status='clean' AND a.content_sha256=v.file_hash",
                (r, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file_id", r.getLong("file_id"));
                    row.put("attachment_id", r.getLong("attachment_id"));
                    row.put("file_hash", r.getString("file_hash"));
                    row.put("object_key_encrypted", r.getBytes("object_key_encrypted"));
                    row.put("file_name", r.getString("file_name"));
                    row.put("mime_type", r.getString("mime_type"));
                    row.put("size_bytes", r.getLong("size_bytes"));
                    row.put("scan_status", r.getString("scan_status"));
                    row.put("expires_at", r.getObject("expires_at", LocalDateTime.class));
                    return row;
                }, version, owner, campus);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public static Map<String, Object> snapshot(Map<String, Object> row, long version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialVersionId", Long.toString(version));
        result.put("fileId", row.get("file_id").toString());
        result.put("attachmentId", row.get("attachment_id").toString());
        result.put("fileHash", row.get("file_hash"));
        result.put("fileName", row.get("file_name"));
        result.put("mimeType", row.get("mime_type"));
        result.put("sizeBytes", row.get("size_bytes"));
        result.put("scanStatus", "clean");
        result.put("expiresAt", row.get("expires_at") == null ? null : ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC).toString());
        return result;
    }

    public static byte[] magic(Map<String, Object> row, SensitiveDataCrypto crypto, ObjectStorage storage) {
        byte[] encryptedKey = (byte[]) row.get("object_key_encrypted");
        if (encryptedKey == null) return null;
        try (var input = storage.open(crypto.decrypt("file-object-key", encryptedKey))) {
            byte[] bytes = input.readNBytes((int) MaterialPrecheckRules.MAX_SIZE_BYTES + 1);
            if (bytes.length != ((Number) row.get("size_bytes")).longValue() || bytes.length > MaterialPrecheckRules.MAX_SIZE_BYTES
                    || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(row.get("file_hash"))) return null;
            return java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 16));
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
