package com.orqix.compiler.internal.model;

import java.util.Map;

import com.orqix.compiler.internal.model.Enums.NodeKind;

/** A workflow step (schema {@code nodes[]}); {@code index} is the position in the canonical array. */
public sealed interface Node {

    int index();

    String id();

    String label();

    NodeKind kind();

    /** @return JSON Pointer of the node, e.g. {@code /nodes/3} */
    default String path() {
        return "/nodes/" + index();
    }

    /** @return JSON Pointer of a config member of the node */
    default String configPath(String member) {
        return path() + "/config/" + member;
    }

    /** Request form. */
    record Start(int index, String id, String label) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.START;
        }
    }

    /** Ask for approval. {@code dueAfterHours} and {@code timeoutAfter} (ISO-8601 duration) may be null. */
    record Approval(int index, String id, String label, String candidateGroupRef, boolean rejectReasonRequired,
                    Integer dueAfterHours, String timeoutAfter, java.util.List<String> requiredSkills,
                    tools.jackson.databind.JsonNode calendar, tools.jackson.databind.JsonNode escalation,
                    java.util.List<String> separateFrom) implements Node {
        public Approval(int index, String id, String label, String candidateGroupRef, boolean rejectReasonRequired,
                Integer dueAfterHours, String timeoutAfter) {
            this(index,id,label,candidateGroupRef,rejectReasonRequired,dueAfterHours,timeoutAfter,java.util.List.of(),null,null,java.util.List.of());
        }
        @Override
        public NodeKind kind() {
            return NodeKind.APPROVAL;
        }
    }

    /** Make a decision with a decision table. */
    record Decision(int index, String id, String label, String decisionRef) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.DECISION;
        }
    }

    /** Choose a path. */
    record Branch(int index, String id, String label) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.BRANCH;
        }
    }

    /**
     * Call a system. Mapping maps are sorted by key (JCS order). {@code compensates} (may be null) names the earlier
     * step this call is the designer's undo of; documentation and validation only (S32).
     */
    record Http(int index, String id, String label, String connectionRef, String operation,
                Map<String, String> inputMapping, Map<String, String> resultMapping, int timeoutSeconds,
                String compensates) implements Node {
        public Http(int index, String id, String label, String connectionRef, String operation,
                Map<String, String> inputMapping, Map<String, String> resultMapping, int timeoutSeconds) {
            this(index, id, label, connectionRef, operation, inputMapping, resultMapping, timeoutSeconds, null);
        }
        @Override
        public NodeKind kind() {
            return NodeKind.HTTP;
        }
    }

    record GenerateDocument(int index, String id, String label, String format, String fileName, String template,
            Map<String, String> inputMapping) implements Node {
        @Override public NodeKind kind() { return NodeKind.GENERATE_DOCUMENT; }
    }

    /** Notify someone. */
    record Notification(int index, String id, String label, String channel, String recipient, String message)
            implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.NOTIFICATION;
        }
    }

    /** Run in parallel: starts one branch per outgoing path; {@code joinRef} names the matching join. */
    record ParallelSplit(int index, String id, String label, String joinRef) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.PARALLEL_SPLIT;
        }
    }

    /** Wait for all branches of the matching split. */
    record ParallelJoin(int index, String id, String label) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.PARALLEL_JOIN;
        }
    }

    /**
     * Wait for a fixed time (ISO-8601 {@code duration}) or until a calendar date ({@code untilField}, a reference to a
     * date field) at {@code atTime} UTC (HH:mm, null means 09:00). Exactly one of duration and untilField is set in a
     * valid workflow; the rule {@code WF_WAIT_CHOICE} reports anything else.
     */
    record Wait(int index, String id, String label, String duration, String untilField, String atTime)
            implements Node {

        /** Time of day when {@code atTime} is not given. */
        public static final String DEFAULT_TIME = "09:00";

        /** @return the time of day in HH:mm (UTC) at which a date wait ends */
        public String time() {
            return atTime == null ? DEFAULT_TIME : atTime;
        }

        @Override
        public NodeKind kind() {
            return NodeKind.WAIT;
        }
    }

    /**
     * Start another workflow as a child request. {@code inputMapping}: child request field to field reference here;
     * {@code resultMapping}: output name here to child result field. Both sorted by key.
     */
    record StartWorkflow(int index, String id, String label, String workflowRef, Map<String, String> inputMapping,
                         Map<String, String> resultMapping, Repeat repeat, ForEach forEach,
                         Map<String, String> collectionMapping) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.START_WORKFLOW;
        }
    }

    /** Bounded sequential child occurrences; the outer workflow remains acyclic. */
    record Repeat(int maxIterations, String untilOutcome) { }

    record ForEach(String itemsRef, String itemInput, int maxItems, boolean parallel) { }

    /**
     * Wait for a named message from outside (S16). {@code correlationField} is a field reference whose value identifies
     * the request; {@code timeoutAfter} (ISO-8601 duration, may be null) adds a {@code timedOut} route; {@code payload}
     * declares the values the message may carry (outputs of this step), in authored order.
     */
    record WaitForMessage(int index, String id, String label, String message, String correlationField,
                          String timeoutAfter, java.util.List<PayloadField> payload) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.WAIT_FOR_MESSAGE;
        }

        /** @return the declared payload field with this name, or null */
        public PayloadField field(String name) {
            return payload.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** A value a message may carry: {@code type} is a scalar kind (string, number, integer, boolean or date). */
    record PayloadField(String name, Enums.FieldKind type, String title, boolean required) { }

    /** Finish. */
    record End(int index, String id, String label, String businessOutcome, String outcomeKind) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.END;
        }
    }
}
