package com.orqix.operations.alerts;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.AlertChannels;
import com.orqix.shared.Ids;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/**
 * Alert rules and destinations (CG-O06, docs/build/S144). Everything needs {@code alert.manage} (grant-only,
 * workspace-wide); changes need it fresh and are audited. Evaluation and delivery are {@link AlertEngine}'s job.
 */
@Service
public class AlertService {

    public static final int MAX_RULES = 50;
    public static final int MAX_DESTINATIONS = 20;

    public record CreateDestination(@NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "email|teams|slack|pagerDuty|webhook") String kind,
            @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String adapterKey,
            @Size(min = 1, max = 10) List<@NotBlank @Size(max = 254) String> recipients,
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef, UUID webhookSubscriptionId,
            Boolean enabled) {
    }

    /** The kind never changes; {@code expectedVersion} is the version the editor loaded. */
    public record UpdateDestination(@NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String adapterKey,
            @Size(min = 1, max = 10) List<@NotBlank @Size(max = 254) String> recipients,
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef, UUID webhookSubscriptionId,
            @NotNull Boolean enabled, @NotNull @Min(1) Integer expectedVersion) {
    }

    public record DestinationView(UUID id, String name, String kind, String adapterKey, List<String> recipients,
            String secretRef, UUID webhookSubscriptionId, boolean enabled, int version, Instant createdAt,
            Instant updatedAt) {
    }

    public record DestinationList(List<DestinationView> items) {
    }

    public record SaveRule(@NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "needsAttention|slaBreaches|circuitOpen|tokenRefreshFailing|deadLetters|pollChecksFailing|aiDriftFallback") String signal,
            @NotNull @Min(1) @Max(100000) Integer threshold, @Min(5) @Max(1440) Integer windowMinutes,
            UUID workflowId, @Size(min = 1, max = 64) String connectionKey,
            @NotNull @Pattern(regexp = "info|warning|critical") String severity,
            @Min(0) @Max(1440) Integer quietMinutes, Boolean notifyResolve, Boolean enabled,
            @NotNull @Size(min = 1, max = 5) List<@NotNull UUID> destinationIds, @Min(1) Integer expectedVersion) {
    }

    public record RuleView(UUID id, String name, String signal, int threshold, int windowMinutes, boolean windowed,
            UUID workflowId, String connectionKey, String severity, int quietMinutes, boolean notifyResolve,
            boolean enabled, Instant silencedUntil, String state, Integer lastValue, Instant lastEvaluatedAt,
            UUID openIncidentId, List<UUID> destinationIds, int version, Instant createdAt, Instant updatedAt) {
    }

    public record RuleList(List<RuleView> items) {
    }

    public record SilenceRequest(@NotNull @Min(0) @Max(10080) Integer minutes) {
    }

    public record DeliveryView(UUID id, UUID destinationId, String destinationName, String kind, String status,
            int attempts, String outcome, String errorCode, Instant createdAt, Instant settledAt) {
    }

    public record IncidentView(UUID id, UUID ruleId, String ruleName, String signal, String severity, String status,
            Instant openedAt, Instant resolvedAt, int openingValue, int peakValue, int lastValue, int threshold,
            String suppressed, List<DeliveryView> deliveries) {
    }

    public record IncidentList(List<IncidentView> items) {
    }

    public record TestResult(UUID destinationId, String status, String outcome, String errorCode) {
    }

    private final PolicyService policy;
    private final AlertChannels channels;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AlertService(PolicyService policy, AlertChannels channels, AuditWriter audit, JdbcClient jdbc, Clock clock) {
        this.policy = policy;
        this.channels = channels;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- destinations

    @Transactional(readOnly = true)
    public DestinationList listDestinations(Caller caller, UUID ws) {
        read(caller, ws, "listAlertDestinations");
        return new DestinationList(jdbc.sql(DESTINATION + " WHERE workspace_id = :ws ORDER BY name, id")
                .param("ws", ws).query((rs, n) -> destination(rs)).list());
    }

    @Transactional
    public DestinationView createDestination(Caller caller, UUID ws, CreateDestination request) {
        write(caller, ws, "createAlertDestination");
        long count = jdbc.sql("SELECT count(*) FROM alert_destination WHERE workspace_id = :ws").param("ws", ws)
                .query(Long.class).single();
        if (count >= MAX_DESTINATIONS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A workspace can have at most " + MAX_DESTINATIONS
                    + " alert destinations.");
        }
        AlertChannels.Target target = target(request.kind(), request.adapterKey(), request.recipients(),
                request.secretRef(), request.webhookSubscriptionId());
        channels.validate(ws, target);
        UUID id = Ids.newId();
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        jdbc.sql("""
                INSERT INTO alert_destination (id, workspace_id, name, kind, adapter_key, recipients, secret_ref,
                                               webhook_subscription_id, enabled, created_by, created_at)
                VALUES (:id, :ws, :name, :kind, :adapter, CAST(:recipients AS text[]), :secret, :hook, :enabled, :by, :now)
                """).param("id", id).param("ws", ws).param("name", request.name().strip()).param("kind", request.kind())
                .param("adapter", target.adapterKey()).param("recipients", pgArray(target.recipients()))
                .param("secret", target.secretRef()).param("hook", target.webhookSubscriptionId())
                .param("enabled", enabled).param("by", caller.subjectId()).param("now", Sql.ts(clock.instant()))
                .update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "alert.destination-created", "alertDestination", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("kind", request.kind(), "enabled", enabled)));
        return findDestination(ws, id);
    }

    @Transactional
    public DestinationView updateDestination(Caller caller, UUID ws, UUID id, UpdateDestination request) {
        write(caller, ws, "updateAlertDestination");
        DestinationView current = jdbc.sql(DESTINATION + " WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", ws).param("id", id).query((rs, n) -> destination(rs)).optional()
                .orElseThrow(ApiException::notFound);
        if (current.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The destination changed since you loaded it (now version " + current.version() + ").");
        }
        AlertChannels.Target target = target(current.kind(), request.adapterKey(), request.recipients(),
                request.secretRef(), request.webhookSubscriptionId());
        channels.validate(ws, target);
        jdbc.sql("""
                UPDATE alert_destination SET name = :name, adapter_key = :adapter, recipients = CAST(:recipients AS text[]),
                       secret_ref = :secret, webhook_subscription_id = :hook, enabled = :enabled, version = version + 1,
                       updated_by = :by, updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """).param("name", request.name().strip()).param("adapter", target.adapterKey())
                .param("recipients", pgArray(target.recipients())).param("secret", target.secretRef())
                .param("hook", target.webhookSubscriptionId()).param("enabled", request.enabled())
                .param("by", caller.subjectId()).param("now", Sql.ts(clock.instant())).param("ws", ws).param("id", id)
                .update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "alert.destination-updated", "alertDestination", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("kind", current.kind(), "enabled", request.enabled(), "version", current.version() + 1)));
        return findDestination(ws, id);
    }

    /** Sends a test notification now (through the same channel and rules as a real one) and records it. */
    @Transactional
    public TestResult testDestination(Caller caller, UUID ws, UUID id) {
        write(caller, ws, "testAlertDestination");
        DestinationView d = findDestination(ws, id);
        if (!d.enabled()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The destination is switched off.");
        }
        Instant now = clock.instant();
        UUID deliveryId = Ids.newId();
        AlertChannels.Outcome outcome = channels.deliver(ws, target(d),
                new AlertChannels.Message("alert-test:" + deliveryId, "test", "Test notification", "needsAttention",
                        "info", 0, 1, 15, "test", now));
        String status = outcome.delivered() ? "sent" : "failed";
        jdbc.sql("""
                INSERT INTO alert_delivery (id, workspace_id, incident_id, destination_id, kind, status, attempts,
                                            next_attempt_at, outcome, error_code, created_at, settled_at)
                VALUES (:id, :ws, NULL, :dest, 'test', :status, 1, :now, :outcome, :error, :now, :now)
                """).param("id", deliveryId).param("ws", ws).param("dest", id).param("status", status)
                .param("outcome", outcome.outcome()).param("error", outcome.errorCode()).param("now", Sql.ts(now))
                .update();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", d.kind());
        meta.put("status", status);
        meta.put("errorCode", outcome.errorCode());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "alert.destination-tested", "alertDestination", id.toString(), AuditWriter.Outcome.SUCCESS, meta));
        return new TestResult(id, status, outcome.outcome(), outcome.errorCode());
    }

    // ---------------------------------------------------------------- rules

    @Transactional(readOnly = true)
    public RuleList listRules(Caller caller, UUID ws) {
        read(caller, ws, "listAlertRules");
        return new RuleList(jdbc.sql(RULE + " WHERE r.workspace_id = :ws ORDER BY r.name, r.id").param("ws", ws)
                .query((rs, n) -> rule(rs)).list());
    }

    @Transactional
    public RuleView createRule(Caller caller, UUID ws, SaveRule request) {
        write(caller, ws, "createAlertRule");
        long count = jdbc.sql("SELECT count(*) FROM alert_rule WHERE workspace_id = :ws").param("ws", ws)
                .query(Long.class).single();
        if (count >= MAX_RULES) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A workspace can have at most " + MAX_RULES
                    + " alert rules.");
        }
        List<UUID> destinations = validate(ws, request);
        UUID id = Ids.newId();
        Instant now = clock.instant();
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        jdbc.sql("""
                INSERT INTO alert_rule (id, workspace_id, name, signal, threshold, window_minutes, workflow_id,
                                        connection_key, severity, quiet_minutes, notify_resolve, enabled,
                                        next_evaluation_at, created_by, created_at)
                VALUES (:id, :ws, :name, :signal, :threshold, :window, :wf, :conn, :severity, :quiet, :resolve,
                        :enabled, :now, :by, :now)
                """).param("id", id).param("ws", ws).param("name", request.name().strip())
                .param("signal", request.signal()).param("threshold", request.threshold())
                .param("window", window(request)).param("wf", request.workflowId())
                .param("conn", request.connectionKey()).param("severity", request.severity())
                .param("quiet", quiet(request)).param("resolve", !Boolean.FALSE.equals(request.notifyResolve()))
                .param("enabled", enabled).param("now", Sql.ts(now)).param("by", caller.subjectId()).update();
        linkDestinations(ws, id, destinations);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "alert.rule-created",
                "alertRule", id.toString(), AuditWriter.Outcome.SUCCESS, ruleMeta(request, enabled, 1)));
        return findRule(ws, id);
    }

    @Transactional
    public RuleView updateRule(Caller caller, UUID ws, UUID id, SaveRule request) {
        write(caller, ws, "updateAlertRule");
        RuleView current = lockRule(ws, id);
        if (request.expectedVersion() == null || current.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The rule changed since you loaded it (now version " + current.version() + ").");
        }
        List<UUID> destinations = validate(ws, request);
        Instant now = clock.instant();
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        // A changed signal, scope or threshold starts afresh: an open incident is closed without a notification.
        boolean reset = !current.signal().equals(request.signal()) || current.threshold() != request.threshold()
                || !java.util.Objects.equals(current.workflowId(), request.workflowId())
                || !java.util.Objects.equals(current.connectionKey(), request.connectionKey()) || !enabled;
        if (reset) {
            closeSilently(ws, id, now);
        }
        jdbc.sql("""
                UPDATE alert_rule SET name = :name, signal = :signal, threshold = :threshold, window_minutes = :window,
                       workflow_id = :wf, connection_key = :conn, severity = :severity, quiet_minutes = :quiet,
                       notify_resolve = :resolve, enabled = :enabled, next_evaluation_at = :now,
                       state = CASE WHEN :reset THEN 'ok' ELSE state END,
                       version = version + 1, updated_by = :by, updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """).param("name", request.name().strip()).param("signal", request.signal())
                .param("threshold", request.threshold()).param("window", window(request))
                .param("wf", request.workflowId()).param("conn", request.connectionKey())
                .param("severity", request.severity()).param("quiet", quiet(request))
                .param("resolve", !Boolean.FALSE.equals(request.notifyResolve())).param("enabled", enabled)
                .param("reset", reset).param("now", Sql.ts(now)).param("by", caller.subjectId()).param("ws", ws)
                .param("id", id).update();
        jdbc.sql("DELETE FROM alert_rule_destination WHERE rule_id = :id").param("id", id).update();
        linkDestinations(ws, id, destinations);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "alert.rule-updated",
                "alertRule", id.toString(), AuditWriter.Outcome.SUCCESS,
                ruleMeta(request, enabled, current.version() + 1)));
        return findRule(ws, id);
    }

    @Transactional
    public void deleteRule(Caller caller, UUID ws, UUID id) {
        write(caller, ws, "deleteAlertRule");
        RuleView current = lockRule(ws, id);
        jdbc.sql("DELETE FROM alert_rule WHERE workspace_id = :ws AND id = :id").param("ws", ws).param("id", id)
                .update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "alert.rule-deleted",
                "alertRule", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("signal", current.signal())));
    }

    /** Silences the rule's notifications for {@code minutes} (0 ends a silence). Evaluation goes on. */
    @Transactional
    public RuleView silenceRule(Caller caller, UUID ws, UUID id, SilenceRequest request) {
        write(caller, ws, "silenceAlertRule");
        lockRule(ws, id);
        Instant now = clock.instant();
        Instant until = request.minutes() == 0 ? null : now.plus(Duration.ofMinutes(request.minutes()));
        jdbc.sql("UPDATE alert_rule SET silenced_until = :until WHERE workspace_id = :ws AND id = :id")
                .param("until", Sql.ts(until)).param("ws", ws).param("id", id).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "alert.rule-silenced",
                "alertRule", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("minutes", request.minutes())));
        return findRule(ws, id);
    }

    // ---------------------------------------------------------------- incidents

    @Transactional(readOnly = true)
    public IncidentList listIncidents(Caller caller, UUID ws, UUID ruleId, String status) {
        read(caller, ws, "listAlertIncidents");
        if (status != null && !List.of("firing", "resolved").contains(status)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "status must be firing or resolved.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/status", "enum", "firing or resolved")));
        }
        List<IncidentView> incidents = jdbc.sql("""
                SELECT i.id, i.rule_id, r.name, r.signal, r.severity, i.status, i.opened_at, i.resolved_at,
                       i.opening_value, i.peak_value, i.last_value, i.threshold, i.suppressed
                FROM alert_incident i JOIN alert_rule r ON r.workspace_id = i.workspace_id AND r.id = i.rule_id
                WHERE i.workspace_id = :ws AND (CAST(:rule AS uuid) IS NULL OR i.rule_id = CAST(:rule AS uuid))
                  AND (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text))
                ORDER BY i.opened_at DESC, i.id DESC LIMIT 100
                """).param("ws", ws).param("rule", ruleId).param("status", status)
                .query((rs, n) -> new IncidentView(Sql.uuid(rs, "id"), Sql.uuid(rs, "rule_id"), rs.getString("name"),
                        rs.getString("signal"), rs.getString("severity"), rs.getString("status"),
                        Sql.instant(rs, "opened_at"), Sql.instant(rs, "resolved_at"), rs.getInt("opening_value"),
                        rs.getInt("peak_value"), rs.getInt("last_value"), rs.getInt("threshold"),
                        rs.getString("suppressed"), new ArrayList<>()))
                .list();
        if (!incidents.isEmpty()) {
            Map<UUID, IncidentView> byId = new LinkedHashMap<>();
            incidents.forEach(i -> byId.put(i.id(), i));
            jdbc.sql("""
                    SELECT d.id, d.incident_id, d.destination_id, t.name, d.kind, d.status, d.attempts, d.outcome,
                           d.error_code, d.created_at, d.settled_at
                    FROM alert_delivery d JOIN alert_destination t ON t.workspace_id = d.workspace_id AND t.id = d.destination_id
                    WHERE d.workspace_id = :ws AND d.incident_id IN (:ids) ORDER BY d.created_at, d.id
                    """).param("ws", ws).param("ids", byId.keySet())
                    .query((rs, n) -> {
                        byId.get(Sql.uuid(rs, "incident_id")).deliveries().add(new DeliveryView(Sql.uuid(rs, "id"),
                                Sql.uuid(rs, "destination_id"), rs.getString("name"), rs.getString("kind"),
                                rs.getString("status"), rs.getInt("attempts"), rs.getString("outcome"),
                                rs.getString("error_code"), Sql.instant(rs, "created_at"),
                                Sql.instant(rs, "settled_at")));
                        return 1;
                    }).list();
        }
        return new IncidentList(incidents);
    }

    // ---------------------------------------------------------------- helpers

    private void read(Caller caller, UUID ws, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.ALERT_MANAGE, operationId, false);
    }

    private void write(Caller caller, UUID ws, String operationId) {
        WorkspaceAccess access = policy.freshAccess(caller, ws).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.ALERT_MANAGE, operationId, true);
    }

    private List<UUID> validate(UUID ws, SaveRule request) {
        java.util.Set<String> scopes = AlertSignals.SCOPES.get(request.signal());
        if (request.workflowId() != null && request.connectionKey() != null) {
            throw invalid("/connectionKey", "Scope a rule to a workflow or a connection, not both.");
        }
        if (request.workflowId() != null) {
            if (!scopes.contains("workflow")) {
                throw invalid("/workflowId", "This signal cannot be limited to a workflow.");
            }
            if (jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id = :ws AND id = :id").param("ws", ws)
                    .param("id", request.workflowId()).query(Integer.class).single() == 0) {
                throw invalid("/workflowId", "Unknown workflow.");
            }
        }
        if (request.connectionKey() != null && !scopes.contains("connection")) {
            throw invalid("/connectionKey", "This signal cannot be limited to a connection.");
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(request.destinationIds()));
        int known = jdbc.sql("SELECT count(*) FROM alert_destination WHERE workspace_id = :ws AND id IN (:ids)")
                .param("ws", ws).param("ids", ids).query(Integer.class).single();
        if (known != ids.size()) {
            throw invalid("/destinationIds", "Choose existing alert destinations of this workspace.");
        }
        return ids;
    }

    private void linkDestinations(UUID ws, UUID rule, List<UUID> destinations) {
        for (UUID d : destinations) {
            jdbc.sql("INSERT INTO alert_rule_destination (workspace_id, rule_id, destination_id) VALUES (:ws, :rule, :d)")
                    .param("ws", ws).param("rule", rule).param("d", d).update();
        }
    }

    private void closeSilently(UUID ws, UUID rule, Instant now) {
        jdbc.sql("""
                UPDATE alert_incident SET status = 'resolved', resolved_at = :now
                WHERE workspace_id = :ws AND rule_id = :rule AND status = 'firing'
                """).param("now", Sql.ts(now)).param("ws", ws).param("rule", rule).update();
    }

    private static int window(SaveRule request) {
        return request.windowMinutes() == null ? 15 : request.windowMinutes();
    }

    private static int quiet(SaveRule request) {
        return request.quietMinutes() == null ? 30 : request.quietMinutes();
    }

    private static Map<String, Object> ruleMeta(SaveRule request, boolean enabled, int version) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("signal", request.signal());
        meta.put("threshold", request.threshold());
        meta.put("windowMinutes", window(request));
        meta.put("severity", request.severity());
        meta.put("destinations", request.destinationIds().size());
        meta.put("enabled", enabled);
        meta.put("version", version);
        return meta;
    }

    private RuleView lockRule(UUID ws, UUID id) {
        jdbc.sql("SELECT id FROM alert_rule WHERE workspace_id = :ws AND id = :id FOR UPDATE").param("ws", ws)
                .param("id", id).query(UUID.class).optional().orElseThrow(ApiException::notFound);
        return findRule(ws, id);
    }

    private RuleView findRule(UUID ws, UUID id) {
        return jdbc.sql(RULE + " WHERE r.workspace_id = :ws AND r.id = :id").param("ws", ws).param("id", id)
                .query((rs, n) -> rule(rs)).optional().orElseThrow(ApiException::notFound);
    }

    private DestinationView findDestination(UUID ws, UUID id) {
        return jdbc.sql(DESTINATION + " WHERE workspace_id = :ws AND id = :id").param("ws", ws).param("id", id)
                .query((rs, n) -> destination(rs)).optional().orElseThrow(ApiException::notFound);
    }

    static AlertChannels.Target target(DestinationView d) {
        return new AlertChannels.Target(d.kind(), d.adapterKey(), d.recipients(), d.secretRef(),
                d.webhookSubscriptionId());
    }

    /** Keeps only the fields the kind uses; a missing required field is a 422 with its pointer. */
    private static AlertChannels.Target target(String kind, String adapterKey, List<String> recipients,
            String secretRef, UUID webhookSubscriptionId) {
        return switch (kind) {
            case "email" -> {
                require(adapterKey, "/adapterKey", "Choose the email adapter to send through.");
                if (recipients == null || recipients.isEmpty()) {
                    throw invalid("/recipients", "Add at least one recipient.");
                }
                List<String> normalized = recipients.stream().map(r -> r.strip().toLowerCase(Locale.ROOT)).distinct()
                        .toList();
                for (String r : normalized) {
                    if (!r.matches("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$")) {
                        throw invalid("/recipients", "Enter email addresses only.");
                    }
                }
                yield new AlertChannels.Target(kind, adapterKey, normalized, null, null);
            }
            case "teams" -> {
                require(adapterKey, "/adapterKey", "Choose the Teams adapter to send through.");
                yield new AlertChannels.Target(kind, adapterKey, null, null, null);
            }
            case "slack", "pagerDuty" -> {
                require(secretRef, "/secretRef", "pagerDuty".equals(kind)
                        ? "Name the secret that holds the PagerDuty routing key."
                        : "Name the secret that holds the Slack incoming webhook address.");
                yield new AlertChannels.Target(kind, null, null, secretRef, null);
            }
            case "webhook" -> {
                if (webhookSubscriptionId == null) {
                    throw invalid("/webhookSubscriptionId", "Choose the webhook subscription to send through.");
                }
                yield new AlertChannels.Target(kind, null, null, null, webhookSubscriptionId);
            }
            default -> throw invalid("/kind", "Unknown destination kind.");
        };
    }

    private static void require(String value, String pointer, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(pointer, message);
        }
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }

    private static String pgArray(List<String> values) {
        if (values == null) {
            return null;
        }
        // Addresses were checked against a pattern without quotes, commas, braces or spaces.
        return "{" + values.stream().map(v -> "\"" + v.replace("\\", "").replace("\"", "") + "\"")
                .reduce((a, b) -> a + "," + b).orElse("") + "}";
    }

    static final String DESTINATION = """
            SELECT id, name, kind, adapter_key, recipients, secret_ref, webhook_subscription_id, enabled, version,
                   created_at, updated_at FROM alert_destination""";

    static DestinationView destination(java.sql.ResultSet rs) throws SQLException {
        Array recipients = rs.getArray("recipients");
        List<String> list = recipients == null ? null : List.of((String[]) recipients.getArray());
        return new DestinationView(Sql.uuid(rs, "id"), rs.getString("name"), rs.getString("kind"),
                rs.getString("adapter_key"), list, rs.getString("secret_ref"), Sql.uuid(rs, "webhook_subscription_id"),
                rs.getBoolean("enabled"), rs.getInt("version"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }

    private static final String RULE = """
            SELECT r.id, r.name, r.signal, r.threshold, r.window_minutes, r.workflow_id, r.connection_key, r.severity,
                   r.quiet_minutes, r.notify_resolve, r.enabled, r.silenced_until, r.state, r.last_value,
                   r.last_evaluated_at, r.version, r.created_at, r.updated_at,
                   (SELECT i.id FROM alert_incident i WHERE i.rule_id = r.id AND i.status = 'firing') AS open_incident,
                   ARRAY(SELECT d.destination_id FROM alert_rule_destination d WHERE d.rule_id = r.id
                         ORDER BY d.destination_id) AS destinations
            FROM alert_rule r""";

    private static RuleView rule(java.sql.ResultSet rs) throws SQLException {
        int last = rs.getInt("last_value");
        Integer lastValue = rs.wasNull() ? null : last;
        List<UUID> destinations = new ArrayList<>();
        Array array = rs.getArray("destinations");
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                destinations.add((UUID) o);
            }
        }
        String signal = rs.getString("signal");
        return new RuleView(Sql.uuid(rs, "id"), rs.getString("name"), signal, rs.getInt("threshold"),
                rs.getInt("window_minutes"), AlertSignals.WINDOWED.contains(signal), Sql.uuid(rs, "workflow_id"),
                rs.getString("connection_key"), rs.getString("severity"), rs.getInt("quiet_minutes"),
                rs.getBoolean("notify_resolve"), rs.getBoolean("enabled"), Sql.instant(rs, "silenced_until"),
                rs.getString("state"), lastValue, Sql.instant(rs, "last_evaluated_at"), Sql.uuid(rs, "open_incident"),
                destinations, rs.getInt("version"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }
}
