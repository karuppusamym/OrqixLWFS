package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;

import tools.jackson.databind.node.ObjectNode;

/**
 * The run side of "Wait for a message" (S16): a wait starts (subscription row, step, timeline), its time limit passes,
 * or the request ends. Called by the engine projection listener inside the engine transaction.
 */
@Service
public class MessageWaits {

    private final MessageStore store;
    private final RunStore runs;
    private final RunEvents events;
    private final MessageDelivery delivery;
    private final Json json;
    private final Clock clock;

    public MessageWaits(MessageStore store, RunStore runs, RunEvents events,
            @org.springframework.context.annotation.Lazy MessageDelivery delivery, Json json, Clock clock) {
        this.store = store;
        this.runs = runs;
        this.events = events;
        this.delivery = delivery;
        this.json = json;
        this.clock = clock;
    }

    /**
     * A token reached a message wait: record who waits for what, show the step and look for a message that arrived
     * earlier (after this transaction commits, so the engine already holds the subscription).
     *
     * @param deadline end of the time limit, or null when the step has none (or its timer is not created yet)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void started(UUID workspaceId, UUID runId, String nodeId, String label, String message,
            String correlationKey, String engineExecutionId, Instant deadline) {
        Instant now = clock.instant();
        UUID id = Ids.newId();
        if (store.insertSubscription(id, workspaceId, runId, nodeId, message, correlationKey, engineExecutionId, now,
                deadline).isEmpty()) {
            return; // a repeated event of the same wait
        }
        runs.mutate(workspaceId, runId, m -> {
            ObjectNode step = json.object();
            step.put("nodeId", nodeId);
            step.put("label", label);
            step.put("kind", "wait");
            step.put("message", message);
            step.put("since", now.toString());
            step.putNull("candidateGroup");
            step.putNull("assigneeId");
            if (deadline == null) {
                step.putNull("dueAt");
            } else {
                step.put("dueAt", deadline.toString());
            }
            m.addStep(step);
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("message", message);
        data.put("duration", null);
        data.put("until", deadline == null ? null : deadline.toString());
        events.append(workspaceId, runId, "WAIT_STARTED", nodeId, null, data);
        afterCommit(workspaceId, id);
    }

    /** The time limit's timer exists: remember the moment it ends (it may be created before or after the wait). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deadline(UUID workspaceId, UUID runId, String nodeId, Instant deadline) {
        if (store.findForStep(workspaceId, runId, nodeId).filter(s -> MessageStore.WAITING.equals(s.status())).isEmpty()) {
            return;
        }
        store.setDeadline(workspaceId, runId, nodeId, deadline);
        runs.mutate(workspaceId, runId, m -> m.updateStep(nodeId, step -> step.put("dueAt", deadline.toString())));
    }

    /** The time limit passed before a message arrived: the step ends and the request follows its timedOut path. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void timedOut(UUID workspaceId, UUID runId, String nodeId, String label, String message, String after) {
        runs.lock(workspaceId, runId).orElseThrow();
        MessageStore.Subscription subscription = store.findForStep(workspaceId, runId, nodeId).orElse(null);
        if (subscription != null) {
            store.lock(workspaceId, subscription.id()).filter(s -> MessageStore.WAITING.equals(s.status()))
                    .ifPresent(s -> store.close(workspaceId, s.id(), MessageStore.TIMED_OUT, null, clock.instant()));
        }
        runs.mutate(workspaceId, runId, m -> {
            m.removeStep(nodeId);
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("message", message);
        data.put("outcome", "timedOut");
        data.put("after", after);
        events.append(workspaceId, runId, "WAIT_ENDED", nodeId, null, data);
    }

    /** The request ended: no message can reach it any more. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(UUID workspaceId, UUID runId) {
        store.closeAllForRun(workspaceId, runId, clock.instant());
    }

    private void afterCommit(UUID workspaceId, UUID subscriptionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return; // the sweeper finds it
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    delivery.consumeBuffered(workspaceId, subscriptionId);
                } catch (RuntimeException e) {
                    // Best effort: the sweeper retries every few seconds.
                }
            }
        });
    }
}
