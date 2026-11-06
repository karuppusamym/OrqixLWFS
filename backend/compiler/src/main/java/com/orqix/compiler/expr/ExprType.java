package com.orqix.compiler.expr;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Static type of an Orqix expression (docs/build/S137): number, text, true or false, date, a list of one element type,
 * a row with named columns, the {@code null} literal, or unknown.
 */
public sealed interface ExprType {

    /** Scalar types. {@code NULL} is the type of the literal {@code null}; {@code ANY} accepts everything. */
    enum Scalar implements ExprType {
        NUMBER("a number"), STRING("text"), BOOLEAN("true or false"), DATE("a date"), NULL("nothing"), ANY("a value");

        private final String phrase;

        Scalar(String phrase) {
            this.phrase = phrase;
        }

        @Override
        public String describe() {
            return phrase;
        }

        @Override
        public String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A list whose elements have one type. */
    record ListOf(ExprType element) implements ExprType {
        @Override
        public String describe() {
            return element == Scalar.ANY ? "a list" : "a list of " + plural(element);
        }

        @Override
        public String tag() {
            return "list";
        }
    }

    /** One row of a list of rows: named, typed columns. */
    record Row(Map<String, ExprType> fields) implements ExprType {
        public Row {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        @Override
        public String describe() {
            return "a row";
        }

        @Override
        public String tag() {
            return "row";
        }
    }

    ExprType NUMBER = Scalar.NUMBER;
    ExprType STRING = Scalar.STRING;
    ExprType BOOLEAN = Scalar.BOOLEAN;
    ExprType DATE = Scalar.DATE;
    ExprType NULL = Scalar.NULL;
    ExprType ANY = Scalar.ANY;

    /** @return business wording, e.g. "a number" or "a list of numbers" */
    String describe();

    /** @return the short tag: number, string, boolean, date, null, any, list or row */
    String tag();

    private static String plural(ExprType t) {
        return switch (t) {
            case Scalar s when s == Scalar.NUMBER -> "numbers";
            case Scalar s when s == Scalar.STRING -> "texts";
            case Scalar s when s == Scalar.BOOLEAN -> "true or false values";
            case Scalar s when s == Scalar.DATE -> "dates";
            case Row r -> "rows";
            case ListOf l -> "lists";
            default -> "values";
        };
    }

    /**
     * Reads the type notation of the shared case file and the compiler: {@code "number"}, {@code "integer"} (a
     * number), {@code "string"}, {@code "boolean"}, {@code "date"}, {@code {"list": T}}, {@code {"row": {col: T}}}.
     */
    static ExprType read(JsonNode node) {
        if (node.isString()) {
            return switch (node.asString()) {
                case "number", "integer" -> NUMBER;
                case "string" -> STRING;
                case "boolean" -> BOOLEAN;
                case "date" -> DATE;
                case "any" -> ANY;
                default -> throw new IllegalArgumentException("Unknown type " + node.asString());
            };
        }
        if (node.has("list")) {
            return new ListOf(read(node.get("list")));
        }
        if (node.has("row")) {
            Map<String, ExprType> fields = new LinkedHashMap<>();
            node.get("row").properties().forEach(e -> fields.put(e.getKey(), read(e.getValue())));
            return new Row(fields);
        }
        throw new IllegalArgumentException("Unknown type " + node);
    }

    /**
     * The expression type of a form field schema (S12/S35 subset): number and integer are numbers, a string with
     * {@code format: date} is a date, other strings are text, arrays are lists of their item type (rows for objects).
     *
     * @return the type, or null for a schema outside the subset
     */
    static ExprType ofSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return null;
        }
        String type = schema.path("type").asString("");
        return switch (type) {
            case "number", "integer" -> NUMBER;
            case "boolean" -> BOOLEAN;
            case "string" -> "date".equals(schema.path("format").asString("")) ? DATE : STRING;
            case "array" -> {
                ExprType item = ofSchema(schema.path("items"));
                yield item == null ? null : new ListOf(item);
            }
            case "object" -> {
                Map<String, ExprType> fields = new LinkedHashMap<>();
                for (var e : schema.path("properties").properties()) {
                    ExprType f = ofSchema(e.getValue());
                    if (f != null) {
                        fields.put(e.getKey(), f);
                    }
                }
                yield new Row(fields);
            }
            default -> null;
        };
    }
}
