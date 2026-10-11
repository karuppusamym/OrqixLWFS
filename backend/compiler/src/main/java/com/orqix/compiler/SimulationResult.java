package com.orqix.compiler;

import java.util.List;

/**
 * Outcome of a dry run.
 *
 * @param status      what happened; {@link Status#FINISHED} and {@link Status#NEEDS_ATTENTION} carry a trace
 * @param inputErrors the sample request does not fit the request form (status INVALID_INPUT); pointers are relative to
 *                    the input object
 * @param blockers    problems that must be fixed before the workflow can be tested (status CANNOT_RUN)
 * @param steps       number of trace entries (0 unless a trace exists)
 * @param warnings    number of warnings (0 unless a trace exists)
 * @param json        the trace document {outcome, outputs, trace, warnings, coverage, limits}; null unless a trace exists
 */
public record SimulationResult(Status status, List<FormValidator.FieldError> inputErrors,
                               List<ValidationIssue> blockers, int steps, int warnings, String json) {

    public enum Status {
        FINISHED("finished"), NEEDS_ATTENTION("needsAttention"), INVALID_INPUT("invalidInput"), CANNOT_RUN("cannotRun");

        private final String apiValue;

        Status(String apiValue) {
            this.apiValue = apiValue;
        }

        public String apiValue() {
            return apiValue;
        }
    }

    public SimulationResult {
        inputErrors = List.copyOf(inputErrors);
        blockers = List.copyOf(blockers);
    }

    public boolean hasTrace() {
        return json != null;
    }
}
