package com.caizhitong.material.precheck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单材料基础预检纯规则：格式/大小/命名/安全扫描/有效期/基础识别（文件头）。
 * 结果三档 passed/confirm/missing；不声称主办方一定接受，不宣称机会材料已齐备。
 */
public final class MaterialPrecheckRules {
    public static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    public record Finding(String itemKey, String level, String message, Map<String, Object> evidence) {}

    public static List<Finding> check(String fileName, String mimeType, long sizeBytes, Instant expiresAt,
            String scanStatus, byte[] magic, Instant now) {
        List<Finding> findings = new ArrayList<>();
        if (mimeType == null || !ALLOWED_MIME.contains(mimeType)) {
            findings.add(new Finding("format", "missing", "文件格式不支持", Map.of("mimeType", String.valueOf(mimeType))));
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) {
            findings.add(new Finding("size", "missing", "文件大小不符合要求", Map.of("sizeBytes", sizeBytes)));
        }
        if (fileName == null || fileName.isBlank() || fileName.length() > 255) {
            findings.add(new Finding("name", "missing", "文件名不合法", Map.of("fileName", String.valueOf(fileName))));
        }
        if (!"clean".equals(scanStatus)) {
            findings.add(new Finding("scan", "missing", "文件未通过安全检查", Map.of("scanStatus", String.valueOf(scanStatus))));
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            findings.add(new Finding("expiry", "confirm", "材料已过有效期，请确认是否继续使用", Map.of("expiresAt", expiresAt.toString())));
        }
        if (magic != null && !magicMatches(mimeType, magic)) {
            findings.add(new Finding("content", "confirm", "文件头与声明类型不一致，请人工确认", Map.of("mimeType", String.valueOf(mimeType))));
        }
        return List.copyOf(findings);
    }

    /** 文件头基础识别：JPEG/PNG/PDF；magic 为空表示存储不可读，交由调用方给 confirm。 */
    public static boolean magicMatches(String mimeType, byte[] magic) {
        if (magic == null || magic.length < 4) return false;
        return switch (mimeType) {
            case "image/jpeg" -> (magic[0] & 0xFF) == 0xFF && (magic[1] & 0xFF) == 0xD8 && (magic[2] & 0xFF) == 0xFF;
            case "image/png" -> (magic[0] & 0xFF) == 0x89 && magic[1] == 'P' && magic[2] == 'N' && magic[3] == 'G';
            case "application/pdf" -> magic[0] == '%' && magic[1] == 'P' && magic[2] == 'D' && magic[3] == 'F';
            default -> true;
        };
    }

    public static boolean basicComplete(List<Finding> findings) {
        return findings.stream().noneMatch(finding -> "missing".equals(finding.level()));
    }
}
