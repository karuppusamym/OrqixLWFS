package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.orqix.shared.json.Json;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** MIG11 end to end on PostgreSQL/Flowable: native archive → reviewed conversion → compile → separate publish → runs (docs/build/S112). */
class PegaNativeApiTest extends RuntimeTestBase {
    private static byte[] fixture(String name) throws Exception { return Files.readAllBytes(Path.of("../../examples/migration/pega-native/" + name + ".zip")); }

    private Response upload(String user, String name) throws Exception {
        return api(user).post(Map.of("fileName", name + ".zip", "content", Base64.getEncoder().encodeToString(fixture(name)), "sourceFormat", "pegaRules"),
                "/workspaces/{ws}/migrations", PURCHASING);
    }

    private ObjectNode request() {
        ObjectNode r = json.object().put("workflowKey", "pega-native-" + UUID.randomUUID().toString().substring(0, 8)).put("name", "Purchase request (native Pega)")
                .put("acknowledgeInfo", true).put("acknowledgeReview", true);
        r.putObject("groupMappings").put("Managers", "managers").put("Finance", "finance-approvers").put("Procurement", "managers");
        ObjectNode routes = r.putObject("approvalRoutes");
        routes.putObject("ManagerReview").put("approvedFlowId", "managerApprove").put("rejectedFlowId", "managerReject");
        routes.putObject("FinanceReview").put("approvedFlowId", "financeApprove").put("rejectedFlowId", "financeReject");
        routes.putObject("ConfirmOrder").put("approvedFlowId", "confirm").put("rejectedFlowId", "cancel");
        return r;
    }

    private Response convert(JsonNode job, JsonNode request) throws Exception {
        return api("dana.designer").post(request, "/workspaces/{ws}/migrations/{id}/convert", PURCHASING, job.path("id").asString());
    }

    private UUID start(UUID workflow, Map<String, ?> input) throws Exception {
        var started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    @Test void nativeArchiveConvertsCompilesPublishesSeparatelyAndRuns() throws Exception {
        var uploaded = upload("dana.designer", "purchase-request");
        assertThat(uploaded.status()).as(uploaded.toString()).isEqualTo(201);
        JsonNode job = uploaded.body();
        assertThat(job.path("report").path("adapterVersion").asString()).isEqualTo("pega-native-0.1");
        assertThat(job.path("report").path("sourceHash").asString()).isEqualTo(Json.sha256Hex(fixture("purchase-request")));
        assertThat(job.path("report").path("requiredMappings").path("groups").toString()).contains("Managers", "Finance", "Procurement");

        var view = api("dana.designer").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, job.path("id").asString());
        assertThat(view.status()).as(view.toString()).isEqualTo(200);
        assertThat(view.body().path("entries").size()).isEqualTo(jdbc.sql("SELECT count(*) FROM migration_pega_rule WHERE migration_id=:id")
                .param("id", UUID.fromString(job.path("id").asString())).query(Integer.class).single().intValue());
        assertThat(view.body().path("stack").toString()).contains("PurchasingOrg", "01-01-10");
        assertThat(view.body().path("contexts").get(0).path("chain").get(0).asString()).isEqualTo("ACME-Purchasing-Work-Request");
        JsonNode startView = view.body().path("startingPoints").get(0);
        assertThat(startView.path("rule").asString()).isEqualTo("casetypes/PurchaseRequest.xml");
        assertThat(startView.path("blocked").asBoolean()).isFalse();
        assertThat(startView.path("rules").toString()).contains("\"conversion\":\"shadowed\"").contains("\"resolution\":\"maskedByWithdrawn\"").contains("dmnDecision");

        assertThat(convert(job, request().put("acknowledgeInfo", false)).status()).isEqualTo(422);
        var noRoutes = request(); noRoutes.remove("approvalRoutes");
        assertThat(convert(job, noRoutes).status()).isEqualTo(422);
        assertThat(convert(job, request().set("inputSchema", json.object().put("type", "object"))).status()).isEqualTo(400);
        assertThat(convert(job, request().put("pegaStartRule", "flows/ApproveRequest.xml")).status()).isEqualTo(400);
        assertThat(api("rhea.requester").post(request(), "/workspaces/{ws}/migrations/{id}/convert", PURCHASING, job.path("id").asString()).status()).isEqualTo(403);

        ObjectNode mappings = request();
        var converted = convert(job, mappings);
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        JsonNode body = converted.body();
        assertThat(body.path("workflow").path("activeRelease").isNull()).isTrue();
        assertThat(body.path("draft").toString()).contains("\"stages\"").contains("\"P3D\"").contains("dueAfterMinutes");
        assertThat(convert(job, mappings).status()).isEqualTo(201);
        assertThat(convert(job, mappings.deepCopy().put("name", "Changed")).status()).isEqualTo(409);
        var recorded = api("dana.designer").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, job.path("id").asString()).body();
        assertThat(recorded.path("convertedStartRule").asString()).isEqualTo("casetypes/PurchaseRequest.xml");
        assertThat(recorded.path("conversion").toString()).contains("\"targetSha256\":\"");
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}", PURCHASING, job.path("id").asString()).body().path("sourceMap").isEmpty()).isFalse();

        UUID workflow = UUID.fromString(body.path("workflow").path("id").asString());
        var published = api("paul.publisher").post(Map.of("draftRevision", body.path("draft").path("revision").asInt(), "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);

        Map<String, Object> input = new LinkedHashMap<>(Map.of("description", "Laptops", "category", "Hardware", "vendorName", "Acme", "vendorPreferred", true));
        input.put("amount", new BigDecimal("4999.99"));
        UUID standard = start(workflow, input);
        awaitLifecycle(PURCHASING, standard, "waiting", "needsAttention");
        assertThat(openTask(PURCHASING, standard, "FinanceReview")).isNull();
        approve("mani.manager", PURCHASING, standard, "ManagerReview");
        awaitLifecycle(PURCHASING, standard, "waiting", "needsAttention");
        approve("mani.manager", PURCHASING, standard, "ConfirmOrder");
        awaitLifecycle(PURCHASING, standard, "completed", "needsAttention");
        assertThat(run(PURCHASING, standard).path("outcome").asString()).isEqualTo("Completed");

        input.put("amount", new BigDecimal("5000"));
        UUID finance = start(workflow, input);
        awaitLifecycle(PURCHASING, finance, "waiting", "needsAttention");
        assertThat(openTask(PURCHASING, finance, "ManagerReview")).isNull();
        approve("fin.finance", PURCHASING, finance, "FinanceReview");
        awaitLifecycle(PURCHASING, finance, "waiting", "needsAttention");
        approve("mani.manager", PURCHASING, finance, "ConfirmOrder");
        awaitLifecycle(PURCHASING, finance, "completed", "needsAttention");

        // Manager approval of a non-preferred vendor over 1,000 goes through the converted when rule to finance.
        input.put("amount", new BigDecimal("1000")); input.put("vendorPreferred", false);
        UUID vendorCheck = start(workflow, input);
        awaitLifecycle(PURCHASING, vendorCheck, "waiting", "needsAttention");
        approve("mani.manager", PURCHASING, vendorCheck, "ManagerReview");
        awaitLifecycle(PURCHASING, vendorCheck, "waiting", "needsAttention");
        assertThat(openTask(PURCHASING, vendorCheck, "FinanceReview")).isNotNull();
    }

    private UUID publish(JsonNode converted) throws Exception {
        UUID workflow = UUID.fromString(converted.path("workflow").path("id").asString());
        var published = api("paul.publisher").post(Map.of("draftRevision", converted.path("draft").path("revision").asInt(), "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return workflow;
    }

    /** S115: a child flow converted and published first, then the case with subflows, a split-join, variants, a business calendar and escalation. */
    @Test void advancedArchiveRunsChildWorkflowsParallelChecksAndCircumstanceVariants() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var childJob = upload("dana.designer", "purchase-advanced").body();
        var childView = api("dana.designer").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, childJob.path("id").asString()).body();
        assertThat(childView.path("startingPoints").size()).isEqualTo(5);
        assertThat(childView.path("entries").toString()).contains("\"status\":\"delegation\"");
        ObjectNode childRequest = json.object().put("workflowKey", "pega-child-" + suffix).put("name", "Record order (native Pega)")
                .put("acknowledgeInfo", true).put("acknowledgeReview", true).put("pegaStartRule", "flows/RecordOrder.xml");
        var child = convert(childJob, childRequest);
        assertThat(child.status()).as(child.toString()).isEqualTo(201);
        publish(child.body());

        var job = upload("dana.designer", "purchase-advanced").body();
        var view = api("dana.designer").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, job.path("id").asString()).body();
        JsonNode order = null;
        for (JsonNode s : view.path("startingPoints")) if (s.path("rule").asString().equals("casetypes/PurchaseOrder.xml")) order = s;
        assertThat(order.path("subflows").size()).isEqualTo(3);
        assertThat(order.path("rules").toString()).contains("\"resolution\":\"variant\"").contains("Purchasing:Managers");
        ObjectNode request = request().put("pegaStartRule", "casetypes/PurchaseOrder.xml");
        assertThat(convert(job, request).status()).as("child workflow mappings are required").isEqualTo(422);
        ObjectNode children = request.putObject("childWorkflowMappings");
        for (String call : List.of("Checks_1", "Checks_2", "Record")) {
            ObjectNode m = children.putObject(call); m.put("workflowRef", "pega-child-" + suffix); m.putObject("inputMapping"); m.putObject("resultMapping");
        }
        var converted = convert(job, request);
        assertThat(converted.status()).as(converted.toString()).isEqualTo(201);
        assertThat(converted.body().path("draft").toString()).contains("parallelSplit").contains("startWorkflow").contains("America/New_York").contains("\"maxNotices\":4");
        UUID workflow = publish(converted.body());

        Map<String, Object> base = new LinkedHashMap<>(Map.of("description", "Laptops", "vendorName", "Acme", "vendorPreferred", true));
        // Services at 3,000: the circumstance variant's 2,000 threshold sends it through the parallel child checks to finance.
        Map<String, Object> services = new LinkedHashMap<>(base); services.put("category", "Services"); services.put("amount", new BigDecimal("3000"));
        UUID viaChecks = start(workflow, services);
        awaitLifecycle(PURCHASING, viaChecks, "waiting", "needsAttention");
        assertThat(openTask(PURCHASING, viaChecks, "ManagerReview")).isNull();
        approve("fin.finance", PURCHASING, viaChecks, "FinanceReview");
        awaitLifecycle(PURCHASING, viaChecks, "waiting", "needsAttention");
        approve("mani.manager", PURCHASING, viaChecks, "ConfirmOrder");
        awaitLifecycle(PURCHASING, viaChecks, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, viaChecks)).isEqualTo("completed");
        // Hardware at 3,000 uses the base 5,000 threshold: manager review (business calendar), then the base when rule.
        Map<String, Object> hardware = new LinkedHashMap<>(base); hardware.put("category", "Hardware"); hardware.put("amount", new BigDecimal("3000"));
        UUID viaManager = start(workflow, hardware);
        awaitLifecycle(PURCHASING, viaManager, "waiting", "needsAttention");
        assertThat(openTask(PURCHASING, viaManager, "FinanceReview")).isNull();
        approve("mani.manager", PURCHASING, viaManager, "ManagerReview");
        awaitLifecycle(PURCHASING, viaManager, "waiting", "needsAttention");
        assertThat(openTask(PURCHASING, viaManager, "ConfirmOrder")).isNotNull();
        // Software at 600 with a preferred vendor: only the Software variant of the when rule (>= 500) routes to the checks.
        Map<String, Object> software = new LinkedHashMap<>(base); software.put("category", "Software"); software.put("amount", new BigDecimal("600"));
        UUID variantWhen = start(workflow, software);
        awaitLifecycle(PURCHASING, variantWhen, "waiting", "needsAttention");
        approve("mani.manager", PURCHASING, variantWhen, "ManagerReview");
        awaitLifecycle(PURCHASING, variantWhen, "waiting", "needsAttention");
        assertThat(openTask(PURCHASING, variantWhen, "FinanceReview")).isNotNull();
        // Hardware at 50: the decision tree's current (undated) rule makes it Express, so no order confirmation.
        Map<String, Object> small = new LinkedHashMap<>(base); small.put("category", "Hardware"); small.put("amount", new BigDecimal("50"));
        UUID express = start(workflow, small);
        awaitLifecycle(PURCHASING, express, "waiting", "needsAttention");
        approve("mani.manager", PURCHASING, express, "ManagerReview");
        awaitLifecycle(PURCHASING, express, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, express)).isEqualTo("completed");
    }

    @Test void unsupportedRulesBlockConversionAndAccessIsEnforced() throws Exception {
        var uploaded = upload("dana.designer", "refusals");
        assertThat(uploaded.status()).as(uploaded.toString()).isEqualTo(201);
        JsonNode job = uploaded.body();
        assertThat(job.path("report").path("publishable").asBoolean()).isFalse();
        var refused = convert(job, json.object().put("workflowKey", "pega-native-refused").put("name", "Refused").put("acknowledgeInfo", true).put("acknowledgeReview", true));
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.body().toString()).contains("PEGA_NATIVE_ACTIVITY_NOT_EXECUTED").contains("PEGA_NATIVE_RULE_BLOCKED").contains("binary/legacy.bin");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id=:ws AND key='pega-native-refused'").param("ws", PURCHASING).query(Integer.class).single()).isZero();
        assertThat(upload("rhea.requester", "purchase-request").status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, job.path("id").asString()).status()).isIn(403, 404);
        var other = api("dana.designer").post(Map.of("fileName", "flow.bpmn", "content", Files.readString(Path.of("../../examples/migration/camunda7-purchase.bpmn")), "sourceFormat", "bpmn"),
                "/workspaces/{ws}/migrations", PURCHASING);
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}/pega-rules", PURCHASING, other.body().path("id").asString()).status()).isEqualTo(404);
    }
}
