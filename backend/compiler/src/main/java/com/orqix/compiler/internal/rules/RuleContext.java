package com.orqix.compiler.internal.rules;

import java.util.ArrayList;
import java.util.List;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/** Everything the rule stages share. */
public record RuleContext(WorkflowDefinition definition, CompileContext workspace, Graph graph,
                          Connections connections, FieldTypes types, Issues issues) {

    /** @return every transition whose {@code from} names this node (also those with an unknown target) */
    public List<Transition> leaving(Node n) {
        List<Transition> list = new ArrayList<>();
        for (Transition t : definition.transitions()) {
            if (graph.node(t.from()) == n) {
                list.add(t);
            }
        }
        return list;
    }

    /** @return the node's label in quotes, for use inside a sentence */
    public static String q(Node n) {
        return "\"" + n.label() + "\"";
    }

    /** @return the text in quotes */
    public static String q(String text) {
        return "\"" + text + "\"";
    }
}
