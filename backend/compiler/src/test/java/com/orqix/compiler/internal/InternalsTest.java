package com.orqix.compiler.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.orqix.compiler.internal.bpmn.RetryCycle;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.JsonProblem;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.rules.Disjointness;
import com.orqix.compiler.internal.rules.FieldTypes.FieldType;

/** Unit tests for the disjointness algebra and small helpers. */
class InternalsTest {

    private static Disjointness.Typed num(String op, String value, FieldType type) {
        return new Disjointness.Typed(new Condition("/input/x", Operator.fromJson(op),
                new Literal.Num(new BigDecimal(value))), type);
    }

    private static List<Disjointness.Typed> conditions(FieldType type, String... spec) {
        List<Disjointness.Typed> list = new ArrayList<>();
        for (String s : spec) {
            String[] parts = s.split(" ");
            list.add(num(parts[0], parts[1], type));
        }
        return list;
    }

    private static final FieldType REAL = new FieldType(FieldKind.NUMBER, null, false, null, null);
    private static final FieldType INT = new FieldType(FieldKind.INTEGER, null, false, null, null);

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource({
            "gte 5000, gte 10000, true",
            "gte 5000, lt 5000, false",
            "lt 5000, lt 1, true",
            "eq 5000, gte 5000.00, true",
            "eq 4999.99, gte 5000, false",
            "eq 5, eq 5.0, true",
            "eq 5, eq 6, false",
            "lt 5, eq 5, false",
            "gte 5, eq 5, true"
    })
    void numberOverlap(String a, String b, boolean expected) {
        List<Disjointness.Typed> c = conditions(REAL, a, b);
        assertThat(Disjointness.overlap(c.get(0), c.get(1))).isEqualTo(expected);
        assertThat(Disjointness.overlap(c.get(1), c.get(0))).isEqualTo(expected);
    }

    @Test
    void realCoverage() {
        assertThat(Disjointness.covers(conditions(REAL, "gte 5000", "lt 5000"), REAL)).isTrue();
        assertThat(Disjointness.covers(conditions(REAL, "lt 5000", "gte 5000"), REAL)).isTrue();
        assertThat(Disjointness.covers(conditions(REAL, "lt 1", "lt 5", "gte 5"), REAL)).isTrue();
        assertThat(Disjointness.covers(conditions(REAL, "lt 5", "eq 5", "gte 6"), REAL)).isFalse();
        assertThat(Disjointness.covers(conditions(REAL, "lt 5", "eq 5"), REAL)).isFalse();
        assertThat(Disjointness.covers(conditions(REAL, "gte 5"), REAL)).isFalse();
        assertThat(Disjointness.covers(conditions(REAL, "lt 5", "gte 6"), REAL)).isFalse();
        assertThat(Disjointness.covers(conditions(REAL, "lt 5", "eq 5", "gte 5"), REAL)).isTrue();
        assertThat(Disjointness.covers(List.of(), REAL)).isFalse();
        FieldType bounded = new FieldType(FieldKind.NUMBER, null, false, new BigDecimal("0.01"),
                new BigDecimal("100"));
        assertThat(Disjointness.covers(conditions(bounded, "gte 0.01"), bounded)).isTrue();
        assertThat(Disjointness.covers(conditions(bounded, "gte 1"), bounded)).isFalse();
        assertThat(Disjointness.covers(conditions(bounded, "lt 50", "gte 50"), bounded)).isTrue();
        assertThat(Disjointness.covers(conditions(bounded, "lt 100"), bounded)).isFalse();
        assertThat(Disjointness.covers(conditions(bounded, "lt 100", "eq 100"), bounded)).isTrue();
        assertThat(Disjointness.covers(conditions(bounded, "lt 0", "gte 200"), bounded)).isFalse();
        FieldType upper = new FieldType(FieldKind.NUMBER, null, false, null, new BigDecimal("10"));
        assertThat(Disjointness.covers(conditions(upper, "lt 20"), upper)).isTrue();
        assertThat(Disjointness.covers(conditions(upper, "gte 0"), upper)).isFalse();
        FieldType empty = new FieldType(FieldKind.NUMBER, null, false, BigDecimal.TEN, BigDecimal.ONE);
        assertThat(Disjointness.covers(conditions(empty, "eq 1"), empty)).isTrue();
    }

    @Test
    void integerCoverage() {
        assertThat(Disjointness.covers(conditions(INT, "lt 5", "eq 5", "gte 6"), INT)).isTrue();
        assertThat(Disjointness.covers(conditions(INT, "lt 5", "gte 6"), INT)).isFalse();
        assertThat(Disjointness.covers(conditions(INT, "lt 5", "lt 3", "gte 5"), INT)).isTrue();
        assertThat(Disjointness.covers(conditions(INT, "gte 0"), INT)).isFalse();
        FieldType small = new FieldType(FieldKind.INTEGER, null, false, BigDecimal.ONE, new BigDecimal("3"));
        assertThat(Disjointness.covers(conditions(small, "eq 1", "eq 2", "eq 3"), small)).isTrue();
        assertThat(Disjointness.covers(conditions(small, "eq 1", "eq 3"), small)).isFalse();
        assertThat(Disjointness.covers(conditions(small, "eq 2", "eq 3"), small)).isFalse();
        assertThat(Disjointness.covers(conditions(small, "eq 1", "eq 2"), small)).isFalse();
        assertThat(Disjointness.covers(conditions(small, "eq 7"), small)).isFalse();
        assertThat(Disjointness.covers(conditions(small, "lt 9"), small)).isTrue();
        FieldType fractionalBounds = new FieldType(FieldKind.INTEGER, null, false, new BigDecimal("1.5"),
                new BigDecimal("1.7"));
        assertThat(Disjointness.covers(conditions(fractionalBounds, "eq 9"), fractionalBounds)).isTrue();
    }

    @Test
    void stringAndBooleanCoverage() {
        FieldType domain = new FieldType(FieldKind.STRING, Set.of("a", "b"), false, null, null);
        Disjointness.Typed a = new Disjointness.Typed(new Condition("/x", Operator.EQ, new Literal.Str("a")), domain);
        Disjointness.Typed b = new Disjointness.Typed(new Condition("/x", Operator.EQ, new Literal.Str("b")), domain);
        assertThat(Disjointness.overlap(a, b)).isFalse();
        assertThat(Disjointness.overlap(a, a)).isTrue();
        assertThat(Disjointness.covers(List.of(a, b), domain)).isTrue();
        assertThat(Disjointness.covers(List.of(a), domain)).isFalse();
        FieldType open = new FieldType(FieldKind.STRING, null, false, null, null);
        assertThat(Disjointness.covers(List.of(a, b), open)).isFalse();
        FieldType bool = new FieldType(FieldKind.BOOLEAN, null, false, null, null);
        Disjointness.Typed t = new Disjointness.Typed(new Condition("/x", Operator.EQ, new Literal.Bool(true)), bool);
        Disjointness.Typed f = new Disjointness.Typed(new Condition("/x", Operator.EQ, new Literal.Bool(false)), bool);
        assertThat(Disjointness.covers(List.of(t, f), bool)).isTrue();
        assertThat(Disjointness.covers(List.of(t), bool)).isFalse();
    }

    @Test
    void pointers() {
        assertThat(JsonPointers.escape("a/b~c")).isEqualTo("a~1b~0c");
        assertThat(JsonPointers.of("x", 1, "a/b")).isEqualTo("/x/1/a~1b");
        assertThat(JsonPointers.parse("/a~1b/~0c/")).containsExactly("a/b", "~c", "");
        assertThat(JsonPointers.parse("")).isEmpty();
        assertThatThrownBy(() -> JsonPointers.parse("x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonPointers.parse("/a~2")).isInstanceOf(IllegalArgumentException.class);
        List<String> paths = new ArrayList<>(List.of("/nodes/10", "/nodes/2", "", "/nodes", "/nodes/2/id",
                "/a", "/nodes/x", "/nodes/02", "bad"));
        paths.sort(JsonPointers::compare);
        assertThat(paths).containsExactly("", "/a", "/nodes", "/nodes/2", "/nodes/2/id", "/nodes/10", "/nodes/02",
                "/nodes/x", "bad");
    }

    @Test
    void strictJson() {
        assertThat(StrictJson.parse("{\"a\":5000.00}").get("a").decimalValue().toPlainString()).isEqualTo("5000.00");
        assertThat(StrictJson.parse("123456789012").isLong()).isTrue();
        assertThat(StrictJson.parse("123456789012345678901234567890").isBigInteger()).isTrue();
        assertThatThrownBy(() -> StrictJson.parse("[" + "1".repeat(400) + "]"))
                .isInstanceOfSatisfying(JsonProblem.class, p -> assertThat(p.kind())
                        .isEqualTo(JsonProblem.Kind.NUMBER_OUT_OF_RANGE));
        assertThatThrownBy(() -> StrictJson.parse("[[1]]", 1))
                .isInstanceOfSatisfying(JsonProblem.class, p -> {
                    assertThat(p.kind()).isEqualTo(JsonProblem.Kind.TOO_DEEP);
                    assertThat(p.pointer()).isEqualTo("/0");
                    assertThat(p.line()).isEqualTo(1);
                    assertThat(p.column()).isPositive();
                });
        assertThatThrownBy(() -> StrictJson.parse("{\"a\":[1,}"))
                .isInstanceOfSatisfying(JsonProblem.class, p -> assertThat(p.kind())
                        .isEqualTo(JsonProblem.Kind.SYNTAX));
    }

    @Test
    void literals() {
        assertThat(Literal.ofJava(5).text()).isEqualTo("5");
        assertThat(Literal.ofJava(1.25d).text()).isEqualTo("1.25");
        assertThat(((Literal.Num) Literal.ofJava(new BigDecimal("1E+2"))).integral()).isTrue();
        assertThat(((Literal.Num) Literal.ofJava(new BigDecimal("0.000"))).integral()).isTrue();
        assertThat(((Literal.Num) Literal.ofJava(new BigDecimal("1.50"))).integral()).isFalse();
        assertThat(Literal.ofJava(true).text()).isEqualTo("true");
        assertThatThrownBy(() -> Literal.ofJava(Float.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Literal.ofJava(new java.util.concurrent.atomic.AtomicInteger(1) {
            @Override
            public String toString() {
                return "one";
            }
        })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Literal.of(StrictJson.parse("null"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryCycle() {
        assertThat(RetryCycle.forAttempts(3, 30)).isEqualTo("R3/PT30S");
        assertThat(RetryCycle.forAttempts(1, 3600)).isEqualTo("R1/PT3600S");
        assertThatThrownBy(() -> RetryCycle.forAttempts(0, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryCycle.forAttempts(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
