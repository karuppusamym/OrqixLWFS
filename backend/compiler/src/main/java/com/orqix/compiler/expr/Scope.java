package com.orqix.compiler.expr;

import java.util.Map;

/**
 * The names an expression may use and their types (docs/build/S137). Each method returns null for an unknown name.
 */
public interface Scope {

    /** @return the type of request field {@code input.<key>} */
    ExprType input(String key);

    /** @return the type of output {@code steps.<stepId>.<key>} of an earlier step */
    ExprType step(String stepId, String key);

    /** @return the type of a local name: an earlier output of the same Calculate step */
    default ExprType local(String name) {
        return null;
    }

    /** A scope over fixed maps: request fields, step outputs ({@code stepId -> key -> type}) and locals. */
    static Scope of(Map<String, ExprType> input, Map<String, Map<String, ExprType>> steps, Map<String, ExprType> locals) {
        return new Scope() {
            @Override
            public ExprType input(String key) {
                return input.get(key);
            }

            @Override
            public ExprType step(String stepId, String key) {
                Map<String, ExprType> outputs = steps.get(stepId);
                return outputs == null ? null : outputs.get(key);
            }

            @Override
            public ExprType local(String name) {
                return locals.get(name);
            }
        };
    }
}
