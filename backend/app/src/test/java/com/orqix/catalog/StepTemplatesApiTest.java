package com.orqix.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/** CG-S03 (docs/build/S141): workspace step templates through the public API on PostgreSQL. */
class StepTemplatesApiTest extends RuntimeTestBase {

    private static Map<String, Object> approvalStep(String label, int hours) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("candidateGroupRef", "managers");
        config.put("rejectReasonRequired", true);
        config.put("dueAfterHours", hours);
        return Map.of("id", "copied", "type", "approval", "label", label, "config", config,
                "position", Map.of("x", 10, "y", 20));
    }

    private static Map<String, Object> create(String key, Map<String, Object> step, boolean shared) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("name", "Manager review " + key);
        body.put("description", "Managers decide within a working day");
        body.put("shared", shared);
        body.put("step", step);
        return body;
    }

    private String key() {
        return "tpl-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void designersSaveVersionShareAndArchiveTemplatesAndReadersSearchThemByOperation() throws Exception {
        String key = key();
        var created = api("dana.designer").post(create(key, approvalStep("Manager review", 8), false),
                "/workspaces/{ws}/step-templates", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode template = created.body();
        assertThat(template.path("latestVersion").asInt()).isEqualTo(1);
        assertThat(template.path("operation").asString()).isEqualTo("managers");
        // Only type, label and config are kept: no id, position or routes.
        assertThat(template.path("step").has("id")).isFalse();
        assertThat(template.path("step").has("position")).isFalse();

        // Readers search by name or operation; a viewer may read but not create.
        JsonNode found = api("vic.viewer").get("/workspaces/{ws}/step-templates?q=MANAGERS", PURCHASING).body();
        assertThat(found.path("items").findValues("key").stream().map(JsonNode::asString)).contains(key);
        assertThat(api("vic.viewer").get("/workspaces/{ws}/step-templates?q=no-such-operation", PURCHASING).body()
                .path("items").findValues("key").stream().map(JsonNode::asString)).doesNotContain(key);
        assertThat(api("vic.viewer").post(create(key(), approvalStep("X", 1), false), "/workspaces/{ws}/step-templates",
                PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/step-templates", PURCHASING).status()).isEqualTo(404);

        // Keys are unique; versions are immutable and keep the step type.
        assertThat(api("dana.designer").post(create(key, approvalStep("Again", 8), false), "/workspaces/{ws}/step-templates",
                PURCHASING).status()).isEqualTo(409);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 5, "step", approvalStep("Later", 16)),
                "/workspaces/{ws}/step-templates/{key}/versions", PURCHASING, key).status()).isEqualTo(409);
        var retyped = api("dana.designer").post(Map.of("expectedVersion", 1, "step", Map.of("type", "wait", "label", "Wait",
                "config", Map.of("duration", "PT1H"))), "/workspaces/{ws}/step-templates/{key}/versions", PURCHASING, key);
        assertThat(retyped.status()).isEqualTo(422);
        var v2 = api("dana.designer").post(Map.of("expectedVersion", 1, "step", approvalStep("Manager review", 16),
                "note", "Two days"), "/workspaces/{ws}/step-templates/{key}/versions", PURCHASING, key);
        assertThat(v2.status()).as(v2.toString()).isEqualTo(201);
        assertThat(v2.body().path("step").path("config").path("dueAfterHours").asInt()).isEqualTo(16);
        JsonNode detail = api("vic.viewer").get("/workspaces/{ws}/step-templates/{key}", PURCHASING, key).body();
        assertThat(detail.path("versions").findValues("version").stream().map(JsonNode::asInt)).containsExactly(2, 1);
        assertThat(detail.path("versions").get(0).path("sha256").asString())
                .isNotEqualTo(detail.path("versions").get(1).path("sha256").asString());

        // Not shared: other workspaces of the organization do not see it. Shared: they see it read-only.
        assertThat(api("dana.designer").get("/workspaces/{ws}/step-templates", SANDBOX).body().path("items")
                .findValues("key").stream().map(JsonNode::asString)).doesNotContain(key);
        var shared = api("dana.designer").put(Map.of("expectedVersion", 2, "name", "Manager review (shared)",
                "shared", true, "status", "active"), null, "/workspaces/{ws}/step-templates/{key}", PURCHASING, key);
        assertThat(shared.status()).as(shared.toString()).isEqualTo(200);
        JsonNode elsewhere = null;
        for (JsonNode item : api("dana.designer").get("/workspaces/{ws}/step-templates", SANDBOX).body().path("items")) {
            if (key.equals(item.path("key").asString())) {
                elsewhere = item;
            }
        }
        assertThat(elsewhere).isNotNull();
        assertThat(elsewhere.path("own").asBoolean()).isFalse();
        assertThat(elsewhere.path("latestVersion").asInt()).isEqualTo(2);
        // A shared template is still only changed in its own workspace.
        assertThat(api("dana.designer").get("/workspaces/{ws}/step-templates/{key}", SANDBOX, key).status()).isEqualTo(404);

        // Archived templates leave the palette but stay listable on request.
        assertThat(api("dana.designer").put(Map.of("expectedVersion", 2, "name", "Manager review", "shared", true,
                "status", "archived"), null, "/workspaces/{ws}/step-templates/{key}", PURCHASING, key).status()).isEqualTo(200);
        assertThat(api("dana.designer").get("/workspaces/{ws}/step-templates", PURCHASING).body().path("items")
                .findValues("key").stream().map(JsonNode::asString)).doesNotContain(key);
        assertThat(api("dana.designer").get("/workspaces/{ws}/step-templates?includeArchived=true", PURCHASING).body()
                .path("items").findValues("key").stream().map(JsonNode::asString)).contains(key);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_type = 'stepTemplate' AND resource_id = :k")
                .param("k", key).query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void stepsAreCheckedAgainstTheWorkflowSchema() throws Exception {
        var start = api("dana.designer").post(create(key(), Map.of("type", "start", "label", "Start", "config", Map.of()), false),
                "/workspaces/{ws}/step-templates", PURCHASING);
        assertThat(start.status()).isEqualTo(422);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("candidateGroupRef", "managers");
        config.put("rejectReasonRequired", true);
        config.put("notAConfigMember", 1);
        var invalid = api("dana.designer").post(create(key(), Map.of("type", "approval", "label", "Bad", "config", config), false),
                "/workspaces/{ws}/step-templates", PURCHASING);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().path("fieldErrors").findValues("pointer").stream().map(JsonNode::asString))
                .allMatch(pointer -> pointer.startsWith("/step"));
        // References (here an unknown group) are checked where the template is used, not when it is saved.
        Map<String, Object> other = new LinkedHashMap<>(config);
        other.remove("notAConfigMember");
        other.put("candidateGroupRef", "group-of-another-workspace");
        assertThat(api("dana.designer").post(create(key(), Map.of("type", "approval", "label", "Elsewhere", "config", other), false),
                "/workspaces/{ws}/step-templates", PURCHASING).status()).isEqualTo(201);
    }
}
