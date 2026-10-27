package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import tools.jackson.databind.JsonNode;

/** Callback waits and tokens (docs/build/S98, EX10). No token is ever part of a view except the one-time issue answer. */
public final class CallbackDtos {

    private CallbackDtos() {
    }

    /** A value the external system may send back: one declared payload field of the waiting step. */
    public record ExpectedField(String name, String type, boolean required) {
    }

    /**
     * Operator view of one callback wait.
     *
     * @param state  {@code waiting}, {@code resumed}, {@code expired} or {@code cancelled}
     * @param reason for {@code cancelled}: {@code timedOut}, {@code requestEnded} or {@code answeredElsewhere}
     */
    public record CallbackWait(UUID id, UUID runId, String runReference, String nodeId, String stepLabel,
            String messageName, String state, String reason, boolean suspended, boolean tokenIssued,
            Instant tokenExpiresAt, int tokensIssued, String tokenValidFor, Instant resumedAt, Instant createdAt,
            List<ExpectedField> fields) {
    }

    public record CallbackWaitList(List<CallbackWait> items) {
    }

    /** The only answer that contains the clear token; it cannot be read again. */
    public record CallbackTokenIssued(UUID waitId, String token, Instant expiresAt, String pollPath, String resumePath,
            String header) {
    }

    /** What the token holder sees. {@code result} is the original resume answer once the wait was resumed. */
    public record CallbackWaitState(UUID waitId, UUID runId, String runReference, String nodeId, String state,
            String reason, boolean suspended, Instant expiresAt, Instant resumedAt, List<ExpectedField> fields,
            JsonNode result) {
    }

    /** @param result values for the waiting step's declared payload fields */
    public record CallbackResumeRequest(@NotNull JsonNode result) {
    }

    public record CallbackResumed(UUID waitId, UUID runId, String runReference, String nodeId, String state,
            Instant resumedAt, List<String> acceptedFields) {
    }
}
