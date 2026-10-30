package com.orqix.compiler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.Literal;

import tools.jackson.databind.JsonNode;

/**
 * The single implementation of the v0 comparison operators, used by the evaluator and by the runtime route bean so
 * that draft tests and executions cannot disagree. Numbers compare with BigDecimal.compareTo (5000 equals 5000.00).
 * <p>
 * Numbers may be passed as {@link java.math.BigDecimal} or any other {@link Number} (converted exactly through its
 * decimal string form); strings compare with {@link String#equals}, booleans with {@link Boolean#equals}.
 * <p>
 * The decision-only set operators (docs/build/S121) take a {@link Collection} of literals for {@code in} (true when the
 * value equals one of them) and a {@link Range} for {@code between}.
 */
public final class ConditionMatcher {

    /** Bounds of {@code between}; an excluded bound is open. */
    public record Range(BigDecimal from, boolean includeFrom, BigDecimal to, boolean includeTo) {
    }

    private ConditionMatcher() {
    }

    /**
     * @param operator eq, ne, gte, gt, lt, lte, in or between
     * @param actual   BigDecimal, String or Boolean value of the field
     * @param literal  BigDecimal, String or Boolean literal from the definition; a collection of them for {@code in};
     *                 a {@link Range} for {@code between}
     * @throws IllegalArgumentException for unknown operators or mismatched types
     */
    public static boolean matches(String operator, Object actual, Object literal) {
        Operator op = Operator.fromJson(operator);
        if (op == null) {
            throw new IllegalArgumentException("Unknown operator: " + operator);
        }
        if (op == Operator.IN) {
            if (!(literal instanceof Collection<?> values) || values.isEmpty()) {
                throw new IllegalArgumentException("Operator in needs a list of values");
            }
            for (Object value : values) {
                if (matches("eq", actual, value)) {
                    return true;
                }
            }
            return false;
        }
        if (op == Operator.BETWEEN) {
            if (!(literal instanceof Range range)) {
                throw new IllegalArgumentException("Operator between needs a range");
            }
            if (!(Literal.ofJava(actual) instanceof Literal.Num n)) {
                throw new IllegalArgumentException("Operator between compares numbers only");
            }
            int lo = n.number().compareTo(range.from());
            int hi = n.number().compareTo(range.to());
            return (range.includeFrom() ? lo >= 0 : lo > 0) && (range.includeTo() ? hi <= 0 : hi < 0);
        }
        Literal a = Literal.ofJava(actual);
        Literal l = Literal.ofJava(literal);
        if (a instanceof Literal.Num an && l instanceof Literal.Num ln) {
            int c = an.number().compareTo(ln.number());
            return switch (op) {
                case EQ -> c == 0;
                case NE -> c != 0;
                case GTE -> c >= 0;
                case GT -> c > 0;
                case LT -> c < 0;
                case LTE -> c <= 0;
                case IN, BETWEEN -> throw new IllegalStateException("handled above");
            };
        }
        if (!a.typeName().equals(l.typeName())) {
            throw new IllegalArgumentException("Cannot compare " + a.typeName() + " with " + l.typeName());
        }
        if (op != Operator.EQ && op != Operator.NE) {
            throw new IllegalArgumentException("Operator " + operator + " compares numbers only, not "
                    + a.typeName());
        }
        return op == Operator.EQ ? a.value().equals(l.value()) : !a.value().equals(l.value());
    }

    /**
     * The right-hand side of a stored condition as {@link #matches} takes it: a scalar for the comparison operators, a
     * list for {@code in} and a {@link Range} for {@code between}.
     *
     * @param value the condition's {@code value} member
     * @throws IllegalArgumentException when the value does not fit the operator
     */
    public static Object operand(String operator, JsonNode value) {
        Operator op = Operator.fromJson(operator);
        if (op == null || value == null) {
            throw new IllegalArgumentException("Unknown operator or missing value");
        }
        if (op == Operator.IN) {
            if (!value.isArray()) {
                throw new IllegalArgumentException("Operator in needs a list of values");
            }
            List<Object> values = new ArrayList<>();
            value.forEach(v -> values.add(Literal.of(v).value()));
            return List.copyOf(values);
        }
        if (op == Operator.BETWEEN) {
            if (!value.isObject() || !value.path("from").isNumber() || !value.path("to").isNumber()) {
                throw new IllegalArgumentException("Operator between needs from and to numbers");
            }
            return new Range(value.path("from").decimalValue(), value.path("includeFrom").asBoolean(true),
                    value.path("to").decimalValue(), value.path("includeTo").asBoolean(true));
        }
        return Literal.of(value).value();
    }
}
