package com.orqix.compiler.expr;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.orqix.compiler.BusinessCalendar;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs a Calculate step (CG-X01, docs/build/S137): the outputs in order, each seeing the earlier ones by name, numbers
 * rounded to their declared scale and rounding, whole numbers checked. The engine delegate, the simulator and the
 * browser simulator ({@code frontend/src/lib/expr/calculate.ts}) all use this definition.
 */
public final class Calculation {

    private Calculation() {
    }

    /** A Calculate step that cannot finish: an output without a value, a non-whole integer or an exceeded bound. */
    public static final class Failure extends Exception {
        private final String output;
        private final String code;

        public Failure(String output, String code, String message) {
            super(message);
            this.output = output;
            this.code = code;
        }

        public String output() {
            return output;
        }

        /** EXPR_NO_VALUE, EXPR_NOT_WHOLE, an evaluation bound code, or an EXPR_* compile code (a stale release) */
        public String code() {
            return code;
        }
    }

    /**
     * @param config the step config ({@code outputs}, optional {@code calendar})
     * @param types  the pinned types ({@link PinnedTypes})
     * @param input  the request
     * @param steps  earlier step results by step ID (null when absent)
     * @return the outputs as JSON, in declared order; an optional output without a value is JSON null
     */
    public static ObjectNode run(JsonNode config, JsonNode types, JsonNode input, Function<String, JsonNode> steps,
            Instant now) throws Failure {
        BusinessCalendar calendar = Values.calendar(config.path("calendar"));
        Map<String, ExprType> localTypes = new LinkedHashMap<>();
        Map<String, Object> locals = new LinkedHashMap<>();
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (JsonNode o : config.path("outputs")) {
            String key = o.path("key").asString();
            String type = o.path("type").asString();
            Object value;
            try {
                Expression e = Expression.compile(o.path("expression").asString(), PinnedTypes.scope(types, localTypes));
                value = e.evaluate(Values.of(input, steps, locals, now, calendar));
            } catch (ExpressionException problem) {
                throw new Failure(key, problem.code(), "The output " + key + " cannot be calculated: " + problem.getMessage());
            }
            if (value instanceof BigDecimal n) {
                boolean integer = type.equals("integer");
                if (integer || o.has("scale")) {
                    int scale = integer ? 0 : o.path("scale").asInt();
                    n = n.setScale(scale, Expression.rounding(o.path("rounding").asString("halfUp")));
                }
                value = n;
            }
            if (value == null && !o.path("optional").asBoolean(false)) {
                throw new Failure(key, "EXPR_NO_VALUE", "The output " + key + " has no value for this request (a"
                        + " missing field, a division by zero or a value that is not " + type + ").");
            }
            locals.put(key, value);
            localTypes.put(key, switch (type) {
                case "number", "integer" -> ExprType.NUMBER;
                case "boolean" -> ExprType.BOOLEAN;
                case "date" -> ExprType.DATE;
                default -> ExprType.STRING;
            });
            out.set(key, value instanceof BigDecimal n ? JsonNodeFactory.instance.numberNode(n) : Expression.toJson(value));
        }
        return out;
    }
}
