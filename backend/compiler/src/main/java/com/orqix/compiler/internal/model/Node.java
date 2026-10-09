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
                    Integer dueAfterHours, String timeoutAfter) implements Node {
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

    /** Call a system. Mapping maps are sorted by key (JCS order). */
    record Http(int index, String id, String label, String connectionRef, String operation,
                Map<String, String> inputMapping, Map<String, String> resultMapping, int timeoutSeconds)
            implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.HTTP;
        }
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

    /** Wait for a fixed time (ISO-8601 duration). */
    record Wait(int index, String id, String label, String duration) implements Node {
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
                         Map<String, String> resultMapping) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.START_WORKFLOW;
        }
    }

    /** Finish. */
    record End(int index, String id, String label, String businessOutcome, String outcomeKind) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.END;
        }
    }
}
