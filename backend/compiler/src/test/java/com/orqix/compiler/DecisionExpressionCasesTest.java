package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;

/**
 * The shared decision expression cases (S146 part A): {@code examples/fixtures/decision-expression-cases.json}, which
 * the TypeScript {@code frontend/src/lib/expr/decision.test.ts} also runs.
 */
class DecisionExpressionCasesTest {

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode c : TestSupport.json("examples/fixtures/decision-expression-cases.json").path("cases")) {
            out.add(Arguments.of(c.path("name").asString(), c));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void evaluatesLikeTheSharedCase(String name, JsonNode c) {
        Map<String, Object> values = new LinkedHashMap<>();
        c.path("values").properties().forEach(e -> values.put(e.getKey(), plain(e.getValue())));
        String types = c.has("types") ? c.get("types").toString() : null;
        String table = c.get("table").toString();
        if (c.path("error").asBoolean(false)) {
            assertThatThrownBy(() -> DecisionTables.evaluate(table, values, types))
                    .isInstanceOf(IllegalArgumentException.class);
            return;
        }
        DecisionEvaluator.Result r = DecisionTables.evaluate(table, values, types);
        JsonNode expect = c.get("expect");
        assertThat(r.matchedRow()).isEqualTo(expect.get("matchedRow").isNull() ? null : expect.get("matchedRow").asInt());
        List<Integer> rows = new ArrayList<>();
        expect.get("matchedRows").forEach(n -> rows.add(n.asInt()));
        assertThat(r.matchedRows()).isEqualTo(rows);
        assertThat(r.usedDefault()).isEqualTo(expect.get("usedDefault").asBoolean());
        assertThat(r.output().keySet()).containsExactlyInAnyOrderElementsOf(expect.get("output").propertyNames());
        expect.get("output").properties().forEach(e -> same(r.output().get(e.getKey()), e.getValue(), e.getKey()));
        JsonNode entries = expect.get("expressions");
        assertThat(r.expressions()).hasSize(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JsonNode want = entries.get(i);
            DecisionEvaluator.Entry got = r.expressions().get(i);
            assertThat(got.row()).as("row of entry " + i).isEqualTo(want.get("row").asInt());
            assertThat(got.term()).as("term of entry " + i).isEqualTo(want.get("term").asInt());
            assertThat(got.kind()).isEqualTo(want.get("kind").asString());
            assertThat(got.target()).isEqualTo(want.get("target").asString());
            same(got.value(), want.get("value"), "entry " + i);
        }
    }

    private static void same(Object actual, JsonNode expected, String what) {
        if (expected.isNull()) {
            assertThat(actual).as(what).isNull();
        } else if (expected.isNumber()) {
            assertThat(actual).as(what).isInstanceOf(BigDecimal.class);
            assertThat(((BigDecimal) actual).compareTo(expected.decimalValue())).as(what + " = " + actual).isZero();
        } else if (expected.isBoolean()) {
            assertThat(actual).as(what).isEqualTo(expected.booleanValue());
        } else {
            assertThat(actual).as(what).isEqualTo(expected.asString());
        }
    }

    private static Object plain(JsonNode v) {
        if (v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isBoolean()) return v.booleanValue();
        return v.asString();
    }
}
