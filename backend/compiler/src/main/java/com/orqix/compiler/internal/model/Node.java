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
                    java.util.List<String> separateFrom, java.util.List<String> correctable,
                    Integer approvers, Integer dueAfterMinutes, PerItem perItem,
                    java.util.Map<String, String> fieldAccess,
                    com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema capture,
                    /** CG-T04 (S141): the raw escalation policy, or null. */
                    tools.jackson.databind.JsonNode escalationPolicy) implements Node {
        public Approval(int index, String id, String label, String candidateGroupRef, boolean rejectReasonRequired,
                Integer dueAfterHours, String timeoutAfter) {
            this(index,id,label,candidateGroupRef,rejectReasonRequired,dueAfterHours,timeoutAfter,java.util.List.of(),null,null,java.util.List.of(),java.util.List.of(),null,null,null,java.util.Map.of(),null,null);
        }

        /**
         * Request fields the person holding the task may change (S42, S123): {@code correctable} plus fields whose
         * step-form access is {@code editable} or {@code required}, in a stable order without repeats.
         */
        public java.util.List<String> editableFields() {
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(correctable);
            fieldAccess.forEach((key, access) -> {
                if (access.equals("editable") || access.equals("required")) keys.add(key);
            });
            return java.util.List.copyOf(keys);
        }

        /** @return true when the step has a step form: field access settings or captured fields (S123) */
        public boolean hasStepForm() {
            return !fieldAccess.isEmpty() || capture != null && !capture.properties().isEmpty();
        }

        /** @return true when several different people must each approve (S51); null {@code approvers} means one */
        public boolean multiInstance() {
            return approvers != null && approvers > 1;
        }

        /** @return true when the step opens one task per item of a request list (S88) */
        public boolean listDriven() {
            return perItem != null;
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
                String compensates, boolean automaticUndo, String raiseOnFailure, Poll poll) implements Node {
        public Http(int index, String id, String label, String connectionRef, String operation,
                Map<String, String> inputMapping, Map<String, String> resultMapping, int timeoutSeconds,
                String compensates, boolean automaticUndo, String raiseOnFailure) {
            this(index, id, label, connectionRef, operation, inputMapping, resultMapping, timeoutSeconds, compensates,
                    automaticUndo, raiseOnFailure, null);
        }

        public Http(int index, String id, String label, String connectionRef, String operation,
                Map<String, String> inputMapping, Map<String, String> resultMapping, int timeoutSeconds) {
            this(index, id, label, connectionRef, operation, inputMapping, resultMapping, timeoutSeconds, null, false,
                    null);
        }

        public Http(int index, String id, String label, String connectionRef, String operation,
                Map<String, String> inputMapping, Map<String, String> resultMapping, int timeoutSeconds,
                String compensates, boolean automaticUndo) {
            this(index, id, label, connectionRef, operation, inputMapping, resultMapping, timeoutSeconds, compensates,
                    automaticUndo, null);
        }

        public Http(int index, String id, String label, String connectionRef, String operation,
                Map<String, String> inputMapping, Map<String, String> resultMapping, int timeoutSeconds,
                String compensates) {
            this(index, id, label, connectionRef, operation, inputMapping, resultMapping, timeoutSeconds, compensates,
                    false, null);
        }

        /** @return true for an automatic undo handler (S59): it has no paths and runs only from an Undo step */
        public boolean undoHandler() {
            return automaticUndo && compensates != null;
        }

        /** @return true when the step keeps checking until done (S129, {@code config.poll}) */
        public boolean polls() {
            return poll != null;
        }

        /**
         * @return the business error code this call raises when it fails for good with a known failure (S71 opt-in,
         *         {@code config.raiseErrorOnFailure}), or null for the S32 behavior (failed path or needs attention)
         */
        public String raiseOnFailure() {
            return raiseOnFailure;
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

    /**
     * Work done by an external worker (S43): {@code topic} names the kind of job, {@code inputMapping} is what the worker
     * receives (name to field reference, sorted by name) and {@code result} declares what it must return.
     */
    record ExternalTask(int index, String id, String label, String topic, Map<String, String> inputMapping,
                        java.util.List<PayloadField> result, int leaseSeconds, String raiseOnFailure) implements Node {

        public ExternalTask(int index, String id, String label, String topic, Map<String, String> inputMapping,
                java.util.List<PayloadField> result, int leaseSeconds) {
            this(index, id, label, topic, inputMapping, result, leaseSeconds, null);
        }

        @Override
        public NodeKind kind() {
            return NodeKind.EXTERNAL_TASK;
        }

        /** @return the declared result field with this name, or null */
        public PayloadField field(String name) {
            return result.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        }
    }

    /**
     * Send through a configured adapter (S73). {@code adapterKind} must equal the adapter's kind. Recipients ({@code to})
     * and the Kafka {@code topic} are literals of the step, never request data, and must be on the adapter's allowlist.
     * {@code subject}, {@code body}, {@code title}, {@code text} and {@code messageKey} may contain {@code {{name}}}
     * placeholders for {@code inputMapping} names; the Kafka value is the mapped values; database parameters are the
     * mapped values. Unused members are null (or empty for {@code to}).
     */
    record SendAdapter(int index, String id, String label, String adapterRef, String adapterKind,
                       java.util.List<String> to, String subject, String body, String title, String text, String topic,
                       String messageKey, Map<String, String> inputMapping) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.SEND_ADAPTER;
        }
    }

    /**
     * AI task (CG-A01, docs/build/S134): runs a pinned, approved AI skill on the mapped values. {@code skillVersion} is
     * null when the designer leaves the pin to publishing (latest approved version). {@code mode} must equal the
     * skill's mode; {@code autonomy} is {@code shadow}, {@code suggest}, {@code autoWithReview} or {@code auto}.
     * Mapping maps are sorted by key.
     */
    record AiTask(int index, String id, String label, String skillRef, Integer skillVersion, String mode,
                  Map<String, String> inputMapping, String autonomy, java.math.BigDecimal minConfidence,
                  int reviewSamplePercent, Map<String, String> redaction) implements Node {

        public static final String SHADOW = "shadow";
        public static final String SUGGEST = "suggest";
        public static final String AUTO_WITH_REVIEW = "autoWithReview";
        public static final String AUTO = "auto";
        public static final java.math.BigDecimal DEFAULT_MIN_CONFIDENCE = new java.math.BigDecimal("0.8");
        public static final int DEFAULT_REVIEW_PERCENT = 10;

        /** @return true for shadow mode: the step records a decision and always continues on next */
        public boolean shadow() {
            return SHADOW.equals(autonomy);
        }

        /** @return true when the AI result chooses the path (autoWithReview or auto) */
        public boolean routes() {
            return AUTO.equals(autonomy) || AUTO_WITH_REVIEW.equals(autonomy);
        }

        @Override
        public NodeKind kind() {
            return NodeKind.AI_TASK;
        }
    }

    /**
     * Calculate (CG-X01, S137): named outputs computed in order with Orqix expressions. {@code calendar} (may be null)
     * is the working calendar of working-day functions and {@code today()}.
     */
    record Calculate(int index, String id, String label, java.util.List<Output> outputs,
                     tools.jackson.databind.JsonNode calendar) implements Node {

        /** One output: its declared type (number, integer, string, boolean, date) and, for numbers, rounding. */
        public record Output(int index, String key, String expression, String type, Integer scale, String rounding,
                             String description, boolean optional) {
        }

        @Override
        public NodeKind kind() {
            return NodeKind.CALCULATE;
        }

        /** @return the output with this key, or null */
        public Output output(String key) {
            return outputs.stream().filter(o -> o.key().equals(key)).findFirst().orElse(null);
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

    /**
     * Run in parallel: starts one branch per outgoing path; {@code joinRef} names the matching join. An
     * {@code inclusive} split (S51) starts every path whose condition holds, or its default path when none does.
     */
    record ParallelSplit(int index, String id, String label, String joinRef, boolean inclusive) implements Node {
        public ParallelSplit(int index, String id, String label, String joinRef) {
            this(index, id, label, joinRef, false);
        }
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

    /**
     * Collection iteration (S14). A list of single values gives each child its item through {@code itemInput}; a list of
     * rows (S105) copies the columns named in {@code itemMapping} (child field -> row column) instead. Exactly one of the
     * two is set; {@code itemMapping} is empty for a list of single values.
     */
    record ForEach(String itemsRef, String itemInput, int maxItems, boolean parallel,
            java.util.Map<String, String> itemMapping) {
        public ForEach(String itemsRef, String itemInput, int maxItems, boolean parallel) {
            this(itemsRef, itemInput, maxItems, parallel, java.util.Map.of());
        }

        /** @return true when the child request field is filled from the current item */
        public boolean fills(String childField) {
            return childField.equals(itemInput) || itemMapping.containsKey(childField);
        }
    }

    /**
     * List-driven approval (S88, {@code config.perItem}): one task per item of the request list {@code itemsRef}.
     * {@code itemLabel} is the column of a list of rows shown on each task (null for a list of single values, whose
     * value is shown). {@code allowSamePerson} lets one person decide several items; by default each item needs a
     * different person (the S51 rule).
     */
    record PerItem(String itemsRef, String itemLabel, boolean allowSamePerson, boolean sequential, Integer quorum,
            String assigneeFrom) {
        /** Most items a list-driven approval may open tasks for. */
        public static final int MAX_ITEMS = 20;

        public PerItem(String itemsRef, String itemLabel, boolean allowSamePerson) {
            this(itemsRef, itemLabel, allowSamePerson, false, null, null);
        }

        /**
         * @return true when the step ends by the S104 settle rule (a quorum or one item at a time) rather than the S88
         *         "all approved or first rejection" condition; S88 steps keep generating exactly the BPMN they did
         */
        public boolean settles() {
            return sequential || quorum != null;
        }

        /** @return the request field key of the list */
        public String listKey() {
            return itemsRef.startsWith("/input/") ? itemsRef.substring("/input/".length()) : itemsRef;
        }
    }

    /**
     * Wait for a named message from outside (S16). {@code correlationField} is a field reference whose value identifies
     * the request; {@code timeoutAfter} (ISO-8601 duration, may be null) adds a {@code timedOut} route; {@code payload}
     * declares the values the message may carry (outputs of this step), in authored order.
     * {@code callbackTokenValidFor} (S98, may be null) marks a wait answered by an external system through a callback
     * token valid for that ISO-8601 duration. {@code guestResponse} (S140, may be null) is the guest response form
     * option as authored JSON.
     */
    record WaitForMessage(int index, String id, String label, String message, String correlationField,
                          String timeoutAfter, java.util.List<PayloadField> payload, String callbackTokenValidFor,
                          FallbackCheck fallbackCheck, tools.jackson.databind.JsonNode guestResponse)
            implements Node {

        public WaitForMessage(int index, String id, String label, String message, String correlationField,
                String timeoutAfter, java.util.List<PayloadField> payload, String callbackTokenValidFor,
                FallbackCheck fallbackCheck) {
            this(index, id, label, message, correlationField, timeoutAfter, payload, callbackTokenValidFor,
                    fallbackCheck, null);
        }

        public WaitForMessage(int index, String id, String label, String message, String correlationField,
                String timeoutAfter, java.util.List<PayloadField> payload, String callbackTokenValidFor) {
            this(index, id, label, message, correlationField, timeoutAfter, payload, callbackTokenValidFor, null);
        }

        public WaitForMessage(int index, String id, String label, String message, String correlationField,
                String timeoutAfter, java.util.List<PayloadField> payload) {
            this(index, id, label, message, correlationField, timeoutAfter, payload, null);
        }
        @Override
        public NodeKind kind() {
            return NodeKind.WAIT_FOR_MESSAGE;
        }

        /** @return the declared payload field with this name, or null */
        public PayloadField field(String name) {
            return payload.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        }
    }

    /**
     * Keep checking until done (S129, CG-W01). Durations are ISO-8601 texts with seconds allowed; {@code multiplier}
     * is 1 without backoff; {@code maxInterval} equals {@code every} without backoff. {@code maxChecks} is null when
     * the designer left it to the computed worst case. {@code failWhen} may be null.
     */
    record Poll(boolean firstCheckImmediate, String every, java.math.BigDecimal multiplier, String maxInterval,
                int jitterPercent, WorkflowDefinition.Condition until, WorkflowDefinition.Condition failWhen,
                String giveUpAfter, Integer maxChecks, int maxConsecutiveErrors, boolean errorsTimeOut,
                boolean finalCheckAtDeadline, java.util.List<String> showWhileWaiting,
                java.util.List<String> optionalResults, tools.jackson.databind.JsonNode json) {

        /** @return true when this mapped result may be missing in an answer (it is then an optional output) */
        public boolean optional(String key) {
            return optionalResults.contains(key);
        }
    }

    /**
     * Safety-net check of a message wait (S129, CG-W03): the same call settings as a "Call a system" step and a slow
     * interval; when {@code until} holds the mapped fields complete the wait as its payload.
     */
    record FallbackCheck(String connectionRef, String operation, Map<String, String> inputMapping,
                         Map<String, String> resultMapping, int timeoutSeconds, String every, int jitterPercent,
                         WorkflowDefinition.Condition until, Integer maxChecks, tools.jackson.databind.JsonNode json) { }

    /**
     * Wait for the first of several events (S139, CG-W07): 2 to 5 branches, each with its own route
     * ({@code on: branch}); the first branch to fire wins and the others are cancelled. Outputs are {@code branch} (the
     * id of the winning branch) and the declared values of the message, callback and check branches.
     */
    record WaitForFirst(int index, String id, String label, java.util.List<FirstBranch> branches) implements Node {

        /** Output naming the branch that won. */
        public static final String BRANCH_OUTPUT = "branch";

        @Override
        public NodeKind kind() {
            return NodeKind.WAIT_FOR_FIRST;
        }

        /** @return the branch with this id, or null */
        public FirstBranch branch(String branchId) {
            return branches.stream().filter(b -> b.id().equals(branchId)).findFirst().orElse(null);
        }

        /** @return the branch declaring this value, or null */
        public FirstBranch declaring(String field) {
            return branches.stream().filter(b -> b.field(field) != null).findFirst().orElse(null);
        }

        /** BPMN id of the catch event of a branch (also the id its wait is shown and stored under). */
        public static String elementId(String stepId, String branchId) {
            return stepId + "__" + branchId;
        }

        /**
         * Internal message name of a callback or check branch: they are delivered to their branch as a message
         * correlated by the request id. Names start with {@code orqix-}, which the message API refuses from outside.
         */
        public static String internalMessage(String stepId, String branchId) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest((stepId + "/" + branchId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return "orqix-" + branchId + "-" + java.util.HexFormat.of().formatHex(digest).substring(0, 10);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * One branch of {@link WaitForFirst}. {@code kind} is {@code message} ({@code message}, {@code correlationField},
     * {@code payload}), {@code signal} ({@code signal}), {@code callback} ({@code tokenValidFor}, {@code payload}),
     * {@code timer} (exactly one of {@code after}, {@code untilField} with {@code atTime}, or {@code workingDuration}
     * with {@code calendarRef}) or {@code check} ({@code check} and the {@code payload} its mapped results fill).
     */
    record FirstBranch(int index, String id, String label, String kind, String message, String correlationField,
            java.util.List<PayloadField> payload, String signal, String after, String untilField, String atTime,
            String workingDuration, String calendarRef, String tokenValidFor, FallbackCheck check) {

        public static final java.util.List<String> KINDS = java.util.List.of("message", "signal", "callback", "timer",
                "check");

        /** @return true for the branches delivered as a message (message, callback, check) */
        public boolean messageLike() {
            return kind.equals("message") || kind.equals("callback") || kind.equals("check");
        }

        /** @return the declared value with this name, or null */
        public PayloadField field(String name) {
            return payload.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        }

        /** @return the name the engine waits for: the authored message, or the internal one */
        public String messageName(String stepId) {
            return kind.equals("message") ? message : WaitForFirst.internalMessage(stepId, id);
        }

        /** @return the JSON Pointer of this branch inside its step */
        public String path(Node step) {
            return step.configPath("branches") + "/" + index;
        }
    }

    /** A value a message may carry: {@code type} is a scalar kind (string, number, integer, boolean or date). */
    record PayloadField(String name, Enums.FieldKind type, String title, boolean required) { }

    /** Raise a business error (S59): a terminal step whose error is caught by a section or an error handler. */
    record RaiseError(int index, String id, String label, String errorCode) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.RAISE_ERROR;
        }
    }

    /** Send a signal to the steps of the same request that wait for it (S59). */
    record SendSignal(int index, String id, String label, String signal) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.SEND_SIGNAL;
        }
    }

    /** Wait until a signal is sent in the same request (S59). */
    record WaitForSignal(int index, String id, String label, String signal) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.WAIT_FOR_SIGNAL;
        }
    }

    /** Run the automatic undo call of every call that completed in this request, most recent first (S59). */
    record Compensate(int index, String id, String label) implements Node {
        @Override
        public NodeKind kind() {
            return NodeKind.COMPENSATE;
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
