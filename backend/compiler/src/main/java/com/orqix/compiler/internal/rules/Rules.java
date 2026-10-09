package com.orqix.compiler.internal.rules;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.schema.SchemaValidation;

/** Pipeline stages 5-7 over a typed definition. */
public final class Rules {

    private Rules() {
    }

    /** Builds the analysis context and runs graph, type and binding rules into {@code issues}. */
    public static RuleContext run(WorkflowDefinition definition, CompileContext workspace, SchemaValidation schemas,
                                  Issues issues) {
        Graph graph = new Graph(definition);
        Connections connections = new Connections(definition, workspace, schemas);
        FieldTypes types = new FieldTypes(definition, graph, connections, workspace);
        RuleContext rc = new RuleContext(definition, workspace, graph, connections, types, issues);
        GraphRules.check(rc);
        ParallelRules.check(rc);
        TimerRules.check(rc);
        TypeRules.check(rc);
        BindingRules.check(rc);
        ChildRules.check(rc);
        return rc;
    }
}
