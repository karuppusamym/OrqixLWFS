package com.orqix.runtime.internal;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.InboundCallbacks;
import com.orqix.integration.OutboundWebhooks;
import com.orqix.runtime.api.CallbackDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Callback tokens for waits answered by an external system (docs/build/S98, EX10). A wait row is created when the
 * request reaches a "Wait for a message" step with {@code externalCallback}; a person or machine client with
 * {@code run.operate} issues a token (returned once, stored as SHA-256); the token holder polls the wait and resumes it
 * once with a result. Resuming goes through {@link MessageDelivery#deliver}, the same path as the message API, so there
 * is no second way into the engine. Lock order: callback row, then request, then subscription.
 */
@Service
public class CallbackService implements InboundCallbacks {

    public static final String TOKEN_HEADER = "X-Orqix-Callback-Token";
    static final String PREFIX = "ocb_";
    static final int MAX_LIVE_TOKENS_PER_WORKSPACE = 10_000;
    static final int MAX_BODY_BYTES = 32 * 1024;
    private static final Pattern TOKEN = Pattern.compile("^ocb_[A-Za-z0-9_-]{43}$");
    private static final Set<String> ACTIVE = Set.of("running", "waiting", "needsAttention");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The poll answer and its ETag; {@code notModified} when the caller's tag is current. */
    public record Poll(CallbackDtos.CallbackWaitState state, String etag, boolean notModified) {
    }

    public record Resume(CallbackDtos.CallbackResumed body, boolean replayed) {
    }

    private final PolicyService policy;
    private final CallbackStore store;
    private final MessageStore messages;
    private final MessageDelivery delivery;
    private final MessageSettings settings;
    private final RunSuspensions suspensions;
    private final ReleaseQueries releases;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final OutboundWebhooks webhooks;
    private final Json json;
    private final Clock clock;

    public CallbackService(PolicyService policy, CallbackStore store, MessageStore messages,
            @Lazy MessageDelivery delivery, MessageSettings settings, RunSuspensions suspensions,
            ReleaseQueries releases, IdempotencyService idempotency, AuditWriter audit,
            @Lazy OutboundWebhooks webhooks, Json json, Clock clock) {
        this.policy = policy;
        this.store = store;
        this.messages = messages;
        this.delivery = delivery;
        this.settings = settings;
        this.suspensions = suspensions;
        this.releases = releases;
        this.idempotency = idempotency;
        this.audit = audit;
        this.webhooks = webhooks;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the wait starts (engine transaction)

    /** Called by {@link MessageWaits#started} for a new subscription; does nothing for an ordinary message wait. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void waitStarted(RunStore.RunRow run, String nodeId, UUID subscriptionId) {
        JsonNode node = releases.require(run.workspaceId(), run.releaseId()).node(nodeId);
        String validFor = node == null ? null
                : node.path("config").path("externalCallback").path("tokenValidFor").asString(null);
        if (validFor == null) {
            return;
        }
        Duration duration;
        try {
            duration = Duration.parse(validFor);
        } catch (RuntimeException e) {
            return; // the compiler admits only valid durations; an old release without it simply has no token
        }
        if (duration.compareTo(Duration.ofMinutes(1)) < 0 || duration.compareTo(Duration.ofDays(30)) > 0) {
            return;
        }
        Instant now = clock.instant();
        UUID id = Ids.newId();
        if (store.insert(id, run.workspaceId(), run.id(), nodeId, subscriptionId, duration, now)
                && webhooks.wanted(run.workspaceId(), "callback.waiting")) {
            webhooks.record(new OutboundWebhooks.RequestEvent(run.workspaceId(), "callback.waiting",
                    "callback:" + id, run.id(), run.reference(), run.workflowId(), RunStore.WAITING, nodeId, now));
        }
    }

    // ---------------------------------------------------------------- operators

    @Transactional(readOnly = true)
    public CallbackDtos.CallbackWaitList list(Caller caller, UUID workspaceId, UUID runId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_OPERATE, "listCallbackWaits", false);
        Instant now = clock.instant();
        List<CallbackDtos.CallbackWait> items = new ArrayList<>();
        for (CallbackStore.Row row : store.list(workspaceId, runId, 200)) {
            items.add(view(row, now));
        }
        return new CallbackDtos.CallbackWaitList(items);
    }

    @Transactional
    public CallbackDtos.CallbackTokenIssued issue(Caller caller, UUID workspaceId, UUID waitId) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "issueCallbackToken", true);
        CallbackStore.Row row = store.lock(workspaceId, waitId).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        String[] state = state(row, now);
        if (row.resumedAt() != null || "cancelled".equals(state[0])) {
            throw new ApiException(ProblemCode.CALLBACK_NOT_WAITING,
                    "The wait is no longer open (" + state[0] + "), so no token can be issued.");
        }
        if (store.liveTokens(workspaceId, now) >= MAX_LIVE_TOKENS_PER_WORKSPACE) {
            throw new ApiException(ProblemCode.RATE_LIMITED,
                    "This workspace already has " + MAX_LIVE_TOKENS_PER_WORKSPACE + " live callback tokens.")
                    .withHeader("Retry-After", "60");
        }
        String token = newToken();
        Instant expires = now.plus(row.validFor());
        boolean replaced = row.tokenSha256() != null;
        store.issue(row.id(), Json.sha256Hex(token), now, expires, caller.subjectId());
        AuditWriter.ActorType actor = caller.machine() ? AuditWriter.ActorType.SERVICE : AuditWriter.ActorType.USER;
        if (replaced) {
            audit.write(new AuditWriter.Entry(workspaceId, actor, caller.subjectId(), "callback.token-revoked",
                    "callbackWait", row.id().toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("waitId", row.id().toString(), "runId", row.runId().toString(), "replaced", true)));
        }
        audit.write(new AuditWriter.Entry(workspaceId, actor, caller.subjectId(), "callback.token-issued",
                "callbackWait", row.id().toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("waitId", row.id().toString(), "runId", row.runId().toString(), "nodeId", row.nodeId(),
                        "validForSeconds", row.validFor().toSeconds())));
        String base = "/api/v1/workspaces/" + workspaceId + "/callbacks/wait";
        return new CallbackDtos.CallbackTokenIssued(row.id(), token, expires, base, base + "/resume", TOKEN_HEADER);
    }

    /** Revokes the current token (if any); the wait itself goes on and a new token can be issued. */
    @Transactional
    public void revoke(Caller caller, UUID workspaceId, UUID waitId) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "revokeCallbackToken", true);
        CallbackStore.Row row = store.lock(workspaceId, waitId).orElseThrow(ApiException::notFound);
        if (row.resumedAt() != null) {
            // The token now only replays the original answer; revoking it would turn a safe replay into a 404.
            throw new ApiException(ProblemCode.CALLBACK_ALREADY_RESUMED,
                    "The wait was already resumed; its token only replays the original answer and stays as it is.");
        }
        if (row.tokenSha256() == null) {
            return;
        }
        store.revoke(row.id());
        audit.write(new AuditWriter.Entry(workspaceId,
                caller.machine() ? AuditWriter.ActorType.SERVICE : AuditWriter.ActorType.USER, caller.subjectId(),
                "callback.token-revoked", "callbackWait", row.id().toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("waitId", row.id().toString(), "runId", row.runId().toString(), "replaced", false)));
    }

    // ---------------------------------------------------------------- token holder

    @Transactional(readOnly = true)
    public Poll poll(UUID workspaceId, String token, String ifNoneMatch) {
        CallbackStore.Row row = resolve(workspaceId, token, false);
        Instant now = clock.instant();
        String[] state = state(row, now);
        String etag = "\"cbw-" + row.version() + "-" + state[0] + (state[1] == null ? "" : "." + state[1]) + "\"";
        if (ifNoneMatch != null && List.of(ifNoneMatch.split(",")).stream().map(String::strip)
                .anyMatch(t -> t.equals(etag) || t.equals("W/" + etag))) {
            return new Poll(null, etag, true);
        }
        return new Poll(new CallbackDtos.CallbackWaitState(row.id(), row.runId(), row.runReference(), row.nodeId(),
                state[0], state[1], isSuspended(row, state[0]), row.tokenExpiresAt(), row.resumedAt(), fields(row),
                row.resumeResult()), etag, false);
    }

    @Transactional
    public Resume resume(UUID workspaceId, String token, String idempotencyKey, JsonNode body) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        JsonNode result = body == null ? null : body.get("result");
        return resume(workspaceId, null, token, key, result, "api");
    }

    /** IN05 (S99 A1-A4): the signed inbound route resumes a wait of the trigger's own workflow. */
    @Override
    @Transactional
    public Resumed resume(UUID workspaceId, UUID workflowId, String token, String idempotencyKey, ObjectNode result) {
        Resume done = resume(workspaceId, workflowId, token, idempotencyKey, result, "inbound");
        return new Resumed(done.body().waitId(), done.body().runId(), done.replayed());
    }

    private Resume resume(UUID workspaceId, UUID requiredWorkflow, String token, String key, JsonNode result,
            String channel) {
        CallbackStore.Row row = resolve(workspaceId, token, true);
        if (requiredWorkflow != null && !requiredWorkflow.equals(row.workflowId())) {
            throw ApiException.notFound();
        }
        if (result == null || !result.isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The body must be {\"result\": { ... }}.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/result", "type", "Send an object of values.")));
        }
        String hash = idempotency.requestHash("resumeCallbackWait", Map.of(), result);
        if (row.resumedAt() != null) {
            if (key.equals(row.resumeIdemKey()) && hash.equals(row.resumeRequestHash())) {
                return new Resume(json.mapper().treeToValue(row.resumeResult(), CallbackDtos.CallbackResumed.class),
                        true);
            }
            throw new ApiException(ProblemCode.CALLBACK_ALREADY_RESUMED,
                    "This wait was already resumed at " + row.resumedAt() + "; only a repeat of that request is accepted.");
        }
        Instant now = clock.instant();
        String[] state = state(row, now);
        if (!"waiting".equals(state[0])) {
            throw new ApiException(ProblemCode.CALLBACK_NOT_WAITING, "expired".equals(state[0])
                    ? "The callback token expired; ask an operator for a new one."
                    : "The wait is no longer open (" + state[1] + ").");
        }
        if (suspensions.isSuspended(workspaceId, row.runId())) {
            throw new ApiException(ProblemCode.RUN_SUSPENDED,
                    "An operator suspended this request. Send the same request again after it is resumed.");
        }
        byte[] bytes = json.write(result).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Math.min(settings.maxPayloadBytes(), MAX_BODY_BYTES)) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,
                    "The result exceeds " + Math.min(settings.maxPayloadBytes(), MAX_BODY_BYTES) + " bytes.");
        }
        List<ApiException.FieldError> shape = MessagePayloads.shape(result);
        if (!shape.isEmpty() || Json.depth(result) > 10) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The result is not valid.").withFieldErrors(
                    shape.isEmpty() ? List.of(new ApiException.FieldError("/result", "depth", "Nested too deeply."))
                            : asResult(shape));
        }
        MessageStore.Subscription subscription = messages.find(workspaceId, row.subscriptionId())
                .orElseThrow(() -> new ApiException(ProblemCode.CALLBACK_NOT_WAITING, "The wait is no longer open."));
        UUID messageId = Ids.newId();
        messages.insertInbound(messageId, workspaceId, subscription.messageName(), subscription.correlationKey(),
                result, null, now, now.plus(settings.bufferTtl()));
        MessageDelivery.Outcome outcome;
        try {
            outcome = delivery.deliver(subscription, messageId, result, false, null);
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "The request is busy; send the same request again shortly.")
                    .withHeader("Retry-After", "2");
        }
        switch (outcome.status()) {
            case INVALID -> throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "The result does not match the values the waiting step declares.")
                    .withFieldErrors(asResult(outcome.errors()));
            case SKIPPED -> throw new ApiException(ProblemCode.CALLBACK_NOT_WAITING, "The wait is no longer open.");
            case DELIVERED -> {
            }
        }
        messages.closeInbound(workspaceId, messageId, MessageStore.DELIVERED, 1, now);
        CallbackDtos.CallbackResumed answer = new CallbackDtos.CallbackResumed(row.id(), row.runId(),
                row.runReference(), row.nodeId(), "resumed", now, outcome.fields());
        store.resumed(row.id(), now, key, hash, json.toTree(answer));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("waitId", row.id().toString());
        metadata.put("runId", row.runId().toString());
        metadata.put("fieldCount", outcome.fields().size());
        metadata.put("channel", channel);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null, "callback.resumed",
                "callbackWait", row.id().toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new Resume(answer, false);
    }

    // ---------------------------------------------------------------- helpers

    /** Unknown, malformed, replaced or revoked token, or a token of another workspace: 404, all alike. */
    private CallbackStore.Row resolve(UUID workspaceId, String token, boolean lock) {
        if (token == null || !TOKEN.matcher(token.strip()).matches() || workspaceId == null) {
            throw ApiException.notFound();
        }
        String sha = Json.sha256Hex(token.strip());
        CallbackStore.Row row = (lock ? store.lockByToken(sha) : store.findByToken(sha)).orElseThrow(ApiException::notFound);
        if (!workspaceId.equals(row.workspaceId())) {
            throw ApiException.notFound();
        }
        return row;
    }

    /** {state, reason}: the poll states of S98 D6. */
    static String[] state(CallbackStore.Row row, Instant now) {
        if (row.resumedAt() != null) {
            return new String[] {"resumed", null};
        }
        boolean open = MessageStore.WAITING.equals(row.subscriptionStatus()) && ACTIVE.contains(row.lifecycle());
        if (!open) {
            String reason = switch (row.subscriptionStatus()) {
                case MessageStore.TIMED_OUT -> "timedOut";
                case MessageStore.RECEIVED -> "answeredElsewhere";
                default -> "requestEnded";
            };
            return new String[] {"cancelled", reason};
        }
        if (row.tokenExpiresAt() != null && !now.isBefore(row.tokenExpiresAt())) {
            return new String[] {"expired", null};
        }
        return new String[] {"waiting", null};
    }

    private boolean isSuspended(CallbackStore.Row row, String state) {
        return ("waiting".equals(state) || "expired".equals(state))
                && suspensions.isSuspended(row.workspaceId(), row.runId());
    }

    private CallbackDtos.CallbackWait view(CallbackStore.Row row, Instant now) {
        String[] state = state(row, now);
        JsonNode node = releases.require(row.workspaceId(), row.releaseId()).node(row.nodeId());
        return new CallbackDtos.CallbackWait(row.id(), row.runId(), row.runReference(), row.nodeId(),
                node == null ? row.nodeId() : node.path("label").asString(row.nodeId()), row.messageName(), state[0],
                state[1], isSuspended(row, state[0]), row.tokenSha256() != null, row.tokenExpiresAt(),
                row.tokensIssued(), row.validFor().toString(), row.resumedAt(), row.createdAt(), fields(row));
    }

    private List<CallbackDtos.ExpectedField> fields(CallbackStore.Row row) {
        JsonNode node = releases.require(row.workspaceId(), row.releaseId()).node(row.nodeId());
        List<CallbackDtos.ExpectedField> fields = new ArrayList<>();
        if (node != null) {
            for (JsonNode f : node.path("config").path("payload")) {
                fields.add(new CallbackDtos.ExpectedField(f.path("name").asString(), f.path("type").asString(),
                        f.path("required").asBoolean(false)));
            }
        }
        return fields;
    }

    private static List<ApiException.FieldError> asResult(List<ApiException.FieldError> errors) {
        return errors.stream().map(e -> new ApiException.FieldError(
                e.pointer().startsWith("/payload") ? "/result" + e.pointer().substring("/payload".length()) : e.pointer(),
                e.code(), e.message())).toList();
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
