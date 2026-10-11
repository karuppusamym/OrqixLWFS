package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.api.MessageDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;

/**
 * The inbound message API (S16 section 3): authorize in the caller's workspace, look for waiting requests with the same
 * message name and correlation key, deliver to each, and otherwise keep the message for a bounded time. One
 * transaction covers the message row, every delivery, the engine's continuation, the audit record and the idempotency
 * record, so a failure leaves nothing half done and a retry with the same key is safe.
 */
@Service
public class MessageService {

    public record Sent(MessageDtos.MessageAccepted body, boolean replayed) {
    }

    private final PolicyService policy;
    private final MessageStore store;
    private final MessageDelivery delivery;
    private final MessageSettings settings;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;

    public MessageService(PolicyService policy, MessageStore store, MessageDelivery delivery, MessageSettings settings,
            IdempotencyService idempotency, AuditWriter audit, Json json, Clock clock) {
        this.policy = policy;
        this.store = store;
        this.delivery = delivery;
        this.settings = settings;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Sent send(Caller caller, UUID workspaceId, String idempotencyKey, MessageDtos.SendMessageRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.MESSAGE_SEND, "sendMessage", true);
        String key = IdempotencyService.requireKey(idempotencyKey);
        JsonNode payload = request.payload() == null || request.payload().isNull() ? json.object() : request.payload();
        List<ApiException.FieldError> shape = MessagePayloads.shape(payload);
        if (!shape.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The payload is not valid.").withFieldErrors(shape);
        }
        if (json.write(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > settings.maxPayloadBytes()) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,
                    "The payload exceeds " + settings.maxPayloadBytes() + " bytes.");
        }
        store.serializeSend(workspaceId);
        String hash = idempotency.requestHash("sendMessage", Map.of("workspaceId", workspaceId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "sendMessage", key, hash);
        if (claim.isReplay()) {
            return new Sent(view(workspaceId, UUID.fromString(claim.replay().resourceId())), true);
        }

        Instant now = clock.instant();
        UUID messageId = Ids.newId();
        store.insertInbound(messageId, workspaceId, request.name(), request.correlationKey(), payload,
                caller.subjectId(), now, now.plus(settings.bufferTtl()));

        List<MessageStore.Subscription> waiting = store.waiting(workspaceId, request.name(), request.correlationKey(),
                settings.maxFanOut() + 1);
        if (waiting.size() > settings.maxFanOut()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "More than " + settings.maxFanOut()
                    + " requests wait for this message and key. Use a more specific key.");
        }
        List<MessageDtos.DeliveredRun> delivered = new ArrayList<>();
        for (MessageStore.Subscription subscription : waiting) {
            MessageDelivery.Outcome outcome;
            try {
                outcome = delivery.deliver(subscription, messageId, payload, false, caller.subjectId());
            } catch (EngineCommands.EngineConflictException e) {
                throw new ApiException(ProblemCode.RUN_BUSY, "A request is busy; send the message again shortly.")
                        .withHeader("Retry-After", "2");
            }
            switch (outcome.status()) {
                case INVALID -> throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "The payload does not match the values the waiting step declares.")
                        .withFieldErrors(outcome.errors());
                case DELIVERED -> delivered.add(new MessageDtos.DeliveredRun(outcome.delivered().runId(),
                        outcome.delivered().runReference(), outcome.delivered().nodeId()));
                case SKIPPED -> {
                    // Closed in the meantime (timed out or ended): not a match.
                }
            }
        }
        Instant bufferedUntil = null;
        if (delivered.isEmpty()) {
            // Nobody waits: keep it for a wait that starts later, within bounds.
            if (store.bufferedInWorkspace(workspaceId, now) > settings.maxBufferedPerWorkspace()
                    || store.bufferedForKey(workspaceId, request.name(), request.correlationKey(), now)
                    >= settings.maxBufferedPerKey() + 1) {
                throw new ApiException(ProblemCode.RATE_LIMITED,
                        "Too many messages are waiting for requests that have not reached their wait yet.")
                        .withHeader("Retry-After", "60");
            }
            bufferedUntil = now.plus(settings.bufferTtl());
        } else {
            store.closeInbound(workspaceId, messageId, MessageStore.DELIVERED, delivered.size(), now);
        }
        // Names and counts only: never the correlation key or the payload (S16 section 5).
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", request.name());
        metadata.put("matched", delivered.size());
        metadata.put("buffered", delivered.isEmpty());
        audit.write(new AuditWriter.Entry(workspaceId, caller.machine() ? AuditWriter.ActorType.SERVICE
                : AuditWriter.ActorType.USER, caller.subjectId(), "message.received", "message", messageId.toString(),
                AuditWriter.Outcome.SUCCESS, metadata));
        MessageDtos.MessageAccepted accepted = new MessageDtos.MessageAccepted(messageId, delivered.isEmpty() ? "buffered" : "delivered",
                delivered.size(), delivered, bufferedUntil);
        store.accepted(workspaceId,messageId,json.parse(json.write(accepted)));
        idempotency.complete(claim, "message", messageId.toString(), 202);
        return new Sent(accepted, false);
    }

    /** Replay the immutable acceptance, even after consumption or buffer expiry. */
    private MessageDtos.MessageAccepted view(UUID workspaceId, UUID messageId) {
        JsonNode snapshot = store.acceptance(workspaceId, messageId).orElse(null);
        if (snapshot == null) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE,
                    "The original message acceptance is unavailable; its delivery status cannot be recovered.");
        }
        return json.mapper().treeToValue(snapshot, MessageDtos.MessageAccepted.class);
    }
}
