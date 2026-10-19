package com.orqix.integration;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.node.ObjectNode;

/**
 * What workflows may do with outbound adapters (S68): read the adapter facts the compiler checks a step against, and send
 * one step occurrence through the effect journal. Sending re-checks the adapter's current settings; an adapter being
 * available never authorizes a recipient or topic by itself, and this build delivers only to the local test stub.
 */
public interface AdapterSteps {

    /** @param configJson the stored allowlist configuration (never a secret) */
    record Fact(String key, String kind, String name, boolean enabled, String configJson) {
    }

    /**
     * One step occurrence. {@code payload} is the request shape of the adapter kind (S47), built only from the step's fixed
     * recipients or topic and its mapped values.
     */
    record Send(UUID workspaceId, UUID runId, String nodeId, int occurrence, String adapterKey, String kind,
            ObjectNode payload, String leaseOwner) {
    }

    List<Fact> facts(UUID workspaceId);

    /**
     * Journal protocol of S5 section 6.1: register and lease the effect, refuse it when the adapter is missing, disabled,
     * of another kind or now refuses the message, deliver it once, record the outcome. A succeeded effect is reused, never
     * resent; an email, Teams or Kafka send whose outcome is unknown is never repeated automatically.
     *
     * @return the journal outcome; the stored result is {@code {outcome, detail}} and never contains the message
     */
    EffectDispatcher.DispatchResult send(Send request);
}
