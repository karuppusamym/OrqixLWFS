package com.orqix.resilience;

import static com.orqix.resilience.Flows.PURCHASING;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.orqix.support.Stub;

/**
 * T-RES-02 (in-JVM, completes spike S-5): two application contexts with different lock owners share one database; 50
 * runs are approved concurrently through both. Every run gets exactly one effect (one stub order, one journal row,
 * one attempt), one decision and one completion, and the jobs are executed by both lock owners. A second phase forces
 * retries (HTTP 500 on the first attempt of every run) so that the retry jobs are picked up through the timer and job
 * acquisition of whichever instance gets them first: still one order per run.
 */
@Tag("resilience")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoExecutorsTest {

    private static final int RUNS = 50;

    private Node a;
    private Node b;

    @BeforeAll
    void startBothInstances() {
        a = Node.start(Nodes.NodeA.class);
        b = Node.start(Nodes.NodeB.class);
        Flows.bindStub(a, PURCHASING, Stub.baseUrl());
        assertThat(a.lockOwner()).isNotEqualTo(b.lockOwner());
    }

    @BeforeEach
    void resetStub() {
        Stub.reset();
        JobExecutionRecorder.clear();
    }

    @AfterAll
    void stopBothInstances() {
        if (b != null) {
            b.stop();
        }
        if (a != null) {
            a.stop();
        }
    }

    @Test
    void tRes02_fiftyRunsApprovedConcurrentlyOneEffectEachJobsSplitAcrossBothInstances() throws Exception {
        UUID workflowId = Flows.publishPurchase(a, PURCHASING, 10, null);
        Stub.fault(Stub.Mode.DELAY, RUNS, 400); // keeps jobs in flight long enough for both pools to be busy
        JobExecutionRecorder.clear();

        List<UUID> runs = startRuns(workflowId, RUNS);
        long started = System.nanoTime();
        approveConcurrently(runs, 12);
        for (UUID run : runs) {
            Flows.awaitLifecycle(a, PURCHASING, run, Duration.ofSeconds(90), "completed", "needsAttention");
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // --- exactly one effect, decision and completion per run
        assertThat(runs).allSatisfy(run -> assertThat(Flows.lifecycle(a, PURCHASING, run)).isEqualTo("completed"));
        assertThat(Stub.orderCount()).as("stub orders").isEqualTo(RUNS);
        assertThat(Stub.postCount()).as("stub calls: no retry, no duplicate").isEqualTo(RUNS);
        assertOnePerRun(workflowId, "TASK_DECIDED", RUNS);
        assertOnePerRun(workflowId, "EFFECT_SUCCEEDED", RUNS);
        assertOnePerRun(workflowId, "RUN_COMPLETED", RUNS);
        List<Map<String, Object>> journal = a.jdbc().sql("""
                SELECT j.state, j.attempts, count(*) AS n FROM effect_journal j
                JOIN workflow_run r ON r.workspace_id = j.workspace_id AND r.id = j.run_id
                WHERE r.workflow_id = :wf GROUP BY j.state, j.attempts
                """).param("wf", workflowId).query().listOfRows();
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0)).containsEntry("state", "succeeded").containsEntry("attempts", 1)
                .containsEntry("n", (long) RUNS);
        assertNoJobsLeft();

        // --- jobs were executed by both lock owners
        Map<String, Long> byOwner = JobExecutionRecorder.successesByOwner();
        System.out.printf("T-RES-02: %d runs, last completion %d ms after the first decision; jobs by lock owner %s; "
                + "failed attempts %d%n", RUNS, elapsedMs, byOwner, JobExecutionRecorder.failedAttempts().size());
        assertThat(byOwner.keySet()).containsExactlyInAnyOrder(a.lockOwner(), b.lockOwner());
        assertThat(byOwner.values().stream().mapToLong(Long::longValue).sum()).as("one job per run").isEqualTo(RUNS);
        assertThat(byOwner.values()).allSatisfy(n -> assertThat(n).isGreaterThanOrEqualTo(10));
    }

    @Test
    void tRes02_retriesAcquiredByEitherInstanceStillOneOrderPerRun() throws Exception {
        UUID workflowId = Flows.publishPurchase(a, PURCHASING, 10, null);
        int runsInPhase = 20;
        Stub.fault(Stub.Mode.STATUS500, runsInPhase, 0); // the first attempt of every run fails
        JobExecutionRecorder.clear();

        List<UUID> runs = startRuns(workflowId, runsInPhase);
        approveConcurrently(runs, 10);
        for (UUID run : runs) {
            Flows.awaitLifecycle(a, PURCHASING, run, Duration.ofSeconds(120), "completed", "needsAttention");
        }
        assertThat(runs).allSatisfy(run -> assertThat(Flows.lifecycle(a, PURCHASING, run)).isEqualTo("completed"));
        assertThat(Stub.orderCount()).isEqualTo(runsInPhase);
        assertThat(Stub.postCount()).as("one failed and one successful call per run").isEqualTo(2 * runsInPhase);
        List<Map<String, Object>> journal = a.jdbc().sql("""
                SELECT j.state, j.attempts, count(*) AS n FROM effect_journal j
                JOIN workflow_run r ON r.workspace_id = j.workspace_id AND r.id = j.run_id
                WHERE r.workflow_id = :wf GROUP BY j.state, j.attempts
                """).param("wf", workflowId).query().listOfRows();
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0)).containsEntry("state", "succeeded").containsEntry("attempts", 2)
                .containsEntry("n", (long) runsInPhase);
        assertOnePerRun(workflowId, "RUN_COMPLETED", runsInPhase);
        assertNoJobsLeft();
        Map<String, Long> byOwner = JobExecutionRecorder.successesByOwner();
        System.out.printf("T-RES-02 (retries): completed jobs by lock owner %s, failed first attempts %d%n", byOwner,
                JobExecutionRecorder.failedAttempts().size());
        assertThat(JobExecutionRecorder.failedAttempts()).hasSize(runsInPhase);
        assertThat(byOwner.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(runsInPhase);
    }

    private List<UUID> startRuns(UUID workflowId, int count) throws Exception {
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            runs.add(Flows.start(i % 2 == 0 ? a : b, "rhea.requester", PURCHASING, workflowId,
                    new BigDecimal("1800").add(BigDecimal.valueOf(i))));
        }
        return runs;
    }

    /** Claim and approve every run; request i goes to instance a or b alternately, like a load balancer. */
    private void approveConcurrently(List<UUID> runs, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < runs.size(); i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    Node node = index % 2 == 0 ? a : b;
                    String manager = index % 4 < 2 ? "mani.manager" : "max.manager";
                    Flows.approve(node, manager, PURCHASING, runs.get(index), "manager");
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
    }

    private void assertOnePerRun(UUID workflowId, String eventType, int expectedRuns) {
        List<Map<String, Object>> rows = a.jdbc().sql("""
                SELECT e.run_id, count(*) AS n FROM run_event e
                JOIN workflow_run r ON r.workspace_id = e.workspace_id AND r.id = e.run_id
                WHERE r.workflow_id = :wf AND e.type = :type GROUP BY e.run_id
                """).param("wf", workflowId).param("type", eventType).query().listOfRows();
        assertThat(rows).as(eventType + " rows").hasSize(expectedRuns);
        assertThat(rows).as(eventType + " per run").allSatisfy(row -> assertThat(row).containsEntry("n", 1L));
    }

    private void assertNoJobsLeft() {
        for (String table : List.of("act_ru_job", "act_ru_timer_job", "act_ru_deadletter_job", "act_ru_suspended_job")) {
            assertThat(a.jdbc().sql("SELECT count(*) FROM flowable." + table).query(Integer.class).single())
                    .as(table).isZero();
        }
    }
}
