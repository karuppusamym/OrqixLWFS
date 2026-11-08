package com.orqix.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.analytics.internal.AnalyticsQueries;
import com.orqix.analytics.internal.KpiEvaluator;
import com.orqix.runtime.internal.AnalyticsProjector;
import com.orqix.shared.web.Sql;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * Analytics read model (docs/build/S145): the outbox fills from the timeline, the projector builds run, visit, route and
 * task facts (idempotently, also after a backfill), and the heatmap, human work and KPI endpoints aggregate them for
 * people with run.read only, with no request data. Real PostgreSQL and Flowable; responses are checked against
 * contracts/openapi.yaml by the test client.
 */
class AnalyticsApiTest extends RuntimeTestBase {

    private static final String HEATMAP = "/workspaces/{ws}/analytics/heatmap";
    private static final String HUMAN = "/workspaces/{ws}/analytics/human-work";
    private static final String KPIS = "/workspaces/{ws}/analytics/kpis";
    private static final String STATUS = "/workspaces/{ws}/analytics/status";
    private static final String TARGETS = "/workspaces/{ws}/workflows/{wf}/kpi-targets";

    @Autowired
    AnalyticsProjector projector;
    @Autowired
    AnalyticsQueries queries;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    Clock clock;

    private UUID run(UUID workflow, String outcome) throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        if ("approve".equals(outcome)) {
            approve("mani.manager", PURCHASING, runId, "manager");
            awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        } else if ("reject".equals(outcome)) {
            JsonNode task = openTask(PURCHASING, runId, "manager");
            assertThat(claim("mani.manager", PURCHASING, task.path("id").asString()).status()).isEqualTo(200);
            assertThat(decide("mani.manager", PURCHASING, task.path("id").asString(), "reject", "Not needed").status())
                    .isEqualTo(201);
            awaitLifecycle(PURCHASING, runId, "completed");
        }
        return runId;
    }

    private void project(List<UUID> runs) {
        projector.runFor(runs);
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_outbox WHERE run_id IN (:runs)").param("runs", runs)
                .query(Integer.class).single()).as("outbox rows left").isZero();
    }

    private JsonNode step(JsonNode heatmap, String nodeId) {
        for (JsonNode s : heatmap.path("steps")) {
            if (nodeId.equals(s.path("nodeId").asString())) {
                return s;
            }
        }
        return null;
    }

    @Test
    void onlyPeopleWithRunReadSeeAnalyticsAndParametersAreChecked() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        String q = "?workflowId=" + workflow;
        for (String path : new String[] {HEATMAP + q, HUMAN, KPIS, STATUS}) {
            assertThat(api("rhea.requester").get(path, PURCHASING).status()).as("requester " + path).isEqualTo(403);
            assertThat(api("vic.viewer").get(path, PURCHASING).status()).as("viewer " + path).isEqualTo(200);
            assertThat(api("olga.operator").get(path, PURCHASING).status()).as("operator " + path).isEqualTo(200);
            assertThat(api("otto.outsider").get(path, PURCHASING).status()).as("outsider " + path).isEqualTo(404);
            assertThat(api("fred.facilities").get(path, PURCHASING).status()).as("other workspace " + path).isEqualTo(404);
        }
        assertThat(api("vic.viewer").get(HEATMAP, PURCHASING).status()).as("workflow required").isEqualTo(400);
        assertThat(api("rhea.requester").get(HEATMAP, PURCHASING).status()).as("access before parameters").isEqualTo(403);
        assertThat(api("vic.viewer").get(HEATMAP + q + "&releaseId=" + UUID.randomUUID(), PURCHASING).status())
                .as("foreign release").isEqualTo(404);
        assertThat(api("vic.viewer").get(HEATMAP + "?workflowId=" + UUID.randomUUID(), PURCHASING).status()).isEqualTo(404);
        assertThat(api("vic.viewer").get(HUMAN + "?from=2026-03-01&to=2026-02-01", PURCHASING).status()).isEqualTo(400);
        assertThat(api("vic.viewer").get(KPIS + "?from=2025-01-01&to=2026-01-02", PURCHASING).status()).isEqualTo(400);
        // A backfill re-reads timelines: operators only.
        assertThat(api("vic.viewer").post(Map.of(), "/workspaces/{ws}/analytics/backfill", PURCHASING).status())
                .isEqualTo(403);
    }

    @Test
    void heatmapCountsVisitsDurationsAndConnectionsFromTheReadModelOnly() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        List<UUID> runs = new ArrayList<>(List.of(run(workflow, "approve"), run(workflow, "approve"), run(workflow, "reject"),
                run(workflow, "none")));
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_outbox WHERE run_id IN (:runs) AND reason = 'event'")
                .param("runs", runs).query(Integer.class).single()).as("timeline fed the outbox").isPositive();
        project(runs);

        String q = HEATMAP + "?workflowId=" + workflow;
        Response response = api("vic.viewer").get(q, PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        JsonNode heat = response.body();
        assertThat(heat.path("runs").asInt()).isEqualTo(4);
        assertThat(heat.path("finishedRuns").asInt()).isEqualTo(3);
        JsonNode manager = step(heat, "manager");
        assertThat(manager).as(heat.toString()).isNotNull();
        assertThat(manager.path("kind").asString()).isEqualTo("approval");
        assertThat(manager.path("visits").asInt()).as("finished visits only").isEqualTo(3);
        assertThat(manager.path("runs").asInt()).isEqualTo(3);
        assertThat(manager.path("p95Seconds").asLong()).isGreaterThanOrEqualTo(manager.path("averageSeconds").asLong() - 1);
        assertThat(heat.path("routes").size()).isPositive();
        for (JsonNode r : heat.path("routes")) {
            assertThat(r.path("runs").asInt()).isBetween(1, 4);
            assertThat(r.path("share").asDouble()).isEqualTo(Math.round(r.path("runs").asInt() * 10000.0 / 4) / 10000.0);
        }
        assertThat(heat.path("freshness").path("lastProjectedAt").isString()).isTrue();
        assertThat(heat.toString()).doesNotContain("Replacement laptop").doesNotContain("Device failed").doesNotContain("OPS");

        // The same release by id gives the same figures; projecting again (a backfill) changes nothing.
        UUID release = UUID.fromString(jdbc.sql("SELECT release_id FROM workflow_run WHERE id = :id")
                .param("id", runs.getFirst()).query(String.class).single());
        JsonNode byRelease = api("vic.viewer").get(q + "&releaseId=" + release, PURCHASING).body();
        assertThat(byRelease.path("steps")).isEqualTo(heat.path("steps"));
        Map<String, Object> before = facts(runs);
        Map<String, Object> since = Map.of("since", Instant.now().minus(1, ChronoUnit.DAYS).toString().substring(0, 10));
        Response backfill = api("olga.operator").post(since, "/workspaces/{ws}/analytics/backfill", PURCHASING);
        assertThat(backfill.status()).as(backfill.toString()).isEqualTo(202);
        assertThat(backfill.body().path("enqueued").asInt()).isGreaterThanOrEqualTo(4);
        Response again = api("olga.operator").post(since, "/workspaces/{ws}/analytics/backfill", PURCHASING);
        assertThat(again.body().path("enqueued").asInt()).as("runs already queued are not queued twice").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'analytics.backfill.requested'")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(2);
        project(runs);
        assertThat(facts(runs)).isEqualTo(before);
    }

    /** Every projected fact of the runs except projection times. */
    private Map<String, Object> facts(List<UUID> runs) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("runs", jdbc.sql("""
                SELECT run_id, lifecycle, outcome_kind, cycle_seconds, human_tasks, automated FROM analytics_run
                WHERE run_id IN (:runs) ORDER BY run_id""").param("runs", runs).query().listOfRows());
        f.put("visits", jdbc.sql("""
                SELECT run_id, ordinal, node_id, kind, duration_seconds, outcome FROM analytics_step_visit
                WHERE run_id IN (:runs) ORDER BY run_id, ordinal""").param("runs", runs).query().listOfRows());
        f.put("routes", jdbc.sql("SELECT run_id, transition_id FROM analytics_route WHERE run_id IN (:runs) ORDER BY 1, 2")
                .param("runs", runs).query().listOfRows());
        f.put("tasks", jdbc.sql("""
                SELECT task_id, status, outcome, waiting_seconds, working_seconds, claims, releases, reassignments, sla
                FROM analytics_task WHERE run_id IN (:runs) ORDER BY task_id""").param("runs", runs).query().listOfRows());
        return f;
    }

    @Test
    void humanWorkSplitsWaitingFromWorkingAndCountsReassignmentsAndLoad() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID busy = run(workflow, "none");
        String taskId = openTask(PURCHASING, busy, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        JsonNode task = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
        assertThat(api("mani.manager").post(Map.of("expectedVersion", task.path("version").asInt()),
                "/workspaces/{ws}/tasks/{id}/release", PURCHASING, taskId).status()).isEqualTo(200);
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        task = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
        Response reassigned = api("olga.operator").post(Map.of("expectedVersion", task.path("version").asInt(),
                "reason", "Back to the team"), "/workspaces/{ws}/tasks/{id}/reassign", PURCHASING, taskId);
        assertThat(reassigned.status()).as(reassigned.toString()).isEqualTo(200);
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, taskId, "approve", null).status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, busy, "completed", "needsAttention");
        UUID waiting = run(workflow, "none");
        project(List.of(busy, waiting));

        Map<String, Object> fact = jdbc.sql("""
                SELECT t.waiting_seconds, t.working_seconds, t.claims, t.releases, t.reassignments, t.outcome, t.approximate,
                       EXTRACT(EPOCH FROM (c.ended_at - c.created_at)) AS total
                FROM analytics_task t JOIN task_context c ON c.engine_task_id = t.task_id WHERE t.task_id = :id
                """).param("id", taskId).query().singleRow();
        assertThat(fact.get("claims")).isEqualTo(3);
        assertThat(fact.get("releases")).isEqualTo(1);
        assertThat(fact.get("reassignments")).isEqualTo(1);
        assertThat(fact.get("outcome")).isEqualTo("decided");
        assertThat(fact.get("approximate")).isEqualTo(false);
        double split = ((Number) fact.get("waiting_seconds")).doubleValue() + ((Number) fact.get("working_seconds")).doubleValue();
        assertThat(split).isCloseTo(((Number) fact.get("total")).doubleValue(), org.assertj.core.data.Offset.offset(1.0));

        Response response = api("vic.viewer").get(HUMAN + "?workflowId=" + workflow, PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        JsonNode steps = response.body().path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).path("nodeId").asString()).isEqualTo("manager");
        assertThat(steps.get(0).path("finished").asInt()).isEqualTo(1);
        assertThat(steps.get(0).path("reassignments").asInt()).isEqualTo(1);
        int met = steps.get(0).path("slaMet").asInt();
        int missed = steps.get(0).path("slaMissed").asInt();
        assertThat(met + missed).isLessThanOrEqualTo(1);
        assertThat(steps.get(0).path("slaAttainment").isNull()).as("attainment only with a due time").isEqualTo(met + missed == 0);
        JsonNode group = null;
        for (JsonNode g : response.body().path("groups")) {
            if (steps.get(0).path("groupKey").asString().equals(g.path("groupKey").asString())) {
                group = g;
            }
        }
        assertThat(group).isNotNull();
        assertThat(group.path("openNow").asInt()).as("the second request waits at the group").isEqualTo(1);
        assertThat(group.path("openUnassigned").asInt()).isEqualTo(1);
        assertThat(group.path("finished").asInt()).isEqualTo(1);
        assertThat(response.toString()).doesNotContain("mani").doesNotContain("Replacement laptop")
                .doesNotContain("Back to the team");
    }

    @Test
    void kpiTargetsRankOffTargetWorkflowsByDelayCostAndTellTheHooksAboutChanges() throws Exception {
        UUID slow = publishPurchase(PURCHASING, null);
        UUID fine = publishPurchase(PURCHASING, null);
        // Targets are workflow settings: designers set them, viewers cannot.
        assertThat(api("vic.viewer").put(Map.of("cycleP95Seconds", 3600, "expectedVersion", 0), "\"r1\"", TARGETS,
                PURCHASING, slow).status()).isEqualTo(403);
        assertThat(api("dana.designer").put(Map.of("cycleP50Seconds", 7200, "cycleP95Seconds", 3600, "expectedVersion", 0),
                "\"r1\"", TARGETS, PURCHASING, slow).status()).as("p50 over p95").isEqualTo(422);
        assertThat(api("dana.designer").put(Map.of("delayCostPerHour", 50, "expectedVersion", 0), "\"r1\"", TARGETS,
                PURCHASING, slow).status()).as("cost needs a currency").isEqualTo(422);
        Response set = api("dana.designer").put(Map.of("cycleP95Seconds", 3600, "automationRate", 0.5,
                "delayCostPerHour", 50, "currency", "EUR", "expectedVersion", 0), "\"r1\"", TARGETS, PURCHASING, slow);
        assertThat(set.status()).as(set.toString()).isEqualTo(200);
        assertThat(set.body().path("version").asInt()).isEqualTo(1);
        assertThat(api("dana.designer").put(Map.of("cycleP95Seconds", 60, "expectedVersion", 0), "\"r1\"", TARGETS,
                PURCHASING, slow).status()).as("stale version").isEqualTo(409);
        assertThat(api("dana.designer").put(Map.of("cycleP95Seconds", 86400, "expectedVersion", 0), "\"r1\"", TARGETS,
                PURCHASING, fine).status()).isEqualTo(200);
        assertThat(api("vic.viewer").get(TARGETS, PURCHASING, slow).body().path("cycleP95Seconds").asLong()).isEqualTo(3600);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'analytics.kpi-targets.updated'"
                + " AND resource_id = :wf").param("wf", slow.toString()).query(Integer.class).single()).isEqualTo(1);

        // Six completed requests of two hours each for the slow workflow, six of ten minutes for the other.
        seed(slow, 6, 7200);
        seed(fine, 6, 600);
        Response overview = api("vic.viewer").get(KPIS, PURCHASING);
        assertThat(overview.status()).as(overview.toString()).isEqualTo(200);
        List<String> order = new ArrayList<>();
        JsonNode slowItem = null;
        for (JsonNode item : overview.body().path("items")) {
            String id = item.path("workflow").path("id").asString();
            if (id.equals(slow.toString()) || id.equals(fine.toString())) {
                order.add(id);
            }
            if (id.equals(slow.toString())) {
                slowItem = item;
            }
        }
        assertThat(order).containsExactly(slow.toString(), fine.toString());
        assertThat(slowItem.path("offTarget").asInt()).isEqualTo(2);
        assertThat(slowItem.path("excessHours").asDouble()).isEqualTo(6.0);
        assertThat(slowItem.path("delayCost").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(slowItem.path("currency").asString()).isEqualTo("EUR");
        Map<String, String> statuses = new LinkedHashMap<>();
        slowItem.path("measures").forEach(m -> statuses.put(m.path("measure").asString(), m.path("status").asString()));
        assertThat(statuses).containsEntry("cycleP95", "offTarget").containsEntry("automationRate", "offTarget");

        List<KpiAlertHook.KpiStatusChange> heard = new CopyOnWriteArrayList<>();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("recorder", (KpiAlertHook) heard::add);
        KpiEvaluator evaluator = new KpiEvaluator(jdbc, transactions, queries, beans.getBeanProvider(KpiAlertHook.class),
                clock);
        evaluator.evaluate();
        assertThat(heard).filteredOn(c -> c.workflowId().equals(slow))
                .extracting(KpiAlertHook.KpiStatusChange::measure, KpiAlertHook.KpiStatusChange::status)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("cycleP95", "offTarget"),
                        org.assertj.core.groups.Tuple.tuple("automationRate", "offTarget"));
        heard.clear();
        evaluator.evaluate();
        assertThat(heard).as("no change, no call").filteredOn(c -> c.workflowId().equals(slow)).isEmpty();
        // Clearing all targets removes them and their states.
        assertThat(api("dana.designer").put(Map.of("expectedVersion", 1), "\"r1\"", TARGETS, PURCHASING, slow).status())
                .isEqualTo(200);
        assertThat(api("vic.viewer").get(TARGETS, PURCHASING, slow).body().path("version").asInt()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_kpi_state WHERE workflow_id = :wf").param("wf", slow)
                .query(Integer.class).single()).isZero();
    }

    /** Completed runs straight into the read model (no human task), ended an hour ago. */
    private void seed(UUID workflow, int count, int cycleSeconds) {
        UUID release = UUID.fromString(jdbc.sql("SELECT active_release_id FROM workflow WHERE id = :id").param("id", workflow)
                .query(String.class).single());
        Instant ended = clock.instant().minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                    INSERT INTO analytics_run (run_id, workspace_id, workflow_id, release_id, lifecycle, outcome_kind,
                                               started_at, ended_at, cycle_seconds, human_tasks, automated, projected_at)
                    VALUES (:id, :ws, :wf, :rel, 'completed', 'success', :started, :ended, :cycle, 1, false, :ended)
                    """).param("id", UUID.randomUUID()).param("ws", PURCHASING).param("wf", workflow).param("rel", release)
                    .param("started", Sql.ts(ended.minusSeconds(cycleSeconds))).param("ended", Sql.ts(ended))
                    .param("cycle", (double) cycleSeconds).update();
        }
    }

    @Test
    void factsOfARunThatNoLongerExistsAreRemoved() {
        UUID gone = UUID.randomUUID();
        UUID workflow = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO analytics_run (run_id, workspace_id, workflow_id, release_id, lifecycle, started_at, projected_at)
                VALUES (:id, :ws, :wf, :wf, 'running', :now, :now)
                """).param("id", gone).param("ws", PURCHASING).param("wf", workflow).param("now", Sql.ts(now)).update();
        jdbc.sql("""
                INSERT INTO analytics_outbox (workspace_id, run_id, reason, created_at, next_attempt_at)
                VALUES (:ws, :id, 'backfill', :now, :now)
                """).param("ws", PURCHASING).param("id", gone).param("now", Sql.ts(now)).update();
        assertThat(projector.runFor(List.of(gone))).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_run WHERE run_id = :id").param("id", gone)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM analytics_outbox WHERE run_id = :id").param("id", gone)
                .query(Integer.class).single()).isZero();
    }
}
