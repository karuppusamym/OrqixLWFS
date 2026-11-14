package com.orqix.compiler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.orqix.compiler.expr.Calculation;
import com.orqix.compiler.expr.ExprType;
import com.orqix.compiler.expr.Expression;
import com.orqix.compiler.expr.ExpressionException;
import com.orqix.compiler.expr.PinnedTypes;
import com.orqix.compiler.expr.Scope;
import com.orqix.compiler.expr.Values;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Orqix expression language for the application (CG-X01, docs/build/S137): the engine's Calculate steps,
 * expression conditions and = mappings with the types the compiler pinned in the BPMN, and the guided editor's
 * server-side preview. All values are JSON; the engine itself is {@code com.orqix.compiler.expr}.
 */
public final class Expressions {

    private Expressions() {
    }

    /** An expression that cannot give a result: its EXPR_* code and business message. */
    public static final class Failure extends Exception {
        private final String code;
        private final String output;

        public Failure(String code, String output, String message) {
            super(message);
            this.code = code;
            this.output = output;
        }

        public String code() {
            return code;
        }

        /** @return the Calculate output it is about, or null */
        public String output() {
            return output;
        }
    }

    /**
     * Runs a Calculate step's {@code config} with its pinned {@code types} over the request and step results.
     *
     * @return the outputs in declared order
     */
    public static ObjectNode calculate(JsonNode config, JsonNode types, JsonNode input, Function<String, JsonNode> steps,
            Instant now) throws Failure {
        try {
            return Calculation.run(config, types, input, steps, now);
        } catch (Calculation.Failure f) {
            throw new Failure(f.code(), f.output(), f.getMessage());
        }
    }

    /** Evaluates one expression (a condition or an = mapping) with its pinned types; JSON null for no value. */
    public static JsonNode evaluate(String source, JsonNode types, JsonNode input, Function<String, JsonNode> steps,
            Instant now) throws Failure {
        try {
            Object value = Expression.compile(source, PinnedTypes.scope(types, Map.of()))
                    .evaluate(Values.of(input, steps, Map.of(), now, null));
            return Expression.toJson(value);
        } catch (ExpressionException e) {
            throw new Failure(e.code(), null, e.getMessage());
        }
    }

    /**
     * The preview's answer: whether the expression is valid, its type and the fields it uses, the value when sample
     * values were given, and the problem (with its offset) when there is one.
     */
    public record Preview(boolean valid, String type, String typeDescription, List<String> fieldRefs, JsonNode value,
            String problemCode, String problemMessage, Integer problemPosition) {
    }

    /**
     * @param types    {@code {"input": {k: T}, "steps": {id: {k: T}}, "locals": {k: T}}} in the type notation
     * @param values   {@code {"input": ..., "steps": ..., "locals": ...}}, or null to check only
     * @param calendar working calendar (zone, weekdays, holidays) or null
     * @throws IllegalArgumentException for malformed types or calendar
     */
    public static Preview preview(String source, JsonNode types, JsonNode values, JsonNode calendar, Instant now) {
        Map<String, ExprType> locals = new LinkedHashMap<>();
        if (types != null) types.path("locals").properties().forEach(e -> locals.put(e.getKey(), ExprType.read(e.getValue())));
        Scope scope = PinnedTypes.scope(types == null ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode() : types, locals);
        Expression e;
        try {
            e = Expression.compile(source, scope);
        } catch (ExpressionException problem) {
            return new Preview(false, null, null, List.of(), null, problem.code(), problem.getMessage(), problem.position());
        }
        List<String> refs = e.fieldRefs().stream().sorted().toList();
        if (values == null || values.isNull() || values.isMissingNode()) {
            return new Preview(true, e.type().tag(), e.type().describe(), refs, null, null, null, null);
        }
        Map<String, Object> localValues = new LinkedHashMap<>();
        values.path("locals").properties().forEach(entry -> localValues.put(entry.getKey(), entry.getValue()));
        var cal = Values.calendar(calendar);
        try {
            Object value = e.evaluate(Values.of(values.path("input"), id -> values.path("steps").get(id), localValues, now, cal));
            return new Preview(true, e.type().tag(), e.type().describe(), refs, Expression.toJson(value), null, null, null);
        } catch (ExpressionException problem) {
            return new Preview(true, e.type().tag(), e.type().describe(), refs, null, problem.code(), problem.getMessage(),
                    problem.position());
        }
    }
}
