package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.PollSchedule;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.integration.ConnectionService;
import com.orqix.integration.EffectDispatcher;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Keep checking until..." (CG-W01) and the safety-net check of a message wait (CG-W03), docs/build/S129.
 *
 * <p>One {@code poll_wait} row per step occurrence. Entering the step inserts the row in the engine transaction. The
 * scheduler ({@link PollScheduler}) claims due rows with {@code FOR UPDATE SKIP LOCKED}, takes a lease and raises the
 * fence (transaction 1), performs the read-only check through {@link EffectDispatcher} outside any lock (one effect
 * journal entry per check, occurrence = check number), and records the outcome only while it still holds that fence
 * (transaction 2). Lock order in every writer: the request row, then the message subscription, then the poll row.
 *
 * <p>After downtime a due row gets exactly one check: the next check is always computed from the time of the check
 * that just ran, never from the missed schedule. A deadline gets one final check (when the step asks for it) and then
 * the timedOut route.
 */
@Service
public class PollWaits {

    public static final String CHECK = "check";
    public static final String FALLBACK = "fallback";
    /** Operator "Check now" is accepted at most once a minute per wait. */
    static final Duration MANUAL_CHECK_SPACING = Duration.ofMinutes(1);
    /** Lease beyond the call timeout for the journal writes and the outcome transaction. */
    static final Duration LEASE_MARGIN = Duration.ofSeconds(30);
    /** Rows claimed per scheduler tick. */
    static final int BATCH = 20;

    /** The outcome variable the generated route gateway reads ({@code orqixRoute.polled}). */
    public static String outcomeVariable(String nodeId) {
        return "orqix_poll_" + nodeId;
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final RunStore runs;
    private final RunEvents events;
    private final ReleaseQueries releases;
    private final EngineCommands engine;
    private final EffectDispatcher dispatcher;
    private final ConnectionService connections;
    private final PolicyService policy;
    private final RunAccess runAccess;
    private final AuditWriter audit;
    private final RunSuspensions suspensions;
    private final MessageStore messages;
    private final MessageDelivery delivery;
    private final MessageSettings messageSettings;
    private final OrqixProperties properties;
    private final TransactionTemplate tx;
    private final com.orqix.shared.idempotency.IdempotencyService idempotency;

    public PollWaits(JdbcClient jdbc, Json json, Clock clock, RunStore runs, RunEvents events, ReleaseQueries releases,
            EngineCommands engine, EffectDispatcher dispatcher, ConnectionService connections, PolicyService policy,
            RunAccess runAccess, AuditWriter audit, RunSuspensions suspensions, MessageStore messages,
            @org.springframework.context.annotation.Lazy MessageDelivery delivery, MessageSettings messageSettings,
            OrqixProperties properties, PlatformTransactionManager transactions,
            com.orqix.shared.idempotency.IdempotencyService idempotency) {
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.runs = runs;
        this.events = events;
        this.releases = releases;
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.connections = connections;
        this.policy = policy;
        this.runAccess = runAccess;
        this.audit = audit;
        this.suspensions = suspensions;
        this.messages = messages;
        this.delivery = delivery;
        this.messageSettings = messageSettings;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ settings

    /** The settings of one wait, read from the pinned release (the definition is immutable). */
    record Settings(JsonNode until, JsonNode failWhen, Map<String, String> resultMapping, PollSchedule schedule,
            int jitterPercent, int maxConsecutiveErrors, boolean errorsTimeOut, List<String> shown, String label,
            boolean failedRoute) {
    }

    private Settings settings(UUID workspaceId, UUID releaseId, String nodeId, String kind) {
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, releaseId);
        JsonNode node = release.node(nodeId);
        String label = node.path("label").asString(nodeId);
        boolean failedRoute = false;
        for (JsonNode t : release.definition().path("transitions")) {
            if (nodeId.equals(t.path("from").asString()) && "failed".equals(t.path("on").asString())) {
                failedRoute = true;
            }
        }
        if (FALLBACK.equals(kind)) {
            JsonNode f = node.path("config").path("fallbackCheck");
            long every = PollSchedule.seconds(f.path("every").asString());
            String limit = node.path("config").path("timeoutAfter").asString(null);
            long deadline = limit == null ? PollSchedule.MAX_DEADLINE : Duration.parse(limit).getSeconds();
            int worst = PollSchedule.worstCase(every, BigDecimal.ONE, every, deadline, false, false);
            int max = Math.min(PollSchedule.CHECK_LIMIT, f.has("maxChecks") ? Math.min(worst, f.path("maxChecks").asInt()) : worst);
            return new Settings(f.path("until"), null, stringMap(f.path("resultMapping")),
                    new PollSchedule(every, BigDecimal.ONE, every, deadline, false, false, Math.max(1, max)),
                    f.path("jitterPercent").asInt(10), Integer.MAX_VALUE, false, List.of(), label, false);
        }
        JsonNode p = node.path("config").path("poll");
        long every = PollSchedule.seconds(p.path("every").asString());
        BigDecimal multiplier = p.path("backoff").isObject() ? p.path("backoff").path("multiplier").decimalValue() : BigDecimal.ONE;
        long cap = p.path("backoff").isObject() ? PollSchedule.seconds(p.path("backoff").path("maxInterval").asString()) : every;
        long deadline = PollSchedule.seconds(p.path("giveUpAfter").asString());
        boolean immediate = !"afterInterval".equals(p.path("firstCheck").asString("immediate"));
        boolean finalCheck = p.path("finalCheckAtDeadline").asBoolean(true);
        int worst = PollSchedule.worstCase(every, multiplier, cap, deadline, immediate, finalCheck);
        int max = Math.min(PollSchedule.CHECK_LIMIT, p.has("maxChecks") ? Math.min(worst, p.path("maxChecks").asInt()) : worst);
        List<String> shown = new ArrayList<>();
        p.path("showWhileWaiting").forEach(v -> shown.add(v.asString()));
        Map<String, String> mapping = stringMap(node.path("config").path("resultMapping"));
        // Results that may be missing in an answer are passed as optional mappings (missing = still pending).
        p.path("optionalResults").forEach(v -> mapping.computeIfPresent(v.asString(),
                (key, pointer) -> com.orqix.integration.ResultMappings.optional(pointer)));
        return new Settings(p.path("until"), p.has("failWhen") ? p.path("failWhen") : null, mapping,
                new PollSchedule(every, multiplier, cap, deadline, immediate, finalCheck, Math.max(1, max)),
                p.path("jitterPercent").asInt(10), p.path("onCheckError").path("maxConsecutive").asInt(5),
                "timedOut".equals(p.path("onCheckError").path("then").asString("needsAttention")), List.copyOf(shown),
                label, failedRoute);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        node.properties().forEach(e -> map.put(e.getKey(), e.getValue().asString()));
        return map;
    }

    // ------------------------------------------------------------------ start (engine transaction)

    /**
     * A request reached a "Keep checking until..." step. A repeated start of the same occurrence does nothing.
     *
     * @param resolver resolves a field reference of the request at this moment (for condition values)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startCheck(UUID workspaceId, UUID runId, String nodeId, String connectionKey, int connectionVersion,
            String operation, int timeoutSeconds, ObjectNode request, Function<String, JsonNode> resolver) {
        RunStore.RunRow run = runs.find(workspaceId, runId).orElseThrow();
        Settings s = settings(workspaceId, run.releaseId(), nodeId, CHECK);
        ObjectNode context = context(s, nodeId, resolver);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant deadline = now.plusSeconds(s.schedule().deadline());
        Instant first = now.plusSeconds(s.schedule().firstCheckOffset());
        UUID id = Ids.newId();
        if (!insert(id, run, nodeId, CHECK, connectionKey, connectionVersion, operation, timeoutSeconds, request, context,
                s.schedule().maxChecks(), now, first, deadline)) {
            return;
        }
        String system = systemName(workspaceId, connectionKey, connectionVersion);
        runs.mutate(workspaceId, runId, m -> {
            m.removeStep(nodeId);
            ObjectNode step = json.object();
            step.put("nodeId", nodeId).put("label", s.label()).put("kind", "http").put("since", now.toString());
            step.putNull("candidateGroup").putNull("assigneeId").put("dueAt", deadline.toString());
            step.put("polling", true).put("pollWaitId", id.toString()).put("nextCheckAt", first.toString())
                    .put("checks", 0).put("connectionKey", connectionKey).put("subject", subject(system, s.label(), null));
            m.addStep(step);
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connectionKey", connectionKey);
        data.put("operation", operation);
        data.put("deadline", deadline.toString());
        data.put("plannedChecks", s.schedule().maxChecks());
        events.append(workspaceId, runId, "POLL_STARTED", nodeId, null, data);
    }

    /**
     * A message wait started ({@link MessageWaits#started}): marks a wait answered by an external system (S98) for the
     * "Waiting for" panel and starts its safety-net check (CG-W03) when the step has one.
     *
     * @param resolver request values at this moment, or null when the caller has none (no safety net then)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void waitStarted(RunStore.RunRow run, String nodeId, Function<String, JsonNode> resolver) {
        JsonNode node = releases.require(run.workspaceId(), run.releaseId()).node(nodeId);
        if (node == null) {
            return;
        }
        if (node.path("config").path("externalCallback").isObject()) {
            runs.mutate(run.workspaceId(), run.id(), m -> m.updateStep(nodeId, step -> step.put("callback", true)));
        }
        if (resolver != null && node.path("config").path("fallbackCheck").isObject()) {
            startFallback(run, nodeId, resolver);
        }
    }

    /** S129 CG-W03: a message wait with a safety-net check started. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startFallback(RunStore.RunRow run, String nodeId, Function<String, JsonNode> resolver) {
        JsonNode node = releases.require(run.workspaceId(), run.releaseId()).node(nodeId);
        JsonNode f = node == null ? null : node.path("config").path("fallbackCheck");
        if (f == null || !f.isObject()) {
            return;
        }
        Settings s = settings(run.workspaceId(), run.releaseId(), nodeId, FALLBACK);
        ObjectNode request = json.object();
        for (Map.Entry<String, JsonNode> e : f.path("inputMapping").properties()) {
            JsonNode value = resolver.apply(e.getValue().asString());
            if (value == null || value.isMissingNode() || value.isNull()) {
                return; // the compiler guarantees the inputs; an old release without them simply has no safety net
            }
            request.set(e.getKey(), value);
        }
        int version = 1;
        for (JsonNode c : releases.require(run.workspaceId(), run.releaseId()).definition().path("dependencies")
                .path("connections")) {
            if (c.path("key").asString().equals(f.path("connectionRef").asString())) {
                version = c.path("definitionVersion").asInt();
            }
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant deadline = now.plusSeconds(s.schedule().deadline());
        Instant first = now.plusSeconds(s.schedule().firstCheckOffset());
        UUID id = Ids.newId();
        if (!insert(id, run, nodeId, FALLBACK, f.path("connectionRef").asString(), version, f.path("operation").asString(),
                f.path("timeoutSeconds").asInt(10), request, context(s, nodeId, resolver), s.schedule().maxChecks(), now,
                first, deadline)) {
            return;
        }
        runs.mutate(run.workspaceId(), run.id(), m -> m.updateStep(nodeId, step -> step.put("pollWaitId", id.toString())
                .put("nextCheckAt", first.toString()).put("checks", 0)
                .put("connectionKey", f.path("connectionRef").asString())));
        events.append(run.workspaceId(), run.id(), "POLL_STARTED", nodeId, null, Map.of("connectionKey",
                f.path("connectionRef").asString(), "operation", f.path("operation").asString(), "fallback", true,
                "deadline", deadline.toString()));
    }

    private ObjectNode context(Settings s, String nodeId, Function<String, JsonNode> resolver) {
        ObjectNode context = json.object();
        java.util.Set<String> refs = new java.util.LinkedHashSet<>(PollConditions.foreignRefs(s.until(), own(nodeId)));
        refs.addAll(PollConditions.foreignRefs(s.failWhen(), own(nodeId)));
        for (String ref : refs) {
            JsonNode value = resolver.apply(ref);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                context.set(ref, value);
            }
        }
        return context;
    }

    private static String own(String nodeId) {
        return "/steps/" + nodeId + "/";
    }

    private boolean insert(UUID id, RunStore.RunRow run, String nodeId, String kind, String connectionKey,
            int connectionVersion, String operation, int timeoutSeconds, ObjectNode request, ObjectNode context,
            int maxChecks, Instant now, Instant first, Instant deadline) {
        return jdbc.sql("""
                INSERT INTO poll_wait (id, workspace_id, run_id, node_id, kind, connection_key, connection_version, operation,
                                       timeout_seconds, request, context, state, max_checks, started_at, next_check_at,
                                       deadline_at, authored_deadline_at, created_at)
                VALUES (:id, :ws, :run, :node, :kind, :conn, :version, :op, :timeout, CAST(:request AS jsonb),
                        CAST(:context AS jsonb), 'waiting', :max, :now, :first, :deadline, :deadline, :now)
                ON CONFLICT (workspace_id, run_id, node_id, occurrence) DO NOTHING
                """).param("id", id).param("ws", run.workspaceId()).param("run", run.id()).param("node", nodeId)
                .param("kind", kind).param("conn", connectionKey).param("version", connectionVersion).param("op", operation)
                .param("timeout", timeoutSeconds).param("request", json.write(request)).param("context", json.write(context))
                .param("max", maxChecks).param("now", Sql.ts(now)).param("first", Sql.ts(first))
                .param("deadline", Sql.ts(deadline)).update() > 0;
    }

    // ------------------------------------------------------------------ the scheduler

    /** A claimed row: the fence proves that the outcome belongs to this claim. */
    record Claim(Row row, String owner) {
    }

    /** Claims due rows and checks them one after the other. @return the number of rows claimed */
    public int runDue() {
        List<Claim> claims = tx.execute(status -> claim(BATCH));
        for (Claim claim : claims == null ? List.<Claim>of() : claims) {
            try {
                check(claim);
            } catch (RuntimeException e) {
                // The lease runs out and the row is claimed again; the effect journal reuses a finished check.
                org.slf4j.LoggerFactory.getLogger(PollWaits.class).warn("Poll check {} failed ({}); it is retried after its lease",
                        claim.row().id(), e.getClass().getSimpleName());
            }
        }
        return claims == null ? 0 : claims.size();
    }

    List<Claim> claim(int limit) {
        Instant now = clock.instant();
        String owner = properties.runtime().lockOwner() + ":" + UUID.randomUUID().toString().substring(0, 8);
        List<Row> rows = jdbc.sql("""
                WITH picked AS (
                    SELECT p.id FROM poll_wait p
                    WHERE p.next_check_at <= :now
                      AND (p.state = 'waiting' OR (p.state = 'checking' AND p.lease_until < :now))
                      AND NOT EXISTS (SELECT 1 FROM run_suspension s WHERE s.run_id = p.run_id)
                    ORDER BY p.next_check_at, p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED)
                UPDATE poll_wait p SET state = 'checking', lease_owner = :owner,
                       lease_until = CAST(:now AS timestamptz) + make_interval(secs => p.timeout_seconds + :margin),
                       fence = p.fence + 1, version = p.version + 1
                FROM picked WHERE p.id = picked.id
                RETURNING p.*
                """).param("now", Sql.ts(now)).param("limit", limit).param("owner", owner)
                .param("margin", (int) LEASE_MARGIN.getSeconds()).query(this::row).list();
        return rows.stream().map(r -> new Claim(r, owner)).toList();
    }

    /** What one check found. */
    enum Outcome { DONE, FAILED, PENDING, ERROR, REJECTED, DEADLINE, UNAVAILABLE, BUSY, DEFERRED }

    /**
     * @param retryAfter    the server's {@code Retry-After} of a retryable failure (429, 503), else null
     * @param deferredUntil when an open circuit or a used-up request rate lets the call run again (CG-W04), else null
     */
    record Checked(Outcome outcome, ObjectNode result, Integer httpStatus, String code, String message,
            Duration retryAfter, Instant deferredUntil) {

        Checked(Outcome outcome, ObjectNode result, Integer httpStatus, String code, String message) {
            this(outcome, result, httpStatus, code, message, null, null);
        }
    }

    /** Performs one claimed check outside any transaction, then records it. */
    void check(Claim claim) {
        Row row = claim.row();
        RunStore.RunRow run = runs.find(row.workspaceId(), row.runId()).orElse(null);
        if (run == null || !run.isActive()) {
            tx.executeWithoutResult(status -> record(claim, new Checked(Outcome.DEADLINE, null, null, "REQUEST_ENDED", null)));
            return;
        }
        Settings s = settings(row.workspaceId(), run.releaseId(), row.nodeId(), row.kind());
        Instant now = clock.instant();
        boolean pastDeadline = !now.isBefore(row.deadlineAt());
        Checked checked;
        if (pastDeadline && (!s.schedule().finalCheck() || row.finalCheckDone())) {
            checked = new Checked(Outcome.DEADLINE, null, null, "DEADLINE", null);
        } else {
            checked = call(row, run, s);
        }
        tx.executeWithoutResult(status -> record(claim, checked));
    }

    private Checked call(Row row, RunStore.RunRow run, Settings s) {
        int occurrence = row.checksDone() + 1;
        EffectDispatcher.DispatchResult result;
        try {
            result = dispatcher.dispatch(new EffectDispatcher.DispatchRequest(row.workspaceId(),
                    row.runId(), row.nodeId(), occurrence, row.connectionKey(), row.connectionVersion(), row.operation(),
                    (ObjectNode) row.request(), s.resultMapping(), row.timeoutSeconds(), properties.runtime().lockOwner(),
                    run.initiator()));
        } catch (com.orqix.integration.CallDeferredException deferred) {
            // CG-W04: the binding's circuit is open or its request rate is used up. Nothing was sent or registered,
            // so this is neither a check nor an error: the check waits until the call may run.
            return new Checked(Outcome.DEFERRED, null, null, deferred.reason(), null, null, deferred.until());
        }
        if (result instanceof EffectDispatcher.Succeeded ok) {
            Function<String, JsonNode> values = ref -> ref.startsWith(own(row.nodeId()))
                    ? ok.result().path(ref.substring(own(row.nodeId()).length()))
                    : row.context().path(ref);
            boolean done = PollConditions.holds(s.until(), values);
            boolean failed = !done && s.failWhen() != null && PollConditions.holds(s.failWhen(), values);
            return new Checked(done ? Outcome.DONE : failed ? Outcome.FAILED : Outcome.PENDING, ok.result(),
                    ok.httpStatus(), done ? "done" : failed ? "failed" : "pending", null);
        }
        EffectDispatcher.Failed failed = (EffectDispatcher.Failed) result;
        if ("BUSY".equals(failed.errorCode())) {
            return new Checked(Outcome.BUSY, null, null, "BUSY", null);
        }
        if (failed.retryable()) {
            return new Checked(Outcome.ERROR, null, failed.httpStatus(), failed.errorCode(), failed.businessMessage(),
                    failed.retryAfter(), null);
        }
        if ("CONNECTION_UNAVAILABLE".equals(failed.attentionReason())) {
            return new Checked(Outcome.UNAVAILABLE, null, null, failed.errorCode(), failed.businessMessage());
        }
        return new Checked(Outcome.REJECTED, null, failed.httpStatus(), failed.errorCode(), failed.businessMessage());
    }

    /** Transaction 2: applies a check's outcome while the claim still holds the fence. */
    void record(Claim claim, Checked checked) {
        Row claimed = claim.row();
        RunStore.RunRow run = runs.lock(claimed.workspaceId(), claimed.runId()).orElse(null);
        Row row = jdbc.sql("SELECT * FROM poll_wait WHERE id = :id AND fence = :fence AND state = 'checking' AND lease_owner = :owner FOR UPDATE")
                .param("id", claimed.id()).param("fence", claimed.fence()).param("owner", claim.owner())
                .query(this::row).optional().orElse(null);
        if (row == null) {
            return; // the lease was lost: another claim decides
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (run == null || !run.isActive()) {
            end(row, "cancelled", "requestEnded", now);
            return;
        }
        Settings s = settings(row.workspaceId(), run.releaseId(), row.nodeId(), row.kind());
        boolean ranCheck = checked.outcome() != Outcome.DEADLINE && checked.outcome() != Outcome.BUSY
                && checked.outcome() != Outcome.DEFERRED;
        int checks = row.checksDone() + (ranCheck ? 1 : 0);
        boolean finalCheck = ranCheck && !now.isBefore(row.deadlineAt());
        String digest = checked.result() == null ? row.lastResultDigest() : json.canonicalSha256(checked.result());
        ObjectNode shown = shown(s, checked.result(), row.shown());
        if (ranCheck) {
            jdbc.sql("""
                    UPDATE poll_wait SET checks_done = :checks, last_checked_at = :now, last_http_status = :status,
                           last_outcome = :outcome, last_result_digest = :digest, shown = CAST(:shown AS jsonb),
                           final_check_done = final_check_done OR :final, version = version + 1
                    WHERE id = :id
                    """).param("checks", checks).param("now", Sql.ts(now)).param("status", checked.httpStatus())
                    .param("outcome", checked.code() == null ? null : truncate(checked.code(), 40))
                    .param("digest", digest).param("shown", json.write(shown)).param("final", finalCheck)
                    .param("id", row.id()).update();
            if (!shown.equals(row.shown()) && !s.shown().isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("check", checks);
                data.put("from", row.shown().isEmpty() ? null : json.toTree(row.shown()));
                data.put("to", json.toTree(shown));
                events.append(row.workspaceId(), row.runId(), "POLL_VALUE_CHANGED", row.nodeId(), null, data);
            }
        }
        switch (checked.outcome()) {
            case DONE -> finish(row, run, "met", checks, checked.result(), now);
            case FAILED -> finish(row, run, "failed", checks, checked.result(), now);
            case REJECTED -> {
                if (s.failedRoute() && CHECK.equals(row.kind())) {
                    finish(row, run, "failed", checks, null, now);
                } else {
                    attention(row, run, s, checks, "EFFECT_REJECTED", checked.message(), now);
                }
            }
            case UNAVAILABLE -> attention(row, run, s, checks, "CONNECTION_UNAVAILABLE", checked.message(), now);
            case DEADLINE -> {
                if ("REQUEST_ENDED".equals(checked.code())) {
                    end(row, "cancelled", "requestEnded", now);
                } else {
                    timedOut(row, run, checks, "deadline", now);
                }
            }
            case BUSY -> reschedule(row, s, checks, row.consecutiveErrors(), now, now.plusSeconds(5));
            case DEFERRED -> deferred(row, run, s, checks, checked, now);
            case PENDING -> pendingOrGiveUp(row, run, s, checks, 0, finalCheck, now, null);
            case ERROR -> {
                int errors = row.consecutiveErrors() + 1;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("check", checks);
                data.put("httpStatus", checked.httpStatus());
                data.put("errorCode", checked.code());
                data.put("consecutiveErrors", errors);
                events.append(row.workspaceId(), row.runId(), "POLL_CHECK_FAILED", row.nodeId(), null, data);
                if (errors >= s.maxConsecutiveErrors()) {
                    if (s.errorsTimeOut()) {
                        timedOut(row, run, checks, "checkErrors", now);
                    } else {
                        attention(row, run, s, checks, "RETRIES_EXHAUSTED", "The checks of " + q(s.label())
                                + " failed " + errors + " times in a row. Check the other system, then use Check now or Retry.", now);
                    }
                    return;
                }
                pendingOrGiveUp(row, run, s, checks, errors, finalCheck, now, checked.retryAfter());
            }
        }
    }

    /**
     * The call was deferred by the binding's circuit or rate limit: the next check is at the end of the wait, capped
     * at the deadline, with the checks and consecutive errors unchanged. At or after the deadline the request gives
     * up (the other system cannot be called for a final check).
     */
    private void deferred(Row row, RunStore.RunRow run, Settings s, int checks, Checked checked, Instant now) {
        if (!now.isBefore(row.deadlineAt())) {
            if (FALLBACK.equals(row.kind())) {
                end(row, "timedOut", "deadline", now);
                clearStepPolling(row);
            } else {
                timedOut(row, run, checks, "deadline", now);
            }
            return;
        }
        Instant until = checked.deferredUntil() == null || !checked.deferredUntil().isAfter(now) ? now.plusSeconds(1)
                : checked.deferredUntil();
        Instant next = until.isBefore(row.deadlineAt()) ? until : row.deadlineAt();
        if (!"deferred".equals(row.lastOutcome())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", checked.code());
            data.put("until", next.toString());
            events.append(row.workspaceId(), row.runId(), "POLL_CHECK_DEFERRED", row.nodeId(), null, data);
        }
        jdbc.sql("UPDATE poll_wait SET last_outcome = 'deferred' WHERE id = :id").param("id", row.id()).update();
        reschedule(row, s, checks, row.consecutiveErrors(), now, next);
    }

    /** @param retryAfter the server's Retry-After of the failed check, or null */
    private void pendingOrGiveUp(Row row, RunStore.RunRow run, Settings s, int checks, int errors, boolean finalCheck,
            Instant now, Duration retryAfter) {
        if (finalCheck || checks >= row.maxChecks()) {
            if (FALLBACK.equals(row.kind())) {
                // The safety net stops; the message may still come or the step's own time limit decides.
                end(row, "timedOut", "checksUsedUp", now);
                clearStepPolling(row);
                return;
            }
            timedOut(row, run, checks, finalCheck ? "deadline" : "maxChecks", now);
            return;
        }
        long interval = s.schedule().intervalAfter(checks);
        Instant next = now.plusSeconds(jitter(interval, s.jitterPercent()));
        if (retryAfter != null && retryAfter.getSeconds() > 0) {
            // CG-W04: never sooner than the server asked, never later than the longest interval (then the deadline cap).
            long wait = Math.min(s.schedule().maxInterval(), Math.max(interval, retryAfter.getSeconds()));
            next = now.plusSeconds(wait);
        }
        if (!next.isBefore(row.deadlineAt())) {
            if (!s.schedule().finalCheck() && FALLBACK.equals(row.kind())) {
                end(row, "timedOut", "deadline", now);
                clearStepPolling(row);
                return;
            }
            next = row.deadlineAt();
        }
        reschedule(row, s, checks, errors, now, next);
    }

    private void reschedule(Row row, Settings s, int checks, int errors, Instant now, Instant next) {
        jdbc.sql("""
                UPDATE poll_wait SET state = 'waiting', next_check_at = :next, consecutive_errors = :errors,
                       lease_owner = NULL, lease_until = NULL, version = version + 1
                WHERE id = :id
                """).param("next", Sql.ts(next)).param("errors", errors).param("id", row.id()).update();
        updateStep(row, next, checks);
    }

    private void updateStep(Row row, Instant next, int checks) {
        ObjectNode shown = jdbc.sql("SELECT shown::text FROM poll_wait WHERE id = :id").param("id", row.id())
                .query(String.class).optional().map(text -> (ObjectNode) json.parse(text)).orElse(json.object());
        runs.mutate(row.workspaceId(), row.runId(), m -> m.updateStep(row.nodeId(), step -> {
            step.put("nextCheckAt", next == null ? null : next.toString()).put("checks", checks);
            if (CHECK.equals(row.kind())) {
                step.put("subject", subject(systemName(row.workspaceId(), row.connectionKey(), row.connectionVersion()),
                        step.path("label").asString(row.nodeId()), shown));
            }
        }));
    }

    private void clearStepPolling(Row row) {
        runs.mutate(row.workspaceId(), row.runId(), m -> m.updateStep(row.nodeId(), step -> {
            step.remove("pollWaitId");
            step.remove("nextCheckAt");
        }));
    }

    private static long jitter(long seconds, int percent) {
        if (percent <= 0 || seconds <= 1) {
            return seconds;
        }
        double spread = seconds * percent / 100.0;
        return Math.max(1, Math.round(seconds + ThreadLocalRandom.current().nextDouble(-spread, spread)));
    }

    /** The work is done ({@code met}) or failed for good ({@code failed}): the request continues on that route. */
    private void finish(Row row, RunStore.RunRow run, String outcome, int checks, ObjectNode result, Instant now) {
        if (FALLBACK.equals(row.kind())) {
            if ("met".equals(outcome)) {
                resumeWait(row, run, result, checks, now);
            }
            return;
        }
        ObjectNode outputs = result == null ? json.object() : result.deepCopy();
        if ("failed".equals(outcome)) {
            outputs.put("failureKind", result == null ? "rejected" : "remoteFailed");
            outputs.put("failureHttpStatus", row.lastHttpStatus() == null ? 0 : row.lastHttpStatus());
            outputs.put("failureMessage", result == null ? "The external system rejected the check."
                    : "The other system reports that the work failed.");
        }
        trigger(row, run, outcome, outputs, checks, now);
    }

    private void timedOut(Row row, RunStore.RunRow run, int checks, String why, Instant now) {
        if (FALLBACK.equals(row.kind())) {
            end(row, "timedOut", why, now);
            clearStepPolling(row);
            return;
        }
        trigger(row, run, "timedOut", json.object(), checks, now, why);
    }

    private void trigger(Row row, RunStore.RunRow run, String outcome, ObjectNode outputs, int checks, Instant now) {
        trigger(row, run, outcome, outputs, checks, now, null);
    }

    private void trigger(Row row, RunStore.RunRow run, String outcome, ObjectNode outputs, int checks, Instant now,
            String why) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(outcomeVariable(row.nodeId()), outcome);
        if (!"timedOut".equals(outcome)) {
            variables.put(ProcessData.stepVariable(row.nodeId()), json.write(outputs));
        }
        try {
            engine.resumeChild(run.engineProcessInstanceId(), row.nodeId(), variables);
        } catch (IllegalStateException e) {
            // The step is no longer active (a section around it ended); nothing is waiting for this check any more.
            end(row, "cancelled", "stepEnded", now);
            return;
        }
        end(row, outcome, why == null ? outcome : why, now);
        runs.mutate(row.workspaceId(), row.runId(), m -> {
            m.removeStep(row.nodeId());
            m.settle();
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", outcome);
        data.put("checks", checks);
        if (why != null) {
            data.put("reason", why);
        }
        events.append(row.workspaceId(), row.runId(), "POLL_ENDED", row.nodeId(), null, data);
    }

    /** CG-W03: the safety-net check found the work done: complete the message wait as if the message had come. */
    private void resumeWait(Row row, RunStore.RunRow run, ObjectNode result, int checks, Instant now) {
        MessageStore.Subscription subscription = messages.findForStep(row.workspaceId(), row.runId(), row.nodeId())
                .filter(sub -> MessageStore.WAITING.equals(sub.status())).orElse(null);
        if (subscription == null) {
            end(row, "cancelled", "answeredElsewhere", now);
            return;
        }
        UUID messageId = Ids.newId();
        messages.insertInbound(messageId, row.workspaceId(), subscription.messageName(), subscription.correlationKey(),
                result, null, now, now.plus(messageSettings.bufferTtl()));
        // Close the poll row first so the delivery's own close of the safety net finds nothing left to do.
        end(row, "met", "met", now);
        MessageDelivery.Outcome outcome = delivery.deliver(subscription, messageId, result, false, null);
        if (outcome.status() == MessageDelivery.Outcome.Status.DELIVERED) {
            messages.closeInbound(row.workspaceId(), messageId, MessageStore.DELIVERED, 1, now);
            events.append(row.workspaceId(), row.runId(), "POLL_ENDED", row.nodeId(), null,
                    Map.of("outcome", "met", "checks", checks, "fallback", true));
        } else {
            messages.closeInbound(row.workspaceId(), messageId, MessageStore.DISCARDED, 0, now);
            jdbc.sql("UPDATE poll_wait SET state = 'cancelled', end_reason = :why WHERE id = :id")
                    .param("why", outcome.status() == MessageDelivery.Outcome.Status.INVALID ? "invalidAnswer" : "answeredElsewhere")
                    .param("id", row.id()).update();
        }
    }

    /** Ends the row and clears the request values it held. */
    private void end(Row row, String state, String reason, Instant now) {
        jdbc.sql("""
                UPDATE poll_wait SET state = :state, end_reason = :reason, ended_at = :now, lease_owner = NULL,
                       lease_until = NULL, next_check_at = NULL, request = '{}'::jsonb, context = '{}'::jsonb,
                       version = version + 1
                WHERE id = :id
                """).param("state", state).param("reason", reason).param("now", Sql.ts(now)).param("id", row.id()).update();
    }

    private void attention(Row row, RunStore.RunRow run, Settings s, int checks, String reason, String message,
            Instant now) {
        if (FALLBACK.equals(row.kind())) {
            // A failing safety net never stops the request: the message can still arrive.
            end(row, "cancelled", reason, now);
            clearStepPolling(row);
            events.append(row.workspaceId(), row.runId(), "POLL_ENDED", row.nodeId(), null,
                    Map.of("outcome", "stopped", "reason", reason, "fallback", true));
            return;
        }
        jdbc.sql("""
                UPDATE poll_wait SET state = 'attention', next_check_at = NULL, lease_owner = NULL, lease_until = NULL,
                       consecutive_errors = 0, version = version + 1
                WHERE id = :id
                """).param("id", row.id()).update();
        ObjectNode attention = json.object();
        attention.put("reason", reason);
        attention.put("nodeId", row.nodeId());
        attention.putNull("effectId");
        attention.put("message", message == null ? "The checks of " + q(s.label()) + " stopped. An operator must check"
                + " the other system, then use Check now or Retry." : message);
        attention.put("since", now.toString());
        runs.mutate(row.workspaceId(), row.runId(), m -> {
            m.lifecycle = RunStore.NEEDS_ATTENTION;
            m.attention = attention;
            m.updateStep(row.nodeId(), step -> step.putNull("nextCheckAt").put("checks", checks));
        });
        events.append(row.workspaceId(), row.runId(), "RUN_NEEDS_ATTENTION", row.nodeId(), null, Map.of("reason", reason));
    }

    /** Rows deleted per purge batch; at most 20 batches per pass. */
    static final int PURGE_BATCH = 500;

    /**
     * Deletes ended rows whose end is older than the retention as of {@code now} (also called by tests), in batches of
     * 500 with SKIP LOCKED, with one audit entry per workspace and batch. A row a "Waiting for" entry still points to
     * is kept. @return the number of rows deleted
     */
    public int purge(Instant now, Duration retention) {
        Instant before = now.minus(retention);
        int total = 0;
        for (int batch = 0; batch < 20; batch++) {
            Integer deleted = tx.execute(status -> {
                List<UUID> workspaces = jdbc.sql("""
                        WITH doomed AS (
                            SELECT p.id FROM poll_wait p
                            WHERE p.ended_at IS NOT NULL AND p.ended_at < :before
                              AND NOT EXISTS (SELECT 1 FROM wait_state w WHERE w.poll_wait_id = p.id)
                            ORDER BY p.ended_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                        DELETE FROM poll_wait p USING doomed d WHERE p.id = d.id RETURNING p.workspace_id
                        """).param("before", Sql.ts(before)).param("limit", PURGE_BATCH)
                        .query((rs, n) -> Sql.uuid(rs, "workspace_id")).list();
                Map<UUID, Integer> counts = new LinkedHashMap<>();
                workspaces.forEach(ws -> counts.merge(ws, 1, Integer::sum));
                counts.forEach((ws, n) -> audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.SYSTEM, null,
                        "poll.waits-purged", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS,
                        Map.of("count", n, "retentionSeconds", retention.toSeconds()))));
                return workspaces.size();
            });
            int n = deleted == null ? 0 : deleted;
            total += n;
            if (n < PURGE_BATCH) {
                break;
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ closing (caller's transaction)

    /** The request ended or was cancelled: its waits are closed in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void runEnded(UUID workspaceId, UUID runId) {
        jdbc.sql("""
                UPDATE poll_wait SET state = 'cancelled', end_reason = 'requestEnded', ended_at = :now, next_check_at = NULL,
                       lease_owner = NULL, lease_until = NULL, request = '{}'::jsonb, context = '{}'::jsonb, version = version + 1
                WHERE workspace_id = :ws AND run_id = :run AND state IN ('waiting', 'checking', 'attention')
                """).param("now", Sql.ts(clock.instant())).param("ws", workspaceId).param("run", runId).update();
    }

    /** The message wait ended another way (message, time limit): its safety-net check is closed with it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fallbackClosed(UUID workspaceId, UUID runId, String nodeId, String reason) {
        jdbc.sql("""
                UPDATE poll_wait SET state = 'cancelled', end_reason = :reason, ended_at = :now, next_check_at = NULL,
                       lease_owner = NULL, lease_until = NULL, request = '{}'::jsonb, context = '{}'::jsonb, version = version + 1
                WHERE workspace_id = :ws AND run_id = :run AND node_id = :node AND kind = 'fallback'
                  AND state IN ('waiting', 'checking', 'attention')
                """).param("reason", reason).param("now", Sql.ts(clock.instant())).param("ws", workspaceId)
                .param("run", runId).param("node", nodeId).update();
    }

    /** Operator Retry of a request whose checks stopped: the next check runs now. @return true when there was one */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retryAttention(UUID workspaceId, UUID runId) {
        Instant now = clock.instant();
        List<String> nodes = jdbc.sql("""
                UPDATE poll_wait SET state = 'waiting', next_check_at = :now, consecutive_errors = 0, version = version + 1
                WHERE workspace_id = :ws AND run_id = :run AND kind = 'check' AND state = 'attention' RETURNING node_id
                """).param("now", Sql.ts(now)).param("ws", workspaceId).param("run", runId).query(String.class).list();
        for (String node : nodes) {
            runs.mutate(workspaceId, runId, m -> m.updateStep(node, step -> step.put("nextCheckAt", now.toString())));
        }
        return !nodes.isEmpty();
    }

    // ------------------------------------------------------------------ operator actions

    /** Check now (at most once a minute), Extend deadline, Stop waiting. */
    public enum Action { CHECK_NOW, EXTEND, STOP }

    /** @param replayed true when the Idempotency-Key repeated an earlier, completed request (nothing was changed) */
    public record Operated(Row row, boolean replayed) {
    }

    @Transactional
    public Operated operate(Caller caller, UUID workspaceId, UUID runId, UUID waitId, Action action, String reason,
            String extendBy, String operationId, String idempotencyKey) {
        RunAccess.Visible visible = runAccess.require(caller, workspaceId, runId);
        policy.require(visible.access(), Permission.RUN_OPERATE, operationId, true);
        String key = com.orqix.shared.idempotency.IdempotencyService.requireKey(idempotencyKey);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("reason", reason == null ? "" : reason);
        body.put("extendBy", extendBy == null ? "" : extendBy);
        var claim = idempotency.claim(workspaceId, caller.subjectId(), operationId, key, idempotency.requestHash(operationId,
                Map.of("workspaceId", workspaceId.toString(), "runId", runId.toString(), "waitId", waitId.toString()), body));
        if (claim.isReplay()) {
            return new Operated(find(waitId).orElseThrow(ApiException::notFound), true);
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Give a reason; it is kept in the audit trail.");
        }
        if (reason.length() > 500) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The reason can have at most 500 characters.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/reason", "maxLength", "At most 500 characters.")));
        }
        RunStore.RunRow run = runs.lock(workspaceId, runId).orElseThrow(ApiException::notFound);
        Row row = jdbc.sql("SELECT * FROM poll_wait WHERE workspace_id = :ws AND run_id = :run AND id = :id FOR UPDATE")
                .param("ws", workspaceId).param("run", runId).param("id", waitId).query(this::row).optional()
                .orElseThrow(ApiException::notFound);
        if (!run.isActive() || !List.of("waiting", "checking", "attention").contains(row.state())) {
            throw new ApiException(ProblemCode.RUN_NOT_RECOVERABLE, "This wait is no longer open.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Settings s = settings(workspaceId, run.releaseId(), row.nodeId(), row.kind());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("waitId", waitId.toString());
        detail.put("nodeId", row.nodeId());
        detail.put("kind", row.kind());
        String auditAction;
        switch (action) {
            case CHECK_NOW -> {
                if (suspensions.isSuspended(workspaceId, runId)) {
                    throw new ApiException(ProblemCode.RUN_SUSPENDED, "Resume the request first; checks pause while it is suspended.");
                }
                if ("checking".equals(row.state())) {
                    throw new ApiException(ProblemCode.RATE_LIMITED, "A check is running now.").withHeader("Retry-After", "5");
                }
                if (row.lastManualCheckAt() != null && row.lastManualCheckAt().plus(MANUAL_CHECK_SPACING).isAfter(now)) {
                    long wait = Math.max(1, Duration.between(now, row.lastManualCheckAt().plus(MANUAL_CHECK_SPACING)).getSeconds());
                    throw new ApiException(ProblemCode.RATE_LIMITED, "Check now can be used once a minute.")
                            .withHeader("Retry-After", Long.toString(wait));
                }
                if (row.checksDone() >= row.maxChecks()) {
                    throw new ApiException(ProblemCode.RUN_NOT_RECOVERABLE, "This wait has used all of its checks.");
                }
                jdbc.sql("""
                        UPDATE poll_wait SET state = 'waiting', next_check_at = :now, last_manual_check_at = :now,
                               consecutive_errors = CASE WHEN state = 'attention' THEN 0 ELSE consecutive_errors END,
                               version = version + 1
                        WHERE id = :id
                        """).param("now", Sql.ts(now)).param("id", waitId).update();
                if ("attention".equals(row.state()) && CHECK.equals(row.kind())) {
                    runs.mutate(workspaceId, runId, m -> {
                        m.lifecycle = RunStore.RUNNING;
                        m.attention = null;
                        m.settle();
                    });
                }
                runs.mutate(workspaceId, runId, m -> m.updateStep(row.nodeId(), step -> step.put("nextCheckAt", now.toString())));
                auditAction = "poll.checked-now";
            }
            case EXTEND -> {
                long by = PollSchedule.seconds(extendBy);
                if (by < 60) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Extend by at least one minute.")
                            .withFieldErrors(List.of(new ApiException.FieldError("/extendBy", "minimum", "At least PT1M.")));
                }
                long authored = Duration.between(row.startedAt(), row.authoredDeadlineAt()).getSeconds();
                Instant limit = row.startedAt().plusSeconds(Math.min(2 * authored, PollSchedule.MAX_DEADLINE));
                Instant extended = row.deadlineAt().plusSeconds(by);
                if (extended.isAfter(limit)) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "The deadline can be extended to at most "
                            + limit + " (twice the authored time, never beyond 90 days from the start).")
                            .withFieldErrors(List.of(new ApiException.FieldError("/extendBy", "maximum",
                                    "At most " + PollSchedule.describe(Math.max(0, Duration.between(row.deadlineAt(), limit).getSeconds()))
                                            + " more.")));
                }
                int extraChecks = (int) Math.min(PollSchedule.CHECK_LIMIT, row.maxChecks()
                        + Math.max(1, by / Math.max(1, s.schedule().maxInterval())) + 1);
                jdbc.sql("""
                        UPDATE poll_wait SET deadline_at = :deadline, deadline_extensions = deadline_extensions + 1,
                               max_checks = :max, final_check_done = false,
                               next_check_at = CASE WHEN state = 'waiting' AND next_check_at >= deadline_at THEN
                                   LEAST(:deadline, CAST(:now AS timestamptz) + make_interval(secs => :interval)) ELSE next_check_at END,
                               version = version + 1
                        WHERE id = :id
                        """).param("deadline", Sql.ts(extended)).param("max", extraChecks).param("now", Sql.ts(now))
                        .param("interval", (double) s.schedule().maxInterval()).param("id", waitId).update();
                runs.mutate(workspaceId, runId, m -> m.updateStep(row.nodeId(), step -> step.put("dueAt", extended.toString())));
                detail.put("deadline", extended.toString());
                auditAction = "poll.deadline-extended";
            }
            default -> {
                if ("checking".equals(row.state())) {
                    throw new ApiException(ProblemCode.RUN_BUSY, "A check is running now; try again shortly.")
                            .withHeader("Retry-After", "5");
                }
                if (FALLBACK.equals(row.kind())) {
                    end(row, "cancelled", "stoppedByOperator", now);
                    clearStepPolling(row);
                } else {
                    if ("attention".equals(row.state())) {
                        runs.mutate(workspaceId, runId, m -> {
                            m.lifecycle = RunStore.RUNNING;
                            m.attention = null;
                        });
                    }
                    trigger(row, run, "timedOut", json.object(), row.checksDone(), now, "stoppedByOperator");
                }
                auditAction = "poll.stopped";
            }
        }
        Map<String, Object> data = new LinkedHashMap<>(detail);
        data.put("reason", reason);
        events.append(workspaceId, runId, switch (action) {
            case CHECK_NOW -> "POLL_CHECK_REQUESTED";
            case EXTEND -> "POLL_DEADLINE_EXTENDED";
            case STOP -> "POLL_STOPPED";
        }, row.nodeId(), caller.subjectId(), data);
        audit.write(new AuditWriter.Entry(workspaceId, caller.machine() ? AuditWriter.ActorType.SERVICE : AuditWriter.ActorType.USER,
                caller.subjectId(), auditAction, "pollWait", waitId.toString(), AuditWriter.Outcome.SUCCESS, detail));
        idempotency.complete(claim, "pollWait", waitId.toString(), 200);
        return new Operated(find(waitId).orElseThrow(), false);
    }

    // ------------------------------------------------------------------ reads

    public record Row(UUID id, UUID workspaceId, UUID runId, String nodeId, int occurrence, String kind,
            String connectionKey, int connectionVersion, String operation, int timeoutSeconds, JsonNode request,
            JsonNode context, String state, int checksDone, int maxChecks, int consecutiveErrors, Instant startedAt,
            Instant nextCheckAt, Instant deadlineAt, Instant authoredDeadlineAt, int deadlineExtensions,
            boolean finalCheckDone, Instant lastCheckedAt, Integer lastHttpStatus, String lastOutcome,
            String lastResultDigest, ObjectNode shown, Instant lastManualCheckAt, int fence, int version,
            Instant endedAt, String endReason) {
    }

    @Transactional(readOnly = true)
    public Optional<Row> find(UUID id) {
        return jdbc.sql("SELECT * FROM poll_wait WHERE id = :id").param("id", id).query(this::row).optional();
    }

    @Transactional(readOnly = true)
    public List<Row> forRun(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT * FROM poll_wait WHERE workspace_id = :ws AND run_id = :run ORDER BY started_at, id")
                .param("ws", workspaceId).param("run", runId).query(this::row).list();
    }

    /** @return the latest deadline an operator may extend this wait to */
    public static Instant extensionLimit(Row row) {
        long authored = Duration.between(row.startedAt(), row.authoredDeadlineAt()).getSeconds();
        return row.startedAt().plusSeconds(Math.min(2 * authored, PollSchedule.MAX_DEADLINE));
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "run_id"), rs.getString("node_id"),
                rs.getInt("occurrence"), rs.getString("kind"), rs.getString("connection_key"),
                rs.getInt("connection_version"), rs.getString("operation"), rs.getInt("timeout_seconds"),
                json.parse(rs.getString("request")), json.parse(rs.getString("context")), rs.getString("state"),
                rs.getInt("checks_done"), rs.getInt("max_checks"), rs.getInt("consecutive_errors"),
                Sql.instant(rs, "started_at"), Sql.instant(rs, "next_check_at"), Sql.instant(rs, "deadline_at"),
                Sql.instant(rs, "authored_deadline_at"), rs.getInt("deadline_extensions"), rs.getBoolean("final_check_done"),
                Sql.instant(rs, "last_checked_at"), rs.getObject("last_http_status", Integer.class),
                rs.getString("last_outcome"), rs.getString("last_result_digest"), (ObjectNode) json.parse(rs.getString("shown")),
                Sql.instant(rs, "last_manual_check_at"), rs.getInt("fence"), rs.getInt("version"),
                Sql.instant(rs, "ended_at"), rs.getString("end_reason"));
    }

    // ------------------------------------------------------------------ texts

    private ObjectNode shown(Settings s, ObjectNode result, ObjectNode previous) {
        if (result == null || s.shown().isEmpty()) {
            return previous;
        }
        ObjectNode shown = json.object();
        for (String key : s.shown()) {
            JsonNode value = result.path(key);
            if (value.isValueNode() && !value.isNull()) {
                shown.set(key, value);
            }
        }
        return shown;
    }

    private String systemName(UUID workspaceId, String key, int version) {
        return connections.definition(workspaceId, key, version).map(r -> r.definition().name()).orElse(key);
    }

    /** "Order tracking to report that "Track the order" is done (state: pending)". */
    static String subject(String system, String label, ObjectNode shown) {
        StringBuilder sb = new StringBuilder(system).append(" to report that ").append(q(label)).append(" is done");
        if (shown != null && !shown.isEmpty()) {
            List<String> parts = new ArrayList<>();
            shown.properties().forEach(e -> parts.add(e.getKey() + ": " + e.getValue().asString()));
            sb.append(" (").append(String.join(", ", parts)).append(')');
        }
        return truncate(sb.toString(), 300);
    }

    private static String q(String text) {
        return "“" + text + "”";
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
