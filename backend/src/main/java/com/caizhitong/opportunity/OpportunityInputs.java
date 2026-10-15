package com.caizhitong.opportunity;

import com.caizhitong.common.BusinessException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static com.caizhitong.identity.IdentityInputs.invalid;

public final class OpportunityInputs {
    private OpportunityInputs() {}

    public static final Set<String> CATEGORIES = Set.of("scholarship", "competition", "research", "internship", "volunteer");
    public static final Set<String> FAVORITE_TARGETS = Set.of("opportunity", "resource", "mutual_aid_post", "community_post");
    public static final Set<String> REMINDER_TARGETS = Set.of("opportunity", "material", "borrow", "reservation");

    /** 必须为带时区的 RFC3339，拒绝无时区时间与不存在的日期。 */
    public static Instant rfc3339(String text) {
        if (text == null) throw invalid();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    public static Instant optionalRfc3339(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalid();
        return rfc3339(node.asString());
    }

    public static String text(JsonNode node, int max, boolean nullable) {
        if (node == null || node.isNull()) {
            if (nullable) return null;
            throw invalid();
        }
        if (!node.isTextual() || node.asString().isBlank() || node.asString().length() > max) throw invalid();
        return node.asString();
    }

    public static String category(String value) {
        if (value != null && !CATEGORIES.contains(value)) throw invalid();
        return value;
    }

    public static String targetType(String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) throw invalid();
        return value;
    }

    public static List<EligibilityEngine.Rule> rules(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 100) throw invalid();
        List<EligibilityEngine.Rule> rules = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid();
            String field = text(item.get("field"), 64, false);
            String operator = text(item.get("operator"), 16, false);
            int sortOrder = sortOrder(item.get("sortOrder"));
            String severity = text(item.get("severity"), 16, false);
            rules.add(EligibilityEngine.validateRule(field, operator, item.get("value"), severity, sortOrder));
        }
        rules.sort(java.util.Comparator.comparingInt(EligibilityEngine.Rule::sortOrder));
        return List.copyOf(rules);
    }

    public static List<OpportunityViews.MaterialRequirement> materialRequirements(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 100) throw invalid();
        List<OpportunityViews.MaterialRequirement> items = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid();
            String name = text(item.get("name"), 128, false);
            String description = item.get("description") == null || item.get("description").isNull() ? null
                    : text(item.get("description"), 512, false);
            if (item.has("required") && !item.get("required").isBoolean()) throw invalid();
            boolean required = item.get("required") == null || item.get("required").asBoolean();
            int sortOrder = sortOrder(item.get("sortOrder"));
            items.add(new OpportunityViews.MaterialRequirement(name, description, required, sortOrder, validationRules(item.get("validationRules"))));
        }
        items.sort(java.util.Comparator.comparingInt(OpportunityViews.MaterialRequirement::sortOrder));
        return List.copyOf(items);
    }

    /** Small bounded schema, shared by writes and immutable precheck snapshots. */
    public static JsonNode validationRules(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw invalid();
        for (var entry : node.properties()) {
            switch (entry.getKey()) {
                case "mimeTypes" -> {
                    var value = entry.getValue();
                    if (!value.isArray() || value.isEmpty() || value.size() > 4) throw invalid();
                    var distinct = new java.util.HashSet<String>();
                    for (var mime : value) {
                        if (!mime.isTextual() || !com.caizhitong.material.precheck.MaterialPrecheckRules.ALLOWED_MIME.contains(mime.asString())
                                || !distinct.add(mime.asString())) throw invalid();
                    }
                }
                case "maxSizeBytes" -> {
                    var value = entry.getValue();
                    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0
                            || value.asLong() > com.caizhitong.material.precheck.MaterialPrecheckRules.MAX_SIZE_BYTES) throw invalid();
                }
                case "fileNameContains" -> text(entry.getValue(), 128, false);
                default -> throw invalid();
            }
        }
        return node.deepCopy();
    }

    public static int sortOrder(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0) throw invalid();
        return node.asInt();
    }

    public static BusinessException stateConflict(String message) { return new BusinessException(409, "STATE_CONFLICT", message); }
}
