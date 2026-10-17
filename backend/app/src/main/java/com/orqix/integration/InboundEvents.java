package com.orqix.integration;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

/** What the runtime may do with a received inbound event (S47): lock it, then mark it started. Nothing else crosses the module. */
public interface InboundEvents {

    /** @param input only the mapped scalar values; the raw payload is never stored */
    record Event(UUID id, UUID workflowId, ObjectNode input, String status, UUID runId) {
    }

    /** Locks the event row for the current transaction; empty when it does not exist in the workspace. */
    Optional<Event> lockEvent(UUID workspaceId, UUID eventId);

    void markStarted(UUID workspaceId, UUID eventId, UUID runId, UUID startedBy);
}
