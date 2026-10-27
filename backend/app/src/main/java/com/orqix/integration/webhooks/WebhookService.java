package com.orqix.integration.webhooks;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.OutboundWebhooks;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.node.ObjectNode;

/**
 * Outbound webhooks (docs/build/S99 (b)): subscriptions, the transactional outbox and the operator's dead-letter list.
 * Sending is {@link WebhookDispatcher}'s job. The payload carries ids and states only.
 */
@Service
public class WebhookService implements OutboundWebhooks {

    public static final int MAX_SUBSCRIPTIONS_PER_WORKSPACE = 10;

    public record CreateSubscription(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 2000) String url,
            @NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef,
            @NotNull @Size(min = 1, max = 5) List<String> events, Boolean enabled) {
    }

    public record UpdateSubscription(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 2000) String url,
            @NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef,
            @NotNull @Size(min = 1, max = 5) List<String> events, @NotNull Boolean enabled,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** {@code secretRef} only for connection managers; counts let operators see a backlog at a glance. */
    public record SubscriptionView(UUID id, String name, String url, String secretRef, List<String> events,
            boolean enabled, int version, long pending, long dead, Instant createdAt, Instant updatedAt) {
    }

    public record SubscriptionList(List<SubscriptionView> items) {
    }

    public record DeliveryView(UUID id, UUID subscriptionId, String subscriptionName, String eventType, UUID runId,
            String status, int attempts, Integer lastStatusCode, String lastError, Instant nextAttemptAt,
            Instant createdAt, Instant deliveredAt, Instant deadAt, int replays) {
    }

    public record DeliveryList(List<DeliveryView> items) {
    }

    private final PolicyService policy;
    private final EgressPolicy egress;
    private final WebhookSettings settings;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public WebhookService(PolicyService policy, EgressPolicy egress, WebhookSettings settings, AuditWriter audit,
            JdbcClient jdbc, Json json, Clock clock) {
        this.policy = policy;
        this.egress = egress;
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- outbox (runtime transaction)

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean wanted(UUID workspaceId, String type) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM webhook_subscription WHERE workspace_id = :ws AND enabled AND :type = ANY(events))
                """).param("ws", workspaceId).param("type", type).query(Boolean.class).single());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(RequestEvent event) {
        if (!EVENT_TYPES.contains(event.type())) {
            throw new IllegalArgumentException("Unknown webhook event " + event.type());
        }
        List<UUID> subscriptions = jdbc.sql("""
                SELECT id FROM webhook_subscription WHERE workspace_id = :ws AND enabled AND :type = ANY(events)
                """).param("ws", event.workspaceId()).param("type", event.type()).query(UUID.class).list();
        for (UUID subscription : subscriptions) {
            UUID id = Ids.newId();
            ObjectNode payload = json.object();
            payload.put("id", id.toString());
            payload.put("eventId", event.eventKey());
            payload.put("type", event.type());
            payload.put("occurredAt", event.occurredAt().toString());
            payload.put("workspaceId", event.workspaceId().toString());
            ObjectNode request = payload.putObject("request");
            request.put("id", event.runId().toString());
            request.put("reference", event.runReference());
            request.put("workflowId", event.workflowId() == null ? null : event.workflowId().toString());
            request.put("state", event.state());
            if (event.nodeId() == null) {
                payload.putNull("step");
            } else {
                payload.putObject("step").put("nodeId", event.nodeId());
            }
            long pending = jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = :s AND status IN ('pending', 'sending')")
                    .param("s", subscription).query(Long.class).single();
            boolean full = pending >= settings.maxPending();
            Instant now = event.occurredAt();
            jdbc.sql("""
                    INSERT INTO webhook_delivery (id, workspace_id, subscription_id, event_key, event_type, run_id, payload,
                                                  status, next_attempt_at, last_error, created_at, dead_at)
                    VALUES (:id, :ws, :s, :key, :type, :run, CAST(:payload AS jsonb), :status, :now, :error, :now, :dead)
                    ON CONFLICT (subscription_id, event_key) DO NOTHING
                    """).param("id", id).param("ws", event.workspaceId()).param("s", subscription)
                    .param("key", event.eventKey()).param("type", event.type()).param("run", event.runId())
                    .param("payload", json.write(payload)).param("status", full ? "dead" : "pending")
                    .param("now", Sql.ts(now)).param("error", full ? "pending_cap" : null)
                    .param("dead", full ? Sql.ts(now) : null).update();
        }
    }

    // ---------------------------------------------------------------- subscriptions

    @Transactional
    public SubscriptionView create(Caller caller, UUID workspaceId, CreateSubscription request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "createWebhookSubscription", true);
        List<String> events = validate(request.url(), request.events());
        long existing = jdbc.sql("SELECT count(*) FROM webhook_subscription WHERE workspace_id = :ws")
                .param("ws", workspaceId).query(Long.class).single();
        if (existing >= MAX_SUBSCRIPTIONS_PER_WORKSPACE) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "A workspace can have at most " + MAX_SUBSCRIPTIONS_PER_WORKSPACE + " webhook subscriptions.");
        }
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        UUID id = Ids.newId();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO webhook_subscription (id, workspace_id, name, url, secret_ref, events, enabled, created_by, created_at)
                VALUES (:id, :ws, :name, :url, :ref, CAST(:events AS text[]), :enabled, :by, :now)
                """).param("id", id).param("ws", workspaceId).param("name", request.name().strip())
                .param("url", request.url().strip()).param("ref", request.secretRef())
                .param("events", pgArray(events)).param("enabled", enabled).param("by", caller.subjectId())
                .param("now", Sql.ts(now)).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "webhook.subscription-created", "webhookSubscription", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("events", events, "enabled", enabled)));
        return find(workspaceId, id, true);
    }

    @Transactional
    public SubscriptionView update(Caller caller, UUID workspaceId, UUID id, UpdateSubscription request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "updateWebhookSubscription", true);
        Integer version = jdbc.sql("SELECT version FROM webhook_subscription WHERE id = :id AND workspace_id = :ws FOR UPDATE")
                .param("id", id).param("ws", workspaceId).query(Integer.class).optional()
                .orElseThrow(ApiException::notFound);
        if (version.intValue() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The subscription changed since you loaded it (now version " + version + ").");
        }
        List<String> events = validate(request.url(), request.events());
        jdbc.sql("""
                UPDATE webhook_subscription SET name = :name, url = :url, secret_ref = :ref, events = CAST(:events AS text[]),
                       enabled = :enabled, version = version + 1, updated_by = :by, updated_at = :now
                WHERE id = :id AND workspace_id = :ws
                """).param("name", request.name().strip()).param("url", request.url().strip())
                .param("ref", request.secretRef()).param("events", pgArray(events)).param("enabled", request.enabled())
                .param("by", caller.subjectId()).param("now", Sql.ts(clock.instant())).param("id", id)
                .param("ws", workspaceId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "webhook.subscription-updated", "webhookSubscription", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("events", events, "enabled", request.enabled(), "version", version + 1)));
        return find(workspaceId, id, true);
    }

    @Transactional(readOnly = true)
    public SubscriptionList list(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_READ, "listWebhookSubscriptions", false);
        boolean manager = access.has(Permission.CONNECTION_MANAGE);
        return new SubscriptionList(jdbc.sql(SUBSCRIPTION + " WHERE s.workspace_id = :ws ORDER BY s.name, s.id")
                .param("ws", workspaceId).query((rs, n) -> subscription(rs, manager)).list());
    }

    private static final String SUBSCRIPTION = """
            SELECT s.id, s.name, s.url, s.secret_ref, s.events, s.enabled, s.version, s.created_at, s.updated_at,
                   (SELECT count(*) FROM webhook_delivery d WHERE d.subscription_id = s.id AND d.status IN ('pending', 'sending')) AS pending,
                   (SELECT count(*) FROM webhook_delivery d WHERE d.subscription_id = s.id AND d.status = 'dead') AS dead
            FROM webhook_subscription s
            """;

    private SubscriptionView find(UUID workspaceId, UUID id, boolean manager) {
        return jdbc.sql(SUBSCRIPTION + " WHERE s.workspace_id = :ws AND s.id = :id").param("ws", workspaceId)
                .param("id", id).query((rs, n) -> subscription(rs, manager)).single();
    }

    private SubscriptionView subscription(ResultSet rs, boolean manager) throws SQLException {
        Array events = rs.getArray("events");
        List<String> list = new ArrayList<>(List.of((String[]) events.getArray()));
        return new SubscriptionView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"),
                manager ? rs.getString("secret_ref") : null, list, rs.getBoolean("enabled"), rs.getInt("version"),
                rs.getLong("pending"), rs.getLong("dead"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }

    private List<String> validate(String url, List<String> requested) {
        Set<String> events = new LinkedHashSet<>();
        for (String e : requested) {
            if (e == null || !EVENT_TYPES.contains(e)) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown event " + e + ".")
                        .withFieldErrors(List.of(new ApiException.FieldError("/events", "enum",
                                "Choose from " + String.join(", ", EVENT_TYPES.stream().sorted().toList()) + ".")));
            }
            events.add(e);
        }
        try {
            egress.check(url == null ? "" : url.strip());
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new ApiException(ProblemCode.EGRESS_NOT_ALLOWED, e.getMessage())
                    .withFieldErrors(List.of(new ApiException.FieldError("/url", "egress", e.getMessage())));
        }
        return List.copyOf(events);
    }

    private static String pgArray(List<String> values) {
        // Values are from the fixed EVENT_TYPES set (letters, dots and hyphens), so no quoting is needed.
        return "{" + String.join(",", values) + "}";
    }

    // ---------------------------------------------------------------- dead letters

    @Transactional(readOnly = true)
    public DeliveryList deliveries(Caller caller, UUID workspaceId, String status) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_OPERATE, "listWebhookDeliveries", false);
        String filter = status == null || status.isBlank() ? "dead" : status;
        if (!Set.of("pending", "sending", "delivered", "dead").contains(filter)) {
            throw ApiException.malformed("status must be pending, sending, delivered or dead.");
        }
        return new DeliveryList(jdbc.sql("""
                SELECT d.id, d.subscription_id, s.name, d.event_type, d.run_id, d.status, d.attempts, d.last_status_code,
                       d.last_error, d.next_attempt_at, d.created_at, d.delivered_at, d.dead_at, d.replays
                FROM webhook_delivery d JOIN webhook_subscription s ON s.id = d.subscription_id
                WHERE d.workspace_id = :ws AND d.status = :status
                ORDER BY coalesce(d.dead_at, d.delivered_at, d.created_at) DESC, d.id LIMIT 200
                """).param("ws", workspaceId).param("status", filter)
                .query((rs, n) -> new DeliveryView(rs.getObject("id", UUID.class),
                        rs.getObject("subscription_id", UUID.class), rs.getString("name"), rs.getString("event_type"),
                        rs.getObject("run_id", UUID.class), rs.getString("status"), rs.getInt("attempts"),
                        (Integer) rs.getObject("last_status_code"), rs.getString("last_error"),
                        Sql.instant(rs, "next_attempt_at"), Sql.instant(rs, "created_at"),
                        Sql.instant(rs, "delivered_at"), Sql.instant(rs, "dead_at"), rs.getInt("replays")))
                .list());
    }

    /** Puts one dead letter back in the queue for an immediate attempt (S99 B7). */
    @Transactional
    public DeliveryView replay(Caller caller, UUID workspaceId, UUID deliveryId) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "replayWebhookDelivery", true);
        record Current(UUID subscriptionId, String eventType, String status, int attempts) {
        }
        Current current = jdbc.sql("""
                SELECT subscription_id, event_type, status, attempts FROM webhook_delivery
                WHERE id = :id AND workspace_id = :ws FOR UPDATE
                """).param("id", deliveryId).param("ws", workspaceId)
                .query((rs, n) -> new Current(rs.getObject("subscription_id", UUID.class), rs.getString("event_type"),
                        rs.getString("status"), rs.getInt("attempts"))).optional().orElseThrow(ApiException::notFound);
        if (!"dead".equals(current.status())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Only a dead letter can be replayed.");
        }
        jdbc.sql("""
                UPDATE webhook_delivery SET status = 'pending', attempts = 0, next_attempt_at = :now, lease_until = NULL,
                       dead_at = NULL, replays = replays + 1
                WHERE id = :id
                """).param("now", Sql.ts(clock.instant())).param("id", deliveryId).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subscriptionId", current.subscriptionId().toString());
        metadata.put("eventType", current.eventType());
        metadata.put("attempts", current.attempts());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "webhook.delivery-replayed", "webhookDelivery", deliveryId.toString(), AuditWriter.Outcome.SUCCESS,
                metadata));
        return jdbc.sql("""
                SELECT d.id, d.subscription_id, s.name, d.event_type, d.run_id, d.status, d.attempts, d.last_status_code,
                       d.last_error, d.next_attempt_at, d.created_at, d.delivered_at, d.dead_at, d.replays
                FROM webhook_delivery d JOIN webhook_subscription s ON s.id = d.subscription_id WHERE d.id = :id
                """).param("id", deliveryId).query((rs, n) -> new DeliveryView(rs.getObject("id", UUID.class),
                rs.getObject("subscription_id", UUID.class), rs.getString("name"), rs.getString("event_type"),
                rs.getObject("run_id", UUID.class), rs.getString("status"), rs.getInt("attempts"),
                (Integer) rs.getObject("last_status_code"), rs.getString("last_error"),
                Sql.instant(rs, "next_attempt_at"), Sql.instant(rs, "created_at"), Sql.instant(rs, "delivered_at"),
                Sql.instant(rs, "dead_at"), rs.getInt("replays"))).single();
    }
}
