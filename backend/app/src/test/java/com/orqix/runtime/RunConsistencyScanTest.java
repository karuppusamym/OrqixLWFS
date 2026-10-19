package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.runtime.internal.ConsistencyFindings;
import com.orqix.runtime.internal.RunConsistencyScan;
import com.orqix.runtime.internal.RunRepair;
import com.orqix.runtime.internal.RunStore;
import com.orqix.runtime.internal.RunWatchdog;
import com.orqix.support.RuntimeTestBase;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.JsonNode;

/**
 * EX09 periodic consistency scan (docs/build/S72): opt-in, bounded, rate-limited, report-only, and it does not look at
 * healthy requests again before the recheck time. The database is shared with other tests, so every assertion is
 * about the requests created here.
 */
class RunConsistencyScanTest extends RuntimeTestBase {

    @Autowired RunConsistencyScan scan;
    @Autowired RunWatchdog watchdog;
    @Autowired ConsistencyFindings findings;
    @Autowired TransactionTemplate transactions;
    @Autowired RunStore runStore;
    @Autowired RunRepair repair;
    @Autowired Clock clock;
    @Autowired JdbcClient client;

    private UUID startPurchase() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        settle(run);
        return run;
    }

    /** The scan leaves very recent requests alone; age this one past the settling time. */
    private void settle(UUID run) {
        jdbc.sql("UPDATE workflow_run SET updated_at=now()-interval '10 minutes' WHERE id=:id").param("id", run).update();
    }

    private void blank(UUID run) {
        jdbc.sql("UPDATE workflow_run SET current_steps='[]'::jsonb, updated_at=now()-interval '10 minutes' WHERE id=:id")
                .param("id", run).update();
    }

    private String scanState(UUID run) {
        return jdbc.sql("SELECT state FROM run_consistency_scan WHERE run_id=:id").param("id", run)
                .query(String.class).optional().orElse(null);
    }

    private Instant checkedAt(UUID run) {
        return jdbc.sql("SELECT checked_at FROM run_consistency_scan WHERE run_id=:id").param("id", run)
                .query(java.time.OffsetDateTime.class).single().toInstant();
    }

    /** Runs passes until every given request has been looked at (other tests' requests share the queue). */
    private void scanUntilSeen(UUID... runs) {
        for (int i = 0; i < 200; i++) {
            boolean all = true;
            for (UUID run : runs) {
                all &= scanState(run) != null;
            }
            if (all) {
                return;
            }
            scan.scan();
        }
        throw new AssertionError("the scan never reached the requests");
    }

    private String snapshotOf(UUID run) {
        return jdbc.sql("SELECT version || '/' || updated_at || '/' || current_steps::text || '/' || lifecycle FROM workflow_run WHERE id=:id")
                .param("id", run).query(String.class).single();
    }

    @Test
    void reportsDriftWithoutRepairingAndSkipsHealthyRequestsUntilRecheck() throws Exception {
        UUID healthy = startPurchase();
        UUID drifted = startPurchase();
        blank(drifted);
        String healthyBefore = snapshotOf(healthy);
        String driftedBefore = snapshotOf(drifted);
        long eventsBefore = jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:id").param("id", drifted)
                .query(Long.class).single();

        scanUntilSeen(healthy, drifted);

        assertThat(scanState(healthy)).isEqualTo("inSync");
        assertThat(scanState(drifted)).isEqualTo("projectionDrift");
        // Report only: the request is exactly as it was (no version bump, no event, no lifecycle change).
        assertThat(snapshotOf(drifted)).isEqualTo(driftedBefore);
        assertThat(snapshotOf(healthy)).isEqualTo(healthyBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:id").param("id", drifted).query(Long.class).single())
                .isEqualTo(eventsBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:id AND type='RUN_PROJECTION_REPAIRED'")
                .param("id", drifted).query(Long.class).single()).isZero();

        // The healthy request is not due again before the recheck time; the finding is (shorter recheck) but not yet.
        Instant now = clock.instant();
        var due = findings.due(now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofHours(6)),
                now.minus(Duration.ofMinutes(30)), 10_000);
        assertThat(due).extracting(k -> k[1]).doesNotContain(healthy, drifted);
        Instant healthyChecked = checkedAt(healthy);
        scan.scan();
        assertThat(checkedAt(healthy)).isEqualTo(healthyChecked);

        // After the recheck time it is looked at again.
        jdbc.sql("UPDATE run_consistency_scan SET checked_at = checked_at - interval '7 hours' WHERE run_id=:id")
                .param("id", healthy).update();
        due = findings.due(now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofHours(6)),
                now.minus(Duration.ofMinutes(30)), 10_000);
        assertThat(due).extracting(k -> k[1]).contains(healthy);

        // Findings show up for run.operate holders only.
        JsonNode list = api("olga.operator").get("/workspaces/{ws}/consistency-findings", PURCHASING).body();
        assertThat(list.path("total").asInt()).isGreaterThanOrEqualTo(1);
        boolean listed = false;
        for (JsonNode item : list.path("items")) {
            if (item.path("runId").asString().equals(drifted.toString())) {
                listed = true;
                assertThat(item.path("state").asString()).isEqualTo("projectionDrift");
                assertThat(item.path("repairable").asBoolean()).isTrue();
                assertThat(item.path("version").asInt()).isEqualTo(run(PURCHASING, drifted).path("version").asInt());
            }
            assertThat(item.path("runId").asString()).isNotEqualTo(healthy.toString());
        }
        assertThat(listed).as("the drifted request is listed").isTrue();
        assertThat(api("rhea.requester").get("/workspaces/{ws}/consistency-findings", PURCHASING).status()).isEqualTo(403);

        // The operator repairs it with the existing guarded action; the finding disappears at once.
        Response repaired = api("olga.operator").post(Map.of("reason", "Rebuild after projection loss",
                "expectedVersion", run(PURCHASING, drifted).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/repair", PURCHASING, drifted);
        assertThat(repaired.status()).as(repaired.toString()).isEqualTo(200);
        assertThat(scanState(drifted)).isNull();
    }

    @Test
    void isOffByDefaultAndTheWatchdogSweepDoesNotScan() throws Exception {
        assertThat(scan.enabled()).isFalse();
        UUID run = startPurchase();
        // Make sure nothing else reached it first, then run a full watchdog sweep.
        jdbc.sql("DELETE FROM run_consistency_scan WHERE run_id=:id").param("id", run).update();
        watchdog.sweep();
        assertThat(scanState(run)).isNull();
        assertThat(scan.sweep()).isZero();
    }

    @Test
    void isRateLimitedAndBoundedWhenEnabled() throws Exception {
        UUID a = startPurchase();
        UUID b = startPurchase();
        jdbc.sql("DELETE FROM run_consistency_scan WHERE run_id IN (:a,:b)").param("a", a).param("b", b).update();
        // Oversized configuration is capped; a pass checks at most the batch size.
        var bounded = new RunConsistencyScan(client, transactions, runStore, repair, findings, clock, true, 1_000_000,
                Duration.ofHours(1), Duration.ofHours(6), Duration.ofMinutes(30));
        int first = bounded.sweep();
        assertThat(first).isBetween(1, RunConsistencyScan.MAX_BATCH);
        // The second sweep inside the interval does nothing at all.
        long rowsBefore = jdbc.sql("SELECT count(*) FROM run_consistency_scan").query(Long.class).single();
        assertThat(bounded.sweep()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM run_consistency_scan").query(Long.class).single()).isEqualTo(rowsBefore);

        var one = new RunConsistencyScan(client, transactions, runStore, repair, findings, clock, true, 1,
                Duration.ofMillis(1), Duration.ofHours(6), Duration.ofMinutes(30));
        assertThat(one.sweep()).isLessThanOrEqualTo(1);
        // A disabled instance never touches anything.
        var off = new RunConsistencyScan(client, transactions, runStore, repair, findings, clock, false, 10,
                Duration.ofMillis(1), Duration.ofHours(6), Duration.ofMinutes(30));
        assertThat(off.sweep()).isZero();
    }

    @Test
    void endedRequestsAreForgottenAndSuspendedOnesAreNotInspected() throws Exception {
        UUID ended = startPurchase();
        scanUntilSeen(ended);
        int version = run(PURCHASING, ended).path("version").asInt();
        Response cancelled = api("olga.operator").post(Map.of("expectedVersion", version, "reason", "Test cleanup"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, ended);
        assertThat(cancelled.status()).isEqualTo(200);
        scan.scan();
        assertThat(scanState(ended)).isNull();

        UUID suspended = startPurchase();
        Response s = api("olga.operator").post(Map.of("reason", "Review", "expectedVersion",
                run(PURCHASING, suspended).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, suspended);
        assertThat(s.status()).isEqualTo(200);
        settle(suspended);
        blank(suspended);
        for (int i = 0; i < 5; i++) {
            scan.scan();
        }
        assertThat(scanState(suspended)).as("a suspended request is protected and skipped").isNull();
    }
}
