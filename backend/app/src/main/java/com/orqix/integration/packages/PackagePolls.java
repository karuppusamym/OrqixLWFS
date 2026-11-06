package com.orqix.integration.packages;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.orqix.integration.CallDeferredException;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.HttpExecutor;
import com.orqix.integration.health.CallGuard;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Polling triggers (CG-C05, docs/build/S133 section 5.2). Each due trigger is leased by one pod (claimed with SKIP
 * LOCKED, a lease and a fence), so exactly one poller is active across pods. A poll reads the records changed since the
 * stored watermark minus the overlap (clock skew), through the binding, the egress policy and the binding's circuit and
 * request limit, then handles each record in its own transaction: dedupe by key, start or message. The watermark moves
 * to the newest handled record only; a transient failure stops the poll at that record, so nothing is skipped. The
 * result is written back only while the claim still holds. Scheduling runs on worker and all-in-one pods only.
 */
@Component
@Profile("!migrate")
public class PackagePolls {

    private static final Logger LOG = LoggerFactory.getLogger(PackagePolls.class);
    static final int BATCH = 5;
    private static final DateTimeFormatter SERVICENOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcClient jdbc;
    private final PackageTriggerService triggers;
    private final ConnectionService connections;
    private final HttpExecutor executor;
    private final EgressPolicy egress;
    private final SecretStore secrets;
    private final CallGuard guard;
    private final PackageSettings settings;
    private final Json json;
    private final Clock clock;
    private final String owner = "pkg-poll-" + UUID.randomUUID().toString().substring(0, 8);

    public PackagePolls(JdbcClient jdbc, PackageTriggerService triggers, ConnectionService connections,
            HttpExecutor executor, EgressPolicy egress, SecretStore secrets, CallGuard guard, PackageSettings settings,
            Json json, Clock clock) {
        this.jdbc = jdbc;
        this.triggers = triggers;
        this.connections = connections;
        this.executor = executor;
        this.egress = egress;
        this.secrets = secrets;
        this.guard = guard;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.connector-packages.poll-tick:2s}",
            initialDelayString = "${orqix.connector-packages.poll-tick:2s}")
    public void tick() {
        try {
            runDue();
        } catch (RuntimeException e) {
            LOG.warn("Package trigger poll failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${orqix.connector-packages.purge-delay:1h}",
            initialDelayString = "${orqix.connector-packages.purge-delay:1h}")
    public void purge() {
        try {
            jdbc.sql("DELETE FROM package_trigger_record WHERE id IN (SELECT id FROM package_trigger_record"
                    + " WHERE received_at < :before LIMIT 1000)").param("before", Sql.ts(clock.instant()
                            .minus(settings.recordRetention()))).update();
        } catch (RuntimeException e) {
            LOG.warn("Package trigger record purge failed ({})", e.getClass().getSimpleName());
        }
    }

    private record Claim(UUID id, long fence) {
    }

    /** Claims and runs the due triggers; returns how many were polled. */
    public int runDue() {
        Instant now = clock.instant();
        List<Claim> claims = jdbc.sql("""
                UPDATE package_trigger SET lease_owner = :owner, lease_until = :until, fence = fence + 1
                WHERE id IN (SELECT id FROM package_trigger
                             WHERE kind = 'poll' AND enabled AND next_run_at <= :now
                               AND (lease_until IS NULL OR lease_until < :now)
                             ORDER BY next_run_at LIMIT :batch FOR UPDATE SKIP LOCKED)
                RETURNING id, fence
                """).param("owner", owner).param("until", Sql.ts(now.plus(settings.pollLease())))
                .param("now", Sql.ts(now)).param("batch", BATCH)
                .query((rs, n) -> new Claim(Sql.uuid(rs, "id"), rs.getLong("fence"))).list();
        for (Claim claim : claims) {
            try {
                poll(claim);
            } catch (RuntimeException e) {
                finish(claim, null, now.plusSeconds(60), "poll_failed", null, false);
            }
        }
        return claims.size();
    }

    private void poll(Claim claim) {
        PackageTriggerService.Trigger trigger = triggers.byId(claim.id()).orElse(null);
        if (trigger == null) {
            return;
        }
        JsonNode d = trigger.definition();
        Instant now = clock.instant();
        Duration every = Duration.parse(d.path("every").asString("PT2M"));
        ConnectionService.Binding binding = connections.binding(trigger.workspaceId(), trigger.connectionKey())
                .filter(ConnectionService.Binding::enabled).orElse(null);
        Integer version = jdbc.sql("""
                SELECT c.connection_version FROM connector_package_connection c JOIN package_trigger t
                ON t.package_version_id = c.package_version_id AND t.workspace_id = c.workspace_id
                WHERE t.id = :id AND c.connection_key = :key
                """).param("id", trigger.id()).param("key", trigger.connectionKey()).query(Integer.class).optional()
                .orElse(null);
        if (binding == null || version == null) {
            finish(claim, null, now.plus(every), "connection_not_bound", null, false);
            return;
        }
        ConnectionDefinition definition = connections.definition(trigger.workspaceId(), trigger.connectionKey(), version)
                .orElseThrow().definition();
        ConnectionDefinition.Operation operation = definition.operations().get(d.path("operation").asString());
        JsonNode mark = d.path("watermark");
        String format = mark.path("format").asString();
        String watermark = jdbc.sql("SELECT watermark FROM package_trigger WHERE id = :id").param("id", trigger.id())
                .query(String.class).optional().orElse(null);
        Instant from = watermark == null ? now : parse(watermark, format);
        String since = format(from.minus(Duration.parse(mark.path("overlap").asString("PT1M"))), format);
        ObjectNode input = json.object().put(mark.path("input").asString(), since);
        String secret = null;
        try {
            egress.check(binding.baseUrl());
            if (!"none".equals(binding.authType())) {
                secret = secrets.credential(binding.authType(), binding.secretRef(), binding.workspaceId()).orElse(null);
                if (secret == null) {
                    finish(claim, watermark == null ? format(now, format) : null, now.plus(every), "credential_missing",
                            null, false);
                    return;
                }
            }
        } catch (EgressPolicy.EgressDeniedException e) {
            finish(claim, null, now.plus(every), "egress_denied", null, false);
            return;
        }
        CallGuard.Ticket ticket;
        try {
            ticket = guard.before(trigger.workspaceId(), trigger.connectionKey(), operation.name(),
                    operation.maxTimeoutSeconds());
        } catch (CallDeferredException e) {
            finish(claim, null, now.plus(every), "deferred", null, false);
            return;
        }
        String items = d.path("itemsPointer").asString();
        HttpExecutor.CallResult call = executor.call(new HttpExecutor.Target(binding.baseUrl(), binding.authType(),
                binding.authHeaderName(), binding.secretRef(), binding.workspaceId()), definition, operation, input,
                Map.of("records", items + "/[*]"), "package-poll-" + trigger.id(), operation.maxTimeoutSeconds(), secret);
        guard.after(trigger.workspaceId(), trigger.connectionKey(), operation.name(), ticket, call);
        if (call.outcome() != HttpExecutor.Outcome.SUCCEEDED) {
            String code = call.errorCode() == null ? "poll_failed"
                    : call.errorCode().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z_]", "_");
            finish(claim, watermark == null ? format(now, format) : null, now.plus(every),
                    code.length() > 40 ? code.substring(0, 40) : code, null, false);
            return;
        }
        List<JsonNode> records = new ArrayList<>();
        call.result().path("records").forEach(records::add);
        int max = d.path("maxRecordsPerPoll").asInt(100);
        String newest = watermark == null ? format(now, format) : watermark;
        String field = mark.path("field").asString();
        boolean stopped = false;
        int handled = 0;
        for (JsonNode record : records) {
            if (handled >= max) {
                break;
            }
            PackageTriggerService.Outcome outcome = triggers.handle(trigger, record, Json.sha256Hex(json.write(record)));
            if ("retry".equals(outcome.status())) {
                stopped = true;
                break;
            }
            handled++;
            String value = record.at(field).asString(null);
            if (value != null && parsable(value, format) && parse(value, format).isAfter(parse(newest, format))) {
                newest = value;
            }
        }
        Long lag = records.isEmpty() ? 0L : Math.max(0, Duration.between(parse(newest, format), now).toSeconds());
        // A full page or a stop leaves more to read: poll again soon instead of after a whole interval.
        Instant next = stopped ? now.plusSeconds(30) : handled >= max ? now : now.plus(every);
        finish(claim, newest, next, stopped ? "retrying" : null, lag, !stopped);
    }

    /** Writes the poll result while the claim still holds (same owner and fence); a lost lease records nothing. */
    private void finish(Claim claim, String watermark, Instant next, String error, Long lag, boolean success) {
        jdbc.sql("""
                UPDATE package_trigger SET watermark = coalesce(:mark, watermark), next_run_at = :next, lease_owner = NULL,
                       lease_until = NULL, last_run_at = now(), polls = polls + 1, last_error_code = :error,
                       last_lag_seconds = coalesce(:lag, last_lag_seconds),
                       last_success_at = CASE WHEN :success THEN now() ELSE last_success_at END
                WHERE id = :id AND fence = :fence AND lease_owner = :owner
                """).param("mark", watermark).param("next", Sql.ts(next)).param("error", error).param("lag", lag)
                .param("success", success).param("id", claim.id()).param("fence", claim.fence())
                .param("owner", owner).update();
    }

    static String format(Instant instant, String format) {
        return "servicenowDateTime".equals(format) ? SERVICENOW.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC))
                : instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    static Instant parse(String value, String format) {
        return "servicenowDateTime".equals(format) ? LocalDateTime.parse(value, SERVICENOW).toInstant(ZoneOffset.UTC)
                : Instant.parse(value);
    }

    private static boolean parsable(String value, String format) {
        try {
            parse(value, format);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
