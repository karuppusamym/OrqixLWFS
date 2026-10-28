package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

/** S103: archiving a workflow (removes it from the catalog; refused while its requests are open). */
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
}
