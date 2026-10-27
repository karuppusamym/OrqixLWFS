package com.orqix.integration;

import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

/**
 * What a Kafka trigger may do with one record (docs/build/S99 K8), implemented by the runtime: start the trigger's
 * workflow, or send the trigger's message to waiting requests, always as the trigger's run-as person through the same
 * service a person would use. Joins the caller's transaction.
 */
public interface KafkaDispatch {

    enum Outcome { STARTED, RESUMED, BUFFERED }

    record Dispatched(Outcome outcome, UUID runId, UUID messageId) {
    }

    /** The record can never succeed as it is (validation, permission, no active release): dead-letter it. */
    final class Refused extends RuntimeException {
        private final String code;

        public Refused(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Try again later (busy, rate or quota limit, dependency unavailable). */
    final class Retry extends RuntimeException {
        private final String code;

        public Retry(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    Dispatched start(UUID workspaceId, UUID runAs, UUID workflowId, String idempotencyKey, String businessKey,
            ObjectNode input);

    Dispatched resume(UUID workspaceId, UUID runAs, String messageName, String correlationKey, String idempotencyKey,
            ObjectNode payload);
}
