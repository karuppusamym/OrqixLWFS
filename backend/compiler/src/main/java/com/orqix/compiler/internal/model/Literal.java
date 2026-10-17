package com.orqix.compiler.internal.model;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;

/** A comparison or decision-output literal (schema {@code $defs/literal}). */
public sealed interface Literal {

    /** @return "string", "number" or "boolean" (the JSON type, used as {@code valueType} in generated XML) */
    String typeName();

    /** @return BigDecimal, String or Boolean */
    Object value();

    /** @return the text written into generated artifacts (numbers via {@link BigDecimal#toPlainString()}) */
    String text();

    /** Number literal; compared with {@link BigDecimal#compareTo}. */
    record Num(BigDecimal number) implements Literal {
        @Override
        public String typeName() {
            return "number";
        }

        @Override
        public Object value() {
            return number;
        }

        @Override
        public String text() {
            return number.toPlainString();
        }

        /** @return true when the number has no fractional part (1, 1.0 and 1E2 are integral) */
        public boolean integral() {
            return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
        }
    }

    /** String literal. */
    record Str(String string) implements Literal {
        @Override
        public String typeName() {
            return "string";
        }

        @Override
        public Object value() {
            return string;
        }

        @Override
        public String text() {
            return string;
        }
    }

    /** Boolean literal. */
    record Bool(boolean bool) implements Literal {
        @Override
        public String typeName() {
            return "boolean";
        }

        @Override
        public Object value() {
            return bool;
        }

        @Override
        public String text() {
            return Boolean.toString(bool);
        }
    }

    /**
     * @return the literal for a JSON scalar
     * @throws IllegalArgumentException for null, objects and arrays
     */
    static Literal of(JsonNode node) {
        if (node.isString()) {
            return new Str(node.stringValue());
        }
        if (node.isNumber()) {
            return new Num(node.decimalValue());
        }
        if (node.isBoolean()) {
            return new Bool(node.booleanValue());
        }
        throw new IllegalArgumentException("Not a literal: " + node.getNodeType());
    }

    /**
     * @return the literal for a Java value (BigDecimal or any other Number, String, Boolean)
     * @throws IllegalArgumentException for other types and non-finite numbers
     */
    static Literal ofJava(Object value) {
        if (value instanceof String s) {
            return new Str(s);
        }
        if (value instanceof Boolean b) {
            return new Bool(b);
        }
        if (value instanceof BigDecimal d) {
            return new Num(d);
        }
        if (value instanceof Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("Number is not finite: " + n);
                }
            }
            try {
                return new Num(new BigDecimal(n.toString()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unsupported number: " + n, e);
            }
        }
        throw new IllegalArgumentException(
                "Unsupported value type: " + (value == null ? "null" : value.getClass().getSimpleName()));
    }
}
