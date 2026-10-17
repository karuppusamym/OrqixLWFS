package com.orqix.compiler;

import com.orqix.compiler.internal.DefaultWorkflowCompiler;
import com.orqix.compiler.internal.eval.DefaultDecisionEvaluator;
import com.orqix.compiler.internal.eval.DefaultFormValidator;
import com.orqix.compiler.internal.sim.DefaultWorkflowSimulator;

/**
 * Factory for the default implementations. The application registers these as beans. All implementations are
 * immutable and thread-safe; the factories return shared instances (the JSON Schemas are loaded once).
 */
public final class Compilers {

    private Compilers() {
    }

    public static WorkflowCompiler workflowCompiler() {
        return Holder.COMPILER;
    }

    public static FormValidator formValidator() {
        return Holder.FORMS;
    }

    public static DecisionEvaluator decisionEvaluator() {
        return Holder.DECISIONS;
    }

    public static WorkflowSimulator workflowSimulator() {
        return Holder.SIMULATOR;
    }

    private static final class Holder {
        static final WorkflowCompiler COMPILER = new DefaultWorkflowCompiler();
        static final FormValidator FORMS = new DefaultFormValidator();
        static final DecisionEvaluator DECISIONS = new DefaultDecisionEvaluator();
        static final WorkflowSimulator SIMULATOR = new DefaultWorkflowSimulator();
    }
}
