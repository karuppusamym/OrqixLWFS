package com.orqix.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * CG-I01 (docs/build/S151): grants on one folder, workflow, decision table or connection. A resource grant adds its
 * permission on that resource only: everything else stays as it was, and workspace-wide access never changes. Every
 * grant made here is removed again in a finally block.
 */
class ResourceGrantsTest extends RuntimeTestBase {

    private final String group = "orqix-hr-designers-" + UUID.randomUUID().toString().substring(0, 8);

    private Api hr() {
        return api("otto.outsider", List.of(group));
    }

    private String grant(String type, String id, String permission) throws Exception {
        Response created = api("wade.admin").post(Map.of("resourceType", type, "resourceId", id, "permission", permission,
                "principal", Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/resource-grants",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body().path("id").asString();
    }

    private void revoke(List<String> grants) throws Exception {
        for (String id : grants) {
            api("wade.admin").delete("/workspaces/{ws}/access/resource-grants/{id}", PURCHASING, id);
        }
    }

    private JsonNode folder() throws Exception {
        Response created = api("dana.designer").post(Map.of("name", "HR " + UUID.randomUUID().toString().substring(0, 8)),
                "/workspaces/{ws}/workflow-folders", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body();
    }

    private Response move(UUID workflowId, JsonNode folder) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("folderId", folder == null ? null : folder.path("id").asString());
        if (folder != null) {
            body.put("expectedFolderVersion", folder.path("version").asInt());
        }
        return api("dana.designer").put(body, null, "/workspaces/{ws}/workflows/{id}/folder", PURCHASING, workflowId);
    }

    private Set<String> listed(Api api, String path) throws Exception {
        Response list = api.get(path, PURCHASING);
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        Set<String> ids = new HashSet<>();
        list.body().path("items").forEach(i -> ids.add(i.path("id").asString()));
        return ids;
    }

    @Test
    void aFolderGrantGivesItsPermissionsOnTheFolderWorkflowsOnly() throws Exception {
        UUID hrWorkflow = publishPurchase(PURCHASING, null);
        UUID otherWorkflow = publishPurchase(PURCHASING, null);
        JsonNode hrFolder = folder();
        assertThat(move(hrWorkflow, hrFolder).status()).isEqualTo(200);
        UUID hrRun = UUID.fromString(start("rhea.requester", PURCHASING, hrWorkflow, new BigDecimal("120")).path("id").asString());
        UUID otherRun = UUID.fromString(start("rhea.requester", PURCHASING, otherWorkflow, new BigDecimal("120")).path("id").asString());

        // Without a grant the workspace does not exist for the HR designers.
        assertThat(hr().get("/workspaces/{ws}", PURCHASING).status()).isEqualTo(404);
        List<String> grants = new ArrayList<>();
        try {
            String folderId = hrFolder.path("id").asString();
            for (String permission : List.of("workflow.read", "workflow.edit", "run.read")) {
                grants.add(grant("folder", folderId, permission));
            }
            // Read and edit on the HR workflow, nothing on the other one.
            Response view = hr().get("/workspaces/{ws}/workflows/{id}", PURCHASING, hrWorkflow);
            assertThat(view.status()).as(view.toString()).isEqualTo(200);
            assertThat(view.body().path("permissions").toString()).contains("workflow.edit")
                    .doesNotContain("workflow.publish", "run.operate", "workspace.manage-access");
            assertThat(hr().get("/workspaces/{ws}/workflows/{id}", PURCHASING, otherWorkflow).status()).isEqualTo(404);
            assertThat(hr().get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, otherWorkflow).status()).isEqualTo(404);
            assertThat(listed(hr(), "/workspaces/{ws}/workflows?limit=100")).containsExactly(hrWorkflow.toString());

            JsonNode draft = hr().get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, hrWorkflow).body();
            Response saved = hr().put(Map.of("definition", draft.path("definition")), "\"r" + draft.path("revision").asInt()
                    + "\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, hrWorkflow);
            assertThat(saved.status()).as(saved.toString()).isEqualTo(200);

            // No publish, no start, no new workflows, no access administration, no folder moves.
            assertThat(hr().post(Map.of("draftRevision", saved.body().path("revision").asInt(), "acknowledgeWarnings", true),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, hrWorkflow)
                    .status()).isEqualTo(403);
            assertThat(hr().post(Map.of("workflowId", hrWorkflow, "input", input(new BigDecimal("10"))),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING).status()).isEqualTo(403);
            assertThat(hr().post(Map.of("key", "hr-new-" + UUID.randomUUID().toString().substring(0, 6), "name", "New",
                    "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING).status()).isEqualTo(403);
            assertThat(hr().get("/workspaces/{ws}/access", PURCHASING).status()).isEqualTo(403);
            Map<String, Object> unfile = new HashMap<>();
            unfile.put("folderId", null);
            assertThat(hr().put(unfile, null, "/workspaces/{ws}/workflows/{id}/folder", PURCHASING, hrWorkflow).status())
                    .isEqualTo(403);

            // Requests: run.read on the folder lists and opens that workflow's requests only, without their data.
            Set<String> runs = listed(hr(), "/workspaces/{ws}/runs?limit=100&workflowId=" + hrWorkflow);
            assertThat(runs).contains(hrRun.toString()).doesNotContain(otherRun.toString());
            assertThat(listed(hr(), "/workspaces/{ws}/runs?limit=100")).contains(hrRun.toString())
                    .doesNotContain(otherRun.toString());
            assertThat(hr().get("/workspaces/{ws}/runs?limit=10&workflowId=" + otherWorkflow, PURCHASING).status())
                    .isEqualTo(403);
            Response hrRunView = hr().get("/workspaces/{ws}/runs/{id}", PURCHASING, hrRun);
            assertThat(hrRunView.status()).isEqualTo(200);
            assertThat(hrRunView.body().path("input").isNull()).isTrue();
            assertThat(hr().get("/workspaces/{ws}/runs/{id}", PURCHASING, otherRun).status()).isEqualTo(404);
            assertThat(hr().post(Map.of("reason", "Not mine", "expectedVersion", 1), UUID.randomUUID().toString(),
                    "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, hrRun).status()).isEqualTo(403);

            // Moving the workflow out of the folder ends the folder's access at once.
            assertThat(move(hrWorkflow, null).status()).isEqualTo(200);
            assertThat(hr().get("/workspaces/{ws}/workflows/{id}", PURCHASING, hrWorkflow).status()).isEqualTo(404);
            assertThat(hr().get("/workspaces/{ws}/runs/{id}", PURCHASING, hrRun).status()).isEqualTo(404);
        } finally {
            revoke(grants);
        }
        assertThat(hr().get("/workspaces/{ws}", PURCHASING).status()).isEqualTo(404);
    }

    @Test
    void workflowGrantsCanStartAndOperateOneWorkflowAndNeverWidenWorkspaceAccess() throws Exception {
        UUID granted = publishPurchase(PURCHASING, null);
        UUID other = publishPurchase(PURCHASING, null);
        Set<String> viewerBefore = listed(api("vic.viewer"), "/workspaces/{ws}/workflows?limit=100");
        List<String> grants = new ArrayList<>();
        try {
            grants.add(grant("workflow", granted.toString(), "workflow.start"));
            grants.add(grant("workflow", granted.toString(), "run.operate"));
            // A starter without read sees the workflow it may start, and nothing else.
            assertThat(listed(hr(), "/workspaces/{ws}/workflows?limit=100")).containsExactly(granted.toString());
            Response started = hr().post(Map.of("workflowId", granted, "input", input(new BigDecimal("50"))),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
            assertThat(started.status()).as(started.toString()).isEqualTo(201);
            assertThat(hr().post(Map.of("workflowId", other, "input", input(new BigDecimal("50"))),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING).status()).isEqualTo(404);
            // run.operate on the workflow covers its requests (the initiator sees their own request).
            UUID runId = UUID.fromString(started.body().path("id").asString());
            int version = hr().get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body().path("version").asInt();
            Response cancelled = hr().post(Map.of("reason", "Cancelled by HR", "expectedVersion", version),
                    UUID.randomUUID().toString(),
                    "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, runId);
            assertThat(cancelled.status()).as(cancelled.toString()).isIn(200, 202);
            UUID foreign = UUID.fromString(start("rhea.requester", PURCHASING, other, new BigDecimal("50")).path("id").asString());
            assertThat(hr().post(Map.of("reason", "Not mine", "expectedVersion", 1), UUID.randomUUID().toString(),
                    "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, foreign).status()).isEqualTo(404);
            // Workspace-wide holders see exactly what they saw before.
            assertThat(listed(api("vic.viewer"), "/workspaces/{ws}/workflows?limit=100")).containsAll(viewerBefore);
            assertThat(api("vic.viewer").put(Map.of("definition", Map.of()), "\"r2\"",
                    "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, granted).status()).isEqualTo(403);
        } finally {
            revoke(grants);
        }
    }

    @Test
    void decisionAndConnectionGrantsCoverTheirOwnKeyOnly() throws Exception {
        String key = "hr-routing-" + UUID.randomUUID().toString().substring(0, 6);
        Response asset = api("dana.designer").post(Map.of("key", key, "name", "HR routing " + key, "content",
                Map.of("hitPolicy", "UNIQUE", "rows", List.of(Map.of("when", Map.of("fieldRef", "/input/amount",
                        "operator", "gte", "value", 5000), "then", Map.of("route", "financeReview"))),
                        "default", Map.of("route", "standard"))), "/workspaces/{ws}/shared-assets/decision", PURCHASING);
        assertThat(asset.status()).as(asset.toString()).isEqualTo(201);
        List<String> grants = new ArrayList<>();
        try {
            grants.add(grant("decision", key, "workflow.read"));
            grants.add(grant("connection", "procurement", "connection.read"));
            assertThat(hr().get("/workspaces/{ws}/shared-assets/decision/{key}", PURCHASING, key).status()).isEqualTo(200);
            Response search = hr().get("/workspaces/{ws}/shared-assets", PURCHASING);
            assertThat(search.status()).as(search.toString()).isEqualTo(200);
            Set<String> keys = new HashSet<>();
            search.body().path("items").forEach(i -> keys.add(i.path("kind").asString() + ":" + i.path("key").asString()));
            assertThat(keys).containsExactlyInAnyOrder("decision:" + key, "connection:procurement");
            // Read only: no new version, no usage list of workflows.
            assertThat(hr().post(Map.of("expectedVersion", 1, "content", Map.of("hitPolicy", "UNIQUE", "rows",
                    List.of(Map.of("when", Map.of("fieldRef", "/input/amount", "operator", "gte", "value", 9000),
                            "then", Map.of("route", "financeReview"))), "default", Map.of("route", "standard"))),
                    "/workspaces/{ws}/shared-assets/decision/{key}/versions", PURCHASING, key)
                    .status()).isEqualTo(403);
            assertThat(hr().get("/workspaces/{ws}/shared-assets/decision/{key}/usage", PURCHASING, key).status())
                    .isEqualTo(403);
            Response connections = hr().get("/workspaces/{ws}/connections", PURCHASING);
            assertThat(connections.status()).isEqualTo(200);
            List<String> connectionKeys = new ArrayList<>();
            connections.body().path("items").forEach(i -> connectionKeys.add(i.path("key").asString()));
            assertThat(connectionKeys).containsExactly("procurement");
            assertThat(hr().get("/workspaces/{ws}/connections/{key}", PURCHASING, "procurement").status()).isEqualTo(200);
            // No workflow access at all.
            assertThat(hr().get("/workspaces/{ws}/workflows?limit=10", PURCHASING).status()).isEqualTo(403);
        } finally {
            revoke(grants);
        }
    }

    @Test
    void grantsAreValidatedManagedByAdministratorsAndAudited() throws Exception {
        JsonNode hrFolder = folder();
        String folderId = hrFolder.path("id").asString();
        Map<String, Object> principal = Map.of("type", "idpGroup", "value", group);
        assertThat(api("wade.admin").post(Map.of("resourceType", "folder", "resourceId", folderId, "permission",
                "connection.read", "principal", principal), "/workspaces/{ws}/access/resource-grants", PURCHASING).status())
                .isEqualTo(422);
        assertThat(api("wade.admin").post(Map.of("resourceType", "folder", "resourceId", UUID.randomUUID().toString(),
                "permission", "workflow.read", "principal", principal), "/workspaces/{ws}/access/resource-grants",
                PURCHASING).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("resourceType", "folder", "resourceId", folderId, "permission",
                "workflow.read", "principal", principal), "/workspaces/{ws}/access/resource-grants", PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("fred.facilities").get("/workspaces/{ws}/access/resource-grants", PURCHASING).status())
                .isEqualTo(404);
        String id = grant("folder", folderId, "workflow.read");
        try {
            assertThat(api("wade.admin").post(Map.of("resourceType", "folder", "resourceId", folderId, "permission",
                    "workflow.read", "principal", principal), "/workspaces/{ws}/access/resource-grants", PURCHASING)
                    .status()).isEqualTo(409);
            JsonNode list = api("wade.admin").get("/workspaces/{ws}/access/resource-grants?resourceType=folder&resourceId="
                    + folderId, PURCHASING).body();
            assertThat(list.path("items")).hasSize(1);
            assertThat(list.path("items").get(0).path("resourceName").asString()).isEqualTo(hrFolder.path("name").asString());
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'access.resource-grant.created'"
                    + " AND resource_id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
        } finally {
            revoke(List.of(id));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'access.resource-grant.deleted'"
                + " AND resource_id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);

        // Deleting the folder removes its grants.
        String second = grant("folder", folderId, "run.read");
        assertThat(api("dana.designer").delete("/workspaces/{ws}/workflow-folders/{id}", PURCHASING, folderId).status())
                .isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM resource_grant WHERE id = :id").param("id", UUID.fromString(second))
                .query(Long.class).single()).isZero();
    }
}
