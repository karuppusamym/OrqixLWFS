package com.orqix.compiler.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.orqix.compiler.BusinessCalendar;
import com.orqix.compiler.TestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-X01 (docs/build/S137): the Java engine on the shared case file the TypeScript engine also runs
 * ({@code frontend/src/lib/expr/expr.test.ts}). Results, problem codes and offsets must match exactly.
 */
class ExpressionCasesTest {

    static final JsonNode CASES = TestSupport.json("examples/fixtures/expression-cases.json");

    static String source(JsonNode c) {
        if (c.has("expr")) return c.get("expr").asString();
        JsonNode r = c.get("repeat");
        return r.get("text").asString().repeat(r.get("times").asInt()) + r.get("then").asString();
    }

    static Scope scope() {
        JsonNode t = CASES.get("types");
        Map<String, ExprType> input = types(t.get("input"));
        Map<String, Map<String, ExprType>> steps = new LinkedHashMap<>();
        t.get("steps").properties().forEach(e -> steps.put(e.getKey(), types(e.getValue())));
        return Scope.of(input, steps, types(t.get("locals")));
    }

    private static Map<String, ExprType> types(JsonNode node) {
        Map<String, ExprType> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), ExprType.read(e.getValue())));
        return out;
    }

    static Values values(JsonNode c) {
        ObjectNode v = (ObjectNode) CASES.get("values").deepCopy();
        JsonNode over = c.path("values");
        if (over.isObject()) {
            over.properties().forEach(e -> {
                if (e.getKey().equals("input")) {
                    ((ObjectNode) v.get("input")).setAll((ObjectNode) e.getValue());
                } else {
                    v.set(e.getKey(), e.getValue());
                }
            });
        }
        BusinessCalendar calendar = Values.calendar(v.get("calendar"));
        Map<String, Object> locals = new LinkedHashMap<>();
        v.get("locals").properties().forEach(e -> locals.put(e.getKey(), e.getValue()));
        return Values.of(v.get("input"), id -> v.get("steps").get(id), locals, Instant.parse(v.get("now").asString()), calendar);
    }

    /** Numbers and dates as canonical text, as the case file writes them. */
    static JsonNode canonical(Object value) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (value instanceof java.math.BigDecimal n) return f.stringNode(Expression.plain(n));
        if (value instanceof java.util.List<?> list) {
            var out = f.arrayNode();
            list.forEach(i -> out.add(canonical(i)));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            var out = f.objectNode();
            map.forEach((k, i) -> out.set(String.valueOf(k), canonical(i)));
            return out;
        }
        return Expression.toJson(value);
    }

    @TestFactory
    Stream<DynamicTest> everySharedCaseGivesTheSameResult() {
        Scope scope = scope();
        return CASES.get("cases").valueStream().map(c -> {
            String source = source(c);
            String name = "case: " + (source.length() > 70 ? source.substring(0, 70) + "..." : source);
            return DynamicTest.dynamicTest(name, () -> {
                Expression e;
                try {
                    e = Expression.compile(source, scope);
                } catch (ExpressionException problem) {
                    if (!c.has("error")) fail("compile failed: " + problem.code() + " at " + problem.position() + ": " + problem.getMessage());
                    assertThat(problem.code()).as(problem.getMessage()).isEqualTo(c.get("error").asString());
                    assertThat(problem.position()).as(problem.getMessage()).isEqualTo(c.get("at").asInt());
                    assertThat(problem.getMessage()).isNotBlank();
                    return;
                }
                if (c.has("error")) fail("expected " + c.get("error") + " but compiled to " + e.type().describe());
                Object result;
                try {
                    result = e.evaluate(values(c));
                } catch (ExpressionException problem) {
                    if (!c.has("evalError")) fail("evaluation failed: " + problem.code() + ": " + problem.getMessage());
                    assertThat(problem.code()).isEqualTo(c.get("evalError").asString());
                    assertThat(problem.evaluation()).isTrue();
                    if (c.has("at")) assertThat(problem.position()).isEqualTo(c.get("at").asInt());
                    return;
                }
                if (c.has("evalError")) fail("expected " + c.get("evalError") + " but got " + result);
                assertThat(e.type().tag()).isEqualTo(c.get("type").asString());
                assertThat(canonical(result)).isEqualTo(c.get("value"));
            });
        });
    }
}
