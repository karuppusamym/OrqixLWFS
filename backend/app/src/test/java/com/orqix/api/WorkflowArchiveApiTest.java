package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

/** S103: archiving a workflow (removes it from the catalog; refused while its requests are open). S104: restoring it. */
class WorkflowArchiveApiTest extends RuntimeTestBase {

    @Test
    void archivingNeedsEditAndNoOpenRequestsAndRemovesTheWorkflowFromTheCatalog() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        int version = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).body().path("version").asInt();
        Map<String, Object> body = Map.of("expectedVersion", version, "reason", "Replaced by the new purchase flow");
        assertThat(api("rhea.requester").post(body, "/workspaces/{ws}/workflows/{id}/archive", PURCHASING, workflowId).status()).isEqualTo(403);
        // An open request keeps the workflow.
        Response started = api("rhea.requester").post(Map.of("workflowId", workflowId, "input", input(BigDecimal.ONE)), UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        Response busy = api("dana.designer").post(body, "/workspaces/{ws}/workflows/{id}/archive", PURCHASING, workflowId);
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.code()).isEqualTo("RESOURCE_IN_USE");
        UUID run = UUID.fromString(started.body().path("id").asString());
        Response cancelled = api("olga.operator").post(Map.of("reason", "test", "expectedVersion", run(PURCHASING, run).path("version").asInt()),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);

        Response stale = api("dana.designer").post(Map.of("expectedVersion", version + 5, "reason", "Old"), "/workspaces/{ws}/workflows/{id}/archive",
                PURCHASING, workflowId);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(api("dana.designer").post(body, "/workspaces/{ws}/workflows/{id}/archive", PURCHASING, workflowId).status()).isEqualTo(204);
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows", PURCHASING).body().path("items").toString()).doesNotContain(workflowId.toString());
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).status()).isEqualTo(404);
    }

    private Response archive(UUID workflowId, String reason) throws Exception {
        int version = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).body().path("version").asInt();
        return api("dana.designer").post(Map.of("expectedVersion", version, "reason", reason), "/workspaces/{ws}/workflows/{id}/archive",
                PURCHASING, workflowId);
    }

    @Test
    void aReasonOfOnlySpacesIsRefused() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        Response blank = archive(workflowId, "     ");
        assertThat(blank.status()).as(blank.toString()).isEqualTo(400);
        // Three characters only with the spaces around them: still too short once trimmed.
        Response padded = archive(workflowId, "  ab  ");
        assertThat(padded.status()).as(padded.toString()).isEqualTo(422);
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).status()).isEqualTo(200);
    }

    @Test
    void aWorkflowThatAPublishedWorkflowStartsAsAStepIsKept() throws Exception {
        UUID child = publishPurchase(PURCHASING, null);
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, child).body().path("key").asString();
        UUID parent = publishPurchase(PURCHASING, d -> {
            d.set("nodes", json.parse("[{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}},"
                    + "{\"id\":\"child\",\"type\":\"startWorkflow\",\"label\":\"Purchase\",\"config\":{\"workflowRef\":\"" + key
                    + "\",\"inputMapping\":{\"title\":\"/input/title\",\"purpose\":\"/input/purpose\",\"amount\":\"/input/amount\",\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{}}},"
                    + "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Completed\",\"outcomeKind\":\"success\"}}]"));
            d.set("transitions", json.parse("[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"child\",\"on\":\"next\"},"
                    + "{\"id\":\"t2\",\"from\":\"child\",\"to\":\"end\",\"on\":\"next\"}]"));
            d.set("decisions", json.parse("[]"));
            d.set("outputMapping", json.object());
            d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
            d.set("dependencies", json.parse("{\"connections\":[]}"));
            d.remove("uiLayout");
        });
        Response used = archive(child, "Replaced by the new flow");
        assertThat(used.status()).as(used.toString()).isEqualTo(409);
        assertThat(used.code()).isEqualTo("RESOURCE_IN_USE");
        assertThat(used.body().path("detail").asString()).contains("start this one as a step");
        // Once the parent is archived, nothing published starts the child any more.
        assertThat(archive(parent, "Retired").status()).isEqualTo(204);
        assertThat(archive(child, "Replaced by the new flow").status()).isEqualTo(204);
    }

    @Test
    void aWorkflowThatASwitchedOnTriggerStartsIsKept() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID subject = jdbc.sql("SELECT published_by FROM workflow_release WHERE workflow_id = :id LIMIT 1").param("id", workflowId)
                .query(UUID.class).single();
        UUID trigger = UUID.randomUUID();
        String key = "arch-" + trigger.toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO kafka_trigger (id, workspace_id, key, name, cluster, topic, consumer_group, action, workflow_id, mapping,
                                           batch_size, max_per_second, start_from, run_as, enabled, created_by, created_at)
                VALUES (:id, :ws, :key, 'Orders', 'main', 'orders', :key, 'start', :wf, '{}'::jsonb, 10, 10, 'latest', :by, true, :by, now())
                """).param("id", trigger).param("ws", PURCHASING).param("key", key).param("wf", workflowId).param("by", subject).update();
        Response used = archive(workflowId, "Replaced by the new flow");
        assertThat(used.status()).as(used.toString()).isEqualTo(409);
        assertThat(used.code()).isEqualTo("RESOURCE_IN_USE");
        assertThat(used.body().path("detail").asString()).contains(key);
        jdbc.sql("UPDATE kafka_trigger SET enabled = false WHERE id = :id").param("id", trigger).update();
        assertThat(archive(workflowId, "Replaced by the new flow").status()).isEqualTo(204);
    }

    @Test
    void anArchivedWorkflowIsListedAndCanBeRestoredWithItsReleaseSoItStartsAgain() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        int version = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).body().path("version").asInt();
        // Restoring a workflow that is not archived is a 404, so the endpoint cannot be used to probe live workflows.
        assertThat(api("dana.designer").post(Map.of("expectedVersion", version, "reason", "Not archived"),
                "/workspaces/{ws}/workflows/{id}/restore", PURCHASING, workflowId).status()).isEqualTo(404);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", version, "reason", "Retired for now"),
                "/workspaces/{ws}/workflows/{id}/archive", PURCHASING, workflowId).status()).isEqualTo(204);

        assertThat(api("rhea.requester").get("/workspaces/{ws}/archived-workflows", PURCHASING).status()).isEqualTo(403);
        Response list = api("dana.designer").get("/workspaces/{ws}/archived-workflows", PURCHASING);
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        tools.jackson.databind.JsonNode item = null;
        for (tools.jackson.databind.JsonNode candidate : list.body().path("items")) {
            if (candidate.path("id").asString().equals(workflowId.toString())) {
                item = candidate;
            }
        }
        assertThat(item).as(list.toString()).isNotNull();
        assertThat(item.path("archivedBy").path("displayName").asString()).isNotBlank();
        assertThat(item.path("activeReleaseNumber").asInt()).isEqualTo(1);
        int archivedVersion = item.path("version").asInt();
        assertThat(archivedVersion).isEqualTo(version + 1);

        Map<String, Object> body = Map.of("expectedVersion", archivedVersion, "reason", "Needed again this quarter");
        assertThat(api("rhea.requester").post(body, "/workspaces/{ws}/workflows/{id}/restore", PURCHASING, workflowId).status()).isEqualTo(403);
        Response stale = api("dana.designer").post(Map.of("expectedVersion", version, "reason", "Stale copy"),
                "/workspaces/{ws}/workflows/{id}/restore", PURCHASING, workflowId);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("WORKFLOW_VERSION_CONFLICT");
        Response restored = api("dana.designer").post(body, "/workspaces/{ws}/workflows/{id}/restore", PURCHASING, workflowId);
        assertThat(restored.status()).as(restored.toString()).isEqualTo(200);
        assertThat(restored.body().path("activeRelease").path("releaseNumber").asInt()).isEqualTo(1);
        // A second restore with the same version is refused: it is no longer archived.
        assertThat(api("dana.designer").post(body, "/workspaces/{ws}/workflows/{id}/restore", PURCHASING, workflowId).status()).isEqualTo(404);

        assertThat(api("dana.designer").get("/workspaces/{ws}/archived-workflows", PURCHASING).body().path("items").toString())
                .doesNotContain(workflowId.toString());
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows", PURCHASING).body().path("items").toString()).contains(workflowId.toString());
        Response started = api("rhea.requester").post(Map.of("workflowId", workflowId, "input", input(BigDecimal.ONE)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
    }
}
