package com.orqix.cases;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Case tasks that run a pinned workflow release, with result mapping, cancellation and late-result fencing (WM04, S110). */
class CaseWorkflowTaskApiTest extends RuntimeTestBase {
    @Autowired CaseWorkflowReconciler reconciler;
    @Autowired org.flowable.cmmn.api.CmmnRuntimeService cmmnRuntime;

    private static final List<Map<String, Object>> FIELDS = List.of(
            Map.of("key", "title", "label", "Title", "type", "text"),
            Map.of("key", "purpose", "label", "Purpose", "type", "text"),
            Map.of("key", "amount", "label", "Amount", "type", "number"),
            Map.of("key", "costCenter", "label", "Cost center", "type", "text"),
            Map.of("key", "decision", "label", "Decision", "type", "text"),
            Map.of("key", "requestRef", "label", "Request", "type", "text"));
    private static final Map<String, String> INPUT = Map.of("title", "title", "purpose", "purpose", "amount", "amount", "costCenter", "costCenter");

    private UUID workflow() throws Exception {
        return publishPurchase(SANDBOX, d -> {
            d.set("nodes", json.parse("[{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}},"
                    + "{\"id\":\"review\",\"type\":\"approval\",\"label\":\"Review\",\"config\":{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true}},"
                    + "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Approved\",\"outcomeKind\":\"success\"}},"
                    + "{\"id\":\"reject\",\"type\":\"end\",\"label\":\"Rejected\",\"config\":{\"businessOutcome\":\"Rejected\",\"outcomeKind\":\"rejected\"}}]"));
            d.set("transitions", json.parse("[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"review\",\"on\":\"next\"},"
                    + "{\"id\":\"t2\",\"from\":\"review\",\"to\":\"end\",\"on\":\"approved\"},{\"id\":\"t3\",\"from\":\"review\",\"to\":\"reject\",\"on\":\"rejected\"}]"));
            d.set("decisions", json.parse("[]"));
            d.set("outputMapping", json.object());
            d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
            d.set("dependencies", json.parse("{\"connections\":[]}"));
            ((ObjectNode) d).remove("uiLayout");
        });
    }

    private String key(UUID workflow) throws Exception {
        return api("dana.designer").get("/workspaces/{ws}/workflows/{id}", SANDBOX, workflow).body().path("key").asString();
    }

    private Response define(String workflowKey, Object mappings) throws Exception {
        String caseKey = "wf" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String xml = CmmnEventRulesTest.source("workflow-task").replace("workflowCase", caseKey).replace("WORKFLOWKEY", workflowKey);
        return api("dana.designer").post(Map.of("key", caseKey, "name", "Purchase case", "sourceXml", xml, "dataFields", FIELDS,
                "workflowTasks", mappings), "/workspaces/{ws}/cases/definitions", SANDBOX);
    }

    private JsonNode startCase(String definitionId, Map<String, Object> data) throws Exception {
        var started = api("rhea.requester").post(Map.of("data", data), UUID.randomUUID().toString(),
                "/workspaces/{ws}/cases/definitions/{id}/instances", SANDBOX, definitionId);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return started.body();
    }

    private static final Map<String, Object> DATA = Map.of("title", "Replacement laptop", "purpose", "Device failed", "amount", 900, "costCenter", "OPS");

    private String path(JsonNode instance) { return "/workspaces/" + SANDBOX + "/cases/instances/" + instance.path("id").asString(); }
    private JsonNode plan(JsonNode instance) throws Exception { return api("olga.operator").get(path(instance) + "/plan").body(); }
    private JsonNode item(JsonNode plan, String element) {
        for (var item : plan.path("items")) if (element.equals(item.path("elementId").asString()) && !"terminated".equals(item.path("state").asString())) return item;
        throw new AssertionError("Missing " + element + ": " + plan);
    }
    private UUID runOf(JsonNode instance) {
        return jdbc.sql("SELECT run_id FROM case_workflow_task WHERE case_id=:id ORDER BY attempt DESC LIMIT 1")
                .param("id", UUID.fromString(instance.path("id").asString())).query(UUID.class).single();
    }
    private String status(JsonNode instance) {
        return jdbc.sql("SELECT status FROM case_workflow_task WHERE case_id=:id ORDER BY attempt DESC LIMIT 1")
                .param("id", UUID.fromString(instance.path("id").asString())).query(String.class).single();
    }
    private int version(JsonNode instance) throws Exception { return api("olga.operator").get(path(instance)).body().path("version").asInt(); }

    @Test void workflowTaskRunsThePinnedReleaseAndMapsItsResultIntoCaseData() throws Exception {
        UUID workflow = workflow();
        var created = define(key(workflow), List.of(Map.of("taskId", "purchaseTask", "inputMapping", INPUT,
                "resultMapping", Map.of("decision", "outcome", "requestRef", "reference"))));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var detail = api("dana.designer").get("/workspaces/{ws}/cases/definitions/{id}", SANDBOX, created.body().path("id").asString()).body();
        var pin = detail.path("workflowTasks").get(0);
        assertThat(pin.path("workflowKey").asString()).isEqualTo(key(workflow));
        assertThat(pin.path("releaseNumber").asInt()).isEqualTo(1);

        var instance = startCase(created.body().path("id").asString(), DATA);
        var running = item(plan(instance), "purchase");
        assertThat(running.path("type").asString()).isEqualTo("processTask");
        assertThat(running.path("state").asString()).isEqualTo("active");
        assertThat(running.path("workflow").path("status").asString()).isEqualTo("running");
        assertThat(running.path("workflow").path("reference").asString()).startsWith("ORQ-");
        UUID run = runOf(instance);
        assertThat(jdbc.sql("SELECT release_id FROM workflow_run WHERE id=:id").param("id", run).query(UUID.class).single())
                .isEqualTo(UUID.fromString(pin.path("releaseId").asString()));
        assertThat(jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id=:id").param("id", run).query(UUID.class).single())
                .isEqualTo(jdbc.sql("SELECT started_by FROM case_instance WHERE id=:id").param("id", UUID.fromString(instance.path("id").asString())).query(UUID.class).single());
        // The case cannot close while its required workflow is running.
        assertThat(plan(instance).path("canClose").asBoolean()).isFalse();

        approve("mani.manager", SANDBOX, run, "review");
        awaitLifecycle(SANDBOX, run, "completed");
        reconciler.run();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "completed".equals(status(instance)));
        var after = plan(instance);
        assertThat(item(after, "purchase").path("state").asString()).isEqualTo("completed");
        assertThat(item(after, "purchase").path("workflow").path("outcome").asString()).isEqualTo("Approved");
        assertThat(item(after, "followUp").path("state").asString()).isIn("active", "enabled");
        var data = api("olga.operator").get(path(instance) + "/data").body().path("values");
        assertThat(data.path("decision").asString()).isEqualTo("Approved");
        assertThat(data.path("requestRef").asString()).startsWith("ORQ-");
        assertThat(after.path("events").toString()).contains("workflowStarted").contains("workflowCompleted");
        // A replayed reconciliation applies nothing twice.
        int before = version(instance);
        reconciler.run();
        assertThat(version(instance)).isEqualTo(before);
    }

    @Test void definitionRefusesUnknownWorkflowsMissingRequiredInputAndIncompatibleResults() throws Exception {
        UUID workflow = workflow();
        String key = key(workflow);
        var mapping = Map.of("taskId", "purchaseTask", "inputMapping", INPUT, "resultMapping", Map.of());
        assertThat(define("no-such-workflow", List.of(mapping)).status()).isEqualTo(422);
        assertThat(define(key, List.of()).status()).isEqualTo(422);
        assertThat(define(key, List.of(Map.of("taskId", "purchaseTask", "inputMapping", Map.of("title", "title")))).status()).isEqualTo(422);
        assertThat(define(key, List.of(Map.of("taskId", "purchaseTask", "inputMapping", Map.of("title", "title", "purpose", "purpose", "amount", "title", "costCenter", "costCenter")))).status()).isEqualTo(422);
        assertThat(define(key, List.of(Map.of("taskId", "purchaseTask", "inputMapping", INPUT, "resultMapping", Map.of("amount", "outcome")))).status()).isEqualTo(422);
        assertThat(define(key, List.of(Map.of("taskId", "purchaseTask", "inputMapping", INPUT, "resultMapping", Map.of("decision", "missingOutput")))).status()).isEqualTo(422);
    }

    @Test void terminatingTheTaskOrTheCaseCancelsItsRun() throws Exception {
        UUID workflow = workflow();
        var created = define(key(workflow), List.of(Map.of("taskId", "purchaseTask", "inputMapping", INPUT)));
        var instance = startCase(created.body().path("id").asString(), DATA);
        UUID run = runOf(instance);
        var purchase = item(plan(instance), "purchase");
        assertThat(purchase.path("allowedActions").path("terminate").asBoolean()).isTrue();
        var terminated = api("olga.operator").post(Map.of("expectedVersion", version(instance), "reason", "Not needed"), UUID.randomUUID().toString(),
                path(instance) + "/plan-items/" + purchase.path("id").asString() + "/terminate");
        assertThat(terminated.status()).as(terminated.toString()).isEqualTo(200);
        assertThat(lifecycle(SANDBOX, run)).isEqualTo("cancelled");
        assertThat(status(instance)).isEqualTo("cancelled");

        var second = startCase(created.body().path("id").asString(), DATA);
        UUID secondRun = runOf(second);
        var ended = api("olga.operator").post(Map.of("expectedVersion", version(second), "reason", "Withdrawn"), UUID.randomUUID().toString(), path(second) + "/terminate");
        assertThat(ended.status()).as(ended.toString()).isEqualTo(200);
        assertThat(lifecycle(SANDBOX, secondRun)).isEqualTo("cancelled");
    }

    @Test void lateResultsAreFencedAndCancelledOrUnstartableRunsCanBeRetried() throws Exception {
        UUID workflow = workflow();
        var created = define(key(workflow), List.of(Map.of("taskId", "purchaseTask", "inputMapping", INPUT,
                "resultMapping", Map.of("decision", "outcome"))));
        String definition = created.body().path("id").asString();

        // Late result: the engine ended the task (bypassing Orqix); the result that arrives afterwards is never applied.
        var late = startCase(definition, DATA);
        UUID lateRun = runOf(late);
        cmmnRuntime.terminatePlanItemInstance(item(plan(late), "purchase").path("id").asString());
        approve("mani.manager", SANDBOX, lateRun, "review");
        awaitLifecycle(SANDBOX, lateRun, "completed");
        reconciler.run();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "fenced".equals(status(late)));
        assertThat(api("olga.operator").get(path(late) + "/data").body().path("values").has("decision")).isFalse();

        // A cancelled run leaves the task active; the operator retries it with the current data.
        var cancelled = startCase(definition, DATA);
        UUID first = runOf(cancelled);
        var runDetail = api("olga.operator").get("/workspaces/{ws}/runs/{id}", SANDBOX, first).body();
        assertThat(api("olga.operator").post(Map.of("reason", "Wrong supplier", "expectedVersion", runDetail.path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/cancel", SANDBOX, first).status()).isIn(200, 201);
        reconciler.run();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "cancelled".equals(status(cancelled)));
        var purchase = item(plan(cancelled), "purchase");
        assertThat(purchase.path("state").asString()).isEqualTo("active");
        assertThat(purchase.path("allowedActions").path("retryWorkflow").asBoolean()).isTrue();
        assertThat(api("mani.manager").post(Map.of("expectedVersion", version(cancelled), "reason", "Retry"), UUID.randomUUID().toString(),
                path(cancelled) + "/plan-items/" + purchase.path("id").asString() + "/retryWorkflow").status()).isEqualTo(403);
        var retried = api("olga.operator").post(Map.of("expectedVersion", version(cancelled), "reason", "Retry with the corrected supplier"), UUID.randomUUID().toString(),
                path(cancelled) + "/plan-items/" + purchase.path("id").asString() + "/retryWorkflow");
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        assertThat(item(plan(cancelled), "purchase").path("workflow").path("attempt").asInt()).isEqualTo(2);
        assertThat(runOf(cancelled)).isNotEqualTo(first);
        assertThat(lifecycle(SANDBOX, runOf(cancelled))).isIn("running", "waiting");

        // Input that does not satisfy the workflow's request form never starts a run; it waits for corrected data.
        var incomplete = startCase(definition, Map.of("title", "Replacement laptop", "purpose", "Device failed", "costCenter", "OPS"));
        assertThat(status(incomplete)).isEqualTo("failed");
        assertThat(item(plan(incomplete), "purchase").path("workflow").path("detail").asString()).contains("request form");
    }
}
