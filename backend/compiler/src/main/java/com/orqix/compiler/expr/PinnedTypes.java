package com.orqix.compiler.expr;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The types of the request fields and step results an expression uses, as the compiler resolved them (S137). The
 * generated BPMN carries them next to the expression, so the engine evaluates with exactly the checked types (a date
 * field held as text is a date) without reading the workflow definition again. JSON:
 * {@code {"input": {key: T}, "steps": {stepId: {key: T}}}} in the {@link ExprType#read} notation.
 */
public final class PinnedTypes {

    private PinnedTypes() {
    }

    public static JsonNode write(ExprType t) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        return switch (t) {
            case ExprType.ListOf l -> f.objectNode().set("list", write(l.element()));
            case ExprType.Row r -> {
                ObjectNode row = f.objectNode();
                r.fields().forEach((k, v) -> row.set(k, write(v)));
                yield f.objectNode().set("row", row);
            }
            case ExprType.Scalar s when s == ExprType.Scalar.NULL -> f.stringNode("any");
            default -> f.stringNode(t.tag());
        };
    }

    /** @return the types of every field reference the expressions use, looked up in {@code scope} */
    public static ObjectNode of(Iterable<Expression> expressions, Scope scope) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ObjectNode input = out.putObject("input");
        ObjectNode steps = out.putObject("steps");
        for (Expression e : expressions) {
            for (String ref : e.fieldRefs()) {
                String[] parts = ref.split("/");
                if (ref.startsWith("/input/")) {
                    ExprType t = scope.input(parts[2]);
                    if (t != null) input.set(parts[2], write(t));
                } else if (parts.length == 4) {
                    ExprType t = scope.step(parts[2], parts[3]);
                    if (t != null) {
                        ObjectNode step = steps.has(parts[2]) ? (ObjectNode) steps.get(parts[2]) : steps.putObject(parts[2]);
                        step.set(parts[3], write(t));
                    }
                }
            }
        }
        return out;
    }

    /** @return a scope over pinned types and the given locals */
    public static Scope scope(JsonNode types, Map<String, ExprType> locals) {
        Map<String, ExprType> input = new LinkedHashMap<>();
        types.path("input").properties().forEach(e -> input.put(e.getKey(), ExprType.read(e.getValue())));
        Map<String, Map<String, ExprType>> steps = new LinkedHashMap<>();
        types.path("steps").properties().forEach(s -> {
            Map<String, ExprType> outputs = new LinkedHashMap<>();
            s.getValue().properties().forEach(e -> outputs.put(e.getKey(), ExprType.read(e.getValue())));
            steps.put(s.getKey(), outputs);
        });
        return Scope.of(input, steps, locals);
    }
}
