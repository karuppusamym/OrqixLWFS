package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S114: correction and case lifecycle / plan-item chain steps, each re-authorized by its own operation with the carried
 * version, a reason and a derived idempotency key; and request pages with file controls that use the request form's
 * staged uploads and the run start's own attachment checks.
 */
class PageCompletionApiTest extends PageTestBase {

    @Test
    void correctionStepsCorrectOnlyDeclaredFieldsAndCarryTheTaskVersionIntoTheDecision() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, definition -> {
            for (JsonNode node : definition.path("nodes")) {
                if ("manager".equals(node.path("id").asString())) ((ObjectNode) node.path("config")).putArray("correctable").add("purpose");
            }
        });
        Map<String, Object> view = new java.util.LinkedHashMap<>(approvalView());
        view.put("sections", List.of(Map.of("id", "summary", "label", "Summary", "columns", 1, "fields", List.of(
                field("title", "Item", "text", "request.title", true),
                field("purpose", "Purpose", "text", "request.purpose", true),
                field("amount", "Amount", "number", "request.amount", true)))));
        view.put("actions", List.of(Map.of("id", "approve", "label", "Approve", "type", "approve"),
                Map.of("id", "reject", "label", "Reject", "type", "reject"),
                Map.of("id", "fix", "label", "Correct", "type", "requestCorrection")));
        Pin approval = publishedView(view);
        UUID page = createPage(page("Fix and approve", "task", List.of(
                Map.of("id", "decision", "type", "view", "view", approval.ref()),
                Map.of("id", "bar", "type", "actions", "chains", List.of("fix"))),
                List.of(chain("fix", List.of(step("claim", "decision"), step("requestCorrection", "decision"),
                        step("approve", "decision"))))));
        publish(page, 1);
        String slug = unique("fix");
        route(slug, 0, page, 1);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        JsonNode task = openTask(PURCHASING, runId, "manager");
        String taskId = task.path("id").asString();

        // Amount is shown but not correctable here: the correction operation refuses it and nothing after runs.
        var refused = runChain("mani.manager", slug, "fix", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "context", taskId, "inputs", Map.of("decision", Map.of("expectedVersion", task.path("version").asInt(),
                        "reason", "Wrong amount", "values", Map.of("amount", 99)))));
        assertThat(statuses(refused)).containsExactly("succeeded", "failed", "skipped");
        assertThat(refused.path("steps").get(1).path("problemCode").asString()).isEqualTo("VALIDATION_FAILED");

        // The claim above stands (earlier steps are not undone); a page without the claim step finishes the work.
        int version = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body().path("version").asInt();
        Map<String, Object> fix = Map.of("expectedRevision", 1, "context", taskId, "inputs", Map.of("decision",
                Map.of("expectedVersion", version, "reason", "Typo in purpose", "values", Map.of("purpose", "Laptop broke"))));
        UUID second = createPage(page("Fix only", "task", List.of(
                Map.of("id", "decision", "type", "view", "view", approval.ref()),
                Map.of("id", "bar", "type", "actions", "chains", List.of("fix"))),
                List.of(chain("fix", List.of(step("requestCorrection", "decision"), step("approve", "decision"))))));
        publish(second, 1);
        String fixSlug = unique("fixonly");
        route(fixSlug, 0, second, 1);
        var noReason = runChain("mani.manager", fixSlug, "fix", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "context", taskId, "inputs", Map.of("decision", Map.of("expectedVersion", version, "values", Map.of("purpose", "x")))));
        assertThat(statuses(noReason)).containsExactly("failed", "skipped");
        var done = runChain("mani.manager", fixSlug, "fix", UUID.randomUUID().toString(), fix);
        assertThat(done.path("completed").asBoolean()).as(done.toString()).isTrue();
        assertThat(statuses(done)).containsExactly("succeeded", "succeeded");
        assertThat(run(PURCHASING, runId).path("input").path("purpose").asString()).isEqualTo("Laptop broke");
        assertThat(openTask(PURCHASING, runId, "manager")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'task.input-corrected' AND resource_id = :id")
                .param("id", taskId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void caseStepsRunLifecycleAndPlanItemCommandsWithTheCarriedCaseVersion() throws Exception {
        List<Map<String, Object>> fields = List.of(Map.of("key", "summary", "label", "Summary", "type", "text", "required", true, "maxLength", 80));
        Map<String, Object> view = Map.of("schemaVersion", 1, "kind", "case", "sections", List.of(Map.of("id", "details",
                "label", "Details", "columns", 1, "fields", List.of(field("summary", "Summary", "text", "case.summary", true)))),
                "actions", List.of(Map.of("id", "save", "label", "Save", "type", "saveData")));
        UUID viewId = sandboxView(view);
        String key = "pagecase" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String xml;
        try (var input = getClass().getResourceAsStream("/cases/manual-required.cmmn")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("lifecycleCase", key);
        }
        var definition = api("dana.designer").post(Map.of("key", key, "name", "Page case", "sourceXml", xml, "dataFields", fields,
                "viewRef", Map.of("id", viewId.toString(), "version", 1)), "/workspaces/{ws}/cases/definitions", SANDBOX);
        assertThat(definition.status()).as(definition.toString()).isEqualTo(201);
        var started = api("rhea.requester").post(Map.of("data", Map.of("summary", "Start")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/cases/definitions/{id}/instances", SANDBOX, definition.body().path("id").asString());
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        String caseId = started.body().path("id").asString();

        Map<String, Object> pin = Map.of("id", viewId.toString(), "version", 1);
        // Shape rules: plan-item steps name a CMMN element, and nothing on the case follows closing it.
        assertThat(sandboxPageStatus(page("Bad", "case", List.of(Map.of("id", "data", "type", "view", "view", pin),
                Map.of("id", "bar", "type", "actions", "chains", List.of("x"))),
                List.of(chain("x", List.of(step("startPlanItem", "data"))))))).isEqualTo(422);
        assertThat(sandboxPageStatus(page("Bad", "case", List.of(Map.of("id", "data", "type", "view", "view", pin),
                Map.of("id", "bar", "type", "actions", "chains", List.of("x"))),
                List.of(chain("x", List.of(step("closeCase", "data"), step("suspendCase", "data"))))))).isEqualTo(422);

        UUID page = sandboxPage(page("Case desk", "case", List.of(Map.of("id", "data", "type", "view", "view", pin),
                Map.of("id", "bar", "type", "actions", "chains", List.of("work", "hold", "close"))),
                List.of(chain("work", List.of(planStep("startPlanItem", "requiredReview"), planStep("completePlanItem", "requiredReview"))),
                        chain("hold", List.of(step("suspendCase", "data"), step("resumeCase", "data"))),
                        chain("close", List.of(step("closeCase", "data"), navigate("/w/" + SANDBOX + "/cases"))))));
        String slug = unique("casedesk");
        var routed = api("dana.designer").put(Map.of("expectedRevision", 0, "viewRef", Map.of("id", page.toString(), "version", 1)),
                null, PAGES + "/{slug}", SANDBOX, slug);
        assertThat(routed.status()).as(routed.toString()).isEqualTo(200);

        var work = sandboxChain("mani.manager", slug, "work", Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 1, "reason", "Review now"))));
        assertThat(work.path("completed").asBoolean()).as(work.toString()).isTrue();
        assertThat(statuses(work)).containsExactly("succeeded", "succeeded");
        assertThat(caseVersion(caseId)).isEqualTo(3);

        // The worker holds no run.operate: the case hold is refused by its own operation.
        var refused = sandboxChain("mani.manager", slug, "hold", Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 3, "reason", "Pause"))));
        assertThat(statuses(refused)).containsExactly("failed", "skipped");
        assertThat(refused.path("steps").get(0).path("problemCode").asString()).isEqualTo("FORBIDDEN");
        var noReason = sandboxChain("olga.operator", slug, "hold", Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 3))));
        assertThat(statuses(noReason)).containsExactly("failed", "skipped");
        var stale = sandboxChain("olga.operator", slug, "hold", Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 2, "reason", "Pause"))));
        assertThat(stale.path("steps").get(0).path("problemCode").asString()).isEqualTo("TASK_VERSION_CONFLICT");

        var hold = sandboxChain("olga.operator", slug, "hold", Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 3, "reason", "Pause and continue"))));
        assertThat(statuses(hold)).as(hold.toString()).containsExactly("succeeded", "succeeded");
        assertThat(caseVersion(caseId)).isEqualTo(5);

        var close = sandboxChain("olga.operator", slug, "close", Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 5, "reason", "All work is done"))));
        assertThat(statuses(close)).as(close.toString()).containsExactly("succeeded", "succeeded");
        assertThat(api("olga.operator").get("/workspaces/{ws}/cases/instances/{id}", SANDBOX, caseId).body().path("status").asString())
                .isEqualTo("completed");
        // A closed case: the worker can no longer read it, and the operator finds no plan item to start.
        Map<String, Object> again = Map.of("expectedRevision", 1, "context", caseId,
                "inputs", Map.of("data", Map.of("expectedVersion", 6, "reason", "Again")));
        assertThat(sandboxChain("mani.manager", slug, "work", again).path("steps").get(0).path("problemCode").asString()).isEqualTo("NOT_FOUND");
        assertThat(sandboxChain("olga.operator", slug, "work", again).path("steps").get(0).path("problemCode").asString()).isEqualTo("TASK_NOT_OPEN");
    }

    @Test
    void requestPagesWithFileControlsSubmitStagedUploadsThroughTheRunStartChecks() throws Exception {
        jdbc.sql("UPDATE run_attachment SET created_at = created_at - interval '1 hour' WHERE created_at > now() - interval '5 minutes'").update();
        UUID workflow = publishPurchase(PURCHASING, definition -> ((ObjectNode) definition.path("inputSchema").path("properties"))
                .putObject("quote").put("type", "string").put("title", "Supplier quote").put("format", "attachment"));
        Map<String, Object> view = new java.util.LinkedHashMap<>(requestView());
        List<Map<String, Object>> withFile = new ArrayList<>(List.of(
                field("title", "What are you buying?", "text", "request.title", true),
                field("purpose", "Business purpose", "text", "request.purpose", true),
                field("costCenter", "Cost center", "choice", "request.costCenter", true),
                field("amount", "Amount (USD)", "number", "request.amount", true),
                field("quote", "Supplier quote", "file", "request.quote", false)));
        view.put("sections", List.of(Map.of("id", "details", "label", "Purchase", "columns", 2, "fields", withFile)));
        Pin request = publishedView(view);
        UUID page = createPage(requestPage("Buy with quote", request, workflow));
        publish(page, 1);
        String slug = unique("quote");
        route(slug, 0, page, 1);

        String mine = stage("rhea.requester");
        String theirs = stage("mani.manager");
        Map<String, Object> values = new java.util.LinkedHashMap<>(purchase("Monitor"));
        values.put("quote", mine);
        var draft = runChain("rhea.requester", slug, "draft", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "inputs", Map.of("form", Map.of("values", values))));
        assertThat(draft.path("completed").asBoolean()).isTrue();
        JsonNode saved = api("rhea.requester").get(PAGES + "/{slug}/drafts", PURCHASING, slug).body().path("items").get(0).path("values");
        assertThat(saved.has("quote")).as("drafts keep no file references").isFalse();
        assertThat(saved.path("title").asString()).isEqualTo("Monitor");

        Map<String, Object> foreign = new java.util.LinkedHashMap<>(values);
        foreign.put("quote", theirs);
        var refused = runChain("rhea.requester", slug, "send", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "inputs", Map.of("form", Map.of("values", foreign))));
        assertThat(statuses(refused)).containsExactly("failed", "skipped");

        var sent = runChain("rhea.requester", slug, "send", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "inputs", Map.of("form", Map.of("values", values))));
        assertThat(sent.path("completed").asBoolean()).as(sent.toString()).isTrue();
        UUID runId = UUID.fromString(sent.path("steps").get(0).path("runId").asString());
        assertThat(jdbc.sql("SELECT run_id FROM run_attachment WHERE id = :id").param("id", UUID.fromString(mine))
                .query(UUID.class).single()).isEqualTo(runId);
    }

    private String stage(String user) throws Exception {
        var staged = api(user).upload(UUID.randomUUID().toString(), "quote.txt", "text/plain",
                "Quote".getBytes(StandardCharsets.UTF_8), Map.of(), "/workspaces/{ws}/attachment-uploads", PURCHASING);
        assertThat(staged.status()).as(staged.toString()).isEqualTo(201);
        return staged.body().path("id").asString();
    }

    private static Map<String, Object> planStep(String action, String element) {
        return Map.of("action", action, "component", "data", "planItem", element);
    }

    private UUID sandboxView(Map<String, Object> definition) throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("case-view"), "definition", definition), VIEWS, SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        assertThat(api("dana.designer").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", SANDBOX, id).status()).isEqualTo(200);
        return id;
    }

    private int sandboxPageStatus(Map<String, Object> definition) throws Exception {
        return api("dana.designer").post(Map.of("name", unique("page"), "definition", definition), VIEWS, SANDBOX).status();
    }

    private UUID sandboxPage(Map<String, Object> definition) throws Exception {
        var created = api("dana.designer").post(Map.of("name", unique("page"), "definition", definition), VIEWS, SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        var published = api("dana.designer").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", SANDBOX, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(200);
        return id;
    }

    private JsonNode sandboxChain(String user, String slug, String chain, Map<String, Object> body) throws Exception {
        var run = api(user).post(body, UUID.randomUUID().toString(), PAGES + "/{slug}/chains/{chain}/run", SANDBOX, slug, chain);
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        return run.body();
    }

    private int caseVersion(String caseId) {
        return jdbc.sql("SELECT version FROM case_instance WHERE id = :id").param("id", UUID.fromString(caseId)).query(Integer.class).single();
    }

    private static List<String> statuses(JsonNode result) {
        List<String> statuses = new ArrayList<>();
        result.path("steps").forEach(step -> statuses.add(step.path("status").asString()));
        return statuses;
    }
}
