package com.caizhitong.opportunity;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EligibilityEngineTest {
    private final JsonMapper mapper = new JsonMapper();

    private EligibilityEngine.Rule rule(String field, String operator, String valueJson, String severity) {
        return EligibilityEngine.validateRule(field, operator, mapper.readTree(valueJson), severity, 0);
    }

    private EligibilityEngine.Profile profile(String college, String grade, List<String> skills, List<String> interests) {
        return new EligibilityEngine.Profile(college, grade, skills, interests, 1);
    }

    @Test
    void hardConflictIsNotEligibleSoftOnlyKeepsPossible() {
        var rules = List.of(rule("college", "eq", "\"信息学院\"", "hard"));
        assertEquals("not_eligible", EligibilityEngine.evaluate(rules, profile("外语学院", "2024", List.of(), List.of())).conclusion());
        assertEquals("eligible", EligibilityEngine.evaluate(rules, profile("信息学院", "2024", List.of(), List.of())).conclusion());
        var soft = List.of(rule("skills", "contains", "[\"Java\"]", "soft"));
        assertEquals("possible", EligibilityEngine.evaluate(soft, profile("信息学院", "2024", List.of("Python"), List.of())).conclusion());
        assertEquals("eligible", EligibilityEngine.evaluate(soft, profile("信息学院", "2024", List.of("Java"), List.of())).conclusion());
    }

    @Test
    void unknownFieldOrMissingValueIsPossibleNeverEligible() {
        var unknown = List.of(rule("不存在的字段", "eq", "\"x\"", "hard"));
        assertEquals("possible", EligibilityEngine.evaluate(unknown, profile("信息学院", "2024", List.of(), List.of())).conclusion());
        var missing = List.of(rule("college", "eq", "\"信息学院\"", "hard"));
        assertEquals("possible", EligibilityEngine.evaluate(missing, profile(null, "2024", List.of(), List.of())).conclusion());
        assertEquals("possible", EligibilityEngine.evaluate(List.of(), profile("信息学院", "2024", List.of(), List.of())).conclusion());
    }

    @Test
    void rangeOnlyOnParseableGradeAndUnknownOnAmbiguity() {
        var rules = List.of(rule("grade", "range", "{\"min\":\"2024\",\"max\":\"2026\"}", "hard"));
        assertEquals("eligible", EligibilityEngine.evaluate(rules, profile(null, "2025", List.of(), List.of())).conclusion());
        assertEquals("not_eligible", EligibilityEngine.evaluate(rules, profile(null, "2027", List.of(), List.of())).conclusion());
        assertEquals("possible", EligibilityEngine.evaluate(rules, profile(null, "大一", List.of(), List.of())).conclusion());
    }

    @Test
    void arrayOperatorsMatchSemantics() {
        var needs = List.of(rule("skills", "in", "[\"Java\",\"SQL\"]", "hard"));
        assertEquals("eligible", EligibilityEngine.evaluate(needs, profile(null, null, List.of("Java", "SQL", "C"), List.of())).conclusion());
        assertEquals("not_eligible", EligibilityEngine.evaluate(needs, profile(null, null, List.of("Java"), List.of())).conclusion());
        var contains = List.of(rule("interests", "contains", "\"志愿\"", "hard"));
        assertEquals("eligible", EligibilityEngine.evaluate(contains, profile(null, null, List.of(), List.of("志愿", "音乐"))).conclusion());
    }

    @Test
    void invalidRulesAreRejectedAtValidation() {
        assertThrows(RuntimeException.class, () -> rule("college", "eval", "\"x\"", "hard"));
        assertThrows(RuntimeException.class, () -> rule("college", "eq", "123", "hard"));
        assertThrows(RuntimeException.class, () -> rule("skills", "eq", "\"x\"", "hard"));
        assertThrows(RuntimeException.class, () -> rule("college", "eq", "\"x\"", "critical"));
        assertThrows(RuntimeException.class, () -> rule("grade", "range", "{\"min\":\"2024\"}", "hard"));
    }

    @Test
    void unknownNeverFabricatesActualValue() {
        var verdict = EligibilityEngine.evaluate(List.of(rule("college", "eq", "\"信息学院\"", "hard")),
                profile(null, "2024", List.of(), List.of()));
        assertEquals("unknown", verdict.reasons().getFirst().result());
        assertEquals(null, verdict.reasons().getFirst().actual());
    }
}
