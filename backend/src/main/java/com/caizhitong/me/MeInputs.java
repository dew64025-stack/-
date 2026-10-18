package com.caizhitong.me;

import com.caizhitong.common.BusinessException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static com.caizhitong.identity.IdentityInputs.invalid;
import static com.caizhitong.identity.IdentityInputs.text;

/** Missing versus explicit null is preserved for partial growth-record updates. */
public final class MeInputs {
    private MeInputs() {}

    public static final Set<String> GROWTH_TYPES = Set.of("competition", "research", "volunteer", "mutual_aid");

    public record GrowthCreate(JsonNode type, JsonNode title, JsonNode description, JsonNode proofFileId,
                               JsonNode occurredAt, JsonNode confirmed) {
        public GrowthCreate {
            growthType(type);
            text(title, 256, false);
            optionalText(description, 1024);
            optionalId(proofFileId);
            instant(occurredAt);
            bool(confirmed);
        }
    }

    public record GrowthUpdate(JsonNode type, JsonNode title, JsonNode description, JsonNode proofFileId,
                               JsonNode occurredAt, JsonNode confirmed, JsonNode version) {
        public GrowthUpdate {
            com.caizhitong.identity.IdentityInputs.version(version);
            if (type == null && title == null && description == null && proofFileId == null
                    && occurredAt == null && confirmed == null) throw invalid();
            if (type != null) growthType(type);
            if (title != null) text(title, 256, false);
            optionalText(description, 1024);
            optionalId(proofFileId);
            instant(occurredAt);
            if (confirmed != null) bool(confirmed);
        }
    }

    public record CreditDispute(JsonNode reason) {
        public CreditDispute {
            text(reason, 512, false);
        }
    }

    public record Empty() {}

    public static String growthType(JsonNode node) {
        String value = text(node, 32, false);
        if (!GROWTH_TYPES.contains(value)) throw invalid();
        return value;
    }

    public static String optionalText(JsonNode node, int max) {
        if (node == null || node.isNull()) return null;
        return text(node, max, false);
    }

    public static Long optionalId(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return com.caizhitong.identity.IdentityInputs.id(node);
    }

    public static Instant instant(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw invalid();
        try {
            return OffsetDateTime.parse(node.asString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException error) {
            throw invalid();
        }
    }

    public static boolean bool(JsonNode node) {
        if (node == null || !node.isBoolean()) throw invalid();
        return node.asBoolean();
    }

    public static int nonNegativeVersion(int value) {
        if (value < 0) throw invalid();
        return value;
    }

    public static BusinessException conflict() {
        return new BusinessException(409, "VERSION_CONFLICT", "信息已更新，请刷新后重试");
    }
}
