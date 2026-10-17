package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/** Request and response of {@code POST /workspaces/{workspaceId}/messages} (docs/build/S16). */
public final class MessageDtos {

    private MessageDtos() {
    }

    /**
     * @param name           message name, for example {@code payment-received}
     * @param correlationKey value that identifies the request the message is about (an order number)
     * @param payload        optional flat object of scalar values the waiting step declares
     */
    public record SendMessageRequest(
            @NotNull @Size(min = 2, max = 64) @Pattern(regexp = "^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$") String name,
            @NotNull @Size(min = 1, max = 200) String correlationKey, JsonNode payload) {
    }

    /** A request the message was handed to. */
    public record DeliveredRun(UUID runId, String runReference, String nodeId) {
    }

    /**
     * @param status        {@code delivered} when at least one waiting request took it, {@code buffered} when none was
     *                      waiting (it is kept until {@code bufferedUntil} for a wait that starts later)
     * @param matched       number of waiting requests it was delivered to
     * @param bufferedUntil end of the buffer window, only while {@code status} is {@code buffered}
     */
    public record MessageAccepted(UUID id, String status, int matched, List<DeliveredRun> delivered,
            Instant bufferedUntil) {
    }
}
