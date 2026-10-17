package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** PL01 (docs/build/S46): folders and owners organize workflows and never change who may do what. */
class WorkflowOrganizationApiTest extends IntegrationTestBase {

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID workflow(String user, UUID ws) throws Exception {
        var response = api(user).post(Map.of("key", unique("org"), "name", "Organized " + unique("w"),
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", ws);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }

    private JsonNode folder(String name) throws Exception {
        var response = api("dana.designer").post(Map.of("name", name), "/workspaces/{ws}/workflow-folders", PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return response.body();
    }

    private Response move(UUID wf, JsonNode folder) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("folderId", folder == null ? null : folder.path("id").asString());
        if (folder != null) {
            body.put("expectedFolderVersion", folder.path("version").asInt());
        }
        return api("dana.designer").put(body, null, "/workspaces/{ws}/workflows/{id}/folder", PURCHASING, wf);
    }

    private List<String> listedIds(String user, String query) throws Exception {
        var response = api(user).get("/workspaces/{ws}/workflows?limit=100" + query, PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        var ids = new java.util.ArrayList<String>();
        response.body().path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }

    @Test
    void foldersFilterTheListWithoutChangingAccess() throws Exception {
        UUID inFolder = workflow("dana.designer", PURCHASING);
        UUID loose = workflow("dana.designer", PURCHASING);
        JsonNode f = folder(unique("Finance"));
        var moved = move(inFolder, f);
        assertThat(moved.status()).as(moved.toString()).isEqualTo(200);
        assertThat(moved.body().path("folderId").asString()).isEqualTo(f.path("id").asString());

        String folderQuery = "&folder=" + f.path("id").asString();
        assertThat(listedIds("vic.viewer", folderQuery)).containsExactly(inFolder.toString());
        assertThat(listedIds("vic.viewer", "&folder=none")).contains(loose.toString()).doesNotContain(inFolder.toString());
        assertThat(listedIds("vic.viewer", "")).contains(inFolder.toString(), loose.toString());

        // The organization counts and owner list describe what readers can already see.
        JsonNode org = api("vic.viewer").get("/workspaces/{ws}/workflow-organization", PURCHASING).body();
        JsonNode counted = null;
        for (JsonNode candidate : org.path("folders")) {
            if (candidate.path("id").asString().equals(f.path("id").asString())) {
                counted = candidate;
            }
        }
        assertThat(counted).isNotNull();
        assertThat(counted.path("workflowCount").asInt()).isEqualTo(1);
        assertThat(org.path("unfiledCount").asInt()).isPositive();
        assertThat(org.path("folderLimit").asInt()).isEqualTo(50);
        assertThat(org.path("owners").toString()).contains("Dana");

        // Moving a workflow into a folder neither grants nor removes any permission for anyone.
        assertThat(api("vic.viewer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, inFolder).body().path("permissions").toString())
                .doesNotContain("workflow.edit");
        assertThat(api("vic.viewer").put(Map.of("folderId", f.path("id").asString(), "expectedFolderVersion", 1), null,
                "/workspaces/{ws}/workflows/{id}/folder", PURCHASING, loose).status()).isEqualTo(403);
        assertThat(listedIds("vic.viewer", "&folder=none")).contains(loose.toString());
    }

    @Test
    void folderManagementNeedsEditAndWorkspaceVisibility() throws Exception {
        assertThat(api("vic.viewer").post(Map.of("name", unique("Nope")), "/workspaces/{ws}/workflow-folders", PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("paul.publisher").post(Map.of("name", unique("Nope")), "/workspaces/{ws}/workflow-folders", PURCHASING).status())
                .isEqualTo(403);
        // A requester who may only start workflows sees neither folders nor folder filters.
        assertThat(api("rhea.requester").get("/workspaces/{ws}/workflow-organization", PURCHASING).status()).isEqualTo(403);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/workflows?folder=none", PURCHASING).status()).isEqualTo(403);
        // People outside the workspace cannot tell it has folders.
        JsonNode f = folder(unique("Private"));
        for (String user : List.of("fred.facilities", "otto.outsider")) {
            assertThat(api(user).get("/workspaces/{ws}/workflow-organization", PURCHASING).status()).isEqualTo(404);
            assertThat(api(user).post(Map.of("name", unique("Nope")), "/workspaces/{ws}/workflow-folders", PURCHASING).status()).isEqualTo(404);
            assertThat(api(user).put(Map.of("name", "x", "expectedVersion", 1), null, "/workspaces/{ws}/workflow-folders/{id}",
                    PURCHASING, f.path("id").asString()).status()).isEqualTo(404);
            assertThat(api(user).delete("/workspaces/{ws}/workflow-folders/{id}", PURCHASING, f.path("id").asString()).status())
                    .isEqualTo(404);
        }
        // A folder is addressed within its own workspace only.
        assertThat(api("fred.facilities").get("/workspaces/{ws}/workflow-organization", FACILITIES).status()).isEqualTo(403);
        assertThat(api("dana.designer").put(Map.of("name", "x", "expectedVersion", 1), null, "/workspaces/{ws}/workflow-folders/{id}",
                FACILITIES, f.path("id").asString()).status()).isEqualTo(404);
        UUID wf = workflow("dana.designer", PURCHASING);
        assertThat(api("dana.designer").put(Map.of("folderId", f.path("id").asString(), "expectedFolderVersion", 1), null,
                "/workspaces/{ws}/workflows/{id}/folder", FACILITIES, wf).status()).isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows?folder=not-a-folder", PURCHASING).status()).isEqualTo(400);
    }

    @Test
    void namesAreUniqueAndRenameAndMoveAreVersioned() throws Exception {
        String name = unique("Ops");
        JsonNode f = folder(name);
        assertThat(api("dana.designer").post(Map.of("name", name.toUpperCase()), "/workspaces/{ws}/workflow-folders", PURCHASING)
                .status()).isEqualTo(409);
        assertThat(api("dana.designer").post(Map.of("name", "   "), "/workspaces/{ws}/workflow-folders", PURCHASING).status())
                .isEqualTo(400);
        String id = f.path("id").asString();
        var renamed = api("dana.designer").put(Map.of("name", name + " renamed", "expectedVersion", 1), null,
                "/workspaces/{ws}/workflow-folders/{id}", PURCHASING, id);
        assertThat(renamed.status()).as(renamed.toString()).isEqualTo(200);
        assertThat(renamed.body().path("version").asInt()).isEqualTo(2);
        // A stale rename and a move that saw the old folder are both refused.
        assertThat(api("dana.designer").put(Map.of("name", name + " other", "expectedVersion", 1), null,
                "/workspaces/{ws}/workflow-folders/{id}", PURCHASING, id).status()).isEqualTo(409);
        UUID wf = workflow("dana.designer", PURCHASING);
        assertThat(move(wf, f).status()).isEqualTo(409);
        assertThat(move(wf, renamed.body()).status()).isEqualTo(200);
        // Renaming into another folder's name conflicts.
        JsonNode other = folder(unique("Other"));
        assertThat(api("dana.designer").put(Map.of("name", name + " RENAMED", "expectedVersion", 1), null,
                "/workspaces/{ws}/workflow-folders/{id}", PURCHASING, other.path("id").asString()).status()).isEqualTo(409);
        // Missing version with a folder is invalid; a version with no folder too.
        assertThat(api("dana.designer").put(Map.of("folderId", id), null, "/workspaces/{ws}/workflows/{id}/folder", PURCHASING, wf)
                .status()).isEqualTo(422);
        var inconsistent = new HashMap<String, Object>();
        inconsistent.put("folderId", null);
        inconsistent.put("expectedFolderVersion", 2);
        assertThat(api("dana.designer").put(inconsistent, null, "/workspaces/{ws}/workflows/{id}/folder", PURCHASING, wf).status())
                .isEqualTo(422);
        // A folder from another workspace does not exist here.
        assertThat(api("dana.designer").put(Map.of("folderId", UUID.randomUUID().toString(), "expectedFolderVersion", 1), null,
                "/workspaces/{ws}/workflows/{id}/folder", PURCHASING, wf).status()).isEqualTo(404);
    }

    @Test
    void deletingAFolderUnfilesButNeverDeletesWorkflows() throws Exception {
        JsonNode f = folder(unique("Temp"));
        UUID wf = workflow("dana.designer", PURCHASING);
        assertThat(move(wf, f).status()).isEqualTo(200);
        assertThat(api("vic.viewer").delete("/workspaces/{ws}/workflow-folders/{id}", PURCHASING, f.path("id").asString()).status())
                .isEqualTo(403);
        assertThat(api("dana.designer").delete("/workspaces/{ws}/workflow-folders/{id}", PURCHASING, f.path("id").asString()).status())
                .isEqualTo(204);
        var after = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, wf);
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body().has("folderId")).isFalse();
        assertThat(listedIds("vic.viewer", "&folder=none")).contains(wf.toString());
        assertThat(api("dana.designer").delete("/workspaces/{ws}/workflow-folders/{id}", PURCHASING, f.path("id").asString()).status())
                .isEqualTo(404);
        // Removing a workflow from its folder is allowed and idempotent.
        assertThat(move(wf, null).status()).isEqualTo(200);
    }

    @Test
    void folderCountIsLimitedPerWorkspace() throws Exception {
        // A private workspace keeps the 50 folders away from the shared seed workspaces used by the other tests.
        String key = unique("lim");
        String group = "orqix-" + key;
        var groups = List.of(group);
        var org = api("pat.platform").post(Map.of("key", key, "name", "Limit org"), "/admin/organizations");
        assertThat(org.status()).as(org.toString()).isEqualTo(201);
        var ws = api("pat.platform").post(Map.of("organizationId", org.body().path("id").asString(), "key", "ws-" + key,
                "name", "Limit", "isTest", false, "allowSelfApprovalWorkflows", false,
                "initialAdmins", List.of(Map.of("type", "idpGroup", "value", group))), "/admin/workspaces");
        assertThat(ws.status()).as(ws.toString()).isEqualTo(201);
        UUID id = UUID.fromString(ws.body().path("id").asString());
        // Administrators do not inherit workflow permissions; grant the same group the designer role.
        var binding = api("lim.admin", groups).post(Map.of("role", "WORKFLOW_DESIGNER",
                "principal", Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/role-bindings", id);
        assertThat(binding.status()).as(binding.toString()).isEqualTo(201);
        for (int i = 0; i < 50; i++) {
            var created = api("lim.admin", groups).post(Map.of("name", "Folder " + i), "/workspaces/{ws}/workflow-folders", id);
            assertThat(created.status()).as("folder " + i + " " + created).isEqualTo(201);
        }
        var over = api("lim.admin", groups).post(Map.of("name", "One too many"), "/workspaces/{ws}/workflow-folders", id);
        assertThat(over.status()).as(over.toString()).isEqualTo(422);
        // The seed workspace is unaffected, and the full workspace still lists all 50.
        assertThat(api("lim.admin", groups).get("/workspaces/{ws}/workflow-organization", id).body().path("folders").size())
                .isEqualTo(50);
    }

    @Test
    void ownerMovesOnlyToSomeoneWhoCanEditAndIsVersioned() throws Exception {
        UUID wf = workflow("dana.designer", PURCHASING);
        var second = List.of("orqix-purchasing-designers");
        String secondUser = "sam.second";
        String secondId = api(secondUser, second).get("/me").body().path("subject").path("id").asString();
        JsonNode current = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, wf).body();
        int version = current.path("version").asInt();

        var candidates = api("dana.designer").get("/workspaces/{ws}/workflow-owner-candidates", PURCHASING);
        assertThat(candidates.status()).as(candidates.toString()).isEqualTo(200);
        assertThat(candidates.body().path("items").toString()).contains(secondId).doesNotContain("Vic Viewer");
        assertThat(api("vic.viewer").get("/workspaces/{ws}/workflow-owner-candidates", PURCHASING).status()).isEqualTo(403);
        String viewerId = api("vic.viewer").get("/me").body().path("subject").path("id").asString();
        var toViewer = api("dana.designer").put(Map.of("ownerSubjectId", viewerId, "expectedVersion", version), null,
                "/workspaces/{ws}/workflows/{id}/owner", PURCHASING, wf);
        assertThat(toViewer.status()).as(toViewer.toString()).isEqualTo(422);
        assertThat(api("dana.designer").put(Map.of("ownerSubjectId", UUID.randomUUID().toString(), "expectedVersion", version), null,
                "/workspaces/{ws}/workflows/{id}/owner", PURCHASING, wf).status()).isEqualTo(422);
        assertThat(api("vic.viewer").put(Map.of("ownerSubjectId", secondId, "expectedVersion", version), null,
                "/workspaces/{ws}/workflows/{id}/owner", PURCHASING, wf).status()).isEqualTo(403);

        var ok = api("dana.designer").put(Map.of("ownerSubjectId", secondId, "expectedVersion", version), null,
                "/workspaces/{ws}/workflows/{id}/owner", PURCHASING, wf);
        assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
        assertThat(ok.body().path("owner").path("id").asString()).isEqualTo(secondId);
        assertThat(ok.body().path("version").asInt()).isEqualTo(version + 1);
        // The old version no longer applies, and the owner filter finds the workflow.
        assertThat(api("dana.designer").put(Map.of("ownerSubjectId", secondId, "expectedVersion", version), null,
                "/workspaces/{ws}/workflows/{id}/owner", PURCHASING, wf).status()).isEqualTo(409);
        assertThat(listedIds("vic.viewer", "&owner=" + secondId)).contains(wf.toString());
        JsonNode org = api("vic.viewer").get("/workspaces/{ws}/workflow-organization", PURCHASING).body();
        assertThat(org.path("owners").toString()).contains(secondId);
        // Ownership changed nothing about permissions: the previous owner keeps only what her role gives her.
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, wf).status()).isEqualTo(200);
    }
}
