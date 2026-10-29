package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** S111 composed actions: pinned chains, step-by-step server authorization, stop on failure and idempotent replay. */
class PageChainsApiTest extends PageTestBase {

    @Test
    void requestChainsSaveDraftsAndSubmitOnlyWithTheCallersOwnStartPermission() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        Pin request = publishedView(requestView());
        UUID page = createPage(requestPage("Buy", request, workflow));
        publish(page, 1);
        String slug = unique("buy");
        route(slug, 0, page, 1);

        var draft = runChain("rhea.requester", slug, "draft", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "inputs", Map.of("form", Map.of("values", Map.of("title", "Half typed")))));
        assertThat(draft.path("completed").asBoolean()).as(draft.toString()).isTrue();
        var drafts = api("rhea.requester").get(PAGES + "/{slug}/drafts", PURCHASING, slug);
        assertThat(drafts.body().path("items").get(0).path("values").path("title").asString()).isEqualTo("Half typed");
        assertThat(api("mani.manager").get(PAGES + "/{slug}/drafts", PURCHASING, slug).body().path("items").size()).isZero();

        // A field the pinned view does not bind is refused; the navigation after it never runs.
        var extra = runChain("rhea.requester", slug, "send", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "inputs", Map.of("form", Map.of("values", Map.of("title", "x", "secret", "y")))));
        assertThat(extra.path("completed").asBoolean()).isFalse();
        assertThat(extra.path("navigateTo").isNull()).isTrue();
        assertThat(statuses(extra)).containsExactly("failed", "skipped");
        assertThat(extra.path("steps").get(0).path("problemCode").asString()).isEqualTo("VALIDATION_FAILED");

        // The viewer is a member but holds no workflow.start: the run start refuses, not the page.
        var viewer = runChain("vic.viewer", slug, "send", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "inputs", Map.of("form", Map.of("values", purchase("Viewer attempt")))));
        assertThat(statuses(viewer)).containsExactly("failed", "skipped");
        assertThat(viewer.path("steps").get(0).path("problemCode").asString()).isEqualTo("FORBIDDEN");

        var sent = runChain("rhea.requester", slug, "send", UUID.randomUUID().toString(), Map.of("expectedRevision", 1,
                "inputs", Map.of("form", Map.of("values", purchase("Docking station")))));
        assertThat(sent.path("completed").asBoolean()).as(sent.toString()).isTrue();
        assertThat(statuses(sent)).containsExactly("succeeded", "succeeded");
        assertThat(sent.path("navigateTo").asString()).isEqualTo("/w/" + PURCHASING + "/requests");
        UUID runId = UUID.fromString(sent.path("steps").get(0).path("runId").asString());
        assertThat(run(PURCHASING, runId).path("requestTitle").asString()).isEqualTo("Docking station");
        assertThat(api("rhea.requester").get(PAGES + "/{slug}/drafts", PURCHASING, slug).body().path("items").size()).isZero();
        assertThat(api("otto.outsider").post(Map.of("expectedRevision", 1), UUID.randomUUID().toString(),
                PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "send").status()).isEqualTo(404);
    }

    @Test
    void taskChainsStopAtTheFirstFailureAndReplayTheRecordedResult() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        Pin approval = publishedView(approvalView());
        UUID page = createPage(page("Decide", "task", List.of(
                Map.of("id", "decision", "type", "view", "view", approval.ref()),
                Map.of("id", "bar", "type", "actions", "chains", List.of("approveNow")),
                Map.of("id", "facts", "type", "details", "label", "Facts", "source", "task",
                        "fields", List.of(Map.of("field", "task.requestTitle", "label", "Request")))),
                List.of(chain("approveNow", List.of(step("claim", "decision"), step("approve", "decision"),
                        navigate("/w/" + PURCHASING + "/tasks"))),
                        chain("hidden", List.of(step("reject", "decision"))))));
        publish(page, 1);
        String slug = unique("decide");
        route(slug, 0, page, 1);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, java.math.BigDecimal.TEN)
                .path("id").asString());
        JsonNode task = openTask(PURCHASING, runId, "manager");
        String taskId = task.path("id").asString();
        Map<String, Object> body = Map.of("expectedRevision", 1, "context", taskId,
                "inputs", Map.of("decision", Map.of("expectedVersion", task.path("version").asInt(), "comment", "Fine")));

        var worker = runChain("fin.finance", slug, "approveNow", UUID.randomUUID().toString(), body);
        assertThat(worker.path("completed").asBoolean()).isFalse();
        assertThat(statuses(worker)).containsExactly("failed", "skipped", "skipped");
        assertThat(openTask(PURCHASING, runId, "manager")).isNotNull();

        String key = UUID.randomUUID().toString();
        var manager = runChain("mani.manager", slug, "approveNow", key, body);
        assertThat(manager.path("completed").asBoolean()).as(manager.toString()).isTrue();
        assertThat(statuses(manager)).containsExactly("succeeded", "succeeded", "succeeded");
        assertThat(manager.path("navigateTo").asString()).isEqualTo("/w/" + PURCHASING + "/tasks");
        assertThat(openTask(PURCHASING, runId, "manager")).isNull();

        var replay = runChain("mani.manager", slug, "approveNow", key, body);
        assertThat(replay).isEqualTo(manager);
        var reused = api("mani.manager").post(Map.of("expectedRevision", 1, "context", taskId), key,
                PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "approveNow");
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // A second run on the decided task fails at the claim and does nothing else.
        var again = runChain("mani.manager", slug, "approveNow", UUID.randomUUID().toString(), body);
        assertThat(statuses(again)).containsExactly("failed", "skipped", "skipped");

        assertThat(api("mani.manager").post(Map.of("expectedRevision", 9, "context", taskId), UUID.randomUUID().toString(),
                PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "approveNow").status()).isEqualTo(409);
        assertThat(api("mani.manager").post(Map.of("expectedRevision", 1, "context", taskId), UUID.randomUUID().toString(),
                PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "hidden").status()).isEqualTo(403);
        assertThat(api("mani.manager").post(Map.of("expectedRevision", 1, "context", taskId, "source", "decision"),
                UUID.randomUUID().toString(), PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "approveNow").status())
                .isEqualTo(403);
        assertThat(api("mani.manager").post(Map.of("expectedRevision", 1, "context", taskId), UUID.randomUUID().toString(),
                PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "unknown").status()).isEqualTo(404);
        assertThat(api("mani.manager").post(Map.of("expectedRevision", 1), UUID.randomUUID().toString(),
                PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "approveNow").status()).isEqualTo(422);
        assertThat(api("mani.manager").post(Map.of("expectedRevision", 1, "context", taskId), null,
                PAGES + "/{slug}/chains/{chain}/run", PURCHASING, slug, "approveNow").status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'page.chain.run' AND resource_id = :id")
                .param("id", page.toString()).query(Integer.class).single()).isGreaterThanOrEqualTo(3);
    }

    private static List<String> statuses(JsonNode result) {
        List<String> statuses = new java.util.ArrayList<>();
        result.path("steps").forEach(step -> statuses.add(step.path("status").asString()));
        return statuses;
    }
}
