package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.PollWaits;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;
import com.orqix.support.TrackingStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Load test of "Keep checking until..." (docs/build/S129 §7): at least 2,000 waits checking once a minute against the
 * WireMock status endpoint, with the live scheduler plus three more scheduler loops (four "pods"). The measured check
 * rate after a warm-up must match the computed schedule (waits / interval) within 5 percent, and no due wait may lag.
 * Opt-in because it runs for several minutes: {@code ORQIX_LOAD_TEST=true}. Waits are clones of one real waiting
 * request (the answer is always "pending", so the engine is never triggered).
 */
@EnabledIfEnvironmentVariable(named = "ORQIX_LOAD_TEST", matches = "true")
class PollLoadTest extends RuntimeTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(PollLoadTest.class);
    private static final int WAITS = Integer.getInteger("orqix.loadTest.waits", 2000);
    private static final int INTERVAL_SECONDS = 60;
    private static final Duration WARM_UP = Duration.ofSeconds(75);
    private static final Duration MEASURE = Duration.ofSeconds(180);
    private static final int EXTRA_SCHEDULERS = 3;

    @Autowired
    PollWaits polls;

    @AfterEach
    void stop() {
        jdbc.sql("UPDATE poll_wait SET state = 'cancelled', end_reason = 'testEnded', next_check_at = NULL, lease_owner = NULL,"
                + " lease_until = NULL WHERE state IN ('waiting', 'checking', 'attention')").update();
    }

    @Test
    void checkRateMatchesTheComputedSchedule() throws Exception {
        JsonNode connection = json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                "examples", "connections", "order-status.connection.json")));
        api("wade.admin").post(Map.of("definition", connection), "/workspaces/{ws}/connections/{key}/versions", PURCHASING,
                "order-status");
        if (jdbc.sql("SELECT count(*) FROM connection_binding WHERE workspace_id = :ws AND connection_key = 'order-status'")
                .param("ws", PURCHASING).query(Long.class).single() == 0) {
            api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/tracking", "auth", Map.of("type", "none"), "enabled", true),
                    null, "/workspaces/{ws}/connections/{key}/binding", PURCHASING, "order-status");
        }
        jdbc.sql("UPDATE connection_binding SET base_url = :url, enabled = true WHERE workspace_id = :ws AND connection_key = 'order-status'")
                .param("url", Stub.baseUrl() + "/tracking").param("ws", PURCHASING).update();
        jdbc.sql("DELETE FROM connection_circuit WHERE connection_key = 'order-status'").update();
        UUID workflow = publishPurchase(PURCHASING, d -> {
            ((ArrayNode) d.path("dependencies").path("connections"))
                    .add(json.parse("{\"key\":\"order-status\",\"definitionVersion\":1}"));
            ((ArrayNode) d.path("nodes")).add(json.parse("""
                    {"id":"track","type":"http","label":"Track the order","config":{"connectionRef":"order-status",
                     "operation":"getStatus","inputMapping":{"orderId":"/steps/procure/procurementId"},
                     "resultMapping":{"state":"/state"},"timeoutSeconds":5,"poll":{"every":"PT%dS","jitterPercent":10,
                     "giveUpAfter":"P1D","maxChecks":1000,
                     "until":{"fieldRef":"/steps/track/state","operator":"eq","value":"shipped"}}}}""".formatted(INTERVAL_SECONDS)));
            for (JsonNode t : d.path("transitions")) {
                if ("procure".equals(t.path("from").asString())) {
                    ((ObjectNode) t).put("to", "track");
                }
            }
            ArrayNode transitions = (ArrayNode) d.path("transitions");
            transitions.add(json.parse("{\"id\":\"p1\",\"from\":\"track\",\"to\":\"notify\",\"on\":\"next\"}"));
            transitions.add(json.parse("{\"id\":\"p3\",\"from\":\"track\",\"to\":\"notifyRejected\",\"on\":\"timedOut\"}"));
            d.remove("uiLayout");
        });
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                "SELECT count(*) FROM poll_wait WHERE run_id = :run AND checks_done >= 1").param("run", run).query(Long.class).single() == 1);

        // Clone the waiting request: each clone has its own run row (waiting at the polling step) and poll row. The first
        // checks are spread over one interval, as jitter spreads them after a burst of starts.
        long t0 = System.nanoTime();
        jdbc.sql("""
                WITH src AS (SELECT * FROM workflow_run WHERE id = :run),
                     p AS (SELECT * FROM poll_wait WHERE run_id = :run),
                     g AS (SELECT n, gen_random_uuid() AS rid, gen_random_uuid() AS pid FROM generate_series(1, :n) n),
                     runs AS (
                       INSERT INTO workflow_run (id, workspace_id, workflow_id, release_id, engine_process_instance_id,
                                                 initiator_subject_id, lifecycle, current_steps, input, reference)
                       SELECT g.rid, src.workspace_id, src.workflow_id, src.release_id, 'load-' || g.rid, src.initiator_subject_id,
                              'waiting', jsonb_build_array(jsonb_set(src.current_steps -> 0, '{pollWaitId}', to_jsonb(g.pid::text))),
                              src.input, 'ORQ-' || (EXTRACT(EPOCH FROM now())::bigint * 10000 + g.n)::text
                       FROM g, src RETURNING id)
                INSERT INTO poll_wait (id, workspace_id, run_id, node_id, kind, connection_key, connection_version, operation,
                                       timeout_seconds, request, context, state, max_checks, started_at, next_check_at,
                                       deadline_at, authored_deadline_at, created_at)
                SELECT g.pid, p.workspace_id, g.rid, p.node_id, p.kind, p.connection_key, p.connection_version, p.operation,
                       p.timeout_seconds, p.request, p.context, 'waiting', 1000, now(),
                       now() + random() * make_interval(secs => :interval), now() + interval '1 day', now() + interval '1 day', now()
                FROM g, p
                """).param("run", run).param("n", WAITS).param("interval", INTERVAL_SECONDS).update();
        LOG.info("Created {} polling waits in {} ms", WAITS, (System.nanoTime() - t0) / 1_000_000);

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pods = Executors.newFixedThreadPool(EXTRA_SCHEDULERS);
        for (int i = 0; i < EXTRA_SCHEDULERS; i++) {
            pods.submit(() -> {
                while (running.get()) {
                    try {
                        if (polls.runDue() == 0) {
                            Thread.sleep(500);
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException e) {
                        LOG.warn("load scheduler loop: {}", e.toString());
                    }
                }
            });
        }
        try {
            Thread.sleep(WARM_UP.toMillis());
            int callsBefore = TrackingStub.calls();
            long checksBefore = checks();
            long start = System.nanoTime();
            List<Long> lagSamples = new ArrayList<>();
            long until = start + MEASURE.toNanos();
            while (System.nanoTime() < until) {
                Thread.sleep(15_000);
                lagSamples.add(lagging());
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            int calls = TrackingStub.calls() - callsBefore;
            long checks = checks() - checksBefore;
            double expected = (WAITS + 1) * seconds / INTERVAL_SECONDS;
            double ratio = calls / expected;
            String result = String.format(java.util.Locale.ROOT,
                    "LOAD RESULT waits=%d interval=%ds pods=%d window=%.1fs calls=%d checksRecorded=%d expected=%.0f"
                            + " ratio=%.3f rate=%.2f/s expectedRate=%.2f/s maxLagging=%d",
                    WAITS, INTERVAL_SECONDS, EXTRA_SCHEDULERS + 1, seconds, calls, checks, expected, ratio, calls / seconds,
                    (WAITS + 1.0) / INTERVAL_SECONDS, lagSamples.stream().mapToLong(Long::longValue).max().orElse(0));
            LOG.info(result);
            System.out.println(result);
            Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "orqix-poll-load-result.txt"), result);
            assertThat(ratio).as(result).isBetween(0.95, 1.05);
            assertThat(lagSamples.stream().mapToLong(Long::longValue).max().orElse(0)).as(result)
                    .isLessThan(WAITS / 20);
        } finally {
            running.set(false);
            pods.shutdown();
            pods.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    private long checks() {
        return jdbc.sql("SELECT coalesce(sum(checks_done), 0) FROM poll_wait WHERE state IN ('waiting', 'checking')")
                .query(Long.class).single();
    }

    /** Waits that are due for more than 10 seconds but not checked yet (the scheduler lags behind). */
    private long lagging() {
        return jdbc.sql("SELECT count(*) FROM poll_wait WHERE state = 'waiting' AND next_check_at < now() - interval '10 seconds'")
                .query(Long.class).single();
    }
}
