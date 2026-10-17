package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.notification.NotificationService;
import com.orqix.shared.json.Json;

import tools.jackson.databind.node.ObjectNode;

/**
 * Orqix side of failed-job handling (S5 section 6.4). Runs inside the failed-job command's own transaction, after the
 * job transaction rolled back: retryable failures write {@code EFFECT_FAILED}; when retries reach zero the run moves to
 * {@code needsAttention}, the initiator is notified once per run + effect + attempt, and {@code RUN_NEEDS_ATTENTION}
 * is written.
 */
@Component
public class FailedJobRecorder {

    private static final Logger log = LoggerFactory.getLogger(FailedJobRecorder.class);
    static final String ATTENTION_NOTIFICATION = "Your request is delayed; an operator has been alerted.";

    /**
     * @param failure Orqix classification, or null for an unexpected exception (treated as retryable, then
     *                INTERNAL_ERROR)
     */
    public record Failure(UUID workspaceId, UUID runId, UUID initiator, String nodeId, boolean deadLettered,
            Instant nextRetryAt, JobFailure failure) {
    }

    private final RunStore runs;
    private final RunEvents events;
    private final NotificationService notifications;
    private final Json json;
    private final Clock clock;

    public FailedJobRecorder(RunStore runs, RunEvents events, NotificationService notifications, Json json, Clock clock) {
        this.runs = runs;
        this.events = events;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Failure f) {
        JobFailure failure = f.failure();
        RunStore.RunRow run = runs.find(f.workspaceId(), f.runId()).orElse(null);
        if (run == null || !run.isActive()) {
            log.warn("Failed job for run {} that is not active; nothing projected", f.runId());
            return;
        }
        boolean unknown = failure != null && "EFFECT_UNKNOWN".equals(failure.attentionReason());
        if (unknown) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attempt", failure.attempt());
            data.put("errorCode", failure.errorCode());
            events.append(f.workspaceId(), f.runId(), "EFFECT_UNKNOWN", f.nodeId(), null, data);
        } else {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attempt", failure == null ? null : failure.attempt());
            data.put("classification", failure == null ? "retryable" : failure.classification());
            data.put("httpStatus", failure == null ? null : failure.httpStatus());
            data.put("errorCode", failure == null ? "INTERNAL_ERROR" : failure.errorCode());
            data.put("nextRetryAt", f.deadLettered() || f.nextRetryAt() == null ? null : f.nextRetryAt().toString());
            events.append(f.workspaceId(), f.runId(), "EFFECT_FAILED", f.nodeId(), null, data);
        }
        if (!f.deadLettered()) {
            return;
        }
        String reason;
        String message;
        if (failure == null) {
            reason = "INTERNAL_ERROR";
            message = "An unexpected error stopped this step. Engineering has been alerted.";
        } else if (failure.retryable()) {
            reason = "RETRIES_EXHAUSTED";
            message = failure.businessMessage() == null
                    ? "The external system could not be reached after all attempts." : failure.businessMessage();
        } else {
            reason = failure.attentionReason();
            message = failure.businessMessage();
        }
        UUID effectId = failure == null ? null : failure.effectId();
        Instant now = clock.instant();
        ObjectNode attention = json.object();
        attention.put("reason", reason);
        attention.put("nodeId", f.nodeId());
        if (effectId == null) {
            attention.putNull("effectId");
        } else {
            attention.put("effectId", effectId.toString());
        }
        attention.put("message", message == null ? reason : message);
        attention.put("since", now.toString());
        runs.mutate(f.workspaceId(), f.runId(), m -> {
            m.lifecycle = RunStore.NEEDS_ATTENTION;
            m.attention = attention;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("effectId", effectId == null ? null : effectId.toString());
        events.append(f.workspaceId(), f.runId(), "RUN_NEEDS_ATTENTION", f.nodeId(), null, data);
        if (run.isChild()) {
            // A child that needs attention is visible from its parent's timeline (S12 section 4).
            events.append(f.workspaceId(), run.parentRunId(), "CHILD_NEEDS_ATTENTION", run.parentNodeId(), null,
                    Map.of("childRunId", run.id().toString(), "childReference", run.reference(), "reason", reason));
        }
        int attempt = failure == null ? 0 : failure.attempt();
        String dedup = f.runId() + ":attention:" + (effectId == null ? f.nodeId() : effectId) + ":" + attempt;
        if (notifications.create(f.workspaceId(), f.initiator(), f.runId(), NotificationService.Kind.RUN_ATTENTION, dedup,
                ATTENTION_NOTIFICATION)) {
            events.append(f.workspaceId(), f.runId(), "NOTIFICATION_CREATED", f.nodeId(), null,
                    Map.of("kind", NotificationService.Kind.RUN_ATTENTION.value(), "recipient", "initiator"));
        }
    }
}
