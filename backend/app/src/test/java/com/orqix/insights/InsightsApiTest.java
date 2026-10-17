package com.orqix.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * Insights (docs/build/S7-frontend.md section 4): access (run.read only), parameter validation, aggregates over real
 * runs through the public API, no request data in any response, and the bottleneck, timeout and overdue figures.
 * Every response is validated against contracts/openapi.yaml by the test client.
 */
class InsightsApiTest extends RuntimeTestBase {

    private static final String SUMMARY = "/workspaces/{ws}/insights/summary";

    private UUID runWithOutcome(UUID workflow, String outcome) throws Exception {
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

    @Test
    void onlyPeopleWithRunReadSeeInsightsAndForeignWorkspacesSeeNothing() throws Exception {
        for (String path : new String[] {SUMMARY, "/workspaces/{ws}/insights/throughput", "/workspaces/{ws}/insights/steps"}) {
            assertThat(api("rhea.requester").get(path, PURCHASING).status()).as("requester " + path).isEqualTo(403);
            assertThat(api("mani.manager").get(path, PURCHASING).status()).as("approver " + path).isEqualTo(403);
            assertThat(api("vic.viewer").get(path, PURCHASING).status()).as("viewer " + path).isEqualTo(200);
            assertThat(api("olga.operator").get(path, PURCHASING).status()).as("operator " + path).isEqualTo(200);
            assertThat(api("otto.outsider").get(path, PURCHASING).status()).as("outsider " + path).isEqualTo(404);
            assertThat(api("fred.facilities").get(path, PURCHASING).status()).as("other workspace " + path).isEqualTo(404);
        }
        // A caller without access never learns whether the parameters were valid.
        assertThat(api("rhea.requester").get(SUMMARY + "?from=garbage", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get(SUMMARY + "?from=garbage", PURCHASING).status()).isEqualTo(404);
    }

    @Test
    void parametersAreValidated() throws Exception {
        assertThat(api("vic.viewer").get(SUMMARY + "?from=%2B999999999-12-31&to=%2B999999999-12-31", PURCHASING).status()).isEqualTo(400);
        assertThat(api("vic.viewer").get(SUMMARY + "?from=2025-01-01&to=2026-01-02", PURCHASING).status())
                .as("367 days").isEqualTo(400);
        assertThat(api("vic.viewer").get(SUMMARY + "?from=2026-03-01&to=2026-02-01", PURCHASING).status())
                .as("from after to").isEqualTo(400);
        assertThat(api("vic.viewer").get(SUMMARY + "?from=yesterday", PURCHASING).status()).isEqualTo(400);
        assertThat(api("vic.viewer").get(SUMMARY + "?workflowId=" + UUID.randomUUID(), PURCHASING).status())
                .as("unknown workflow").isEqualTo(404);
        assertThat(api("vic.viewer").get(SUMMARY + "?workflowId=not-a-uuid", PURCHASING).status()).isEqualTo(400);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Response year = api("vic.viewer").get(SUMMARY + "?from=" + today.minusDays(364) + "&to=" + today, PURCHASING);
        assertThat(year.status()).as("exactly 365 days").isEqualTo(200);
        assertThat(year.body().path("range").path("days").asInt()).isEqualTo(365);
    }

    @Test
    void aggregatesFollowTheRunsAndNeverContainRequestData() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        runWithOutcome(workflow, "approve");
        runWithOutcome(workflow, "reject");
        UUID open = runWithOutcome(workflow, "none");
        String reference = run(PURCHASING, open).path("reference").asString();
        String filter = "?workflowId=" + workflow;

        Response summary = api("vic.viewer").get(SUMMARY + filter, PURCHASING);
        assertThat(summary.status()).as(summary.toString()).isEqualTo(200);
        JsonNode totals = summary.body().path("totals");
        assertThat(totals.path("started").asInt()).isEqualTo(3);
        assertThat(totals.path("completed").asInt()).isEqualTo(2);
        assertThat(totals.path("fulfilled").asInt()).isEqualTo(1);
        assertThat(totals.path("rejected").asInt()).isEqualTo(1);
        assertThat(totals.path("open").asInt()).isEqualTo(1);
        assertThat(totals.path("cancelled").asInt()).isZero();
        assertThat(summary.body().path("range").path("days").asInt()).isEqualTo(30);
        JsonNode cycle = summary.body().path("cycleTimes");
        assertThat(cycle).hasSize(1);
        assertThat(cycle.get(0).path("completed").asInt()).isEqualTo(2);
        assertThat(cycle.get(0).path("p90Seconds").asLong()).isGreaterThanOrEqualTo(cycle.get(0).path("medianSeconds").asLong());
        assertThat(summary.body().path("oldestOpen")).hasSize(1);
        assertThat(summary.body().path("oldestOpen").get(0).path("reference").asString()).isEqualTo(reference);
        assertThat(summary.body().path("oldestOpen").get(0).path("stepLabel").asString()).isEqualTo("Review request");

        // Aggregates only: nothing the requester typed is in any response.
        for (String path : new String[] {SUMMARY, "/workspaces/{ws}/insights/throughput", "/workspaces/{ws}/insights/steps"}) {
            String text = api("vic.viewer").get(path + filter, PURCHASING).toString();
            assertThat(text).doesNotContain("Replacement laptop").doesNotContain("Device failed").doesNotContain("OPS");
        }

        Response throughput = api("vic.viewer").get("/workspaces/{ws}/insights/throughput" + filter, PURCHASING);
        JsonNode days = throughput.body().path("days");
        assertThat(days).hasSize(30);
        int started = 0;
        int completed = 0;
        for (JsonNode day : days) {
            started += day.path("started").asInt();
            completed += day.path("completed").asInt();
            assertThat(day.path("fulfilled").asInt() + day.path("rejected").asInt()).isEqualTo(day.path("completed").asInt());
        }
        assertThat(started).isEqualTo(3);
        assertThat(completed).isEqualTo(2);
        assertThat(days.get(29).path("date").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());

        Response steps = api("vic.viewer").get("/workspaces/{ws}/insights/steps" + filter, PURCHASING);
        JsonNode manager = null;
        for (JsonNode step : steps.body().path("items")) {
            if ("manager".equals(step.path("nodeId").asString())) {
                manager = step;
            }
        }
        assertThat(manager).as(steps.toString()).isNotNull();
        assertThat(manager.path("kind").asString()).isEqualTo("approval");
        assertThat(manager.path("label").asString()).isEqualTo("Review request");
        assertThat(manager.path("count").asInt()).isEqualTo(2);
        assertThat(manager.path("openNow").asInt()).isEqualTo(1);
        assertThat(manager.path("averageWaitSeconds").isNumber()).isTrue();
        assertThat(manager.path("p90WaitSeconds").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(manager.path("timeouts").asInt()).isZero();
        assertThat(steps.body().path("truncated").asBoolean()).isFalse();
    }

    @Test
    void messageWaitTimeoutIsIncludedInStepBottleneckStatistics() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID id = runWithOutcome(workflow, "none");
        jdbc.sql("""
                INSERT INTO run_event(run_id, workspace_id, seq, type, node_id, occurred_at, data)
                VALUES (:run, :ws, 100, 'WAIT_STARTED', 'payment', now() - interval '10 minutes', '{"label":"Wait for payment"}'::jsonb),
                       (:run, :ws, 101, 'WAIT_ENDED', 'payment', now(), '{"outcome":"timedOut"}'::jsonb)
                """).param("run", id).param("ws", PURCHASING).update();
        var response = api("olga.operator").get("/workspaces/{ws}/insights/steps?workflowId=" + workflow, PURCHASING);
        assertThat(response.status()).isEqualTo(200);
        java.util.List<JsonNode> waits = new java.util.ArrayList<>();
        response.body().path("items").forEach(row -> { if ("payment".equals(row.path("nodeId").asString())) waits.add(row); });
        assertThat(waits).singleElement().satisfies(row -> {
            assertThat(row.path("timeouts").asInt()).isEqualTo(1);
            assertThat(row.path("timeoutShare").asDouble()).isEqualTo(1);
            assertThat(row.path("averageWaitSeconds").asLong()).isEqualTo(600);
        });
    }

    @Test
    void timeoutsAndOverdueApprovalsAppearInTheSlaView() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID waiting = runWithOutcome(workflow, "none");
        UUID timedOut = runWithOutcome(workflow, "none");
        // The first request's approval is overdue.
        jdbc.sql("UPDATE task_context SET due_at = now() - interval '3 hours' WHERE run_id = :run AND status = 'open'")
                .param("run", waiting).update();
        // The second request's approval reached its time limit (what the timer does: task cancelled, TASK_TIMED_OUT recorded).
        jdbc.sql("UPDATE task_context SET status = 'cancelled', ended_at = now() WHERE run_id = :run AND status = 'open'")
                .param("run", timedOut).update();
        jdbc.sql("""
                INSERT INTO run_event (run_id, seq, workspace_id, type, node_id, occurred_at, data)
                SELECT run_id, max(seq) + 1, workspace_id, 'TASK_TIMED_OUT', 'manager', now(), '{"label":"Review request"}'::jsonb
                FROM run_event WHERE run_id = :run GROUP BY run_id, workspace_id
                """).param("run", timedOut).update();

        String filter = "?workflowId=" + workflow;
        JsonNode sla = api("olga.operator").get(SUMMARY + filter, PURCHASING).body().path("sla");
        assertThat(sla.path("overdueCount").asInt()).isEqualTo(1);
        assertThat(sla.path("overdue")).hasSize(1);
        assertThat(sla.path("overdue").get(0).path("kind").asString()).isEqualTo("approval");
        assertThat(sla.path("overdue").get(0).path("overdueSeconds").asLong()).isGreaterThanOrEqualTo(3 * 3600 - 5);
        assertThat(sla.path("timeouts")).hasSize(1);
        assertThat(sla.path("timeouts").get(0).path("timeouts").asInt()).isEqualTo(1);
        assertThat(sla.path("timeouts").get(0).path("share").asDouble()).isEqualTo(1.0);

        JsonNode steps = api("olga.operator").get("/workspaces/{ws}/insights/steps" + filter, PURCHASING).body().path("items");
        JsonNode manager = null;
        for (JsonNode step : steps) {
            if ("manager".equals(step.path("nodeId").asString())) {
                manager = step;
            }
        }
        assertThat(manager).isNotNull();
        assertThat(manager.path("timeouts").asInt()).isEqualTo(1);
        assertThat(manager.path("count").asInt()).isEqualTo(1);
        assertThat(manager.path("timeoutShare").asDouble()).isEqualTo(1.0);
        assertThat(manager.path("openNow").asInt()).isEqualTo(1);
    }
}
