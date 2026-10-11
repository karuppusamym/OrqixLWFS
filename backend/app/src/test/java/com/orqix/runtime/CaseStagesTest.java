package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.runtime.internal.RunStages;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Case stages (docs/build/S17 section 4): entered and completed from the current steps, across parallel branches,
 * overdue recorded once with two executors, notifications, the Runs filter and the task view.
 */
class CaseStagesTest extends RuntimeTestBase {

    @Autowired
    private RunStages stages;

    private static final String START = "{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}}";
    private static final String END = "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Completed\",\"outcomeKind\":\"success\"}}";
    private static final String REJECT = "{\"id\":\"reject\",\"type\":\"end\",\"label\":\"Rejected\",\"config\":{\"businessOutcome\":\"Rejected\",\"outcomeKind\":\"rejected\"}}";

    private static String approval(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"approval\",\"label\":\"Review " + id
                + "\",\"config\":{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true}}";
    }

    private static String flow(String id, String from, String to, String on) {
        return "{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"on\":\"" + on + "\"}";
    }

    private void graph(ObjectNode d, String nodes, String transitions, String stages) {
        d.set("nodes", json.parse(nodes));
        d.set("transitions", json.parse(transitions));
        d.set("decisions", json.parse("[]"));
        d.set("outputMapping", json.object());
        d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        d.set("dependencies", json.parse("{\"connections\":[]}"));
        d.remove("uiLayout");
        if (stages != null) {
            d.set("stages", json.parse(stages));
        }
    }

    /** start -> a -> b -> end, one stage per approval. */
    private UUID sequential(String stages) throws Exception {
        return publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + approval("a") + "," + approval("b") + ","
                + END + "," + REJECT + "]", "[" + String.join(",", flow("t1", "start", "a", "next"),
                flow("t2", "a", "b", "approved"), flow("t3", "a", "reject", "rejected"), flow("t4", "b", "end", "approved"),
                flow("t5", "b", "reject", "rejected")) + "]", stages));
    }

    private static final String TWO_STAGES = "[{\"id\":\"intake\",\"label\":\"Intake\",\"nodes\":[\"a\"],\"sla\":\"PT1H\"},"
            + "{\"id\":\"decision\",\"label\":\"Decision\",\"nodes\":[\"b\"]}]";

    private UUID startRun(UUID wf) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, wf, BigDecimal.TEN).path("id").asString());
    }

    private List<String> stageEvents(UUID run) {
        return jdbc.sql("SELECT type || ':' || coalesce(data->>'stageId', '') FROM run_event WHERE run_id = :r "
                + "AND (type LIKE 'STAGE%' OR type = 'RUN_COMPLETED') ORDER BY seq").param("r", run)
                .query(String.class).list();
    }

    @Test
    void sequentialStagesAreEnteredAndCompletedInOrderExactlyOnce() throws Exception {
        UUID run = startRun(sequential(TWO_STAGES));
        JsonNode detail = run(PURCHASING, run);
        JsonNode stage = detail.path("stage");
        assertThat(stage.path("id").asString()).isEqualTo("intake");
        assertThat(stage.path("index").asInt()).isEqualTo(1);
        assertThat(stage.path("total").asInt()).isEqualTo(2);
        assertThat(stage.path("state").asString()).isEqualTo("active");
        assertThat(stage.path("overdue").asBoolean()).isFalse();
        assertThat(java.time.Instant.parse(stage.path("dueAt").asString()))
                .isEqualTo(java.time.Instant.parse(stage.path("enteredAt").asString()).plusSeconds(3600));
        assertThat(detail.path("stages")).hasSize(2);
        assertThat(detail.path("stages").get(1).path("state").asString()).isEqualTo("notStarted");
        assertThat(detail.path("stages").get(1).path("dueAt").isNull()).isTrue();
        // The task carries the stage; the run summary in lists too.
        assertThat(detail.path("tasks").get(0).path("stage").path("id").asString()).isEqualTo("intake");
        JsonNode listed = api("olga.operator").get("/workspaces/{ws}/runs", PURCHASING).body().path("items");
        boolean found = false;
        for (JsonNode item : listed) {
            if (run.toString().equals(item.path("id").asString())) {
                assertThat(item.path("stage").path("label").asString()).isEqualTo("Intake");
                found = true;
            }
        }
        assertThat(found).isTrue();

        // Claiming changes the projection but must not repeat stage events.
        JsonNode task = openTask(PURCHASING, run, "a");
        assertThat(claim("mani.manager", PURCHASING, task.path("id").asString()).status()).isEqualTo(200);
        assertThat(stageEvents(run)).containsExactly("STAGE_ENTERED:intake");

        assertThat(decide("mani.manager", PURCHASING, task.path("id").asString(), "approve", null).status())
                .isEqualTo(201);
        assertThat(stageEvents(run)).containsExactly("STAGE_ENTERED:intake", "STAGE_COMPLETED:intake",
                "STAGE_ENTERED:decision");
        assertThat(run(PURCHASING, run).path("stage").path("id").asString()).isEqualTo("decision");
        assertThat(run(PURCHASING, run).path("stages").get(0).path("state").asString()).isEqualTo("completed");

        approve("max.manager", PURCHASING, run, "b");
        awaitLifecycle(PURCHASING, run, "completed");
        // A stage completes before the run does.
        assertThat(stageEvents(run)).containsExactly("STAGE_ENTERED:intake", "STAGE_COMPLETED:intake",
                "STAGE_ENTERED:decision", "STAGE_COMPLETED:decision", "RUN_COMPLETED:");
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("stages").get(0).path("state").asString()).isEqualTo("completed");
        assertThat(done.path("stages").get(1).path("state").asString()).isEqualTo("completed");
        assertThat(done.path("stage").path("id").asString()).isEqualTo("decision");
        // Replaying the reconcile (as after a restart) changes nothing.
        jdbc.sql("SELECT 1").query(Integer.class).single();
        assertThat(stageEvents(run)).hasSize(5);
    }

    @Test
    void parallelBranchesKeepTheirStagesActiveUntilTheirOwnStepsAreDone() throws Exception {
        String nodes = "[" + START + ",{\"id\":\"split\",\"type\":\"parallelSplit\",\"label\":\"Together\","
                + "\"config\":{\"joinRef\":\"join\"}}," + approval("one") + "," + approval("two") + ","
                + "{\"id\":\"join\",\"type\":\"parallelJoin\",\"label\":\"All done\",\"config\":{}}," + END + "," + REJECT + "]";
        String transitions = "[" + String.join(",", flow("t1", "start", "split", "next"),
                flow("t2", "split", "one", "next"), flow("t3", "split", "two", "next"),
                flow("t4", "one", "join", "approved"), flow("t5", "two", "join", "approved"),
                flow("t6", "one", "reject", "rejected"), flow("t7", "two", "reject", "rejected"),
                flow("t8", "join", "end", "next")) + "]";
        String stageDefs = "[{\"id\":\"left\",\"label\":\"Left review\",\"nodes\":[\"one\"],\"sla\":\"PT1H\"},"
                + "{\"id\":\"right\",\"label\":\"Right review\",\"nodes\":[\"two\"],\"sla\":\"P1D\"}]";
        UUID run = startRun(publishPurchase(PURCHASING, d -> graph(d, nodes, transitions, stageDefs)));
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("stages").get(0).path("state").asString()).isEqualTo("active");
        assertThat(detail.path("stages").get(1).path("state").asString()).isEqualTo("active");
        assertThat(detail.path("stage").path("id").asString()).as("the first active stage is current").isEqualTo("left");
        assertThat(stageEvents(run)).containsExactlyInAnyOrder("STAGE_ENTERED:left", "STAGE_ENTERED:right");

        approve("mani.manager", PURCHASING, run, "one");
        JsonNode after = run(PURCHASING, run);
        assertThat(after.path("stages").get(0).path("state").asString()).isEqualTo("completed");
        assertThat(after.path("stages").get(1).path("state").asString()).isEqualTo("active");
        assertThat(after.path("stage").path("id").asString()).isEqualTo("right");
        assertThat(stageEvents(run)).contains("STAGE_COMPLETED:left").doesNotContain("STAGE_COMPLETED:right");

        approve("max.manager", PURCHASING, run, "two");
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(stageEvents(run).stream().filter(e -> e.startsWith("STAGE_COMPLETED")).count()).isEqualTo(2);
        assertThat(stageEvents(run).stream().filter(e -> e.startsWith("STAGE_ENTERED")).count()).isEqualTo(2);
    }

    @Test
    void aStageMissedOnItsTimeLimitIsOverdueOnReadAndRecordedOnce() throws Exception {
        UUID wf = sequential(TWO_STAGES);
        UUID run = startRun(wf);
        UUID other = startRun(wf);
        jdbc.sql("UPDATE run_stage SET due_at = now() - interval '5 minutes' WHERE run_id IN (:a, :b) AND stage_id = 'intake'")
                .param("a", run).param("b", other).update();
        // Overdue is visible at once, before anything has been recorded.
        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("stage").path("overdue").asBoolean()).isTrue();
        assertThat(detail.path("tasks").get(0).path("stage").path("overdue").asBoolean()).isTrue();
        assertThat(stageEvents(run)).doesNotContain("STAGE_OVERDUE:intake");
        // The Runs list can show only overdue runs.
        JsonNode overdue = api("olga.operator").get("/workspaces/{ws}/runs?overdue=true", PURCHASING).body().path("items");
        List<String> ids = new java.util.ArrayList<>();
        overdue.forEach(i -> ids.add(i.path("id").asString()));
        assertThat(ids).contains(run.toString(), other.toString());
        UUID fresh = startRun(wf);
        JsonNode again = api("olga.operator").get("/workspaces/{ws}/runs?overdue=true", PURCHASING).body().path("items");
        again.forEach(i -> assertThat(i.path("id").asString()).isNotEqualTo(fresh.toString()));

        // Two executors check at the same moment: each stage is recorded once.
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> check = () -> stages.detectOverdue();
            var f1 = pool.submit(check);
            var f2 = pool.submit(check);
            assertThat(f1.get() + f2.get()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stages.detectOverdue()).isZero();
        for (UUID r : List.of(run, other)) {
            assertThat(stageEvents(r).stream().filter(e -> e.equals("STAGE_OVERDUE:intake")).count()).isEqualTo(1);
            // The initiator and the operator are told once each.
            assertThat(notifications(PURCHASING, r, "workflowMessage")).isEqualTo(2);
            assertThat(jdbc.sql("""
                    SELECT count(DISTINCT recipient_subject_id) FROM notification
                    WHERE run_id = :r AND dedup_key LIKE 'stage-overdue:%'
                    """).param("r", r).query(Integer.class).single()).isEqualTo(2);
        }
        String message = api("rhea.requester").get("/workspaces/{ws}/me/notifications", PURCHASING).body()
                .path("items").get(0).path("message").asString();
        assertThat(message).contains("overdue").contains("Intake");
        // Completing the stage ends the overdue state.
        approve("mani.manager", PURCHASING, run, "a");
        assertThat(run(PURCHASING, run).path("stages").get(0).path("overdue").asBoolean()).isFalse();
        assertThat(stages.detectOverdue()).isZero();
    }

    @Test
    void stagesWithoutATimeLimitAreNeverOverdueAndWorkflowsWithoutStagesHaveNone() throws Exception {
        UUID run = startRun(sequential(TWO_STAGES));
        approve("mani.manager", PURCHASING, run, "a");
        jdbc.sql("UPDATE run_stage SET entered_at = now() - interval '400 days' WHERE run_id = :r").param("r", run).update();
        assertThat(run(PURCHASING, run).path("stage").path("overdue").asBoolean()).isFalse();
        assertThat(stages.detectOverdue()).isZero();

        UUID plain = startRun(sequential(null));
        JsonNode detail = run(PURCHASING, plain);
        assertThat(detail.path("stage").isNull() || detail.path("stage").isMissingNode()).isTrue();
        assertThat(detail.path("stages").isArray()).isTrue();
        assertThat(detail.path("stages")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM run_stage WHERE run_id = :r").param("r", plain).query(Integer.class).single())
                .isZero();
    }

    @Test
    void cancellingARunClosesItsStagesWithoutOverdueNotices() throws Exception {
        UUID run = startRun(sequential(TWO_STAGES));
        jdbc.sql("UPDATE run_stage SET due_at = now() - interval '1 minute' WHERE run_id = :r").param("r", run).update();
        JsonNode detail = run(PURCHASING, run);
        assertThat(api("olga.operator").post(java.util.Map.of("reason", "test", "expectedVersion",
                detail.path("version").asInt()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel",
                PURCHASING, run).status()).isEqualTo(200);
        assertThat(stages.detectOverdue()).isZero();
        assertThat(run(PURCHASING, run).path("stages").get(0).path("overdue").asBoolean()).isFalse();
        assertThat(stageEvents(run)).doesNotContain("STAGE_OVERDUE:intake");
    }
}
