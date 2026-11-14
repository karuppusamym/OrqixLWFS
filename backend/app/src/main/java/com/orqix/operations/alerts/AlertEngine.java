package com.orqix.operations.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.integration.AlertChannels;
import com.orqix.shared.Ids;
import com.orqix.shared.web.Sql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Evaluates due alert rules and sends queued notifications (CG-O06, docs/build/S144). Rules and deliveries are claimed
 * with {@code FOR UPDATE SKIP LOCKED}, so several workers never evaluate the same rule or send the same notification
 * at once.
 * <ul>
 * <li>Deduplication: at most one open incident per rule (partial unique index); a repeated evaluation of a firing rule
 * only updates its values, and each incident notifies a destination at most once per kind.</li>
 * <li>Quiet period: an incident that opens less than {@code quietMinutes} after the rule's last notification is
 * recorded but not notified (nor its resolution), which also absorbs flapping.</li>
 * <li>Silence: while {@code silenced_until} is in the future, new incidents are recorded but not notified.</li>
 * <li>Resolve: when the value drops below the threshold the incident resolves and, when the rule asks for it and the
 * firing was notified, the same destinations get a resolved notification.</li>
 * </ul>
 */
@Service
@Profile("!migrate")
public class AlertEngine {

    static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final AlertSignals signals;
    private final AlertChannels channels;
    private final AuditWriter audit;
    private final Clock clock;
    private final Duration interval;
    private final Counter fired;
    private final Counter resolved;
    private final MeterRegistry meters;

    public AlertEngine(JdbcClient jdbc, AlertSignals signals, AlertChannels channels, AuditWriter audit, Clock clock,
            MeterRegistry meters, @Value("${orqix.alerts.evaluation-interval:1m}") Duration interval) {
        this.jdbc = jdbc;
        this.signals = signals;
        this.channels = channels;
        this.audit = audit;
        this.clock = clock;
        this.interval = interval;
        this.meters = meters;
        this.fired = Counter.builder("orqix_alerts_fired").description("Alert incidents opened").register(meters);
        this.resolved = Counter.builder("orqix_alerts_resolved").description("Alert incidents resolved")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${orqix.alerts.poll-delay:30s}", initialDelayString = "${orqix.alerts.poll-delay:30s}")
    public void tick() {
        Instant now = clock.instant();
        evaluateDue(now);
        dispatchQueued(now);
    }

    private record Rule(UUID id, UUID workspaceId, String name, String signal, int threshold, int windowMinutes,
            UUID workflowId, String workflowName, String connectionKey, String severity, int quietMinutes,
            boolean notifyResolve, Instant silencedUntil, String state, Instant lastNotifiedAt) {
    }

    /** Evaluates every enabled rule that is due at {@code now}; returns how many were evaluated. */
    @Transactional
    public int evaluateDue(Instant now) {
        List<Rule> due = jdbc.sql("""
                SELECT r.id, r.workspace_id, r.name, r.signal, r.threshold, r.window_minutes, r.workflow_id,
                       w.name AS workflow_name, r.connection_key, r.severity, r.quiet_minutes, r.notify_resolve,
                       r.silenced_until, r.state, r.last_notified_at
                FROM alert_rule r LEFT JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE r.enabled AND r.next_evaluation_at <= :now
                ORDER BY r.next_evaluation_at, r.id LIMIT 100 FOR UPDATE OF r SKIP LOCKED
                """).param("now", Sql.ts(now)).query((rs, n) -> new Rule(Sql.uuid(rs, "id"),
                        Sql.uuid(rs, "workspace_id"), rs.getString("name"), rs.getString("signal"),
                        rs.getInt("threshold"), rs.getInt("window_minutes"), Sql.uuid(rs, "workflow_id"),
                        rs.getString("workflow_name"), rs.getString("connection_key"), rs.getString("severity"),
                        rs.getInt("quiet_minutes"), rs.getBoolean("notify_resolve"), Sql.instant(rs, "silenced_until"),
                        rs.getString("state"), Sql.instant(rs, "last_notified_at")))
                .list();
        for (Rule rule : due) {
            evaluate(rule, now);
        }
        return due.size();
    }

    private void evaluate(Rule rule, Instant now) {
        int value = signals.value(rule.workspaceId(), rule.signal(), rule.windowMinutes(), rule.workflowId(),
                rule.connectionKey(), now);
        boolean breached = value >= rule.threshold();
        Instant notifiedAt = rule.lastNotifiedAt();
        String state = rule.state();
        Map<String, Object> incident = jdbc.sql("""
                SELECT id, suppressed FROM alert_incident WHERE rule_id = :rule AND status = 'firing' FOR UPDATE
                """).param("rule", rule.id()).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", Sql.uuid(rs, "id"));
                    m.put("suppressed", rs.getString("suppressed"));
                    return m;
                }).optional().orElse(null);
        if (breached && incident == null) {
            String suppressed = rule.silencedUntil() != null && rule.silencedUntil().isAfter(now) ? "silenced"
                    : notifiedAt != null && notifiedAt.plus(Duration.ofMinutes(rule.quietMinutes())).isAfter(now)
                            ? "quietPeriod" : null;
            UUID id = Ids.newId();
            jdbc.sql("""
                    INSERT INTO alert_incident (id, workspace_id, rule_id, status, opened_at, opening_value, peak_value,
                                                last_value, threshold, suppressed)
                    VALUES (:id, :ws, :rule, 'firing', :now, :v, :v, :v, :t, :suppressed)
                    """).param("id", id).param("ws", rule.workspaceId()).param("rule", rule.id())
                    .param("now", Sql.ts(now)).param("v", value).param("t", rule.threshold())
                    .param("suppressed", suppressed).update();
            int queued = suppressed == null ? enqueue(rule, id, "fired", now) : 0;
            if (queued > 0) {
                notifiedAt = now;
            }
            state = "firing";
            fired.increment();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("ruleId", rule.id().toString());
            meta.put("signal", rule.signal());
            meta.put("value", value);
            meta.put("threshold", rule.threshold());
            meta.put("notifications", queued);
            meta.put("suppressed", suppressed);
            audit.write(new AuditWriter.Entry(rule.workspaceId(), AuditWriter.ActorType.SYSTEM, null, "alert.fired",
                    "alertIncident", id.toString(), AuditWriter.Outcome.SUCCESS, meta));
        } else if (breached) {
            jdbc.sql("""
                    UPDATE alert_incident SET last_value = :v, peak_value = greatest(peak_value, :v) WHERE id = :id
                    """).param("v", value).param("id", incident.get("id")).update();
            state = "firing";
        } else if (incident != null) {
            UUID id = (UUID) incident.get("id");
            jdbc.sql("UPDATE alert_incident SET status = 'resolved', resolved_at = :now, last_value = :v WHERE id = :id")
                    .param("now", Sql.ts(now)).param("v", value).param("id", id).update();
            int queued = rule.notifyResolve() && incident.get("suppressed") == null ? enqueue(rule, id, "resolved", now)
                    : 0;
            if (queued > 0) {
                notifiedAt = now;
            }
            state = "ok";
            resolved.increment();
            audit.write(new AuditWriter.Entry(rule.workspaceId(), AuditWriter.ActorType.SYSTEM, null,
                    "alert.resolved", "alertIncident", id.toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("ruleId", rule.id().toString(), "signal", rule.signal(), "value", value,
                            "notifications", queued)));
        } else {
            state = "ok";
        }
        jdbc.sql("""
                UPDATE alert_rule SET last_value = :v, last_evaluated_at = :now, next_evaluation_at = :next,
                       state = :state, last_notified_at = :notified
                WHERE id = :id
                """).param("v", value).param("now", Sql.ts(now)).param("next", Sql.ts(now.plus(interval)))
                .param("state", state).param("notified", Sql.ts(notifiedAt)).param("id", rule.id()).update();
    }

    private int enqueue(Rule rule, UUID incident, String kind, Instant now) {
        return jdbc.sql("""
                INSERT INTO alert_delivery (id, workspace_id, incident_id, destination_id, kind, status, next_attempt_at,
                                            created_at)
                SELECT gen_random_uuid(), :ws, :incident, d.id, :kind, 'queued', :now, :now
                FROM alert_rule_destination rd JOIN alert_destination d ON d.workspace_id = rd.workspace_id
                     AND d.id = rd.destination_id
                WHERE rd.rule_id = :rule AND d.enabled
                ON CONFLICT DO NOTHING
                """).param("ws", rule.workspaceId()).param("incident", incident).param("kind", kind)
                .param("now", Sql.ts(now)).param("rule", rule.id()).update();
    }

    private record Queued(UUID id, UUID workspaceId, UUID incidentId, String kind, int attempts, String ruleName,
            String signal, String severity, int threshold, int windowMinutes, String scope, int value,
            AlertService.DestinationView destination) {
    }

    /** Sends the due queued notifications; returns how many were settled (sent, failed or skipped). */
    @Transactional
    public int dispatchQueued(Instant now) {
        List<Queued> queued = jdbc.sql("""
                SELECT q.id AS delivery_id, q.workspace_id AS delivery_ws, q.incident_id, q.kind AS delivery_kind,
                       q.attempts, r.name AS rule_name, r.signal, r.severity, i.threshold, r.window_minutes,
                       coalesce(w.name, r.connection_key, 'all workflows') AS scope,
                       CASE WHEN q.kind = 'resolved' THEN i.last_value ELSE i.opening_value END AS value,
                       d.id, d.name, d.kind, d.adapter_key, d.recipients, d.secret_ref, d.webhook_subscription_id,
                       d.enabled, d.version, d.created_at, d.updated_at
                FROM alert_delivery q
                JOIN alert_incident i ON i.workspace_id = q.workspace_id AND i.id = q.incident_id
                JOIN alert_rule r ON r.workspace_id = i.workspace_id AND r.id = i.rule_id
                LEFT JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                JOIN alert_destination d ON d.workspace_id = q.workspace_id AND d.id = q.destination_id
                WHERE q.status = 'queued' AND q.next_attempt_at <= :now
                ORDER BY q.next_attempt_at, q.id LIMIT 50 FOR UPDATE OF q SKIP LOCKED
                """).param("now", Sql.ts(now)).query((rs, n) -> new Queued(Sql.uuid(rs, "delivery_id"),
                        Sql.uuid(rs, "delivery_ws"), Sql.uuid(rs, "incident_id"), rs.getString("delivery_kind"),
                        rs.getInt("attempts"), rs.getString("rule_name"), rs.getString("signal"),
                        rs.getString("severity"), rs.getInt("threshold"), rs.getInt("window_minutes"),
                        rs.getString("scope"), rs.getInt("value"), AlertService.destination(rs)))
                .list();
        for (Queued q : queued) {
            if (!q.destination().enabled()) {
                settle(q.id(), "skipped", q.attempts(), null, "destination_disabled", now);
                count(q.destination().kind(), "skipped");
                continue;
            }
            AlertChannels.Outcome outcome = channels.deliver(q.workspaceId(), AlertService.target(q.destination()),
                    new AlertChannels.Message("alert:" + q.incidentId() + ":" + q.destination().id() + ":" + q.kind(),
                            q.kind(), q.ruleName(), q.signal(), q.severity(), q.value(), q.threshold(),
                            q.windowMinutes(), q.scope(), now));
            int attempts = q.attempts() + 1;
            if (outcome.delivered()) {
                settle(q.id(), "sent", attempts, outcome.outcome(), null, now);
                count(q.destination().kind(), "sent");
            } else if (attempts >= MAX_ATTEMPTS) {
                settle(q.id(), "failed", attempts, null, outcome.errorCode(), now);
                count(q.destination().kind(), "failed");
            } else {
                jdbc.sql("""
                        UPDATE alert_delivery SET attempts = :attempts, error_code = :error, next_attempt_at = :next
                        WHERE id = :id
                        """).param("attempts", attempts).param("error", outcome.errorCode())
                        .param("next", Sql.ts(now.plus(Duration.ofMinutes(1L << attempts)))).param("id", q.id())
                        .update();
            }
        }
        return queued.size();
    }

    private void settle(UUID id, String status, int attempts, String outcome, String error, Instant now) {
        jdbc.sql("""
                UPDATE alert_delivery SET status = :status, attempts = :attempts, outcome = :outcome, error_code = :error,
                       settled_at = :now
                WHERE id = :id
                """).param("status", status).param("attempts", attempts).param("outcome", outcome)
                .param("error", error).param("now", Sql.ts(now)).param("id", id).update();
    }

    private void count(String kind, String status) {
        Counter.builder("orqix_alert_notifications").description("Alert notifications by destination kind and status")
                .tag("kind", kind).tag("status", status).register(meters).increment();
    }
}
