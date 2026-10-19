package com.orqix.catalog.internal;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.support.Fixtures;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.node.ObjectNode;

class ApprovalViewBindingTest extends RuntimeTestBase {
    @Autowired ApprovalViewCheck check;

    private ObjectNode view() {
        var view = json.object().put("schemaVersion", 1).put("kind", "approval");
        var actions = view.putArray("actions");
        actions.addObject().put("id", "yes").put("type", "approve").put("label", "Authorize purchase");
        actions.addObject().put("id", "no").put("type", "reject").put("label", "Decline purchase");
        var fields = view.putArray("sections").addObject().put("id", "context").put("label", "Purchase review").put("columns", 2).putArray("fields");
        for (String key : Fixtures.purchaseTemplate().path("inputSchema").path("properties").propertyNames()) {
            var property = Fixtures.purchaseTemplate().path("inputSchema").path("properties").path(key);
            var field = fields.addObject().put("id", key).put("label", "Review " + key).put("binding", "request." + key)
                    .put("required", true).put("type", property.has("enum") ? "choice" : property.path("type").asString().equals("number") ? "number" : "text");
            if (property.has("enum")) {
                var options = field.putArray("options");
                property.path("enum").forEach(value -> options.addObject().put("value", value.asString()).put("label", value.asString()));
            }
        }
        return view;
    }

    private UUID publishView(ObjectNode definition) throws Exception {
        var created = api("dana.designer").post(Map.of("name", "Approval " + UUID.randomUUID(), "definition", definition), "/workspaces/{ws}/views", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        assertThat(api("paul.publisher").post(Map.of("expectedRevision", 1), "/workspaces/{ws}/views/{id}/publish", PURCHASING, id).status()).isEqualTo(200);
        return id;
    }

    private void bind(ObjectNode workflow, UUID id, ObjectNode definition) {
        for (var node : workflow.path("nodes")) if ("manager".equals(node.path("id").asString())) {
            var binding = ((ObjectNode) node.path("config")).putObject("viewBinding").put("id", id.toString()).put("version", 1);
            binding.set("definition", definition.deepCopy());
        }
    }

    @Test void sharesPinnedPresentationAcrossWorkflowsWithoutGrantingTaskActions() throws Exception {
        var view = view(); UUID id = publishView(view);
        for (int i = 0; i < 2; i++) {
            UUID workflow = publishPurchase(PURCHASING, definition -> bind(definition, id, view));
            UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
            String task = openTask(PURCHASING, run, "manager").path("id").asString();
            var detail = api("max.manager").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task);
            assertThat(detail.status()).as(detail.toString()).isEqualTo(200);
            assertThat(detail.body().path("approvalView")).isEqualTo(view);
            assertThat(detail.body().path("allowedActions").path("decide").path("allowed").asBoolean()).isFalse();
            assertThat(decide("rhea.requester", PURCHASING, task, "approve", null).status()).isIn(403, 404, 409);
            assertThat(claim("max.manager", PURCHASING, task).status()).isEqualTo(200);
            assertThat(decide("max.manager", PURCHASING, task, "reject", null).status()).isEqualTo(422);
            assertThat(decide("max.manager", PURCHASING, task, "reject", "Outside budget").status()).isEqualTo(201);
        }
    }

    @Test void oldReleaseKeepsItsCopyAndDraftWarnsAboutNewerVersion() throws Exception {
        var view = view(); UUID id = publishView(view);
        UUID workflow = publishPurchase(PURCHASING, definition -> bind(definition, id, view));
        var changed = view.deepCopy(); ((ObjectNode) changed.path("sections").get(0)).put("label", "New presentation");
        assertThat(api("dana.designer").put(Map.of("expectedRevision", 1, "name", "Updated " + UUID.randomUUID(), "definition", changed), null,
                "/workspaces/{ws}/views/{id}/draft", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api("paul.publisher").post(Map.of("expectedRevision", 2), "/workspaces/{ws}/views/{id}/publish", PURCHASING, id).status()).isEqualTo(200);
        var draft = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body().path("definition");
        assertThat(check.issues(PURCHASING, draft)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.STALE);
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("100")).path("id").asString());
        String task = openTask(PURCHASING, run, "manager").path("id").asString();
        assertThat(api("max.manager").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task).body().path("approvalView")).isEqualTo(view);
    }

    @Test void refusesForeignMissingMalformedDivergedAndIncompatibleBindings() throws Exception {
        var view = view(); UUID id = publishView(view);
        var workflow = (ObjectNode) Fixtures.purchaseTemplate().deepCopy(); bind(workflow, id, view);
        assertThat(check.issues(PURCHASING, workflow)).isEmpty();
        assertThat(check.issues(SANDBOX, workflow)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.MISSING);
        var absent = workflow.deepCopy(); bind(absent, UUID.randomUUID(), view);
        assertThat(check.issues(PURCHASING, absent)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.MISSING);
        var altered = view.deepCopy(); ((ObjectNode) altered.path("actions").get(0)).put("label", "Changed after pin");
        bind(workflow, id, altered);
        assertThat(check.issues(PURCHASING, workflow)).extracting(com.orqix.compiler.ValidationIssue::code).containsExactly(AssetReferenceCheck.DIVERGED);
        assertThat(check.issues(PURCHASING, workflow).getFirst().nodeId()).isEqualTo("manager");
        for (String type : List.of("submit", "requestCorrection")) {
            var action = view.deepCopy(); ((ObjectNode) action.path("actions").get(0)).put("type", type);
            assertThat(ApprovalViewCheck.compatibility(workflow, action)).isNotNull();
        }
        var field = view.deepCopy(); ((ObjectNode) field.path("sections").get(0).path("fields").get(0)).put("binding", "task.comment");
        assertThat(ApprovalViewCheck.compatibility(workflow, field)).isNotNull();
        ((ObjectNode) workflow.path("inputSchema").path("properties").path("amount")).put("type", "string");
        assertThat(ApprovalViewCheck.compatibility(workflow, view)).isNotNull();
    }

    @Test void publicationIndependentlyBlocksTamperedPresentation() throws Exception {
        var view = view(); UUID id = publishView(view);
        UUID workflow = publishPurchase(PURCHASING, definition -> bind(definition, id, view));
        var draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body();
        var definition = (ObjectNode) draft.path("definition");
        var tampered = view.deepCopy(); ((ObjectNode) tampered.path("sections").get(0)).put("columns", 1); bind(definition, id, tampered);
        int revision = draft.path("revision").asInt();
        assertThat(api("dana.designer").put(Map.of("definition", definition), "\"r" + revision + "\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).status()).isEqualTo(200);
        var blocked = api("paul.publisher").post(Map.of("draftRevision", revision + 1, "acknowledgeWarnings", true), UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        assertThat(blocked.body().toString()).contains(AssetReferenceCheck.DIVERGED);
    }
}
