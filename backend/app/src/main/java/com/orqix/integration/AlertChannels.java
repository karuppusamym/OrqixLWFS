package com.orqix.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CG-O06 (docs/build/S144): delivery of alert notifications through the integration module's channels. Email and
 * Teams go through an existing IN02 adapter (its recipient and egress rules apply), a webhook destination queues a
 * signed S99 delivery on an existing subscription, and Slack and PagerDuty go through a port that has only a local stub
 * in this build. A message carries the rule, signal, value and threshold only, never request values.
 */
public interface AlertChannels {

    /**
     * @param kind                  email, teams, slack, pagerDuty or webhook
     * @param adapterKey            email and teams: the adapter to send through
     * @param recipients            email only
     * @param secretRef             slack (incoming webhook URL) and pagerDuty (routing key); resolved at send time
     * @param webhookSubscriptionId webhook only
     */
    record Target(String kind, String adapterKey, List<String> recipients, String secretRef,
            UUID webhookSubscriptionId) {
    }

    /**
     * @param eventKey unique per incident, destination and event; repeats are ignored by the webhook outbox
     * @param event    fired, resolved or test
     * @param scope    a human description of the rule's scope ("all workflows", a workflow name, a connection key)
     */
    record Message(String eventKey, String event, String ruleName, String signal, String severity, int value,
            int threshold, int windowMinutes, String scope, Instant at) {
    }

    /** @param errorCode lower-case code when not delivered, null otherwise */
    record Outcome(boolean delivered, String outcome, String errorCode) {
    }

    /** Throws a 422 when the target cannot work in this workspace (unknown or wrong adapter, recipient domain, ...). */
    void validate(UUID workspaceId, Target target);

    /** Delivers in the caller's transaction; a delivery problem is returned, never thrown. */
    Outcome deliver(UUID workspaceId, Target target, Message message);
}
