package com.orqix.compiler.expr;

/**
 * A problem with an Orqix expression (docs/build/S137). Compile-time codes: {@code EXPR_SYNTAX},
 * {@code EXPR_TOO_COMPLEX}, {@code EXPR_UNKNOWN_FIELD}, {@code EXPR_UNKNOWN_FUNCTION}, {@code EXPR_ARGUMENTS},
 * {@code EXPR_TYPE}. Evaluation codes (bounded cost, never a crash): {@code EXPR_COST_LIMIT},
 * {@code EXPR_NUMBER_RANGE}, {@code EXPR_TEXT_LIMIT}, {@code EXPR_LIST_LIMIT}. {@code position} is the zero-based
 * offset of the part of the source the problem is about. The TypeScript engine uses the same codes and positions.
 */
public final class ExpressionException extends Exception {

    private final String code;
    private final int position;

    public ExpressionException(String code, int position, String message) {
        super(message);
        this.code = code;
        this.position = position;
    }

    public String code() {
        return code;
    }

    public int position() {
        return position;
    }

    /** @return true for a problem found while evaluating rather than while checking */
    public boolean evaluation() {
        return code.equals("EXPR_COST_LIMIT") || code.equals("EXPR_NUMBER_RANGE") || code.equals("EXPR_TEXT_LIMIT")
                || code.equals("EXPR_LIST_LIMIT");
    }
}
