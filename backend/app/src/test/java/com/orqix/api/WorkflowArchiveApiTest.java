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
