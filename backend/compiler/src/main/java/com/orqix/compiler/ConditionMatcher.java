package com.orqix.compiler;

import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.Literal;

/**
 * The single implementation of the v0 comparison operators, used by the evaluator and by the runtime route bean so
 * that draft tests and executions cannot disagree. Numbers compare with BigDecimal.compareTo (5000 equals 5000.00).
 * <p>
 * Numbers may be passed as {@link java.math.BigDecimal} or any other {@link Number} (converted exactly through its
 * decimal string form); strings compare with {@link String#equals}, booleans with {@link Boolean#equals}.
 */
public final class ConditionMatcher {

    private ConditionMatcher() {
    }

    /**
     * @param operator eq, gte or lt
     * @param actual   BigDecimal, String or Boolean value of the field
     * @param literal  BigDecimal, String or Boolean literal from the definition
     * @throws IllegalArgumentException for unknown operators or mismatched types
     */
    public static boolean matches(String operator, Object actual, Object literal) {
        Operator op = Operator.fromJson(operator);
        if (op == null) {
            throw new IllegalArgumentException("Unknown operator: " + operator);
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
}
