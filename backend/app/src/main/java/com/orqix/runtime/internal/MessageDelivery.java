package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hands one message to one waiting request (S16 section 3). The request row is locked first, then the subscription;
 * the subscription changes from {@code waiting} exactly once, so a message reaches a given wait at most once even when
 * two pods try at the same moment. Everything, including the engine's continuation, happens in the caller's
 * transaction.
 */
@Service
public class MessageDelivery {

    /** Result of one delivery attempt. */
    public record Outcome(Status status, List<FieldError> errors, MessageStore.Delivered delivered, List<String> fields) {

        public enum Status {
            /** The request now continues. */
            DELIVERED,
            /** The wait was closed in the meantime (timed out, message taken by another sender, request ended). */
            SKIPPED,
            /** The payload does not satisfy what the wait declares. */
            INVALID
        }
    }

    private final MessageStore store;
    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final Json json;
    private final Clock clock;
    private final RunSuspensions suspensions;

    public MessageDelivery(MessageStore store, RunStore runs, RunEvents events, ReleaseQueries releases,
            EngineCommands engine, Json json, Clock clock, RunSuspensions suspensions) {
        this.suspensions = suspensions;
        this.store = store;
        this.runs = runs;
        this.events = events;
        this.releases = releases;
        this.engine = engine;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param buffered true when the message was received before the wait started
     * @param sender   the sending principal (the timeline actor), or null for a buffered message
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome deliver(MessageStore.Subscription subscription, UUID messageId, JsonNode payload, boolean buffered,
            UUID sender) {
        UUID workspaceId = subscription.workspaceId();
        Optional<RunStore.RunRow> run = runs.lock(workspaceId, subscription.runId());
        Instant now = clock.instant();
        if (run.isEmpty() || !run.get().isActive()) {
            store.lock(workspaceId, subscription.id()).ifPresent(s ->
                    store.close(workspaceId, s.id(), MessageStore.CANCELLED, null, now));
            return skipped();
        }
        if (suspensions.isSuspended(workspaceId, subscription.runId())) {
            // Frozen by an operator: keep the wait open and the message buffered; the sweeper delivers it after resume.
            return skipped();
        }
        MessageStore.Subscription current = store.lock(workspaceId, subscription.id())
                .filter(s -> MessageStore.WAITING.equals(s.status())).orElse(null);
        if (current == null) {
            return skipped();
        }
        JsonNode step = releases.require(workspaceId, run.get().releaseId()).node(current.nodeId());
        ObjectNode output = json.object();
        List<FieldError> errors = MessagePayloads.validate(step.path("config").path("payload"), payload, output);
        if (!errors.isEmpty()) {
            return new Outcome(Outcome.Status.INVALID, errors, null, List.of());
        }
        store.close(workspaceId, current.id(), MessageStore.RECEIVED, messageId, now);
        runs.mutate(workspaceId, current.runId(), m -> {
            m.removeStep(current.nodeId());
            m.settle();
        });
        List<String> fields = new java.util.ArrayList<>();
        output.propertyNames().forEach(fields::add);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", step.path("label").asString(current.nodeId()));
        data.put("message", current.messageName());
        data.put("outcome", "received");
        data.put("buffered", buffered);
        data.put("fields", fields);
        events.append(workspaceId, current.runId(), "WAIT_ENDED", current.nodeId(), sender, data);
        engine.deliverMessage(current.engineExecutionId(), current.messageName(),
                Map.of(ProcessData.stepVariable(current.nodeId()), json.write(output)));
        return new Outcome(Outcome.Status.DELIVERED, List.of(),
                new MessageStore.Delivered(current.runId(), run.get().reference(), current.nodeId()), fields);
    }

    /**
     * A wait started (or the sweeper came by): take the oldest buffered message for its key, if there is one. A message
     * that does not satisfy the wait's declared values is discarded with a timeline entry and the wait goes on.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consumeBuffered(UUID workspaceId, UUID subscriptionId) {
        Instant now = clock.instant();
        MessageStore.Subscription subscription = store.find(workspaceId, subscriptionId)
                .filter(s -> MessageStore.WAITING.equals(s.status())).orElse(null);
        if (subscription == null) {
            return false;
        }
        // Lock order: the request row, then the subscription, then the message (SKIP LOCKED).
        if (!store.tryLockRun(workspaceId,subscription.runId())) return false;
        MessageStore.Subscription current = store.lock(workspaceId, subscriptionId)
                .filter(s -> MessageStore.WAITING.equals(s.status())).orElse(null);
        if (current == null) {
            return false;
        }
        MessageStore.Inbound message = store.lockOldestBuffered(workspaceId, current.messageName(),
                current.correlationKey(), now).orElse(null);
        if (message == null) {
            return false;
        }
        Outcome outcome = deliver(current, message.id(), message.payload(), true, null);
        switch (outcome.status()) {
            case DELIVERED -> store.closeInbound(workspaceId, message.id(), MessageStore.DELIVERED, 1, now);
            case INVALID -> {
                store.closeInbound(workspaceId, message.id(), MessageStore.DISCARDED, 0, now);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("message", current.messageName());
                data.put("reason", "The message did not match the values this step declares: "
                        + outcome.errors().stream().map(FieldError::message).limit(3).toList());
                events.append(workspaceId, current.runId(), "MESSAGE_DISCARDED", current.nodeId(), null, data);
            }
            case SKIPPED -> {
                return false;
            }
        }
        return true;
    }

    private static Outcome skipped() {
        return new Outcome(Outcome.Status.SKIPPED, List.of(), null, List.of());
    }
}
