package com.caizhitong.opportunity;

import com.caizhitong.common.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 资格规则引擎：三档结论 eligible/possible/not_eligible。
 * 只做已知字段的结构化比较，不执行表达式/SQL/脚本；未知字段、歧义或缺失值一律 unknown，
 * 不得凭缺值或任意字符串比较得出 eligible。
 */
public final class EligibilityEngine {
    public static final Set<String> KNOWN_FIELDS = Set.of("college", "grade", "skills", "interests");
    public static final Set<String> OPERATORS = Set.of("eq", "ne", "in", "contains", "range");
    public static final Set<String> SEVERITIES = Set.of("hard", "soft");

    public record Profile(String college, String grade, List<String> skills, List<String> interests, Integer version) {
        public Profile {
            skills = skills == null ? List.of() : List.copyOf(skills);
            interests = interests == null ? List.of() : List.copyOf(interests);
        }
    }

    public record Rule(String field, String operator, JsonNode value, String severity, int sortOrder) {}

    public record Reason(String field, String expected, String actual, String result, String message) {}

    public record Verdict(String conclusion, List<Reason> reasons) {
        public Verdict {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public static final String ELIGIBLE = "eligible";
    public static final String POSSIBLE = "possible";
    public static final String NOT_ELIGIBLE = "not_eligible";

    /** 创建/更新时的规则结构校验；不合规即 400，不让坏规则入库。 */
    public static Rule validateRule(String field, String operator, JsonNode value, String severity, int sortOrder) {
        if (field == null || field.isBlank() || field.length() > 64) throw invalid("规则字段不合法");
        if (operator == null || !OPERATORS.contains(operator)) throw invalid("规则运算符不合法");
        if (severity == null || !SEVERITIES.contains(severity)) throw invalid("规则强度不合法");
        if (sortOrder < 0) throw invalid("规则排序不合法");
        if (value == null || value.isNull() || value.isMissingNode()) throw invalid("规则取值不合法");
        if (value.isTextual() && value.asText().isBlank()) throw invalid("规则取值不合法");
        String kind = fieldKind(field);
        switch (operator) {
            case "eq", "ne" -> {
                if ("string".equals(kind) && !value.isTextual()) throw invalid("字符串字段规则取值不合法");
                if ("array".equals(kind) && !(value.isArray() && bounded(value))) throw invalid("标签字段规则取值不合法");
            }
            case "in" -> {
                if (!(value.isArray() && bounded(value))) throw invalid("in 规则取值不合法");
                if ("string".equals(kind) && !allTextual(value)) throw invalid("in 规则取值不合法");
                if ("array".equals(kind) && !allTextual(value)) throw invalid("in 规则取值不合法");
            }
            case "contains" -> {
                if ("string".equals(kind) && !(value.isTextual() && value.asString().length() <= 128)) throw invalid("contains 规则取值不合法");
                if ("array".equals(kind) && !(value.isArray() && bounded(value) && allTextual(value))) throw invalid("contains 规则取值不合法");
            }
            case "range" -> {
                if (!"grade".equals(field) || !value.isObject() || !value.has("min") || !value.has("max")
                        || !value.get("min").isTextual() || !value.get("max").isTextual()) throw invalid("range 规则取值不合法");
            }
            default -> throw invalid("规则运算符不合法");
        }
        if ("string".equals(kind) && value.isTextual() && value.asString().length() > 128) throw invalid("规则取值超长");
        return new Rule(field, operator, value, severity, sortOrder);
    }

    private static boolean bounded(JsonNode array) {
        if (!array.isArray() || array.size() == 0 || array.size() > 100) return false;
        for (JsonNode item : array) if (!item.isTextual() || item.asString().isBlank() || item.asString().length() > 128) return false;
        return true;
    }

    private static boolean allTextual(JsonNode array) {
        for (JsonNode item : array) if (!item.isTextual()) return false;
        return true;
    }

    private static String fieldKind(String field) {
        return switch (field) {
            case "college", "grade" -> "string";
            case "skills", "interests" -> "array";
            default -> "unknown";
        };
    }

    /** 按服务器读取的画像评估规则；结论三档，unknown 不冒充满足。 */
    public static Verdict evaluate(List<Rule> rules, Profile profile) {
        if (rules.isEmpty()) return new Verdict(POSSIBLE, List.of(
                new Reason("_overall", "", null, "unknown", "暂无规则可核验，请以主办方通知和最终审核为准")));
        List<Reason> reasons = new ArrayList<>();
        boolean hardViolated = false, unknown = false, anyViolated = false;
        for (Rule rule : rules) {
            Reason reason = evaluate(rule, profile);
            reasons.add(reason);
            boolean hard = "hard".equals(rule.severity());
            if ("violated".equals(reason.result())) { anyViolated = true; if (hard) hardViolated = true; }
            if ("unknown".equals(reason.result())) unknown = true;
        }
        String conclusion;
        if (hardViolated) conclusion = NOT_ELIGIBLE;
        else if (unknown || anyViolated) conclusion = POSSIBLE;
        else conclusion = ELIGIBLE;
        return new Verdict(conclusion, reasons);
    }

    private static Reason evaluate(Rule rule, Profile profile) {
        String expected = expectedText(rule);
        switch (fieldKind(rule.field())) {
            case "string" -> {
                String actual = switch (rule.field()) {
                    case "college" -> profile.college();
                    case "grade" -> profile.grade();
                    default -> null;
                };
                return stringRule(rule, actual, expected);
            }
            case "array" -> {
                List<String> actual = "skills".equals(rule.field()) ? profile.skills() : profile.interests();
                return arrayRule(rule, actual, expected);
            }
            default -> {
                return new Reason(rule.field(), expected, null, "unknown", "暂不支持该画像字段，需人工确认");
            }
        }
    }

    private static Reason stringRule(Rule rule, String actual, String expected) {
        if (actual == null || actual.isBlank()) return unknown(rule, expected, "画像缺少该字段");
        return switch (rule.operator()) {
            case "eq" -> compare(rule, expected, actual, rule.value().asText().equals(actual), actual);
            case "ne" -> compare(rule, expected, actual, !rule.value().asText().equals(actual), actual);
            case "in" -> {
                List<String> options = options(rule.value());
                yield compare(rule, expected, actual, options.contains(actual), actual);
            }
            case "contains" -> compare(rule, expected, actual, actual.contains(rule.value().asText()), actual);
            case "range" -> {
                if (!"grade".equals(rule.field())) yield unknown(rule, expected, "该字段不支持区间比较");
                Long min = parseLong(rule.value().get("min").asText()), max = parseLong(rule.value().get("max").asText()), act = parseLong(actual);
                if (min == null || max == null || act == null || min > max) yield unknown(rule, expected, "区间或画像值无法严格解析");
                yield compare(rule, expected, actual, act >= min && act <= max, actual);
            }
            default -> unknown(rule, expected, "未知运算符");
        };
    }

    private static Reason arrayRule(Rule rule, List<String> actual, String expected) {
        if (actual.isEmpty()) return unknown(rule, expected, "画像缺少该字段");
        return switch (rule.operator()) {
            case "eq" -> compare(rule, expected, joined(actual), Set.copyOf(actual).equals(Set.copyOf(options(rule.value()))), joined(actual));
            case "ne" -> compare(rule, expected, joined(actual), !Set.copyOf(actual).equals(Set.copyOf(options(rule.value()))), joined(actual));
            case "in" -> compare(rule, expected, joined(actual), actual.containsAll(options(rule.value())), joined(actual));
            case "contains" -> {
                List<String> need = rule.value().isArray() ? options(rule.value()) : List.of(rule.value().asText());
                yield compare(rule, expected, joined(actual), actual.containsAll(need), joined(actual));
            }
            default -> unknown(rule, expected, "该字段不支持区间比较");
        };
    }

    private static Reason compare(Rule rule, String expected, String actual, boolean satisfied, String actualText) {
        if (satisfied) return new Reason(rule.field(), expected, actualText, "satisfied", "条件满足");
        if ("hard".equals(rule.severity())) return new Reason(rule.field(), expected, actualText, "violated", "硬性条件不满足");
        return new Reason(rule.field(), expected, actualText, "violated", "软性条件未满足");
    }

    private static Reason unknown(Rule rule, String expected, String message) {
        return new Reason(rule.field(), expected, null, "unknown", message);
    }

    private static List<String> options(JsonNode value) {
        List<String> options = new ArrayList<>();
        value.forEach(item -> options.add(item.asText()));
        return options;
    }

    private static String joined(List<String> values) { return String.join(",", values); }

    private static String expectedText(Rule rule) {
        return switch (rule.operator()) {
            case "range" -> "[" + rule.value().get("min").asText() + "," + rule.value().get("max").asText() + "]";
            default -> rule.value().isArray() ? joined(options(rule.value())) : rule.value().asText();
        };
    }

    private static Long parseLong(String text) {
        try { return Long.parseLong(text.trim()); } catch (RuntimeException e) { return null; }
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, "VALIDATION_FAILED", message); }
}
