package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.JsonNode;

/**
 * Canonical decision evaluation (T-COMP-04 canonical half) and the shared comparison semantics (T-COMP-05 canonical
 * half): decisionBoundaries of purchase-scenarios.json, 5000 vs 5000.00, type mismatches.
 */
class DecisionEvaluatorTest {

    private static final DecisionEvaluator EVALUATOR = Compilers.decisionEvaluator();

    @TestFactory
    Stream<DynamicTest> decisionBoundaries() {
        JsonNode fixtures = TestSupport.json("examples/fixtures/purchase-scenarios.json").get("decisionBoundaries");
        String definition = TestSupport.purchaseJson();
        return fixtures.valueStream().map(f -> DynamicTest.dynamicTest(f.get("amount").decimalValue() + " -> "
                + f.get("route").stringValue(), () -> {
                    DecisionEvaluator.Result result = EVALUATOR.evaluate(definition, "amount-policy",
                            Map.of("/input/amount", f.get("amount").decimalValue()));
                    assertThat(result.output()).containsExactly(Map.entry("route", f.get("route").stringValue()));
                    boolean finance = f.get("route").stringValue().equals("financeReview");
                    assertThat(result.usedDefault()).isEqualTo(!finance);
                    assertThat(result.matchedRow()).isEqualTo(finance ? 0 : null);
                }));
    }

    @Test
    void scaleDoesNotMatter() {
        String definition = TestSupport.purchaseJson();
        for (String amount : new String[] {"5000", "5000.00", "5E+3", "5000.000000"}) {
            assertThat(EVALUATOR.evaluate(definition, "amount-policy", Map.of("/input/amount", new BigDecimal(amount)))
                    .output().get("route")).as(amount).isEqualTo("financeReview");
        }
        assertThat(EVALUATOR.evaluate(definition, "amount-policy", Map.of("/input/amount", 4999)).output())
                .containsEntry("route", "standard");
        assertThat(EVALUATOR.evaluate(definition, "amount-policy", Map.of("/input/amount", 5000L)).output())
                .containsEntry("route", "financeReview");
        assertThat(EVALUATOR.evaluate(definition, "amount-policy", Map.of("/input/amount", 4999.99d)).output())
                .containsEntry("route", "standard");
    }

    @Test
    void outputsKeepTheirTypes() throws Exception {
        String definition = GeneratedXmlTest.everythingJson();
        DecisionEvaluator.Result high = EVALUATOR.evaluate(definition, "risk-policy",
                Map.of("/input/priority", "high"));
        assertThat(high.matchedRow()).isZero();
        assertThat(high.output()).containsExactly(Map.entry("escalate", true),
                Map.entry("lane", "fast"), Map.entry("level", new BigDecimal("2.5")));
        DecisionEvaluator.Result other = EVALUATOR.evaluate(definition, "risk-policy",
                Map.of("/input/priority", "medium"));
        assertThat(other.usedDefault()).isTrue();
        assertThat(other.output().get("level")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsUnknownMissingAndMistypedValues() {
        String definition = TestSupport.purchaseJson();
        assertThatThrownBy(() -> EVALUATOR.evaluate(definition, "nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> EVALUATOR.evaluate(definition, "amount-policy", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/input/amount");
        assertThatThrownBy(() -> EVALUATOR.evaluate(definition, "amount-policy", null))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("/input/amount", null);
        assertThatThrownBy(() -> EVALUATOR.evaluate(definition, "amount-policy", nullValue))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EVALUATOR.evaluate(definition, "amount-policy", Map.of("/input/amount", "5000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EVALUATOR.evaluate("{\"decisions\":{}}", "amount-policy", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EVALUATOR.evaluate("{\"decisions\":[{\"key\":\"k\",\"version\":1,\"rows\":[{}],"
                + "\"default\":{}}]}", "k", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EVALUATOR.evaluate("not json", "k", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource({
            "eq,  N:5000,     N:5000.00, true",
            "eq,  N:5000.00,  N:5000,    true",
            "eq,  N:4999.99,  N:5000,    false",
            "gte, N:5000,     N:5000.00, true",
            "gte, N:4999.99,  N:5000,    false",
            "gte, N:5000.01,  N:5000,    true",
            "lt,  N:4999.99,  N:5000,    true",
            "lt,  N:5000.00,  N:5000,    false",
            "lt,  N:-1,       N:0,       true",
            "eq,  S:standard, S:standard, true",
            "eq,  S:Standard, S:standard, false",
            "eq,  B:true,     B:true,    true",
            "eq,  B:false,    B:true,    false"
    })
    void conditionMatcher(String operator, String actual, String literal, boolean expected) {
        assertThat(ConditionMatcher.matches(operator, value(actual), value(literal))).isEqualTo(expected);
    }

    @Test
    void conditionMatcherRejectsMismatches() {
        assertThatThrownBy(() -> ConditionMatcher.matches("eq", "5000", new BigDecimal("5000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches("eq", true, "true"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches("gte", "b", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches("lt", true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches("ne", 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches(null, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches("eq", null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches("eq", Double.NaN, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConditionMatcher.matches("eq", new Object(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ConditionMatcher.matches("eq", 5000, new BigDecimal("5000.0"))).isTrue();
        assertThat(ConditionMatcher.matches("gte", 1.5f, new BigDecimal("1.5"))).isTrue();
        assertThat(ConditionMatcher.matches("lt", new java.math.BigInteger("10"), (short) 11)).isTrue();
    }

    private static Object value(String typed) {
        String text = typed.substring(2);
        return switch (typed.charAt(0)) {
            case 'N' -> new BigDecimal(text);
            case 'B' -> Boolean.valueOf(text);
            default -> text;
        };
    }
}
