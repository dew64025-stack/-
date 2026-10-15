package com.caizhitong.material.precheck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单材料基础预检纯规则：格式/大小/命名/安全扫描/有效期/基础识别（文件头）。
 * 每个检查维度都产出结果，level=passed/confirm/missing；不声称主办方一定接受，不宣称机会材料已齐备。
 */
public final class MaterialPrecheckRules {
    public static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    public record Finding(String itemKey, String level, String message, Map<String, Object> evidence) {}

    public static List<Finding> check(String fileName, String mimeType, long sizeBytes, Instant expiresAt,
            String scanStatus, byte[] magic, Instant now) {
        return check(fileName, mimeType, sizeBytes, expiresAt, scanStatus, magic, now, null);
    }

    public static List<Finding> check(String fileName, String mimeType, long sizeBytes, Instant expiresAt,
            String scanStatus, byte[] magic, Instant now, tools.jackson.databind.JsonNode rules) {
        rules = com.caizhitong.opportunity.OpportunityInputs.validationRules(rules);
        Set<String> allowed = ALLOWED_MIME;
        long maxSize = MAX_SIZE_BYTES;
        String nameContains = null;
        if (rules != null) {
            if (rules.has("mimeTypes")) {
                allowed = new java.util.HashSet<>();
                for (var mime : rules.get("mimeTypes")) allowed.add(mime.asString());
            }
            if (rules.has("maxSizeBytes")) maxSize = rules.get("maxSizeBytes").asLong();
            if (rules.has("fileNameContains")) nameContains = rules.get("fileNameContains").asString();
        }
        List<Finding> findings = new ArrayList<>();
        findings.add(mimeType == null || !allowed.contains(mimeType)
                ? new Finding("format", "missing", "文件格式不支持", Map.of("mimeType", String.valueOf(mimeType)))
                : new Finding("format", "passed", "文件格式符合要求", Map.of("mimeType", mimeType)));
        findings.add(sizeBytes <= 0 || sizeBytes > maxSize
                ? new Finding("size", "missing", "文件大小不符合要求", Map.of("sizeBytes", sizeBytes))
                : new Finding("size", "passed", "文件大小符合要求", Map.of("sizeBytes", sizeBytes)));
        findings.add(fileName == null || fileName.isBlank() || fileName.length() > 255 || nameContains != null && !fileName.contains(nameContains)
                ? new Finding("name", "missing", "文件名不合法", Map.of("fileName", String.valueOf(fileName)))
                : new Finding("name", "passed", "文件名符合要求", Map.of("fileName", fileName)));
        findings.add(!"clean".equals(scanStatus)
                ? new Finding("scan", "missing", "文件未通过安全检查", Map.of("scanStatus", String.valueOf(scanStatus)))
                : new Finding("scan", "passed", "文件已通过安全检查", Map.of()));
        findings.add(expiresAt != null && !expiresAt.isAfter(now)
                ? new Finding("expiry", "confirm", "材料已过有效期，请确认是否继续使用", Map.of("expiresAt", expiresAt.toString()))
                : new Finding("expiry", "passed", expiresAt == null ? "材料未设置有效期" : "材料在有效期内",
                        expiresAt == null ? Map.of() : Map.of("expiresAt", expiresAt.toString())));
        if (magic != null) {
            findings.add(magicMatches(mimeType, magic)
                    ? new Finding("content", "passed", "文件头与声明类型一致", Map.of())
                    : new Finding("content", "confirm", "文件头与声明类型不一致，请人工确认", Map.of("mimeType", String.valueOf(mimeType))));
        } else {
            findings.add(new Finding("content", "confirm", "无法读取文件内容，请人工确认", Map.of()));
        }
        return List.copyOf(findings);
    }

    /** 文件头基础识别：JPEG/PNG/PDF。 */
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
