package com.caizhitong.material.precheck;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialPrecheckRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-15T06:00:00Z");

    @Test
    void itemRulesRejectAllowedButWrongMimeAndEnforceNameAndSize() {
        var rules = new tools.jackson.databind.json.JsonMapper().readTree(
                "{\"mimeTypes\":[\"application/pdf\"],\"maxSizeBytes\":1024,\"fileNameContains\":\"成绩单\"}");
        var findings = MaterialPrecheckRules.check("photo.png", "image/png", 2048, null, "clean",
                new byte[] { (byte) 0x89, 'P', 'N', 'G' }, NOW, rules);
        assertEquals(List.of("format", "size", "name"), findings.stream().filter(f -> f.level().equals("missing")).map(MaterialPrecheckRules.Finding::itemKey).toList());
        assertFalse(MaterialPrecheckRules.basicComplete(findings));
        var valid = MaterialPrecheckRules.check("成绩单.pdf", "application/pdf", 1024, null, "clean", "%PDF".getBytes(), NOW, rules);
        assertTrue(valid.stream().allMatch(f -> f.level().equals("passed")));
    }

    @Test
    void cleanPdfPassesAllDimensions() {
        var findings = MaterialPrecheckRules.check("材料.pdf", "application/pdf", 1024, null, "clean",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8), NOW);
        assertEquals(6, findings.size());
        assertTrue(MaterialPrecheckRules.basicComplete(findings));
        assertTrue(findings.stream().allMatch(f -> "passed".equals(f.level())));
    }

    @Test
    void expiredAndSpoofedContentNeedConfirmation() {
        var findings = MaterialPrecheckRules.check("材料.pdf", "application/pdf", 1024, NOW.minusSeconds(3600), "clean",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8), NOW);
        assertTrue(findings.stream().anyMatch(f -> f.itemKey().equals("expiry") && f.level().equals("confirm")));
        var spoofed = MaterialPrecheckRules.check("图.png", "image/png", 1024, null, "clean",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8), NOW);
        assertTrue(spoofed.stream().anyMatch(f -> f.itemKey().equals("content") && f.level().equals("confirm")));
    }

    @Test
    void badFormatOversizeAndUncleanAreMissing() {
        var findings = MaterialPrecheckRules.check("x.exe", "application/x-msdownload", 1024, null, "clean", null, NOW);
        assertTrue(findings.stream().anyMatch(f -> f.itemKey().equals("format") && f.level().equals("missing")));
        assertFalse(MaterialPrecheckRules.basicComplete(findings));
        var big = MaterialPrecheckRules.check("f.pdf", "application/pdf", 21L * 1024 * 1024, null, "clean", null, NOW);
        assertTrue(big.stream().anyMatch(f -> f.itemKey().equals("size") && f.level().equals("missing")));
        var dirty = MaterialPrecheckRules.check("f.pdf", "application/pdf", 1024, null, "pending", null, NOW);
        assertTrue(dirty.stream().anyMatch(f -> f.itemKey().equals("scan") && f.level().equals("missing")));
    }

    @Test
    void magicMatchesKnownSignatures() {
        assertTrue(MaterialPrecheckRules.magicMatches("application/pdf", "%PDF".getBytes()));
        assertTrue(MaterialPrecheckRules.magicMatches("image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G' }));
        assertTrue(MaterialPrecheckRules.magicMatches("image/jpeg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 }));
        assertFalse(MaterialPrecheckRules.magicMatches("application/pdf", "GIF8".getBytes()));
    }
}
