package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;


import tools.jackson.databind.JsonNode;

/** UI03 (docs/build/S53): threaded element discussion with read/post boundaries, one-level threads and no leakage. */
class WorkflowDiscussionApiTest extends com.orqix.support.RuntimeTestBase {

    private static final String BASE = "/workspaces/{ws}/workflows/{id}/discussion";

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID workflow() throws Exception {
        var response = api("dana.designer").post(Map.of("key", unique("disc"), "name", "Discussed " + unique("w"),
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }

    private JsonNode definition(UUID wf) throws Exception {
        return api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).body().path("definition");
    }

    private Response post(String user, UUID wf, String element, String body, String parent) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("elementId", element);
        payload.put("body", body);
        if (parent != null) {
            payload.put("parentId", parent);
        }
        return api(user).post(payload, BASE, PURCHASING, wf);
    }

    @Test
    void threadsRepliesAndResolutionOnStepsAndConnections() throws Exception {
        UUID wf = workflow();
        JsonNode def = definition(wf);
        String step = def.path("nodes").get(0).path("id").asString();
        String other = def.path("nodes").get(1).path("id").asString();
        String connection = def.path("transitions").get(0).path("id").asString();

        var root = post("dana.designer", wf, step, "  Why this threshold?  ", null);
        assertThat(root.status()).as(root.toString()).isEqualTo(201);
        assertThat(root.body().path("body").asString()).isEqualTo("Why this threshold?");
        assertThat(root.body().path("resolved").asBoolean()).isFalse();
        String rootId = root.body().path("id").asString();

        // A publisher can reply (a reviewer comments without being able to edit); a viewer cannot.
        var reply = post("paul.publisher", wf, step, "Policy says 5000.", rootId);
        assertThat(reply.status()).as(reply.toString()).isEqualTo(201);
        assertThat(reply.body().path("parentId").asString()).isEqualTo(rootId);
        assertThat(post("vic.viewer", wf, step, "Can I?", rootId).status()).isEqualTo(403);
        assertThat(post("vic.viewer", wf, step, "Can I?", null).status()).isEqualTo(403);

        // Replies stay one level deep, on the same element.
        assertThat(post("dana.designer", wf, step, "nested", reply.body().path("id").asString()).status()).isEqualTo(422);
        assertThat(post("dana.designer", wf, other, "wrong element", rootId).status()).isEqualTo(422);
        assertThat(post("dana.designer", wf, step, "unknown parent", UUID.randomUUID().toString()).status()).isEqualTo(404);

        // Connections can be discussed too; unknown elements and blank text are refused.
        assertThat(post("dana.designer", wf, connection, "Check this route", null).status()).isEqualTo(201);
        assertThat(post("dana.designer", wf, "no-such-step", "ghost", null).status()).isEqualTo(422);
        assertThat(post("dana.designer", wf, step, "   ", null).status()).isEqualTo(400);
        assertThat(post("dana.designer", wf, step, "x".repeat(2001), null).status()).isEqualTo(400);

        // Viewers read everything and are told they cannot post.
        var read = api("vic.viewer").get(BASE, PURCHASING, wf);
        assertThat(read.status()).as(read.toString()).isEqualTo(200);
        assertThat(read.body().path("canPost").asBoolean()).isFalse();
        assertThat(read.body().path("threads")).hasSize(2);
        assertThat(read.body().path("threads").get(0).path("replies")).hasSize(1);
        assertThat(read.body().path("openByElement").path(step).asInt()).isEqualTo(1);
        assertThat(api("dana.designer").get(BASE, PURCHASING, wf).body().path("canPost").asBoolean()).isTrue();
        var filtered = api("vic.viewer").get(BASE + "?elementId=" + connection, PURCHASING, wf);
        assertThat(filtered.body().path("threads")).hasSize(1);

        // Resolve and reopen; replays are no-ops; a reply cannot be resolved.
        assertThat(api("vic.viewer").put(Map.of("resolved", true), null, BASE + "/{c}/resolution", PURCHASING, wf, rootId).status())
                .isEqualTo(403);
        var resolved = api("paul.publisher").put(Map.of("resolved", true), null, BASE + "/{c}/resolution", PURCHASING, wf, rootId);
        assertThat(resolved.status()).as(resolved.toString()).isEqualTo(200);
        assertThat(resolved.body().path("resolved").asBoolean()).isTrue();
        assertThat(resolved.body().path("resolvedByName").asString()).contains("Paul");
        assertThat(api("paul.publisher").put(Map.of("resolved", true), null, BASE + "/{c}/resolution", PURCHASING, wf, rootId).status())
                .isEqualTo(200);
        assertThat(api("dana.designer").get(BASE, PURCHASING, wf).body().path("openByElement").has(step)).isFalse();
        assertThat(api("dana.designer").put(Map.of("resolved", true), null, BASE + "/{c}/resolution", PURCHASING, wf,
                reply.body().path("id").asString()).status()).isEqualTo(422);
        var reopened = api("dana.designer").put(Map.of("resolved", false), null, BASE + "/{c}/resolution", PURCHASING, wf, rootId);
        assertThat(reopened.body().path("resolved").asBoolean()).isFalse();
        assertThat(reopened.body().path("resolvedByName").isNull()).isTrue();

        // Auditing records identifiers, never the text.
        String metadata = jdbc.sql("SELECT string_agg(metadata::text, ' ') FROM audit_event WHERE workspace_id = :ws "
                + "AND action LIKE 'workflow.comment.%'").param("ws", PURCHASING).query(String.class).single();
        assertThat(metadata).contains("commentId").doesNotContain("threshold").doesNotContain("Policy says");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'workflow.comment.resolved'")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void commentsNeverChangeTheDraftAndAreInvisibleOutsideTheWorkspace() throws Exception {
        UUID wf = workflow();
        String step = definition(wf).path("nodes").get(0).path("id").asString();
        int revision = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).body().path("revision").asInt();
        String rootId = post("dana.designer", wf, step, "Private note", null).body().path("id").asString();
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).body().path("revision").asInt())
                .isEqualTo(revision);

        for (String user : new String[] { "fred.facilities", "otto.outsider" }) {
            assertThat(api(user).get(BASE, PURCHASING, wf).status()).as(user).isEqualTo(404);
            assertThat(post(user, wf, step, "Intruder", null).status()).as(user).isEqualTo(404);
            assertThat(api(user).put(Map.of("resolved", true), null, BASE + "/{c}/resolution", PURCHASING, wf, rootId).status())
                    .as(user).isEqualTo(404);
        }
        // A requester who may only start workflows cannot read the review notes (404 while the workflow is not startable by them).
        assertThat(api("rhea.requester").get(BASE, PURCHASING, wf).status()).isIn(403, 404);
        // A comment id is addressed within its own workflow and workspace.
        UUID otherWorkflow = workflow();
        assertThat(api("dana.designer").put(Map.of("resolved", true), null, BASE + "/{c}/resolution", PURCHASING, otherWorkflow,
                rootId).status()).isEqualTo(404);
        assertThat(api("dana.designer").get(BASE, FACILITIES, wf).status()).isIn(403, 404);
        assertThat(api("dana.designer").get(BASE + "?elementId=", PURCHASING, wf).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_element_comment WHERE workflow_id = :wf").param("wf", wf)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
