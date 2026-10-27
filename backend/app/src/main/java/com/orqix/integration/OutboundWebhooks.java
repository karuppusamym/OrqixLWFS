package com.orqix.integration;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The runtime reports request events here inside its own transaction (S99 B1-B3); the integration module turns each
 * into one outbox row per matching subscription. Only ids and states cross: no request data, labels or names.
 */
public interface OutboundWebhooks {

    Set<String> EVENT_TYPES = Set.of("request.started", "task.opened", "request.completed", "request.needs-attention",
            "callback.waiting");

    /**
     * @param eventKey  unique per event (for example {@code runId:seq}); a repeat is ignored
     * @param state     the request lifecycle after the event
     * @param nodeId    the step involved, or null
     */
    record RequestEvent(UUID workspaceId, String type, String eventKey, UUID runId, String runReference,
            UUID workflowId, String state, String nodeId, Instant occurredAt) {
    }

    /** True when an enabled subscription of the workspace wants this event type (cheap pre-check). */
    boolean wanted(UUID workspaceId, String type);

    /** Records the event for every enabled matching subscription, in the caller's transaction. */
    void record(RequestEvent event);
}
