package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.integration.health.ConnectionCircuit;
import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-W04 and CG-C09 on PostgreSQL and Flowable (docs/build/S131): a step's own retry policy waits with exponential
 * backoff and jitter, honours and caps {@code Retry-After} for 429 and 503, never retries an unknown outcome, an open
 * circuit defers calls of two requests without using their attempts until a trial call closes it, and the binding's
 * request limit defers calls the same way. Connection health shows what happened.
 */
class StepRetryAndCircuitTest extends RuntimeTestBase {

    private static final BigDecimal AMOUNT = new BigDecimal("1800");
    private static boolean nonIdempotentRegistered;

    @Autowired
    private ConnectionCircuit circuit;

    @Autowired
    private Json json;

    // ------------------------------------------------------------------ backoff

    @Test
    void exponentialBackoffWithJitterIsCappedAtMaxDelay() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, retry(r -> r.put("maxAttempts", 4).put("delay", "PT10S")
                .put("multiplier", 3).put("maxDelay", "PT1M")));
        Stub.fault(Stub.Mode.STATUS500, 3, 0);
        UUID runId = startAndApprove(workflowId);

        // Attempt n waits min(60 s, 10 s x 3^(n-1)) +-10 percent, never more than 60 s.
        long[][] expected = {{9_000, 11_000}, {27_000, 33_000}, {54_000, 60_000}};
        for (int k = 1; k <= 3; k++) {
            int failures = k;
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> events(runId, "EFFECT_FAILED").size() == failures);
            JsonNode event = events(runId, "EFFECT_FAILED").get(k - 1);
            long delay = Duration.between(Instant.parse(event.path("at").asString()),
                    Instant.parse(event.path("data").path("nextRetryAt").asString())).toMillis();
            assertThat(delay).as("delay after attempt " + k).isBetween(expected[k - 1][0] - 500, expected[k - 1][1]);
            Job timer = awaitTimer(runId);
            assertThat(timer.getRetries()).as("retries left after attempt " + k).isEqualTo(4 - k);
            fastForward(PURCHASING, runId);
        }
        awaitLifecycle(PURCHASING, runId, "completed");
        assertThat(Stub.postCount()).isEqualTo(4);
    }

    // ------------------------------------------------------------------ Retry-After

    @Test
    void retryAfterIsHonouredAndCappedAtMaxDelay() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, retry(r -> r.put("maxAttempts", 3).put("delay", "PT5S")
                .put("multiplier", 2).put("maxDelay", "PT2M")));
        Stub.faultWithRetryAfter(Stub.Mode.STATUS429, 1, "45");
        Stub.faultWithRetryAfter(Stub.Mode.STATUS503, 1, "3600");
        UUID runId = startAndApprove(workflowId);

        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> events(runId, "EFFECT_FAILED").size() == 1);
        assertThat(delayOf(events(runId, "EFFECT_FAILED").get(0))).as("Retry-After: 45 is used as it is")
                .isBetween(44_500L, 45_000L);
        assertThat(events(runId, "EFFECT_FAILED").get(0).path("data").path("httpStatus").asInt()).isEqualTo(429);
        fastForward(PURCHASING, runId);

        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> events(runId, "EFFECT_FAILED").size() == 2);
        assertThat(delayOf(events(runId, "EFFECT_FAILED").get(1))).as("Retry-After: 3600 is capped at maxDelay PT2M")
                .isBetween(119_500L, 120_000L);
        fastForward(PURCHASING, runId);
        awaitLifecycle(PURCHASING, runId, "completed");
        assertThat(Stub.postCount()).isEqualTo(3);

        // Health counts the 429 and keeps the last error.
        JsonNode health = api("olga.operator").get("/workspaces/{ws}/connections/{key}/health", PURCHASING,
                "procurement").body();
        assertThat(health.path("lastDay").path("rateLimited").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(health.path("lastDay").path("calls").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(health.path("lastHour").path("p95Ms").isNumber()).isTrue();
        assertThat(health.path("lastError").path("httpStatus").asInt()).isEqualTo(503);
    }

    // ------------------------------------------------------------------ unknown outcomes

    @Test
    void anUnknownOutcomeIsNeverRetriedAutomaticallyWhateverThePolicySays() throws Exception {
        UUID workflowId = nonIdempotentWorkflow(retry(r -> r.put("maxAttempts", 5).put("delay", "PT5S")));
        Stub.requireIdempotencyKey(false);
        Stub.faultWithRetryAfter(Stub.Mode.STATUS503, 1, "5");
        UUID runId = startAndApprove(workflowId);
        awaitLifecycle(PURCHASING, runId, "needsAttention", "completed");
        JsonNode run = run(PURCHASING, runId);
        assertThat(run.path("lifecycle").asString()).isEqualTo("needsAttention");
        assertThat(run.path("attention").path("reason").asString()).isEqualTo("EFFECT_UNKNOWN");
        String pid = processInstance(PURCHASING, runId);
        assertThat(managementService.createTimerJobQuery().processInstanceId(pid).count()).isZero();
        assertThat(managementService.createDeadLetterJobQuery().processInstanceId(pid).singleResult().getRetries())
                .isZero();
        assertThat(Stub.postCount()).as("one call; the Retry-After and the 5 attempts change nothing").isEqualTo(1);
    }

    // ------------------------------------------------------------------ circuit breaker

    @Test
    void anOpenCircuitDefersTwoRequestsWithoutUsingAttemptsUntilATrialCallClosesIt() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, retry(r -> r.put("maxAttempts", 3).put("delay", "PT5S")));
        // The binding already saw threshold - 1 failures in a row across other requests.
        jdbc.sql("""
                INSERT INTO connection_circuit (workspace_id, connection_key, state, consecutive_failures,
                                                first_failure_at, cooldown_seconds, updated_at)
                VALUES (:ws, 'procurement', 'closed', :n, now(), 60, now())
                """).param("ws", PURCHASING).param("n", circuit.failureThreshold() - 1).update();
        Stub.fault(Stub.Mode.STATUS500, 1, 0);

        // Request A makes the failing call that opens the circuit; its own retry then waits for the circuit.
        UUID a = startAndApprove(workflowId);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> events(a, "EFFECT_FAILED").size() == 1);
        assertThat(circuitState()).isEqualTo("open");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            fastForward(PURCHASING, a);
            return !events(a, "EFFECT_DEFERRED").isEmpty();
        });
        assertThat(awaitTimer(a).getRetries()).as("A keeps the attempts it had after its one failure").isEqualTo(2);

        // Request B never calls while the circuit is open: no effect is registered and no attempt is used.
        UUID b = startAndApprove(workflowId);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !events(b, "EFFECT_DEFERRED").isEmpty());
        JsonNode deferred = events(b, "EFFECT_DEFERRED").get(0);
        assertThat(deferred.path("data").path("reason").asString()).isEqualTo("CIRCUIT_OPEN");
        Job timerB = awaitTimer(b);
        assertThat(timerB.getRetries()).as("B still has all 3 attempts").isEqualTo(3);
        assertThat(Duration.between(Instant.now(), timerB.getDuedate().toInstant()).toSeconds())
                .as("B waits for the end of the cool-down").isBetween(30L, 61L);
        assertThat(effectAttempts(b)).isZero();
        assertThat(events(b, "EFFECT_FAILED")).isEmpty();
        assertThat(lifecycle(PURCHASING, b)).isEqualTo("running");
        assertThat(Stub.postCount()).isEqualTo(1);

        JsonNode health = api("olga.operator").get("/workspaces/{ws}/connections/{key}/health", PURCHASING,
                "procurement").body();
        assertThat(health.path("circuit").path("state").asString()).isEqualTo("open");
        assertThat(health.path("circuit").path("openUntil").isString()).isTrue();
        assertThat(health.path("notices").get(0).path("code").asString()).isEqualTo("CIRCUIT_OPEN");
        assertThat(health.path("notices").get(0).path("severity").asString()).isEqualTo("critical");
        JsonNode list = api("olga.operator").get("/workspaces/{ws}/connection-health", PURCHASING).body();
        assertThat(list.path("noticeCount").asInt()).isGreaterThanOrEqualTo(1);

        // The cool-down ends: B's call is the one trial call; it succeeds and closes the circuit.
        jdbc.sql("UPDATE connection_circuit SET open_until = now() - interval '1 second' WHERE workspace_id = :ws")
                .param("ws", PURCHASING).update();
        awaitLifecycle(PURCHASING, b, "completed");
        assertThat(circuitState()).isEqualTo("closed");
        awaitLifecycle(PURCHASING, a, "completed");
        assertThat(Stub.postCount()).as("one failure, then one call each").isEqualTo(3);
        assertThat(effectAttempts(a)).as("A used 2 attempts: the failure and the success").isEqualTo(2);
        assertThat(effectAttempts(b)).isEqualTo(1);
        JsonNode after = api("olga.operator").get("/workspaces/{ws}/connections/{key}/health", PURCHASING,
                "procurement").body();
        assertThat(after.path("circuit").path("state").asString()).isEqualTo("closed");
        assertThat(after.path("lastHour").path("deferred").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aFailedTrialReopensTheCircuitForTwiceTheCooldownAndOldFailuresDoNotCount() {
        String key = "probe-" + UUID.randomUUID().toString().substring(0, 8);
        Duration trial = Duration.ofSeconds(20);
        for (int i = 0; i < circuit.failureThreshold() - 1; i++) {
            assertThat(circuit.record(SANDBOX, key, true, "HTTP_500", false)).isEqualTo(ConnectionCircuit.State.CLOSED);
        }
        // Failures that started more than the window ago start a new count.
        jdbc.sql("UPDATE connection_circuit SET first_failure_at = now() - interval '6 minutes' WHERE connection_key = :k")
                .param("k", key).update();
        assertThat(circuit.record(SANDBOX, key, true, "HTTP_500", false)).isEqualTo(ConnectionCircuit.State.CLOSED);
        assertThat(circuit.status(SANDBOX, key).orElseThrow().consecutiveFailures()).isEqualTo(1);
        for (int i = 1; i < circuit.failureThreshold(); i++) {
            circuit.record(SANDBOX, key, true, "HTTP_500", false);
        }
        assertThat(circuit.status(SANDBOX, key).orElseThrow().state()).isEqualTo(ConnectionCircuit.State.OPEN);
        assertThat(circuit.status(SANDBOX, key).orElseThrow().cooldownSeconds()).isEqualTo(60);
        assertThat(circuit.admit(SANDBOX, key, trial).reason()).isEqualTo("CIRCUIT_OPEN");

        expire(key);
        ConnectionCircuit.Admission first = circuit.admit(SANDBOX, key, trial);
        assertThat(first.allowed()).isTrue();
        assertThat(first.trial()).isTrue();
        assertThat(circuit.admit(SANDBOX, key, trial).reason()).as("one trial at a time")
                .isEqualTo("CIRCUIT_TRIAL_RUNNING");
        assertThat(circuit.record(SANDBOX, key, true, "TIMEOUT", true)).isEqualTo(ConnectionCircuit.State.OPEN);
        ConnectionCircuit.Status reopened = circuit.status(SANDBOX, key).orElseThrow();
        assertThat(reopened.cooldownSeconds()).isEqualTo(120);
        assertThat(reopened.timesOpened()).isEqualTo(2);
        assertThat(Duration.between(Instant.now(), reopened.openUntil()).toSeconds()).isBetween(100L, 121L);

        expire(key);
        assertThat(circuit.admit(SANDBOX, key, trial).trial()).isTrue();
        assertThat(circuit.record(SANDBOX, key, false, null, true)).isEqualTo(ConnectionCircuit.State.CLOSED);
        assertThat(circuit.admit(SANDBOX, key, trial).allowed()).isTrue();
        assertThat(circuit.status(SANDBOX, key).orElseThrow().cooldownSeconds()).isEqualTo(60);
    }

    // ------------------------------------------------------------------ request limit

    @Test
    void theBindingRequestLimitDefersCallsWithoutUsingAttempts() throws Exception {
        assertThat(api("wade.admin").put(Map.of("maxRequestsPerMinute", 0), null,
                "/workspaces/{ws}/connections/{key}/rate-limit", PURCHASING, "procurement").status()).isEqualTo(422);
        assertThat(api("olga.operator").put(Map.of("maxRequestsPerMinute", 1), null,
                "/workspaces/{ws}/connections/{key}/rate-limit", PURCHASING, "procurement").status())
                .as("operators read health but cannot set the limit").isEqualTo(403);
        Response limited = api("wade.admin").put(Map.of("maxRequestsPerMinute", 1), null,
                "/workspaces/{ws}/connections/{key}/rate-limit", PURCHASING, "procurement");
        assertThat(limited.status()).as(limited.toString()).isEqualTo(200);
        assertThat(limited.body().path("rateLimit").path("maxRequestsPerMinute").asInt()).isEqualTo(1);
        try {
            UUID workflowId = publishPurchase(PURCHASING, retry(r -> r.put("maxAttempts", 2).put("delay", "PT5S")));
            UUID a = startAndApprove(workflowId);
            awaitLifecycle(PURCHASING, a, "completed");
            UUID b = startAndApprove(workflowId);
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !events(b, "EFFECT_DEFERRED").isEmpty());
            assertThat(events(b, "EFFECT_DEFERRED").get(0).path("data").path("reason").asString())
                    .isEqualTo("RATE_LIMITED");
            Job timer = awaitTimer(b);
            assertThat(timer.getRetries()).isEqualTo(2);
            assertThat(Duration.between(Instant.now(), timer.getDuedate().toInstant()).toSeconds()).isBetween(1L, 61L);
            assertThat(effectAttempts(b)).isZero();
            assertThat(Stub.postCount()).isEqualTo(1);
            JsonNode health = api("olga.operator").get("/workspaces/{ws}/connections/{key}/health", PURCHASING,
                    "procurement").body();
            assertThat(health.path("lastHour").path("deferred").asInt()).isGreaterThanOrEqualTo(1);
            List<String> codes = new java.util.ArrayList<>();
            health.path("notices").forEach(n -> codes.add(n.path("code").asString()));
            assertThat(codes).contains("CALLS_DEFERRED");
        } finally {
            Response cleared = api("wade.admin").put(java.util.Collections.singletonMap("maxRequestsPerMinute", null),
                    null, "/workspaces/{ws}/connections/{key}/rate-limit", PURCHASING, "procurement");
            assertThat(cleared.status()).as(cleared.toString()).isEqualTo(200);
            assertThat(cleared.body().path("rateLimit").path("maxRequestsPerMinute").isNull()).isTrue();
        }
        Long audits = jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'connection.rate-limit.updated'")
                .query(Long.class).single();
        assertThat(audits).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private static Consumer<ObjectNode> retry(Consumer<ObjectNode> policy) {
        return d -> {
            for (JsonNode node : d.path("nodes")) {
                if ("procure".equals(node.path("id").asString())) {
                    policy.accept(((ObjectNode) node.path("config")).putObject("retry"));
                }
            }
        };
    }

    private UUID startAndApprove(UUID workflowId) throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, AMOUNT).path("id").asString());
        approve("mani.manager", PURCHASING, runId, "manager");
        return runId;
    }

    /** Events of a type, oldest first, as {at, data}. */
    private List<JsonNode> events(UUID runId, String type) {
        return jdbc.sql("""
                SELECT occurred_at, data::text AS data FROM run_event WHERE run_id = :run AND type = :type ORDER BY seq
                """).param("run", runId).param("type", type)
                .query((rs, n) -> {
                    ObjectNode e = json.object();
                    e.put("at", rs.getTimestamp("occurred_at").toInstant().toString());
                    e.set("data", json.parse(rs.getString("data")));
                    return (JsonNode) e;
                }).list();
    }

    private static long delayOf(JsonNode event) {
        return Duration.between(Instant.parse(event.path("at").asString()),
                Instant.parse(event.path("data").path("nextRetryAt").asString())).toMillis();
    }

    private Job awaitTimer(UUID runId) {
        String pid = processInstance(PURCHASING, runId);
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> managementService.createTimerJobQuery().processInstanceId(pid).count() == 1);
        return managementService.createTimerJobQuery().processInstanceId(pid).singleResult();
    }

    private int effectAttempts(UUID runId) {
        return jdbc.sql("SELECT coalesce(sum(attempts), 0) FROM effect_journal WHERE run_id = :run")
                .param("run", runId).query(Integer.class).single();
    }

    private String circuitState() {
        return jdbc.sql("SELECT state FROM connection_circuit WHERE workspace_id = :ws AND connection_key = 'procurement'")
                .param("ws", PURCHASING).query(String.class).optional().orElse("closed");
    }

    private void expire(String key) {
        jdbc.sql("UPDATE connection_circuit SET open_until = now() - interval '1 second', trial_until = NULL"
                + " WHERE connection_key = :k").param("k", key).update();
    }

    /** procurement v2: createOrder classified nonIdempotent (no idempotency header), as in ReconcileResumeTest. */
    private UUID nonIdempotentWorkflow(Consumer<ObjectNode> patch) throws Exception {
        if (!nonIdempotentRegistered) {
            ObjectNode definition = (ObjectNode) Fixtures.procurementConnection();
            definition.put("version", 2);
            ObjectNode createOrder = (ObjectNode) definition.path("operations").path("createOrder");
            createOrder.put("effectClass", "nonIdempotent");
            createOrder.remove("idempotencyHeader");
            Response registered = api("wade.admin").post(Map.of("definition", definition),
                    "/workspaces/{ws}/connections/{key}/versions", PURCHASING, "procurement");
            assertThat(registered.status()).as(registered.toString()).isIn(201, 409);
            nonIdempotentRegistered = true;
        }
        return publishPurchase(PURCHASING, d -> {
            ((ObjectNode) d.path("dependencies").path("connections").get(0)).put("definitionVersion", 2);
            patch.accept(d);
        });
    }
}
