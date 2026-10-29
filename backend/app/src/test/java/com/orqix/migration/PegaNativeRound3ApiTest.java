package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.cases.CaseWorkflowReconciler;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MIG11 round 3 on PostgreSQL/Flowable (docs/build/S117): a connector flow converts to a call step on the procurement
 * connection and runs against the stub; the case type's process flows are published as workflows; the case type converts
 * to a CMMN case definition (preview, then creation through the case definition API) and a case runs its first workflow task.
 */
class PegaNativeRound3ApiTest extends RuntimeTestBase {
    @Autowired CaseWorkflowReconciler reconciler;

    private static byte[] fixture() throws Exception { return Files.readAllBytes(Path.of("../../examples/migration/pega-native/purchase-connected.zip")); }

    private JsonNode upload() throws Exception {
        var r = api("dana.designer").post(Map.of("fileName", "purchase-connected.zip", "content", Base64.getEncoder().encodeToString(fixture()), "sourceFormat", "pegaRules"),
                "/workspaces/{ws}/migrations", PURCHASING);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.body();
    }

    private Response convert(JsonNode job, JsonNode request) throws Exception {
        return api("dana.designer").post(request, "/workspaces/{ws}/migrations/{id}/convert", PURCHASING, job.path("id").asString());
    }

    private ObjectNode flow(String start, String key) {
        return json.object().put("workflowKey", key).put("name", key).put("acknowledgeInfo", true).put("acknowledgeReview", true).put("pegaStartRule", start);
    }

    private UUID publish(JsonNode converted) throws Exception {
        UUID workflow = UUID.fromString(converted.path("workflow").path("id").asString());
        var published = api("paul.publisher").post(Map.of("draftRevision", converted.path("draft").path("revision").asInt(), "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return workflow;
    }

    /** Converts and publishes one process flow from its own upload; returns the workflow key. */
    private String processWorkflow(String flowFile, String suffix, java.util.function.Consumer<ObjectNode> mappings) throws Exception {
        String key = "pega-" + flowFile.replace("flows/", "").replace(".xml", "").toLowerCase(Locale.ROOT) + "-" + suffix;
        ObjectNode request = flow(flowFile, key);
        mappings.accept(request);
        var converted = convert(upload(), request);
        assertThat(converted.status()).as(flowFile + " " + converted).isEqualTo(201);
        publish(converted.body());
        return key;
    }

    @Test void connectorFlowRunsTheCallStepAndTheCaseTypeBecomesACaseDefinitionThatRuns() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        var job = upload();
        var view = api("dana.designer").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, job.path("id").asString()).body();
        assertThat(view.path("startingPoints").size()).isEqualTo(5);
        JsonNode caseType = view.path("startingPoints").get(0), order = view.path("startingPoints").get(1);
        assertThat(caseType.path("caseTarget").path("processes").size()).isEqualTo(4);
        assertThat(caseType.path("caseTarget").path("alternates").get(0).path("stageId").asString()).isEqualTo("withdrawn");
        assertThat(order.path("calls").get(0).path("inputMapping").path("costCenter").asString()).isEqualTo("/input/costCenter");

        // 1. The order flow: an explicit connection mapping is required; the call step then runs against the stub.
        ObjectNode orderRequest = flow("flows/Order.xml", "pega-order-" + suffix);
        orderRequest.putObject("groupMappings").put("Managers", "managers");
        orderRequest.putObject("approvalRoutes").putObject("Review").put("approvedFlowId", "orderApprove").put("rejectedFlowId", "orderReject");
        assertThat(convert(job, orderRequest).status()).as("connection mapping is explicit").isEqualTo(422);
        orderRequest.putObject("connectionMappings").putObject("PlaceOrder").put("connectionRef", "procurement").put("operation", "createOrder").put("definitionVersion", 1);
        var converted = convert(job, orderRequest);
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        assertThat(converted.body().path("draft").toString()).contains("\"type\":\"http\"").contains("\"connectionRef\":\"procurement\"");
        UUID orderWorkflow = publish(converted.body());
        var started = api("rhea.requester").post(Map.of("workflowId", orderWorkflow, "input", Map.of("title", "Laptops", "amount", new BigDecimal("1200"), "costCenter", "IT")),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        awaitLifecycle(PURCHASING, run, "waiting", "needsAttention");
        approve("mani.manager", PURCHASING, run, "Review");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        var detail = run(PURCHASING, run);
        assertThat(detail.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(detail.path("effects").size()).isGreaterThanOrEqualTo(1);

        // 2. The other process flows become published workflows.
        String escalate = processWorkflow("flows/Escalate.xml", suffix, r -> {
            r.putObject("groupMappings").put("Finance", "finance-approvers");
            r.putObject("approvalRoutes").putObject("FinanceCheck").put("approvedFlowId", "financeOk").put("rejectedFlowId", "financeNo");
        });
        String close = processWorkflow("flows/Close.xml", suffix, r -> {
            r.putObject("groupMappings").put("Managers", "managers");
            r.putObject("approvalRoutes").putObject("WrapUp").put("approvedFlowId", "wrapOk").put("rejectedFlowId", "wrapNo");
        });
        String withdraw = processWorkflow("flows/Withdraw.xml", suffix, r -> { });

        // 3. The case type as a case definition: preview, refusal without mappings, creation, replay and conflict.
        String caseKey = "connected-" + suffix;
        ObjectNode caseRequest = json.object().put("key", caseKey).put("name", "Connected purchase " + suffix).put("pegaStartRule", "casetypes/ConnectedPurchase.xml")
                .put("acknowledgeInfo", true).put("acknowledgeReview", true);
        String path = "/workspaces/{ws}/migrations/{id}/case-definition";
        var missing = api("dana.designer").post(caseRequest.deepCopy().put("dryRun", true), path, PURCHASING, job.path("id").asString());
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().toString()).contains("PEGA_NATIVE_WORKFLOW_MAPPING_REQUIRED");
        caseRequest.putObject("workflowMappings").put("Order", "pega-order-" + suffix).put("Escalate", escalate).put("Close", close).put("Withdraw", withdraw);
        caseRequest.putObject("roleMappings").put("Escalate", "finance-approvers").put("withdrawn", "managers");
        var preview = api("dana.designer").post(caseRequest.deepCopy().put("dryRun", true), path, PURCHASING, job.path("id").asString());
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat(preview.body().path("sourceXml").asString()).contains("discretionaryItem").contains("userEventListener").contains("orqixwf:" + escalate);
        assertThat(preview.body().path("definition").isMissingNode() || preview.body().path("definition").isNull()).isTrue();
        assertThat(api("rhea.requester").post(caseRequest, path, PURCHASING, job.path("id").asString()).status()).isEqualTo(403);
        var created = api("dana.designer").post(caseRequest, path, PURCHASING, job.path("id").asString());
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String definitionId = created.body().path("definition").path("id").asString();
        assertThat(api("dana.designer").post(caseRequest, path, PURCHASING, job.path("id").asString()).status()).isEqualTo(201);
        assertThat(api("dana.designer").post(caseRequest.deepCopy().put("name", "Changed"), path, PURCHASING, job.path("id").asString()).status()).isEqualTo(409);
        var detailDefinition = api("dana.designer").get("/workspaces/{ws}/cases/definitions/{id}", PURCHASING, definitionId).body();
        assertThat(detailDefinition.path("workflowTasks").size()).isEqualTo(4);
        var recorded = api("dana.designer").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, job.path("id").asString()).body();
        assertThat(recorded.path("caseConversion").path("caseDefinitionId").asString()).isEqualTo(definitionId);

        // 4. A case starts and runs the order workflow as its first workflow task.
        var instance = api("rhea.requester").post(Map.of("data", Map.of("title", "Monitors", "amount", 300, "costCenter", "OPS")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/cases/definitions/{id}/instances", PURCHASING, definitionId);
        assertThat(instance.status()).as(instance.toString()).isEqualTo(201);
        UUID caseId = UUID.fromString(instance.body().path("id").asString());
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("SELECT count(*) FROM case_workflow_task WHERE case_id=:id").param("id", caseId).query(Long.class).single() > 0);
        UUID caseRun = jdbc.sql("SELECT run_id FROM case_workflow_task WHERE case_id=:id ORDER BY attempt DESC LIMIT 1").param("id", caseId).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT w.key FROM workflow_run r JOIN workflow w ON w.id=r.workflow_id WHERE r.id=:id").param("id", caseRun).query(String.class).single())
                .isEqualTo("pega-order-" + suffix);
        approve("mani.manager", PURCHASING, caseRun, "Review");
        awaitLifecycle(PURCHASING, caseRun, "completed", "needsAttention");
        reconciler.run();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "completed".equals(jdbc.sql("SELECT status FROM case_workflow_task WHERE case_id=:id ORDER BY attempt DESC LIMIT 1")
                .param("id", caseId).query(String.class).single()));
    }

    @Test void caseDefinitionRequestsAreValidated() throws Exception {
        var job = upload();
        String path = "/workspaces/{ws}/migrations/{id}/case-definition";
        assertThat(api("dana.designer").post(Map.of("key", "x-case", "name", "X", "pegaStartRule", "flows/Order.xml", "dryRun", true), path, PURCHASING, job.path("id").asString()).status()).isEqualTo(400);
        assertThat(api("dana.designer").post(Map.of("key", "x-case", "name", "X", "pegaStartRule", "casetypes/ConnectedPurchase.xml", "extra", 1), path, PURCHASING, job.path("id").asString()).status()).isEqualTo(400);
        assertThat(api("otto.outsider").post(Map.of("key", "x-case", "name", "X", "pegaStartRule", "casetypes/ConnectedPurchase.xml", "dryRun", true), path, PURCHASING, job.path("id").asString()).status()).isIn(403, 404);
        ObjectNode bad = flow("flows/Order.xml", "pega-bad");
        bad.putObject("connectionMappings").putObject("PlaceOrder").put("connectionRef", "Not A Key").put("operation", "createOrder");
        assertThat(convert(job, bad).status()).isEqualTo(400);
    }
}
