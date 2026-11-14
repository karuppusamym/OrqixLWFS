package com.orqix.compiler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.orqix.compiler.expr.ExprType;
import com.orqix.compiler.expr.Expression;
import com.orqix.compiler.expr.ExpressionException;
import com.orqix.compiler.expr.Scope;
import com.orqix.compiler.expr.Values;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Computed form fields and cross-field rules (CG-F04, docs/build/S137), evaluated with the Orqix expression engine
 * exactly as the browser does ({@code frontend/src/lib/form/computed.ts}). Computed fields are filled in a fixed
 * order: repeatedly the alphabetically first field whose computed inputs are already known. The server recomputes
 * them on submission and replaces whatever the browser sent.
 */
public final class FormComputation {

    private FormComputation() {
    }

    /** The request fields of a form schema as expression types (files are left out). */
    public static Scope scope(JsonNode inputSchema) {
        Map<String, ExprType> input = new LinkedHashMap<>();
        for (var e : inputSchema.path("properties").properties()) {
            ExprType t = ExprType.ofSchema(e.getValue());
            if (t != null && !"attachment".equals(e.getValue().path("format").asString(""))) input.put(e.getKey(), t);
        }
        return Scope.of(input, Map.of(), Map.of());
    }

    /**
     * @return the order computed fields are filled in, or null when they use each other in a circle
     */
    public static List<String> order(Map<String, String> computed, Function<String, Set<String>> fieldRefs) {
        Map<String, Set<String>> needs = new LinkedHashMap<>();
        for (String key : new TreeSet<>(computed.keySet())) {
            Set<String> deps = new TreeSet<>();
            for (String ref : fieldRefs.apply(key)) {
                String field = ref.startsWith("/input/") ? ref.substring(7) : null;
                if (field != null && computed.containsKey(field) && !field.equals(key)) deps.add(field);
            }
            needs.put(key, deps);
        }
        List<String> order = new ArrayList<>();
        while (order.size() < needs.size()) {
            String next = null;
            for (var e : needs.entrySet()) {
                if (!order.contains(e.getKey()) && order.containsAll(e.getValue())) {
                    next = e.getKey();
                    break;
                }
            }
            if (next == null) return null;
            order.add(next);
        }
        return order;
    }

    /** @return a computed field that is part of a circle, or null when there is none */
    public static String cycle(Map<String, String> computed, Function<String, Set<String>> fieldRefs) {
        List<String> order = order(computed, fieldRefs);
        if (order != null) return null;
        List<String> partial = new ArrayList<>();
        for (String key : new TreeSet<>(computed.keySet())) partial.add(key);
        return partial.isEmpty() ? null : partial.get(0);
    }

    private static Values values(JsonNode input, Instant now) {
        return Values.of(input, id -> null, Map.of(), now, null);
    }

    /**
     * Fills the computed fields of {@code ui.computed} into a copy of {@code input}. A field whose expression gives no
     * value is removed; an expression that cannot be compiled or exceeds its bounds leaves the field out.
     */
    public static ObjectNode apply(JsonNode inputSchema, JsonNode ui, JsonNode input, Instant now) {
        ObjectNode out = input != null && input.isObject() ? (ObjectNode) input.deepCopy()
                : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode computed = ui == null ? null : ui.path("computed");
        if (computed == null || !computed.isObject() || computed.isEmpty()) return out;
        Scope scope = scope(inputSchema);
        Map<String, String> sources = new LinkedHashMap<>();
        computed.properties().forEach(e -> sources.put(e.getKey(), e.getValue().asString("")));
        Map<String, Expression> compiled = new LinkedHashMap<>();
        sources.forEach((key, source) -> {
            try {
                compiled.put(key, Expression.compile(source, scope));
            } catch (ExpressionException e) {
                // The compiler refuses such a form before it is published.
            }
        });
        List<String> order = order(sources, key -> compiled.containsKey(key) ? compiled.get(key).fieldRefs() : Set.of());
        if (order == null) return out;
        for (String key : order) {
            Expression e = compiled.get(key);
            out.remove(key);
            if (e == null) continue;
            try {
                Object value = e.evaluate(values(out, now));
                if (value != null) {
                    JsonNode json = Expression.toJson(value);
                    String type = inputSchema.path("properties").path(key).path("type").asString("");
                    if (type.equals("integer") && json.isNumber() && json.decimalValue().stripTrailingZeros().scale() <= 0) {
                        json = tools.jackson.databind.node.JsonNodeFactory.instance.numberNode(json.decimalValue().toBigIntegerExact());
                    }
                    out.set(key, json);
                }
            } catch (ExpressionException ex) {
                // Bounded work exceeded: the field stays empty and required-field validation reports it.
            }
        }
        for (String key : order) {
            if (out.has(key) && !FormVisibility.visible(inputSchema, ui, out, key)) out.remove(key);
        }
        return out;
    }

    /** One broken rule: its id, message and the fields to show it at (empty for the request as a whole). */
    public record Broken(String id, String message, List<String> fields) {
    }

    /** @return the cross-field rules of {@code ui.rules} whose check is false for this input */
    public static List<Broken> broken(JsonNode inputSchema, JsonNode ui, JsonNode input, Instant now) {
        List<Broken> out = new ArrayList<>();
        if (ui == null || !ui.path("rules").isArray()) return out;
        Scope scope = scope(inputSchema);
        for (JsonNode rule : ui.path("rules")) {
            Boolean ok = test(rule.path("check").asString(""), scope, input, now);
            if (Boolean.FALSE.equals(ok)) {
                List<String> fields = new ArrayList<>();
                rule.path("fields").forEach(f -> fields.add(f.asString()));
                out.add(new Broken(rule.path("id").asString(""), rule.path("message").asString(""), List.copyOf(fields)));
            }
        }
        return out;
    }

    /** @return the true-or-false result of an expression over the request, or null when it gives no answer */
    public static Boolean test(String source, Scope scope, JsonNode input, Instant now) {
        try {
            Object value = Expression.compile(source, scope).evaluate(values(input, now));
            return value instanceof Boolean b ? b : null;
        } catch (ExpressionException e) {
            return null;
        }
    }
}
