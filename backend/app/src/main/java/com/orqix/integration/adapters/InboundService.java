package com.orqix.integration.adapters;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.InboundEvents;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inbound triggers (S47). A remote relay (or the customer's own connector) posts a signed event; the trigger verifies the
 * HMAC with a secret referenced by name, checks the sender filter, extracts only the mapped scalar values and stores the
 * event as {@code received}. An event never starts a run by itself: a person with {@code workflow.start} for the target
 * workflow starts it, so the run is attributed to an accountable user and normal start permissions apply.
 */
@Service
public class InboundService implements InboundEvents {

    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_PENDING_PER_TRIGGER = 1000;
    static final long MAX_SKEW_SECONDS = 300;
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    public record CreateTrigger(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String key,
            @NotNull @jakarta.validation.constraints.Pattern(regexp = "email|teams|jdbc|kafka") String source,
            @NotBlank @Size(max = 100) String name, @NotNull UUID workflowId,
            @NotNull @jakarta.validation.constraints.Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef,
            JsonNode filter, @NotNull JsonNode mapping) {
    }

    public record TriggerView(UUID id, String key, String source, String name, UUID workflowId, String secretRef,
            JsonNode filter, JsonNode mapping, boolean enabled, int version, String receivePath, Instant createdAt,
            Instant updatedAt) {
    }

    /** Source, key and target workflow never change; {@code expectedVersion} is the version the editor loaded (S68). */
    public record UpdateTrigger(@NotBlank @Size(max = 100) String name,
            @NotNull @jakarta.validation.constraints.Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef,
            JsonNode filter, @NotNull JsonNode mapping, @NotNull Boolean enabled,
            @NotNull @jakarta.validation.constraints.Min(1) Integer expectedVersion) {
    }

    /** Retention purge (S68): events received more than {@code olderThanDays} days ago. */
    public record PurgeRequest(
            @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(3650) Integer olderThanDays,
            Boolean includeUnstarted) {

        /** @return true only when explicitly asked: unstarted events are work someone may still start */
        public boolean unstarted() {
            return Boolean.TRUE.equals(includeUnstarted);
        }
    }

    public record PurgeResult(int deleted, Instant receivedBefore) {
    }

    public record TriggerList(List<TriggerView> items) {
    }

    public record Accepted(UUID eventId, String status, boolean duplicate) {
    }

    public record EventView(UUID id, UUID triggerId, String triggerKey, UUID workflowId, String eventId, String status,
            JsonNode input, UUID runId, Instant receivedAt) {
    }

    public record EventList(List<EventView> items) {
    }

    private final PolicyService policy;
    private final SecretStore secrets;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public InboundService(PolicyService policy, SecretStore secrets, AuditWriter audit, JdbcClient jdbc, Json json,
            Clock clock) {
        this.policy = policy;
        this.secrets = secrets;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- authoring

    @Transactional
    public TriggerView create(Caller caller, UUID workspaceId, CreateTrigger request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "createInboundTrigger", true);
        AdapterRules.Kind kind = AdapterRules.Kind.of(request.source());
        ObjectNode filter;
        ObjectNode mapping;
        try {
            filter = AdapterRules.validateFilter(kind, request.filter());
            mapping = AdapterRules.validateMapping(request.mapping());
            if (kind == AdapterRules.Kind.EMAIL && filter.isEmpty() || kind == AdapterRules.Kind.KAFKA && filter.isEmpty()) {
                throw new AdapterRules.Invalid("/filter", "Approve at least one sender domain or topic.");
            }
        } catch (AdapterRules.Invalid e) {
            throw AdapterService.invalid(e);
        }
        boolean workflowExists = jdbc.sql("SELECT count(*) FROM workflow WHERE id = :id AND workspace_id = :ws")
                .param("id", request.workflowId()).param("ws", workspaceId).query(Long.class).single() > 0;
        if (!workflowExists) {
            throw AdapterService.invalid(new AdapterRules.Invalid("/workflowId", "Choose a workflow of this workspace."));
        }
        UUID id = Ids.newId();
        Instant now = clock.instant();
        try {
            jdbc.sql("""
                    INSERT INTO inbound_trigger (id, workspace_id, key, source, name, workflow_id, secret_ref, filter, mapping,
                                                 enabled, created_by, created_at)
                    VALUES (:id, :ws, :key, :source, :name, :wf, :ref, CAST(:filter AS jsonb), CAST(:mapping AS jsonb), true, :by, :now)
                    """).param("id", id).param("ws", workspaceId).param("key", request.key()).param("source", kind.value())
                    .param("name", request.name().strip()).param("wf", request.workflowId()).param("ref", request.secretRef())
                    .param("filter", json.write(filter)).param("mapping", json.write(mapping))
                    .param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A trigger with this key already exists.");
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "inbound.trigger-created", "inboundTrigger", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("source", kind.value(), "key", request.key(), "workflowId", request.workflowId().toString())));
        return new TriggerView(id, request.key(), kind.value(), request.name().strip(), request.workflowId(),
                request.secretRef(), filter, mapping, true, 1, path(workspaceId, id), now, null);
    }

    /**
     * Edits or disables a trigger (S68). A disabled trigger answers 404 to senders like an unknown one; events already
     * received stay and can still be started or purged, with the values mapped when they arrived.
     */
    @Transactional
    public TriggerView update(Caller caller, UUID workspaceId, UUID triggerId, UpdateTrigger request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "updateInboundTrigger", true);
        record Current(String key, String source, UUID workflowId, int version, Instant createdAt) {
        }
        Current current = jdbc.sql("""
                SELECT key, source, workflow_id, version, created_at FROM inbound_trigger
                WHERE id = :id AND workspace_id = :ws FOR UPDATE
                """).param("id", triggerId).param("ws", workspaceId)
                .query((rs, n) -> new Current(rs.getString("key"), rs.getString("source"),
                        rs.getObject("workflow_id", UUID.class), rs.getInt("version"), Sql.instant(rs, "created_at")))
                .optional().orElseThrow(ApiException::notFound);
        if (current.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The trigger changed since you loaded it (now version " + current.version() + ").");
        }
        AdapterRules.Kind kind = AdapterRules.Kind.of(current.source());
        ObjectNode filter;
        ObjectNode mapping;
        try {
            filter = AdapterRules.validateFilter(kind, request.filter());
            mapping = AdapterRules.validateMapping(request.mapping());
            if (kind == AdapterRules.Kind.EMAIL && filter.isEmpty() || kind == AdapterRules.Kind.KAFKA && filter.isEmpty()) {
                throw new AdapterRules.Invalid("/filter", "Approve at least one sender domain or topic.");
            }
        } catch (AdapterRules.Invalid e) {
            throw AdapterService.invalid(e);
        }
        Instant now = clock.instant();
        int version = current.version() + 1;
        jdbc.sql("""
                UPDATE inbound_trigger SET name = :name, secret_ref = :ref, filter = CAST(:filter AS jsonb),
                       mapping = CAST(:mapping AS jsonb), enabled = :enabled, version = :version, updated_by = :by,
                       updated_at = :now
                WHERE id = :id AND workspace_id = :ws
                """).param("name", request.name().strip()).param("ref", request.secretRef())
                .param("filter", json.write(filter)).param("mapping", json.write(mapping)).param("enabled", request.enabled())
                .param("version", version).param("by", caller.subjectId()).param("now", Sql.ts(now))
                .param("id", triggerId).param("ws", workspaceId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "inbound.trigger-updated", "inboundTrigger", triggerId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("source", kind.value(), "key", current.key(), "version", version, "enabled", request.enabled())));
        return new TriggerView(triggerId, current.key(), kind.value(), request.name().strip(), current.workflowId(),
                request.secretRef(), filter, mapping, request.enabled(), version, path(workspaceId, triggerId),
                current.createdAt(), now);
    }

    /**
     * Retention (S68): deletes events received before the cutoff. Started events are always eligible (the run keeps its
     * own record and the audit log names the event); unstarted ones only with {@code includeUnstarted}, because someone
     * may still start them. Only a count is returned. A sender that repeats a purged event id later is accepted as a new
     * event: deduplication lasts as long as the event is kept.
     */
    @Transactional
    public PurgeResult purge(Caller caller, UUID workspaceId, PurgeRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "purgeInboundEvents", true);
        Instant cutoff = clock.instant().minus(java.time.Duration.ofDays(request.olderThanDays()));
        int deleted = jdbc.sql("""
                DELETE FROM inbound_event WHERE workspace_id = :ws AND received_at < :cutoff
                  AND (status = 'started' OR :unstarted)
                """).param("ws", workspaceId).param("cutoff", Sql.ts(cutoff)).param("unstarted", request.unstarted())
                .update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "inbound.events-purged", "workspace", workspaceId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("olderThanDays", request.olderThanDays(), "includeUnstarted", request.unstarted(),
                        "deleted", deleted)));
        return new PurgeResult(deleted, cutoff);
    }

    @Transactional(readOnly = true)
    public TriggerList list(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_READ, "listInboundTriggers", false);
        boolean manager = access.has(Permission.CONNECTION_MANAGE);
        return new TriggerList(jdbc.sql("""
                SELECT id, key, source, name, workflow_id, secret_ref, filter::text AS filter, mapping::text AS mapping,
                       enabled, version, created_at, updated_at
                FROM inbound_trigger WHERE workspace_id = :ws ORDER BY key
                """).param("ws", workspaceId).query((rs, n) -> new TriggerView(rs.getObject("id", UUID.class),
                rs.getString("key"), rs.getString("source"), rs.getString("name"), rs.getObject("workflow_id", UUID.class),
                manager ? rs.getString("secret_ref") : null, json.parse(rs.getString("filter")),
                json.parse(rs.getString("mapping")), rs.getBoolean("enabled"), rs.getInt("version"),
                manager ? path(workspaceId, rs.getObject("id", UUID.class)) : null, Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"))).list());
    }

    // ---------------------------------------------------------------- receiving

    private record Trigger(UUID id, String key, AdapterRules.Kind source, String secretRef, JsonNode filter, JsonNode mapping,
            boolean enabled) {
    }

    /**
     * Authenticates by signature only: {@code X-Orqix-Signature = sha256=hex(HMAC(secret, timestamp + "." + body))} with
     * {@code X-Orqix-Timestamp} within five minutes. Unknown or disabled triggers answer 404; a bad signature 401.
     */
    @Transactional
    public Accepted receive(UUID workspaceId, String triggerId, String eventId, String timestamp, String signature,
            byte[] body) {
        UUID id = Ids.parseOrNull(triggerId);
        if (id == null || workspaceId == null) {
            throw ApiException.notFound();
        }
        Trigger trigger = jdbc.sql("""
                SELECT id, key, source, secret_ref, filter::text AS filter, mapping::text AS mapping, enabled
                FROM inbound_trigger WHERE id = :id AND workspace_id = :ws
                """).param("id", id).param("ws", workspaceId)
                .query((rs, n) -> new Trigger(rs.getObject("id", UUID.class), rs.getString("key"),
                        AdapterRules.Kind.of(rs.getString("source")), rs.getString("secret_ref"),
                        json.parse(rs.getString("filter")), json.parse(rs.getString("mapping")), rs.getBoolean("enabled")))
                .optional().orElseThrow(ApiException::notFound);
        if (!trigger.enabled()) {
            throw ApiException.notFound();
        }
        byte[] bytes = body == null ? new byte[0] : body;
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The event is larger than 64 KiB.");
        }
        verifySignature(trigger, timestamp, signature, bytes);
        if (eventId == null || !EVENT_ID.matcher(eventId).matches()) {
            throw ApiException.malformed("X-Orqix-Event-Id must be 1-100 characters of [A-Za-z0-9._:-].");
        }
        JsonNode payload;
        try {
            payload = json.parse(bytes);
        } catch (RuntimeException e) {
            throw ApiException.malformed("The event body is not valid JSON.");
        }
        if (Json.depth(payload) > 10) {
            throw ApiException.malformed("The event is nested too deeply.");
        }
        ObjectNode input;
        try {
            AdapterRules.acceptInbound(trigger.source(), trigger.filter(), payload);
            input = AdapterRules.mapInput(trigger.mapping(), payload);
        } catch (AdapterRules.Invalid e) {
            throw AdapterService.invalid(e);
        }
        Optional<UUID> existing = jdbc.sql("SELECT id FROM inbound_event WHERE trigger_id = :t AND event_id = :e")
                .param("t", id).param("e", eventId).query(UUID.class).optional();
        if (existing.isPresent()) {
            return new Accepted(existing.get(), statusOf(existing.get()), true);
        }
        long pending = jdbc.sql("SELECT count(*) FROM inbound_event WHERE trigger_id = :t AND status = 'received'")
                .param("t", id).query(Long.class).single();
        if (pending >= MAX_PENDING_PER_TRIGGER) {
            throw new ApiException(ProblemCode.RATE_LIMITED, "Too many unstarted events are waiting for this trigger.")
                    .withHeader("Retry-After", "60");
        }
        UUID eventKey = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO inbound_event (id, workspace_id, trigger_id, event_id, payload_sha256, payload_bytes, mapped_input,
                                               status, received_at)
                    VALUES (:id, :ws, :t, :e, :sha, :bytes, CAST(:input AS jsonb), 'received', :now)
                    """).param("id", eventKey).param("ws", workspaceId).param("t", id).param("e", eventId)
                    .param("sha", Json.sha256Hex(bytes)).param("bytes", bytes.length).param("input", json.write(input))
                    .param("now", Sql.ts(clock.instant())).update();
        } catch (DuplicateKeyException race) {
            UUID winner = jdbc.sql("SELECT id FROM inbound_event WHERE trigger_id = :t AND event_id = :e")
                    .param("t", id).param("e", eventId).query(UUID.class).single();
            return new Accepted(winner, statusOf(winner), true);
        }
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null, "inbound.received",
                "inboundTrigger", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("source", trigger.source().value(), "key", trigger.key(), "sizeBytes", bytes.length)));
        return new Accepted(eventKey, "received", false);
    }

    private String statusOf(UUID eventKey) {
        return jdbc.sql("SELECT status FROM inbound_event WHERE id = :id").param("id", eventKey).query(String.class).single();
    }

    private void verifySignature(Trigger trigger, String timestamp, String signature, byte[] body) {
        long seconds;
        try {
            seconds = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The event signature is missing or invalid.");
        }
        if (Math.abs(clock.instant().getEpochSecond() - seconds) > MAX_SKEW_SECONDS) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The event signature is missing or invalid.");
        }
        String secret = secrets.read(trigger.secretRef()).orElseThrow(() -> new ApiException(
                ProblemCode.DEPENDENCY_UNAVAILABLE, "The trigger secret is not available."));
        String expected = "sha256=" + sign(secret, timestamp.trim(), body);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The event signature is missing or invalid.");
        }
    }

    /** Hex HMAC-SHA256 of {@code timestamp + "." + body}; shared with tests and documented for senders. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- events and start support

    /** Events whose workflow the caller may start; mapped values are business data, so connection.manage alone is not enough. */
    @Transactional(readOnly = true)
    public EventList events(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.canStartAnything()) {
            throw policy.deny(workspaceId, caller.subjectId(), "workflow.start", "listInboundEvents", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        List<EventView> all = jdbc.sql("""
                SELECT e.id, e.trigger_id, t.key AS trigger_key, t.workflow_id, e.event_id, e.status, e.mapped_input::text AS input,
                       e.run_id, e.received_at
                FROM inbound_event e JOIN inbound_trigger t ON t.id = e.trigger_id
                WHERE e.workspace_id = :ws ORDER BY e.received_at DESC LIMIT 200
                """).param("ws", workspaceId).query((rs, n) -> new EventView(rs.getObject("id", UUID.class),
                rs.getObject("trigger_id", UUID.class), rs.getString("trigger_key"), rs.getObject("workflow_id", UUID.class),
                rs.getString("event_id"), rs.getString("status"), json.parse(rs.getString("input")),
                rs.getObject("run_id", UUID.class), Sql.instant(rs, "received_at"))).list();
        return new EventList(all.stream().filter(e -> access.canStart(e.workflowId())).toList());
    }

    @Override
    @Transactional
    public Optional<Event> lockEvent(UUID workspaceId, UUID eventId) {
        return jdbc.sql("""
                SELECT e.id, t.workflow_id, e.mapped_input::text AS input, e.status, e.run_id
                FROM inbound_event e JOIN inbound_trigger t ON t.id = e.trigger_id
                WHERE e.id = :id AND e.workspace_id = :ws FOR UPDATE OF e
                """).param("id", eventId).param("ws", workspaceId)
                .query((rs, n) -> new Event(rs.getObject("id", UUID.class), rs.getObject("workflow_id", UUID.class),
                        (ObjectNode) json.parse(rs.getString("input")), rs.getString("status"),
                        rs.getObject("run_id", UUID.class))).optional();
    }

    @Override
    @Transactional
    public void markStarted(UUID workspaceId, UUID eventId, UUID runId, UUID startedBy) {
        jdbc.sql("""
                UPDATE inbound_event SET status = 'started', run_id = :run, started_by = :by, started_at = :now
                WHERE id = :id AND workspace_id = :ws AND status = 'received'
                """).param("run", runId).param("by", startedBy).param("now", Sql.ts(clock.instant()))
                .param("id", eventId).param("ws", workspaceId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, startedBy, "inbound.event-started",
                "inboundEvent", eventId.toString(), AuditWriter.Outcome.SUCCESS, Map.of("runId", runId.toString())));
    }

    private static String path(UUID workspaceId, UUID triggerId) {
        return "/api/v1/workspaces/" + workspaceId + "/inbound/" + triggerId;
    }
}
