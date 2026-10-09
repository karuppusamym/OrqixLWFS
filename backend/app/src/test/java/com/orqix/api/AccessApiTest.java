package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** B02.4: access overview, role bindings, grants, groups, platform administration. */
class AccessApiTest extends IntegrationTestBase {

    @Test
    void overviewForWorkspaceAdminsAndPlatformAdminsOnly() throws Exception {
        Response overview = api("wade.admin").get("/workspaces/{ws}/access", PURCHASING);
        assertThat(overview.status()).isEqualTo(200);
        assertThat(overview.body().path("roleBindings").findValues("role").toString()).contains("APPROVER");
        assertThat(overview.body().path("groups").findValues("key").toString()).contains("managers");
        assertThat(api("pat.platform").get("/workspaces/{ws}/access", PURCHASING).status()).isEqualTo(200);
        assertThat(api("dana.designer").get("/workspaces/{ws}/access", PURCHASING).status()).isEqualTo(403);
        assertThat(api("fred.facilities").get("/workspaces/{ws}/access", PURCHASING).status()).isEqualTo(404);
    }

    @Test
    void roleBindingLifecycleChangesPermissionsImmediatelyOnThisInstance() throws Exception {
        String group = "test-group-" + UUID.randomUUID().toString().substring(0, 6);
        Response created = api("wade.admin").post(Map.of("role", "VIEWER",
                "principal", Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/role-bindings", SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        Response duplicate = api("wade.admin").post(Map.of("role", "VIEWER",
                "principal", Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/role-bindings", SANDBOX);
        assertThat(duplicate.status()).isEqualTo(409);
        Response asMember = api(null).send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/workspaces/{ws}", SANDBOX).with(com.orqix.support.TestUsers.as("otto.outsider", List.of(group))));
        assertThat(asMember.status()).isEqualTo(200);
        String id = created.body().path("id").asString();
        assertThat(api("wade.admin").delete("/workspaces/{ws}/access/role-bindings/{id}", SANDBOX, id).status())
                .isEqualTo(204);
        Response after = api(null).send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/workspaces/{ws}", SANDBOX).with(com.orqix.support.TestUsers.as("otto.outsider", List.of(group))));
        assertThat(after.status()).isEqualTo(404);
    }

    @Test
    void grantsAndGroups() throws Exception {
        Response grant = api("wade.admin").post(Map.of("permission", "workflow.start",
                "principal", Map.of("type", "idpGroup", "value", "orqix-some-team")), "/workspaces/{ws}/access/grants", SANDBOX);
        assertThat(grant.status()).as(grant.toString()).isEqualTo(201);
        assertThat(api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", SANDBOX,
                grant.body().path("id").asString()).status()).isEqualTo(204);

        String key = "team-" + UUID.randomUUID().toString().substring(0, 6);
        Response group = api("wade.admin").put(Map.of("name", "Team", "members",
                List.of(Map.of("type", "idpGroup", "value", "orqix-team"))), null, "/workspaces/{ws}/groups/{key}", SANDBOX, key);
        assertThat(group.status()).as(group.toString()).isEqualTo(200);
        assertThat(group.body().path("revision").asInt()).isEqualTo(1);
        Response conflict = api("wade.admin").put(Map.of("name", "Team", "members", List.of(), "expectedRevision", 7),
                null, "/workspaces/{ws}/groups/{key}", SANDBOX, key);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("GROUP_REVISION_CONFLICT");
        Response updated = api("wade.admin").put(Map.of("name", "Team 2", "members", List.of(), "expectedRevision", 1),
                null, "/workspaces/{ws}/groups/{key}", SANDBOX, key);
        assertThat(updated.body().path("revision").asInt()).isEqualTo(2);
    }

    @Test
    void platformAdministration() throws Exception {
        assertThat(api("wade.admin").get("/admin/organizations").status()).isEqualTo(403);
        String key = "org-" + UUID.randomUUID().toString().substring(0, 6);
        Response org = api("pat.platform").post(Map.of("key", key, "name", "Org"), "/admin/organizations");
        assertThat(org.status()).as(org.toString()).isEqualTo(201);
        Response ws = api("pat.platform").post(Map.of("organizationId", org.body().path("id").asString(),
                "key", "ws-" + key, "name", "Workspace", "isTest", false, "allowSelfApprovalWorkflows", false,
                "initialAdmins", List.of(Map.of("type", "idpGroup", "value", "orqix-new-admins"))), "/admin/workspaces");
        assertThat(ws.status()).as(ws.toString()).isEqualTo(201);
        Response invalid = api("pat.platform").post(Map.of("organizationId", org.body().path("id").asString(),
                "key", "ws2-" + key, "name", "Workspace", "isTest", false, "allowSelfApprovalWorkflows", true,
                "initialAdmins", List.of(Map.of("type", "idpGroup", "value", "orqix-new-admins"))), "/admin/workspaces");
        assertThat(invalid.status()).isEqualTo(422);
        JsonNode list = api("pat.platform").get("/admin/workspaces?limit=100").body();
        assertThat(list.path("items").findValues("key").toString()).contains("ws-" + key);
    }
}
