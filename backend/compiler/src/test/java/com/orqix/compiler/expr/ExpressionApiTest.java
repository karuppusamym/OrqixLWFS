package com.orqix.compiler.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/** CG-X01 (docs/build/S137): the public expression API beyond the shared cases. */
class ExpressionApiTest {

    private static final Instant NOW = Instant.parse("2026-03-14T10:00:00Z");

    private static JsonNode n(String json) {
        return StrictJson.parse(json);
    }

    @Test
    void formSchemasMapToExpressionTypes() {
        JsonNode schema = n("""
                {"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"string","format":"date"},
                 "c":{"type":"string"},"d":{"type":"boolean"},"e":{"type":"array","items":{"type":"number"}},
                 "f":{"type":"array","items":{"type":"object","properties":{"x":{"type":"number"},"y":{"type":"weird"}}}},
                 "g":{"type":"weird"},"h":{"type":"array","items":{"type":"weird"}}}}""");
        JsonNode p = schema.get("properties");
        assertThat(ExprType.ofSchema(p.get("a"))).isEqualTo(ExprType.NUMBER);
        assertThat(ExprType.ofSchema(p.get("b"))).isEqualTo(ExprType.DATE);
        assertThat(ExprType.ofSchema(p.get("c"))).isEqualTo(ExprType.STRING);
        assertThat(ExprType.ofSchema(p.get("d"))).isEqualTo(ExprType.BOOLEAN);
        assertThat(ExprType.ofSchema(p.get("e"))).isEqualTo(new ExprType.ListOf(ExprType.NUMBER));
        assertThat(ExprType.ofSchema(p.get("f")).describe()).isEqualTo("a list of rows");
        assertThat(((ExprType.Row) ((ExprType.ListOf) ExprType.ofSchema(p.get("f"))).element()).fields()).containsOnlyKeys("x");
        assertThat(ExprType.ofSchema(p.get("g"))).isNull();
        assertThat(ExprType.ofSchema(p.get("h"))).isNull();
        assertThat(ExprType.ofSchema(null)).isNull();
        assertThat(new ExprType.ListOf(ExprType.DATE).describe()).isEqualTo("a list of dates");
        assertThat(new ExprType.ListOf(ExprType.BOOLEAN).describe()).isEqualTo("a list of true or false values");
        assertThat(new ExprType.ListOf(new ExprType.ListOf(ExprType.ANY)).describe()).isEqualTo("a list of lists");
        assertThat(new ExprType.ListOf(ExprType.ANY).describe()).isEqualTo("a list");
        assertThat(new ExprType.ListOf(ExprType.NULL).describe()).isEqualTo("a list of values");
        assertThat(new ExprType.Row(Map.of()).tag()).isEqualTo("row");
        assertThatThrownBy(() -> ExprType.read(n("\"nope\""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExprType.read(n("{}"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pinnedTypesTravelAndComeBack() throws Exception {
        ExprType rows = new ExprType.ListOf(new ExprType.Row(Map.of("qty", ExprType.NUMBER, "due", ExprType.DATE)));
        Scope scope = Scope.of(Map.of("lines", rows, "start", ExprType.DATE), Map.of("s-1", Map.of("score", ExprType.NUMBER)), Map.of());
        Expression e = Expression.compile("count(filter(input.lines, item.due > input.start)) + steps.s-1.score", scope);
        JsonNode pinned = PinnedTypes.of(List.of(e), scope);
        assertThat(pinned.toString()).contains("\"lines\":{\"list\":{\"row\":{", "\"start\":\"date\"", "\"s-1\":{\"score\":\"number\"}");
        Scope back = PinnedTypes.scope(pinned, Map.of());
        assertThat(back.input("lines")).isEqualTo(rows);
        Object value = Expression.compile(e.source(), back).evaluate(Values.of(
                n("{\"start\":\"2026-01-10\",\"lines\":[{\"qty\":1,\"due\":\"2026-01-01\"},{\"qty\":2,\"due\":\"2026-02-01\"}]}"),
                id -> n("{\"score\":4}"), Map.of(), NOW, null));
        assertThat(value).isEqualTo(BigDecimal.valueOf(5));
        assertThat(PinnedTypes.write(ExprType.NULL).asString()).isEqualTo("any");
        assertThat(back.local("x")).isNull();
        assertThat(back.step("missing", "x")).isNull();
    }

    @Test
    void valuesComeBackAsCanonicalJson() throws Exception {
        Scope scope = Scope.of(Map.of(), Map.of(), Map.of());
        Object list = Expression.compile("[date(\"2026-01-02\"), date(2026, 1, 3)]", scope).evaluate(Values.of(
                (JsonNode) null, id -> null, Map.of(), NOW, null));
        assertThat(Expression.toJson(list).toString()).isEqualTo("[\"2026-01-02\",\"2026-01-03\"]");
        assertThat(Expression.toJson(Map.of("a", new BigDecimal("1.50"))).toString()).isEqualTo("{\"a\":1.5}");
        assertThat(Expression.toJson(null).isNull()).isTrue();
        assertThatThrownBy(() -> Expression.toJson(new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Expression.parseDate("2026-02-29")).isNull();
        assertThat(Expression.parseDate("2028-02-29")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(Expression.fromJson(n("{\"a\":[1,true,null]}"))).isEqualTo(Map.of("a", java.util.Arrays.asList(BigDecimal.ONE, true, null)));
        assertThat(Expression.roundingModes()).contains("halfEven");
        assertThat(Expression.compile("1", scope).toString()).isEqualTo("1");
    }

    @Test
    void untypedValuesAreNormalisedAndListsAreBounded() throws Exception {
        Scope any = Scope.of(Map.of("x", ExprType.ANY, "big", new ExprType.ListOf(ExprType.NUMBER)), Map.of(), Map.of());
        Values values = Values.of(key -> key.equals("x") ? Map.of("k", List.of(1, 2L)) : bigList(1001), (id, k) -> null,
                Map.of(), NOW, null);
        Object x = Expression.compile("input.x", any).evaluate(values);
        assertThat(x).isEqualTo(Map.of("k", List.of(BigDecimal.ONE, BigDecimal.valueOf(2))));
        assertThatThrownBy(() -> Expression.compile("count(input.big)", any).evaluate(values))
                .isInstanceOfSatisfying(ExpressionException.class, e -> assertThat(e.code()).isEqualTo("EXPR_LIST_LIMIT"));
        Values anyBig = Values.of(key -> bigList(1001), (id, k) -> null, Map.of(), NOW, null);
        assertThatThrownBy(() -> Expression.compile("input.x", any).evaluate(anyBig))
                .isInstanceOfSatisfying(ExpressionException.class, e -> assertThat(e.code()).isEqualTo("EXPR_LIST_LIMIT"));
        assertThat(Expression.compile("input.x.k", any).evaluate(values)).isEqualTo(List.of(BigDecimal.ONE, BigDecimal.valueOf(2)));
        assertThat(Expression.compile("-input.x", any).evaluate(values)).isNull();
    }

    private static List<Object> bigList(int size) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < size; i++) out.add(i);
        return out;
    }

    @Test
    void checkerRefusesMisusedFunctions() {
        Scope scope = Scope.of(Map.of("d", ExprType.DATE, "l", new ExprType.ListOf(ExprType.NUMBER),
                "rows", new ExprType.ListOf(new ExprType.Row(Map.of("a", ExprType.NUMBER)))), Map.of(), Map.of());
        for (String bad : List.of("min(1, \"a\")", "max(input.l, 1, 2)", "min(input.rows, item)", "min(true, false)",
                "coalesce(1, \"a\")", "string(input.l)", "date(1, 2)", "count(1)", "input.rows.b", "-\"a\"",
                "[1] in [1]", "1 in 2", "input.d.year", "sum(input.l, item > 1)", "count()", "average(input.rows)",
                "input.rows = 1", "item", "rows.a")) {
            assertThatThrownBy(() -> Expression.compile(bad, scope)).as(bad).isInstanceOf(ExpressionException.class);
        }
    }

    @Test
    void workingCalendarsAreRead() {
        assertThat(Values.calendar(n("{\"zone\":\"UTC\",\"weekdays\":[1],\"start\":\"08:00\",\"end\":\"09:00\"}")).weekdays())
                .containsExactly(1);
        assertThat(Values.calendar(null)).isNull();
        assertThatThrownBy(() -> Values.calendar(n("{\"zone\":\"Nowhere/City\",\"weekdays\":[1]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calculationsRoundAndReportProblems() throws Exception {
        JsonNode config = n("""
                {"outputs":[{"key":"a","expression":"input.x / 3","type":"number","scale":2,"rounding":"down"},
                 {"key":"b","expression":"a * 3","type":"integer"},{"key":"c","expression":"a > 1","type":"boolean"},
                 {"key":"d","expression":"string(b)","type":"string"}]}""");
        JsonNode types = n("{\"input\":{\"x\":\"number\"},\"steps\":{}}");
        assertThat(Calculation.run(config, types, n("{\"x\":10}"), id -> null, NOW).toString())
                .isEqualTo("{\"a\":3.33,\"b\":10,\"c\":true,\"d\":\"10\"}");
        assertThatThrownBy(() -> Calculation.run(n("{\"outputs\":[{\"key\":\"a\",\"expression\":\"input.y\",\"type\":\"number\"}]}"),
                types, n("{}"), id -> null, NOW)).isInstanceOfSatisfying(Calculation.Failure.class, f -> {
                    assertThat(f.code()).isEqualTo("EXPR_UNKNOWN_FIELD");
                    assertThat(f.output()).isEqualTo("a");
                });
    }
}
