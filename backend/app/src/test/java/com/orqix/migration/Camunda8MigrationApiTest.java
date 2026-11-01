package com.orqix.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Camunda 8 fixtures through the real API, PostgreSQL, compiler and Flowable: connector mapped to the procurement operation
 * (approval, 5,000 boundary, procurement call), forms attached to a BPMN import, FEEL gateways, a duration timer and a child.
 */
class Camunda8MigrationApiTest extends RuntimeTestBase {

    private String example(String name) throws Exception {
        return Files.readString(Path.of("../../examples/migration/" + name));
    }

    private JsonNode upload(String content, String format) throws Exception {
        var response = api("dana.designer").post(Map.of("fileName", "camunda8-fixture", "content", content, "sourceFormat", format),
                "/workspaces/{ws}/migrations", PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return response.body();
    }

    private ObjectNode base(String prefix, String... groupKeys) {
        var request = json.object();
        request.put("workflowKey", prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        request.put("name", "Imported " + prefix);
        request.put("acknowledgeInfo", true);
        request.put("acknowledgeReview", true);
        var mappings = new java.util.LinkedHashMap<String, String>();
        for (String key : groupKeys) {
            mappings.put(key, key.equals("source-finance") ? "finance-approvers" : "managers");
        }
        if (!mappings.isEmpty()) {
            request.set("groupMappings", json.toTree(mappings));
        }
        return request;
    }

    private Response convert(JsonNode job, ObjectNode request) throws Exception {
        return api("dana.designer").post(request, "/workspaces/{ws}/migrations/{id}/convert", PURCHASING, job.path("id").asString());
    }

    private UUID publish(JsonNode converted) throws Exception {
        UUID workflow = UUID.fromString(converted.path("workflow").path("id").asString());
        // Converted-but-changed items block publishing until a reviewer other than the mapper (dana) verifies them.
        var open = new java.util.ArrayList<Map<String, String>>();
        for (JsonNode i : converted.path("report").path("issues"))
            if ("verify".equals(i.path("severity").asString()) && "open".equals(i.path("resolutionStatus").asString()))
                open.add(Map.of("sourceElementId", i.path("sourceElementId").asString(), "reasonCode", i.path("reasonCode").asString()));
        if (!open.isEmpty()) {
            String migration = jdbc.sql("SELECT id::text FROM migration_job WHERE converted_workflow_id=:wf").param("wf", workflow).query(String.class).single();
            var verified = api("paul.publisher").post(Map.of("items", open, "reason", "Checked against the source fixture"),
                    "/workspaces/{ws}/migrations/{id}/verifications", PURCHASING, migration);
            assertThat(verified.status()).as(verified.toString()).isEqualTo(200);
        }
        var publish = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true), UUID.randomUUID().toString(),
                "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(publish.status()).as(publish.toString()).isEqualTo(201);
        return workflow;
    }

    private UUID startRun(UUID workflow, Map<String, Object> input) throws Exception {
        var started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    @Test
    void theRestConnectorMapsToTheProcurementOperationAndRunsApprovalBoundaryAndCall() throws Exception {
        var job = upload(example("camunda8-connector-http.bpmn"), "bpmn");
        var report = job.path("report");
        assertThat(report.path("sourceSystem").asString()).isEqualTo("camunda8");
        assertThat(report.path("sourceVersion").asString()).isEqualTo("8.5.0");
        var hint = report.path("mappingHints").path("services").path("procure");
        assertThat(hint.path("kind").asString()).isEqualTo("httpJson");
        assertThat(hint.path("method").asString()).isEqualTo("POST");
        assertThat(hint.path("urlPath").asString()).isEqualTo("/orders");
        assertThat(report.path("requiredMappings").path("serviceTasks").toString()).contains("procure");
        var template = json.parse(Files.readString(Path.of("../../examples/purchase-approval.json")));
        var request = base("c8-connector", "source-managers", "source-finance");
        for (var node : template.path("nodes")) {
            if (node.path("id").asString().equals("procure")) {
                request.set("serviceTaskMappings", json.toTree(Map.of("procure", node.path("config"))));
            }
        }
        var unmapped = request.deepCopy();
        unmapped.remove("serviceTaskMappings");
        assertThat(convert(job, unmapped).status()).isEqualTo(422);
        var converted = convert(job, request);
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        // The original URL is provenance only: it is not part of the workflow draft.
        assertThat(converted.body().path("draft").toString()).doesNotContain("example.com").doesNotContain("/orders");
        assertThat(converted.body().path("report").toString()).contains("CONNECTOR_MAPPED");
        UUID workflow = publish(converted.body());
        for (BigDecimal amount : new BigDecimal[] {new BigDecimal("4999.99"), new BigDecimal("5000")}) {
            var values = input(amount);
            values.remove("purpose");
            UUID runId = startRun(workflow, values);
            approve("mani.manager", PURCHASING, runId, "manager");
            if (amount.compareTo(new BigDecimal("5000")) >= 0) {
                assertThat(openTask(PURCHASING, runId, "finance")).isNotNull();
                approve("fin.finance", PURCHASING, runId, "finance");
            } else {
                assertThat(openTask(PURCHASING, runId, "finance")).isNull();
            }
            awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
            assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
            assertThat(run(PURCHASING, runId).path("outcome").asString()).isEqualTo("Fulfilled");
        }
    }

    @Test
    void feelGatewaysExecuteTheSameDecisionsAsTheSource() throws Exception {
        var job = upload(example("camunda8-feel-gateways.bpmn"), "bpmn");
        var converted = convert(job, base("c8-feel"));
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        UUID workflow = publish(converted.body());
        record Case(String amount, String status, boolean approved, String score, String outcome) { }
        for (Case c : new Case[] {new Case("50", "priority", true, "50", "Small purchase"), new Case("5000", "normal", true, "50", "Standard review"),
                new Case("5000", "vip", false, "50", "Denied"), new Case("5000", "priority", true, "100", "Low value"),
                new Case("5000", "priority", true, "5000", "Middle value"), new Case("5000", "priority", true, "10001", "Outside range"),
                new Case("5000", "priority", true, "1000", "Outside range")}) {
            var values = Map.<String, Object>of("amount", new BigDecimal(c.amount()), "status", c.status(), "approved", c.approved(), "score", new BigDecimal(c.score()));
            UUID runId = startRun(workflow, values);
            awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
            assertThat(run(PURCHASING, runId).path("outcome").asString()).as(c.toString()).isEqualTo(c.outcome());
        }
    }

    @Test
    void aFormFileIsAnImportSourceAndBecomesTheRequestFormOfTheBpmnImport() throws Exception {
        var form = upload(example("camunda8-user-task-form.form"), "camundaForm");
        assertThat(form.path("report").path("sourceSystem").asString()).isEqualTo("camundaForm");
        assertThat(form.path("report").path("mappingHints").path("formPreviews").path("expense-request").path("fields").size()).isEqualTo(7);
        // A form has no target of its own.
        var alone = convert(form, base("form-alone"));
        assertThat(alone.status()).isEqualTo(422);
        var bad = api("dana.designer").post(Map.of("fileName", "x.form", "content", "{\"not\":\"a form\"}", "sourceFormat", "camundaForm"), "/workspaces/{ws}/migrations", PURCHASING);
        assertThat(bad.status()).isEqualTo(422);
        var job = upload(example("camunda8-user-task-form.bpmn"), "bpmn");
        assertThat(job.path("report").path("mappingHints").path("forms").path("start").path("state").asString()).isEqualTo("upload");
        assertThat(job.path("report").path("mappingHints").path("formPreviews").path("managerReview").path("fields").size()).isEqualTo(2);
        var request = base("c8-form", "source-managers");
        request.set("formMappings", json.toTree(Map.of("start", Map.of("migrationId", form.path("id").asString()))));
        var wrongForm = upload("{\"id\":\"other-form\",\"type\":\"default\",\"components\":[{\"type\":\"textfield\",\"id\":\"a\",\"key\":\"x\",\"label\":\"X\"}]}", "camundaForm");
        var mismatched = request.deepCopy();
        mismatched.set("formMappings", json.toTree(Map.of("start", Map.of("migrationId", wrongForm.path("id").asString()))));
        assertThat(convert(job, mismatched).status()).isEqualTo(422);
        var unknown = request.deepCopy();
        unknown.set("formMappings", json.toTree(Map.of("managerReview", Map.of("migrationId", form.path("id").asString()))));
        assertThat(convert(job, unknown).status()).isEqualTo(422);
        var converted = convert(job, request);
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        var properties = converted.body().path("draft").path("definition").path("inputSchema").path("properties");
        assertThat(properties.path("employeeName").path("type").asString()).isEqualTo("string");
        assertThat(properties.path("category").path("enum").toString()).contains("travel");
        UUID workflow = publish(converted.body());
        Map<String, Object> values = Map.of("employeeName", "Ada", "amount", new BigDecimal("42.50"), "category", "travel", "paidBy", "card",
                "reason", "Client visit", "receiptAttached", true, "expenseDate", "2026-10-01");
        UUID runId = startRun(workflow, values);
        var task = openTask(PURCHASING, runId, "managerReview");
        assertThat(task).isNotNull();
        approve("mani.manager", PURCHASING, runId, "managerReview");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        assertThat(run(PURCHASING, runId).path("outcome").asString()).isEqualTo("Approved");
        // The decision is stored and the due time is carried on the task.
        assertThat(converted.body().path("draft").toString()).contains("\"dueAfterHours\":48");
    }

    @Test
    void aDurationTimerBoundaryTimeLimitAndPinnedChildRunToCompletion() throws Exception {
        String childSource = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:test\"><process id=\"simpleChild\" isExecutable=\"true\"><startEvent id=\"start\"/><endEvent id=\"end\"/><sequenceFlow id=\"route\" sourceRef=\"start\" targetRef=\"end\"/></process></definitions>";
        String childKey = "c8-child-" + UUID.randomUUID().toString().substring(0, 8);
        var childRequest = json.object();
        childRequest.put("workflowKey", childKey);
        childRequest.put("name", "Imported child");
        childRequest.put("acknowledgeInfo", true);
        childRequest.put("acknowledgeReview", true);
        publish(convert(upload(childSource, "bpmn"), childRequest).body());
        var job = upload(example("camunda8-timer-callactivity.bpmn"), "bpmn");
        assertThat(job.path("report").path("mappingHints").path("calls").path("child").path("processId").asString()).isEqualTo("reviewed-child");
        var request = base("c8-timer", "source-managers", "source-finance");
        request.set("childWorkflowMappings", json.toTree(Map.of("child", Map.of("workflowRef", childKey, "inputMapping", Map.of(), "resultMapping", Map.of()))));
        var converted = convert(job, request);
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        UUID workflow = publish(converted.body());
        UUID runId = startRun(workflow, Map.of());
        awaitTask(runId, "review");
        approve("mani.manager", PURCHASING, runId, "review");
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
        assertThat(run(PURCHASING, runId).path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workspace_id=:ws AND parent_run_id=:parent AND lifecycle='completed'")
                .param("ws", PURCHASING).param("parent", runId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void importedMessageCatchWaitsForAuthorizedWorkspaceDeliveryAndReplaysExactly() throws Exception {
        var job = upload(example("camunda8-message-wait.bpmn"), "bpmn");
        var converted = convert(job, base("c8-message"));
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        UUID workflow = publish(converted.body());
        String key = "ORDER-" + UUID.randomUUID();
        UUID runId = startRun(workflow, Map.of("orderId", key));
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("waiting");
        String idem = UUID.randomUUID().toString();
        var payload = Map.of("name", "payment-received", "correlationKey", key);
        assertThat(api("rhea.requester").post(payload, idem, "/workspaces/{ws}/messages", PURCHASING).status()).isEqualTo(403);
        var accepted = api("olga.operator").post(payload, idem, "/workspaces/{ws}/messages", PURCHASING);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
        assertThat(accepted.body().path("matched").asInt()).isEqualTo(1);
        awaitLifecycle(PURCHASING, runId, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("completed");
        var replay = api("olga.operator").post(payload, idem, "/workspaces/{ws}/messages", PURCHASING);
        assertThat(replay.body()).isEqualTo(accepted.body());
    }

    /** The duration timer runs on the job executor; poll until the approval after the wait is open. */
    private void awaitTask(UUID runId, String node) throws Exception {
        long deadline = System.currentTimeMillis() + 150_000;
        while (System.currentTimeMillis() < deadline) {
            if (openTask(PURCHASING, runId, node) != null) {
                return;
            }
            Thread.sleep(2_000);
        }
        throw new AssertionError("The approval '" + node + "' did not open after the timer");
    }
}
