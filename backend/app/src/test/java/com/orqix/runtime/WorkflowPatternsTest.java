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

    private UUID repeatParent(UUID child, String outcome, int bound) throws Exception {
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, child).body().path("key").asString();
        return publishPurchase(PURCHASING, d -> {
            graph(d, "[" + START + ","
                    + "{\"id\":\"child\",\"type\":\"startWorkflow\",\"label\":\"Review until accepted\",\"config\":{\"workflowRef\":\"" + key
                    + "\",\"inputMapping\":{\"title\":\"/input/title\",\"purpose\":\"/input/purpose\",\"amount\":\"/input/amount\",\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{},"
                    + "\"repeat\":{\"maxIterations\":" + bound + ",\"untilOutcome\":\"" + outcome + "\"}}}," + END + "]",
                    "[" + flow("t1", "start", "child", "next") + "," + flow("t2", "child", "end", "next") + "]");
            d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"iterations\":{\"type\":\"integer\",\"title\":\"Iterations\"},\"limitReached\":{\"type\":\"boolean\",\"title\":\"Limit reached\"}}}"));
            d.set("outputMapping", json.parse("{\"iterations\":{\"fieldRef\":\"/steps/child/iterations\",\"required\":false},\"limitReached\":{\"fieldRef\":\"/steps/child/limitReached\",\"required\":false}}"));
        });
    }

    private UUID occurrence(UUID parent, int occurrence) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> fastForwardAndCount(parent, occurrence) == 1);
        return jdbc.sql("SELECT id FROM workflow_run WHERE parent_run_id = :id AND parent_occurrence = :occ")
                .param("id", parent).param("occ", occurrence).query(UUID.class).single();
    }

    /** Timers (a one-minute wait in the examples) are moved forward so the test does not wait for them. */
    private long fastForwardAndCount(UUID parent, int occurrence) {
        fastForward(PURCHASING, parent);
        return jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id AND parent_occurrence = :occ")
                .param("id", parent).param("occ", occurrence).query(Long.class).single();
    }

    @Test
    void repeatedReviewPreservesRejectedHistoryThenStopsOnApproval() throws Exception {
        UUID childWf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + approval("review", "") + "," + END + "," + REJECT + "]",
                "[" + flow("a", "start", "review", "next") + "," + flow("b", "review", "end", "approved") + "," + flow("c", "review", "reject", "rejected") + "]"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, repeatParent(childWf, "Completed", 3), BigDecimal.TEN).path("id").asString());
        UUID first = occurrence(run, 1);
        String task = openTask(PURCHASING, first, "review").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, task).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, task, "reject", "Please review again").status()).isEqualTo(201);
        UUID second = occurrence(run, 2);
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().shutdown();
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().start();
        approve("mani.manager", PURCHASING, second, "review");
        awaitLifecycle(PURCHASING, run, "completed");
        var detail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body();
        assertThat(detail.path("output").path("iterations").asInt()).isEqualTo(2);
        assertThat(detail.path("output").path("limitReached").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM task_decision WHERE run_id IN (SELECT id FROM workflow_run WHERE parent_run_id = :id)").param("id", run).query(Long.class).single()).isEqualTo(2);
        assertThat(eventTypes(PURCHASING, run).stream().filter("CHILD_ENDED"::equals).count()).isEqualTo(2);
    }

    @Test
    void repeatedRejectionStopsAtBoundAndExposesExhaustion() throws Exception {
        UUID childWf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + approval("review", "") + "," + END + "," + REJECT + "]",
                "[" + flow("a", "start", "review", "next") + "," + flow("b", "review", "end", "approved") + "," + flow("c", "review", "reject", "rejected") + "]"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, repeatParent(childWf, "Completed", 2), BigDecimal.TEN).path("id").asString());
        for (int iteration = 1; iteration <= 2; iteration++) {
            UUID child = occurrence(run, iteration);
            String task = openTask(PURCHASING, child, "review").path("id").asString();
            assertThat(claim("mani.manager", PURCHASING, task).status()).isEqualTo(200);
            assertThat(decide("mani.manager", PURCHASING, task, "reject", "Still needs work").status()).isEqualTo(201);
        }
        awaitLifecycle(PURCHASING, run, "completed");
        var detail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body();
        assertThat(detail.path("output").path("iterations").asInt()).isEqualTo(2);
        assertThat(detail.path("output").path("limitReached").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void sequentialCollectionCreatesDistinctPinnedChildrenInItemOrder() throws Exception {
        UUID childWf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + END + "]", "[" + flow("a", "start", "end", "next") + "]"));
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, childWf).body().path("key").asString();
        UUID parent = publishPurchase(PURCHASING, d -> {
            graph(d, "[" + START + ","
                    + "{\"id\":\"child\",\"type\":\"startWorkflow\",\"label\":\"Each purchase\",\"config\":{\"workflowRef\":\"" + key
                    + "\",\"inputMapping\":{\"purpose\":\"/input/purpose\",\"amount\":\"/input/amount\",\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{},"
                    + "\"forEach\":{\"itemsRef\":\"/input/items\",\"itemInput\":\"title\",\"maxItems\":3}}}," + END + "]",
                    "[" + flow("t1", "start", "child", "next") + "," + flow("t2", "child", "end", "next") + "]");
            ((ObjectNode) d.path("inputSchema").path("properties")).set("items", json.parse("{\"type\":\"array\",\"title\":\"Items\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"string\",\"title\":\"Purchase title\",\"minLength\":1,\"maxLength\":200}}"));
            ((tools.jackson.databind.node.ArrayNode) d.path("inputSchema").path("required")).add("items");
        });
        var started = api("rhea.requester").post(Map.of("workflowId", parent, "input", Map.of("title", "Purchases", "purpose", "Office equipment", "amount", BigDecimal.TEN,
                "costCenter", "IT", "items", java.util.List.of("Laptop", "Monitor", "Keyboard"))), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(jdbc.sql("SELECT input->>'title' FROM workflow_run WHERE parent_run_id = :id ORDER BY parent_occurrence").param("id", run).query(String.class).list())
                .containsExactly("Laptop", "Monitor", "Keyboard");
        assertThat(eventTypes(PURCHASING, run).stream().filter("CHILD_ENDED"::equals).count()).isEqualTo(3);
    }

    private UUID publishExample(String file, String childKey) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            try {
                String generatedKey = d.path("workflowKey").asString();
                ObjectNode source = (ObjectNode) json.parse(java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("orqix.repoRoot"), "examples", file)));
                source.put("workflowKey", generatedKey);
                if (childKey != null) for (var node : source.path("nodes")) {
                    if (node.path("type").asString().equals("startWorkflow")) ((ObjectNode) node.path("config")).put("workflowRef", childKey);
                }
                d.removeAll();
                d.setAll(source);
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
    }

    @Test
    void simpleMediumAndComplexExamplesPublishAndExecuteThroughThePublicApi() throws Exception {
        UUID simple = publishExample("simple-review.json", null);
        UUID simpleRun = UUID.fromString(start("rhea.requester", PURCHASING, simple, BigDecimal.TEN).path("id").asString());
        approve("mani.manager", PURCHASING, simpleRun, "review");
        awaitLifecycle(PURCHASING, simpleRun, "completed");
        String childKey = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, simple).body().path("key").asString();
        UUID mediumRun = UUID.fromString(start("rhea.requester", PURCHASING, publishExample("medium-bounded-rework.json", childKey), BigDecimal.TEN).path("id").asString());
        approve("mani.manager", PURCHASING, occurrence(mediumRun, 1), "review");
        awaitLifecycle(PURCHASING, mediumRun, "completed");
        UUID complex = publishExample("complex-batch-review.json", childKey);
        var values = new java.util.LinkedHashMap<>(input(BigDecimal.TEN));
        values.put("items", java.util.List.of("Laptop", "Monitor"));
        var started = api("rhea.requester").post(Map.of("workflowId", complex, "input", values), UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID complexRun = UUID.fromString(started.body().path("id").asString());
        approve("mani.manager", PURCHASING, complexRun, "one");
        approve("max.manager", PURCHASING, complexRun, "two");
        UUID first = occurrence(complexRun, 1);
        String task = openTask(PURCHASING, first, "review").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, task).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, task, "reject", "Item not needed").status()).isEqualTo(201);
        approve("mani.manager", PURCHASING, occurrence(complexRun, 2), "review");
        awaitLifecycle(PURCHASING, complexRun, "completed");
        var detail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, complexRun).body();
        assertThat(detail.path("outcome").asString()).isEqualTo("Rejected");
        assertThat(eventTypes(PURCHASING, complexRun)).contains("WAIT_STARTED", "WAIT_ENDED", "CHILD_STARTED", "CHILD_ENDED", "RUN_COMPLETED");
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
        var parentDetail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body();
        assertThat(parentDetail.path("children")).hasSize(1);
        assertThat(parentDetail.path("children").get(0).path("id").asString()).isEqualTo(child.toString());
        assertThat(parentDetail.path("children").get(0).path("lifecycle").asString()).isEqualTo("waiting");
        assertThat(parentDetail.path("parentRun").isMissingNode()).isTrue();
        var childDetail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, child).body();
        assertThat(childDetail.path("parentRun").path("id").asString()).isEqualTo(run.toString());
        assertThat(childDetail.path("parentRun").path("reference").asString()).isEqualTo(parentDetail.path("reference").asString());
        assertThat(childDetail.path("reference").asString()).isNotEqualTo(parentDetail.path("reference").asString());
        approve("mani.manager", PURCHASING, child, "manager");
        awaitLifecycle(PURCHASING, child, "completed");
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(eventTypes(PURCHASING, run)).contains("CHILD_STARTED", "CHILD_ENDED", "RUN_COMPLETED");
        var ended = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body().path("items");
        boolean finishedEvent = false;
        for (var event : ended) {
            if ("CHILD_ENDED".equals(event.path("type").asString())) {
                finishedEvent = event.path("data").path("outcome").asString().equals("Fulfilled")
                        && event.path("data").path("childReference").asString().equals(childDetail.path("reference").asString());
            }
        }
        assertThat(finishedEvent).isTrue();
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
