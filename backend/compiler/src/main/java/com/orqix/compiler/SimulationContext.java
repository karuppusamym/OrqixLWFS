package com.orqix.compiler;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Facts a dry run needs besides the definition.
 *
 * @param workspace        groups, connection definitions and bindings (the same context as validation)
 * @param childDefinitions the canonical definition of the active release of the workflow with this key, or null when
 *                         it has none; may be null when child workflows are unavailable
 * @param today            the date used to tell whether a "wait until a date" is already in the past
 */
public record SimulationContext(CompileContext workspace, Function<String, String> childDefinitions, LocalDate today) {

    public SimulationContext {
        if (workspace == null) {
            workspace = new CompileContext(false, java.util.Map.of(), java.util.List.of(), java.util.Set.of());
        }
        if (childDefinitions == null) {
            childDefinitions = key -> null;
        }
        if (today == null) {
            today = LocalDate.of(2026, 1, 15);
        }
    }
}
