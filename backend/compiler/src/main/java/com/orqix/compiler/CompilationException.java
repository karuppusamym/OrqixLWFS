package com.orqix.compiler;

/** Thrown by {@link WorkflowCompiler#compile} when the definition has blockers. */
public class CompilationException extends Exception {

    private final transient ValidationReport report;

    public CompilationException(ValidationReport report) {
        super("Workflow definition has blocking issues");
        this.report = report;
    }

    public ValidationReport report() {
        return report;
    }
}
