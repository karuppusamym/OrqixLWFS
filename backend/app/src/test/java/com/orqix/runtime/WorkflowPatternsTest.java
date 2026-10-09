package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.node.ObjectNode;

/** Executes the new canonical patterns through publish, engine jobs and the public API. */
class WorkflowPatternsTest extends RuntimeTestBase {
    @org.springframework.beans.factory.annotation.Autowired
    private org.flowable.engine.ProcessEngine processEngine;

    private java.util.List<String> eventTypes(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT type FROM run_event WHERE workspace_id = :ws AND run_id = :id ORDER BY seq")
                .param("ws", workspaceId).param("id", runId).query(String.class).list();
    }
    private void graph(ObjectNode d, String nodes, String transitions) {
        d.set("nodes", json.parse(nodes));
        d.set("transitions", json.parse(transitions));
        d.set("decisions", json.parse("[]"));
        d.set("outputMapping", json.object());
        d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        d.set("dependencies", json.parse("{\"connections\":[]}"));
        d.remove("uiLayout");
    }

    private static final String START = "{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}}";
    private static final String END = "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Completed\",\"outcomeKind\":\"success\"}}";
    private static final String REJECT = "{\"id\":\"reject\",\"type\":\"end\",\"label\":\"Rejected\",\"config\":{\"businessOutcome\":\"Rejected\",\"outcomeKind\":\"rejected\"}}";

    private String approval(String id, String extra) {
        return "{\"id\":\"" + id + "\",\"type\":\"approval\",\"label\":\"Review " + id
                + "\",\"config\":{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true" + extra + "}}";
    }

    private String flow(String id, String from, String to, String on) {
        return "{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"on\":\"" + on + "\"}";
    }

    @Test
    void waitSurvivesExecutorRestartAndResumesOnce() throws Exception {
        UUID wf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + ","
                + "{\"id\":\"pause\",\"type\":\"wait\",\"label\":\"Pause\",\"config\":{\"duration\":\"PT1H\"}}," + END + "]",
                "[" + flow("t1", "start", "pause", "next") + "," + flow("t2", "pause", "end", "next") + "]"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, wf, BigDecimal.TEN).path("id").asString());
        var detail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, run);
        assertThat(detail.body().path("currentSteps").get(0).path("kind").asString()).isEqualTo("wait");
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().shutdown();
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().start();
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(eventTypes(PURCHASING, run)).contains("WAIT_STARTED", "WAIT_ENDED", "RUN_COMPLETED");
    }

    @Test
    void approvalTimeLimitCancelsTaskAndTakesTimeoutRoute() throws Exception {
        UUID wf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + approval("review", ",\"timeoutAfter\":\"PT1H\"") + "," + END + "," + REJECT + "]",
                "[" + flow("t1", "start", "review", "next") + "," + flow("t2", "review", "end", "approved") + ","
                        + flow("t3", "review", "reject", "rejected") + "," + flow("t4", "review", "reject", "timedOut") + "]"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, wf, BigDecimal.TEN).path("id").asString());
        assertThat(openTask(PURCHASING, run, "review")).isNotNull();
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(openTask(PURCHASING, run, "review")).isNull();
        assertThat(eventTypes(PURCHASING, run)).contains("TASK_TIMED_OUT");
        assertThat(jdbc.sql("SELECT status FROM task_context WHERE run_id = :id").param("id", run).query(String.class).single()).isEqualTo("cancelled");
    }

    private UUID parallel() throws Exception {
        return publishPurchase(PURCHASING, d -> graph(d, "[" + START + ","
                + "{\"id\":\"split\",\"type\":\"parallelSplit\",\"label\":\"Together\",\"config\":{\"joinRef\":\"join\"}},"
                + approval("one", "") + "," + approval("two", "") + ","
                + "{\"id\":\"join\",\"type\":\"parallelJoin\",\"label\":\"All done\",\"config\":{}}," + END + "," + REJECT + "]",
                "[" + String.join(",", flow("t1", "start", "split", "next"), flow("t2", "split", "one", "next"), flow("t3", "split", "two", "next"),
                        flow("t4", "one", "join", "approved"), flow("t5", "two", "join", "approved"), flow("t6", "one", "reject", "rejected"),
                        flow("t7", "two", "reject", "rejected"), flow("t8", "join", "end", "next")) + "]"));
    }

    @Test
    void parallelJoinWaitsForBothApprovalsAndContinuesOnce() throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, parallel(), BigDecimal.TEN).path("id").asString());
        approve("mani.manager", PURCHASING, run, "one");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        approve("max.manager", PURCHASING, run, "two");
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(eventTypes(PURCHASING, run).stream().filter("RUN_COMPLETED"::equals).count()).isEqualTo(1);
    }

    @Test
    void rejectionCancelsOtherParallelApproval() throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, parallel(), BigDecimal.TEN).path("id").asString());
        String task = openTask(PURCHASING, run, "one").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, task).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, task, "reject", "Not needed").status()).isEqualTo(201);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("completed");
        assertThat(openTask(PURCHASING, run, "two")).isNull();
    }

    private UUID parent(UUID child) throws Exception {
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, child).body().path("key").asString();
        return publishPurchase(PURCHASING, d -> graph(d, "[" + START + ","
                + "{\"id\":\"child\",\"type\":\"startWorkflow\",\"label\":\"Purchase\",\"config\":{\"workflowRef\":\"" + key
                + "\",\"inputMapping\":{\"title\":\"/input/title\",\"purpose\":\"/input/purpose\",\"amount\":\"/input/amount\",\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{}}}," + END + "]",
                "[" + flow("t1", "start", "child", "next") + "," + flow("t2", "child", "end", "next") + "]"));
    }

    private UUID childOf(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single() == 1);
        return jdbc.sql("SELECT id FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(UUID.class).single();
    }

    @Test
    void childRunUsesPublishedPinAndResumesParent() throws Exception {
        UUID childWf = publishPurchase(PURCHASING, null);
        UUID parentWf = parent(childWf);
        String pinnedRelease = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, childWf)
                .body().path("activeRelease").path("id").asString();
        var draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, childWf).body();
        ObjectNode changed = (ObjectNode) draft.path("definition");
        changed.put("name", "Republished purchase");
        int revision = draft.path("revision").asInt();
        assertThat(api("dana.designer").put(Map.of("definition", changed), "\"r" + revision + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, childWf).status()).isEqualTo(200);
        assertThat(api("paul.publisher").post(Map.of("draftRevision", revision + 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, childWf).status()).isEqualTo(201);
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, parentWf, BigDecimal.TEN).path("id").asString());
        UUID child = childOf(run);
        assertThat(jdbc.sql("SELECT release_id::text FROM workflow_run WHERE id = :id").param("id", child)
                .query(String.class).single()).isEqualTo(pinnedRelease);
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body().path("currentSteps").get(0).path("kind").asString()).isEqualTo("child");
        approve("mani.manager", PURCHASING, child, "manager");
        awaitLifecycle(PURCHASING, child, "completed");
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(eventTypes(PURCHASING, run)).contains("CHILD_STARTED", "CHILD_ENDED", "RUN_COMPLETED");
    }

    @Test
    void childThatCompletesImmediatelyStillResumesParentOnce() throws Exception {
        UUID child = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + END + "]",
                "[" + flow("t1", "start", "end", "next") + "]"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, parent(child), BigDecimal.TEN).path("id").asString());
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(eventTypes(PURCHASING, run).stream().filter("CHILD_ENDED"::equals).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void cancellingParentCancelsItsChildAndOpenTasks() throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, parent(publishPurchase(PURCHASING, null)), BigDecimal.TEN).path("id").asString());
        UUID child = childOf(run);
        var detail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body();
        var cancelled = api("olga.operator").post(Map.of("expectedVersion", detail.path("version").asInt(), "reason", "No longer needed"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
        assertThat(lifecycle(PURCHASING, child)).isEqualTo("cancelled");
        assertThat(openTask(PURCHASING, child, "manager")).isNull();
    }
}
