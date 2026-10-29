package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S116: a holder sends a request back to its requester for the declared fields, the decision waits until the requester
 * answers (or the send-back is withdrawn); chains run declared compensations, authorized again, when a later step fails;
 * a plan-item step may let the person choose the occurrence.
 */
class SendBackAndCompensationApiTest extends PageTestBase {
    private static final String TASK = "/workspaces/{ws}/tasks/{id}";

    private UUID correctableWorkflow() throws Exception {
        return publishPurchase(PURCHASING, definition -> {
            for (JsonNode node : definition.path("nodes")) {
                if ("manager".equals(node.path("id").asString())) ((ObjectNode) node.path("config")).putArray("correctable").add("purpose");
            }
        });
    }

    private String claimedTask(UUID workflow) throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        return taskId;
    }

    private int version(String taskId) throws Exception {
        return api("olga.operator").get(TASK, PURCHASING, taskId).body().path("version").asInt();
    }

    @Test
    void sendBackHoldsTheDecisionUntilTheRequesterAnswersTheDeclaredFields() throws Exception {
        UUID workflow = correctableWorkflow();
        String taskId = claimedTask(workflow);
        UUID runId = UUID.fromString(api("olga.operator").get(TASK, PURCHASING, taskId).body().path("runId").asString());

        assertThat(api("rhea.requester").post(Map.of("expectedVersion", version(taskId), "reason", "Not mine"), UUID.randomUUID().toString(),
                TASK + "/send-back", PURCHASING, taskId).status()).isIn(403, 404, 409);
        var sent = api("mani.manager").post(Map.of("expectedVersion", version(taskId), "reason", "Explain the purpose"),
                UUID.randomUUID().toString(), TASK + "/send-back", PURCHASING, taskId);
        assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
        assertThat(sent.body().path("form").path("sendBack").path("fields").toString()).isEqualTo("[\"purpose\"]");
        assertThat(api("mani.manager").post(Map.of("expectedVersion", version(taskId), "reason", "Again"), UUID.randomUUID().toString(),
                TASK + "/send-back", PURCHASING, taskId).status()).isEqualTo(409);
        var blocked = decide("mani.manager", PURCHASING, taskId, "approve", null);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("TASK_NOT_OPEN");
        assertThat(api("mani.manager").post(Map.of("expectedVersion", version(taskId), "reason", "Fix", "changes", Map.of("purpose", "x")),
                UUID.randomUUID().toString(), TASK + "/correct-input", PURCHASING, taskId).status()).isEqualTo(409);

        String list = "/workspaces/{ws}/runs/{run}/send-backs";
        JsonNode mine = api("rhea.requester").get(list, PURCHASING, runId).body().path("items").get(0);
        assertThat(mine.path("status").asString()).isEqualTo("open");
        assertThat(mine.path("canAnswer").asBoolean()).isTrue();
        assertThat(mine.path("fields").get(0).path("value").asString()).isEqualTo("Device failed");
        assertThat(api("mani.manager").get(list, PURCHASING, runId).body().path("items").get(0).path("canAnswer").asBoolean()).isFalse();
        assertThat(api("otto.outsider").get(list, PURCHASING, runId).status()).isEqualTo(404);

        String answer = list + "/{id}/answer";
        String sendBackId = mine.path("id").asString();
        int shown = mine.path("taskVersion").asInt();
        assertThat(api("mani.manager").post(Map.of("expectedVersion", shown, "changes", Map.of("purpose", "x")), UUID.randomUUID().toString(),
                answer, PURCHASING, runId, sendBackId).status()).isEqualTo(403);
        assertThat(api("rhea.requester").post(Map.of("expectedVersion", shown, "changes", Map.of("amount", 99)), UUID.randomUUID().toString(),
                answer, PURCHASING, runId, sendBackId).status()).isEqualTo(422);
        var answered = api("rhea.requester").post(Map.of("expectedVersion", shown, "changes", Map.of("purpose", "Laptop for the new hire"),
                "note", "Added the reason"), UUID.randomUUID().toString(), answer, PURCHASING, runId, sendBackId);
        assertThat(answered.status()).as(answered.toString()).isEqualTo(200);
        assertThat(answered.body().path("status").asString()).isEqualTo("answered");
        assertThat(run(PURCHASING, runId).path("input").path("purpose").asString()).isEqualTo("Laptop for the new hire");
        // A decision prepared before the answer is refused; one on the answered data goes through.
        var stale = api("mani.manager").post(Map.of("expectedVersion", shown, "action", "approve"), UUID.randomUUID().toString(),
                TASK + "/decision", PURCHASING, taskId);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(decide("mani.manager", PURCHASING, taskId, "approve", null).status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action IN ('task.sent-back', 'task.send-back.answered') AND resource_id = :id")
                .param("id", taskId).query(Integer.class).single()).isEqualTo(2);

        // Withdrawn by an operator: the holder can decide again.
        String other = claimedTask(workflow);
        assertThat(api("mani.manager").post(Map.of("expectedVersion", version(other), "reason", "Check the amount"), UUID.randomUUID().toString(),
                TASK + "/send-back", PURCHASING, other).status()).isEqualTo(200);
        assertThat(api("rhea.requester").post(Map.of("expectedVersion", version(other)), TASK + "/send-back/withdraw", PURCHASING, other).status())
                .isIn(403, 404);
        var withdrawn = api("olga.operator").post(Map.of("expectedVersion", version(other), "note", "Decide as is"), TASK + "/send-back/withdraw", PURCHASING, other);
        assertThat(withdrawn.status()).as(withdrawn.toString()).isEqualTo(200);
        assertThat(withdrawn.body().path("form").path("sendBack").isNull()).isTrue();
        assertThat(decide("mani.manager", PURCHASING, other, "approve", null).status()).isEqualTo(201);
    }

    @Test
    void chainsRunDeclaredCompensationsWhenALaterStepFailsAndCanSendBack() throws Exception {
        UUID workflow = correctableWorkflow();
        Map<String, Object> view = new LinkedHashMap<>(approvalView());
        view.put("actions", List.of(Map.of("id", "approve", "label", "Approve", "type", "approve"),
                Map.of("id", "reject", "label", "Reject", "type", "reject"), Map.of("id", "fix", "label", "Return", "type", "requestCorrection")));
        Pin approval = publishedView(view);
        Map<String, Object> claimUndone = Map.of("action", "claim", "component", "decision", "compensate", "releaseTask");
        // Shape: only a step's own inverse; never a send-back together with a decision.
        assertThat(api("dana.designer").post(Map.of("name", unique("page"), "definition", page("Bad", "task",
                List.of(Map.of("id", "decision", "type", "view", "view", approval.ref()), Map.of("id", "bar", "type", "actions", "chains", List.of("x"))),
                List.of(chain("x", List.of(Map.of("action", "approve", "component", "decision", "compensate", "releaseTask"))))) ), VIEWS, PURCHASING).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("name", unique("page"), "definition", page("Bad", "task",
                List.of(Map.of("id", "decision", "type", "view", "view", approval.ref()), Map.of("id", "bar", "type", "actions", "chains", List.of("x"))),
                List.of(chain("x", List.of(step("sendBack", "decision"), step("approve", "decision")))))), VIEWS, PURCHASING).status()).isEqualTo(422);

        UUID page = createPage(page("Undo desk", "task", List.of(
                Map.of("id", "decision", "type", "view", "view", approval.ref()),
                Map.of("id", "bar", "type", "actions", "chains", List.of("claimThenReject", "returnIt"))),
                List.of(chain("claimThenReject", List.of(claimUndone, step("reject", "decision"))),
                        chain("returnIt", List.of(claimUndone, Map.of("action", "sendBack", "component", "decision", "compensate", "withdrawSendBack"))))));
        publish(page, 1);
        String slug = unique("undo");
        route(slug, 0, page, 1);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();

        // The reject has no reason and fails; the claim before it declared its undo and is returned to the group.
        var undone = runChain("mani.manager", slug, "claimThenReject", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "context", taskId, "inputs", Map.of("decision", Map.of("expectedVersion", version(taskId)))));
        assertThat(undone.path("completed").asBoolean()).isFalse();
        assertThat(undone.path("steps").get(0).path("status").asString()).isEqualTo("succeeded");
        assertThat(undone.path("steps").get(0).path("compensation").path("action").asString()).isEqualTo("releaseTask");
        assertThat(undone.path("steps").get(0).path("compensation").path("status").asString()).isEqualTo("succeeded");
        assertThat(undone.path("steps").get(1).path("status").asString()).isEqualTo("failed");
        assertThat(api("olga.operator").get(TASK, PURCHASING, taskId).body().path("assignee").isNull()).isTrue();

        var returned = runChain("mani.manager", slug, "returnIt", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "context", taskId, "inputs", Map.of("decision", Map.of("expectedVersion", version(taskId), "reason", "Purpose missing"))));
        assertThat(returned.path("completed").asBoolean()).as(returned.toString()).isTrue();
        assertThat(api("olga.operator").get(TASK, PURCHASING, taskId).body().path("form").path("sendBack").path("reason").asString())
                .isEqualTo("Purpose missing");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'page.chain.run' AND resource_id = :id")
                .param("id", page.toString()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void caseDataIsRestoredWhenALaterStepFailsAndChosenOccurrencesMustBeAvailable() throws Exception {
        List<Map<String, Object>> fields = List.of(Map.of("key", "summary", "label", "Summary", "type", "text", "required", true, "maxLength", 80));
        Map<String, Object> view = Map.of("schemaVersion", 1, "kind", "case", "sections", List.of(Map.of("id", "details",
                "label", "Details", "columns", 1, "fields", List.of(field("summary", "Summary", "text", "case.summary", true)))),
                "actions", List.of(Map.of("id", "save", "label", "Save", "type", "saveData")));
        var created = api("dana.designer").post(Map.of("name", unique("case-view"), "definition", view), VIEWS, SANDBOX);
        UUID viewId = UUID.fromString(created.body().path("id").asString());
        assertThat(api("dana.designer").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", SANDBOX, viewId).status()).isEqualTo(200);
        String key = "undocase" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String xml;
        try (var input = getClass().getResourceAsStream("/cases/manual-required.cmmn")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("lifecycleCase", key);
        }
        var definition = api("dana.designer").post(Map.of("key", key, "name", "Undo case", "sourceXml", xml, "dataFields", fields,
                "viewRef", Map.of("id", viewId.toString(), "version", 1)), "/workspaces/{ws}/cases/definitions", SANDBOX);
        assertThat(definition.status()).as(definition.toString()).isEqualTo(201);
        String caseId = api("rhea.requester").post(Map.of("data", Map.of("summary", "Original")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/cases/definitions/{id}/instances", SANDBOX, definition.body().path("id").asString()).body().path("id").asString();
        Map<String, Object> pin = Map.of("id", viewId.toString(), "version", 1);
        var pageCreated = api("dana.designer").post(Map.of("name", unique("page"), "definition", page("Case undo", "case",
                List.of(Map.of("id", "data", "type", "view", "view", pin), Map.of("id", "bar", "type", "actions", "chains", List.of("saveThenFail", "pick"))),
                List.of(chain("saveThenFail", List.of(Map.of("action", "saveCaseData", "component", "data", "compensate", "restoreCaseData"),
                                Map.of("action", "startPlanItem", "component", "data", "planItem", "missingTask"))),
                        chain("pick", List.of(Map.of("action", "startPlanItem", "component", "data", "planItem", "requiredReview", "occurrence", "choose")))))),
                VIEWS, SANDBOX);
        assertThat(pageCreated.status()).as(pageCreated.toString()).isEqualTo(201);
        UUID pageId = UUID.fromString(pageCreated.body().path("id").asString());
        assertThat(api("dana.designer").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", SANDBOX, pageId).status()).isEqualTo(200);
        String slug = unique("caseundo");
        assertThat(api("dana.designer").put(Map.of("expectedRevision", 0, "viewRef", Map.of("id", pageId.toString(), "version", 1)), null,
                PAGES + "/{slug}", SANDBOX, slug).status()).isEqualTo(200);

        JsonNode undone = sandboxChain("mani.manager", slug, "saveThenFail", Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 1, "reason", "Update", "values", Map.of("summary", "Changed")))));
        assertThat(undone.path("steps").get(0).path("compensation").path("status").asString()).as(undone.toString()).isEqualTo("succeeded");
        assertThat(undone.path("steps").get(1).path("problemCode").asString()).isEqualTo("TASK_NOT_OPEN");
        assertThat(api("mani.manager").get("/workspaces/{ws}/cases/instances/{id}/data", SANDBOX, caseId).body().path("values")
                .path("summary").asString()).isEqualTo("Original");

        int version = api("mani.manager").get("/workspaces/{ws}/cases/instances/{id}/data", SANDBOX, caseId).body().path("version").asInt();
        JsonNode plan = api("mani.manager").get("/workspaces/{ws}/cases/instances/{id}/plan", SANDBOX, caseId).body();
        String item = null;
        for (JsonNode candidate : plan.path("items")) if ("requiredReview".equals(candidate.path("elementId").asString())) item = candidate.path("id").asString();
        Map<String, Object> wrong = new LinkedHashMap<>(Map.of("expectedVersion", version, "reason", "Start it", "planItems", Map.of("requiredReview", "elsewhere")));
        assertThat(sandboxChain("mani.manager", slug, "pick", Map.of("expectedRevision", 1, "context", caseId, "inputs", Map.of("data", wrong)))
                .path("steps").get(0).path("problemCode").asString()).isEqualTo("VALIDATION_FAILED");
        JsonNode picked = sandboxChain("mani.manager", slug, "pick", Map.of("expectedRevision", 1, "context", caseId, "inputs",
                Map.of("data", Map.of("expectedVersion", version, "reason", "Start it", "planItems", Map.of("requiredReview", item)))));
        assertThat(picked.path("completed").asBoolean()).as(picked.toString()).isTrue();
    }

    private JsonNode sandboxChain(String user, String slug, String chain, Map<String, Object> body) throws Exception {
        var run = api(user).post(body, UUID.randomUUID().toString(), PAGES + "/{slug}/chains/{chain}/run", SANDBOX, slug, chain);
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        return run.body();
    }

    static List<String> statuses(JsonNode result) {
        List<String> out = new ArrayList<>();
        result.path("steps").forEach(step -> out.add(step.path("status").asString()));
        return out;
    }
}
