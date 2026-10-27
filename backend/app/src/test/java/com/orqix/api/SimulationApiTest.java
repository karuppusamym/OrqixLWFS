package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** "Test this workflow" (WF05): access, validation, contract, no side effects, audit with counts only. */
class SimulationApiTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    private UUID createFromTemplate() throws Exception {
        String key = "sim-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = api("dana.designer").post(Map.of("key", key, "name", "Purchase " + key,
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    private static Map<String, Object> input(Object amount) {
        return Map.of("title", "Laptop", "purpose", "Replacement", "costCenter", "IT", "amount", amount);
    }

    private Response simulate(String user, UUID id, Map<String, Object> body) throws Exception {
        return api(user).post(body, "/workspaces/{ws}/workflows/{id}/simulate", PURCHASING, id);
    }

    private static List<String> stepIds(JsonNode body) {
        return body.path("trace").valueStream().map(e -> e.path("stepId").asString()).toList();
    }

    @Test
    void aDesignerTestsTheDraftWithTheDefaultsAndTheScenario() throws Exception {
        UUID id = createFromTemplate();
        Response small = simulate("dana.designer", id, Map.of("input", input(1800)));
        assertThat(small.status()).as(small.toString()).isEqualTo(200);
        assertThat(small.body().path("outcome").path("businessOutcome").asString()).isEqualTo("Fulfilled");
        assertThat(small.body().path("source").path("revision").asInt()).isEqualTo(1);
        assertThat(stepIds(small.body())).doesNotContain("finance");

        Response large = simulate("dana.designer", id, Map.of("input", input(5000)));
        assertThat(stepIds(large.body())).contains("finance");

        Response rejected = simulate("dana.designer", id, Map.of("input", input(5000), "scenario", Map.of(
                "approvals", Map.of("finance", Map.of("outcome", "reject", "reason", "Over budget")))));
        assertThat(rejected.body().path("outcome").path("businessOutcome").asString()).isEqualTo("Rejected");

        Response failing = simulate("dana.designer", id, Map.of("input", input(1800), "scenario", Map.of(
                "calls", Map.of("procure", Map.of("failure", "unavailable")))));
        assertThat(failing.status()).isEqualTo(200);
        assertThat(failing.body().path("outcome").path("status").asString()).isEqualTo("needsAttention");
        assertThat(failing.body().path("outcome").path("attention").path("reason").asString())
                .isEqualTo("RETRIES_EXHAUSTED");
        assertThat(failing.body().path("limits").asString()).contains("not the workflow engine");
    }

    @Test
    void anInvalidSampleRequestIsAnExplainedUnprocessableEntity() throws Exception {
        UUID id = createFromTemplate();
        Response response = simulate("dana.designer", id, Map.of("input", input(0.001)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body().path("fieldErrors").findValues("pointer").toString()).contains("/input/amount");
        Response badScenario = simulate("dana.designer", id, Map.of("input", input(10), "scenario", Map.of(
                "approvals", Map.of("manager", Map.of("outcome", "maybe")))));
        assertThat(badScenario.status()).isEqualTo(422);
        assertThat(badScenario.body().path("fieldErrors").findValues("pointer").toString()).contains("/scenario");
        Response notObject = simulate("dana.designer", id, Map.of("input", List.of(1)));
        assertThat(notObject.status()).isEqualTo(422);
    }

    @Test
    void aWorkflowWithBlockersReportsThemInsteadOfRunning() throws Exception {
        UUID id = createFromTemplate();
        // Unknown draft revision: not found. Blocking issues: the draft is broken.
        assertThat(simulate("dana.designer", id, Map.of("revision", 9, "input", input(10))).status()).isEqualTo(404);
        var definition = (tools.jackson.databind.node.ObjectNode) api("dana.designer")
                .get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id).body().path("definition");
        ((tools.jackson.databind.node.ArrayNode) definition.path("transitions")).remove(8);
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response response = simulate("dana.designer", id, Map.of("input", input(10)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("WORKFLOW_INVALID");
        assertThat(response.body().path("issues")).isNotEmpty();
    }

    @Test
    void theActiveReleaseCanBeTestedOnceItExists() throws Exception {
        UUID id = createFromTemplate();
        Response none = simulate("dana.designer", id, Map.of("useActiveRelease", true, "input", input(10)));
        assertThat(none.status()).isEqualTo(422);
        assertThat(none.code()).isEqualTo("NO_ACTIVE_RELEASE");
        Response published = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        Response response = simulate("dana.designer", id, Map.of("useActiveRelease", true, "input", input(10)));
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.body().path("source").path("releaseNumber").asInt()).isEqualTo(1);
        assertThat(response.body().path("source").path("revision").isNull()).isTrue();
    }

    @Test
    void accessFollowsWorkflowRead() throws Exception {
        UUID id = createFromTemplate();
        Map<String, Object> body = Map.of("input", input(10));
        assertThat(simulate("rhea.requester", id, body).status()).isIn(403, 404);
        assertThat(simulate("fred.facilities", id, body).status()).isEqualTo(404);
        assertThat(simulate("paul.publisher", id, body).status()).isEqualTo(200);
    }

    @Test
    void aDryRunChangesNothingAndItsAuditEventHoldsOnlyCounts() throws Exception {
        UUID id = createFromTemplate();
        Map<String, Long> before = counts();
        Response response = simulate("dana.designer", id, Map.of("input", input(1800), "scenario", Map.of(
                "approvals", Map.of("manager", Map.of("outcome", "reject", "reason", "A secret reason")))));
        assertThat(response.status()).isEqualTo(200);
        Map<String, Long> after = counts();
        Map<String, Long> expected = new java.util.LinkedHashMap<>(before);
        expected.put("audit_event", before.get("audit_event") + 1);
        assertThat(after).isEqualTo(expected);
        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'workflow.simulated' "
                + "AND resource_id = :id").param("id", id.toString()).query(String.class).single();
        JsonNode meta = json.parse(metadata);
        assertThat(meta.propertyNames()).containsExactlyInAnyOrder("revision", "steps", "warnings", "status");
        assertThat(metadata).doesNotContain("secret").doesNotContain("Laptop").doesNotContain("1800");
    }

    @Test
    void mockedCallsAreAnsweredWithoutAnyCallOrJournalEntryAndAssertionsAreChecked() throws Exception {
        UUID id = createFromTemplate();
        int posts = com.orqix.support.Stub.postCount();
        Map<String, Long> before = counts();
        Map<String, Object> mock = Map.of("status", 201, "body", Map.of("id", "PO-MOCK-1", "status", "CREATED"),
                "delaySeconds", 3);
        Response response = simulate("dana.designer", id, Map.of("input", input(1800),
                "scenario", Map.of("calls", Map.of("procure", mock)),
                "assertions", Map.of("outcome", "Fulfilled",
                        "outputs", List.of(Map.of("field", "/steps/procure/procurementId", "op", "equals", "value", "PO-MOCK-1")),
                        "path", Map.of("steps", List.of("start", "manager", "amountPolicy", "route", "procure"), "match", "inOrder"),
                        "rules", List.of(Map.of("stepId", "amountPolicy", "rule", "otherwise")),
                        "approvals", List.of(Map.of("stepId", "manager", "decision", "approved")))));
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.body().path("assertions").path("passed").asBoolean()).as(response.body().path("assertions").toString()).isTrue();
        JsonNode procure = response.body().path("trace").valueStream()
                .filter(e -> "procure".equals(e.path("stepId").asString())).findFirst().orElseThrow();
        assertThat(procure.path("details").path("mocked").asBoolean()).isTrue();
        assertThat(procure.path("summary").asString()).contains("after 3 seconds");

        Response failing = simulate("dana.designer", id, Map.of("input", input(1800),
                "scenario", Map.of("calls", Map.of("procure", Map.of("status", 503))),
                "assertions", Map.of("outcome", "Fulfilled")));
        assertThat(failing.body().path("assertions").path("passed").asBoolean()).isFalse();
        assertThat(failing.body().path("assertions").path("results").get(0).path("actual").asString())
                .startsWith("Needs attention");

        Map<String, Long> expected = new java.util.LinkedHashMap<>(before);
        expected.put("audit_event", before.get("audit_event") + 2);
        assertThat(counts()).isEqualTo(expected);
        assertThat(com.orqix.support.Stub.postCount()).as("no real call reached the procurement system").isEqualTo(posts);

        Response badAssertions = simulate("dana.designer", id, Map.of("input", input(10),
                "assertions", Map.of("rules", List.of(Map.of("stepId", "x", "rule", 0)))));
        assertThat(badAssertions.status()).isEqualTo(422);
        assertThat(badAssertions.body().path("fieldErrors").findValues("pointer").toString()).contains("/assertions");
    }

    @Test
    void aReaderRunsSeveralCasesInOneActionAndTheAuditHoldsOnlyCounts() throws Exception {
        UUID id = createFromTemplate();
        Map<String, Long> before = counts();
        Response response = api("vic.viewer").post(Map.of("cases", List.of(
                Map.of("id", "ok", "input", input(1800), "assertions", Map.of("outcome", "Fulfilled")),
                Map.of("id", "rejected", "input", input(1800), "scenario", Map.of("calls", Map.of("procure",
                        Map.of("status", 422))), "assertions", Map.of("outcome", "Fulfilled")),
                Map.of("id", "bad", "input", input(0.001)))), "/workspaces/{ws}/workflows/{id}/simulate-cases",
                PURCHASING, id);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.path("total").asInt()).isEqualTo(3);
        assertThat(body.path("passed").asInt()).isEqualTo(1);
        assertThat(body.path("results").get(0).path("passed").asBoolean()).isTrue();
        assertThat(body.path("results").get(1).path("status").asString()).isEqualTo("needsAttention");
        assertThat(body.path("results").get(1).path("assertions").path("results").get(0).path("expected").asString())
                .isEqualTo("Fulfilled");
        assertThat(body.path("results").get(2).path("status").asString()).isEqualTo("invalid");
        assertThat(body.has("trace")).isFalse();
        Map<String, Long> expected = new java.util.LinkedHashMap<>(before);
        expected.put("audit_event", before.get("audit_event") + 1);
        assertThat(counts()).isEqualTo(expected);
        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'workflow.simulated' "
                + "AND resource_id = :id").param("id", id.toString()).query(String.class).single();
        assertThat(json.parse(metadata).propertyNames()).containsExactlyInAnyOrder("revision", "cases", "passed");
        assertThat(metadata).doesNotContain("Laptop").doesNotContain("1800");
        // Duplicate IDs and foreign workspaces are refused.
        assertThat(api("vic.viewer").post(Map.of("cases", List.of(Map.of("id", "a", "input", input(1)),
                Map.of("id", "a", "input", input(1)))), "/workspaces/{ws}/workflows/{id}/simulate-cases", PURCHASING, id)
                .status()).isEqualTo(422);
        assertThat(api("fred.facilities").post(Map.of("cases", List.of(Map.of("id", "a", "input", input(1)))),
                "/workspaces/{ws}/workflows/{id}/simulate-cases", PURCHASING, id).status()).isEqualTo(404);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String table : List.of("workflow", "workflow_draft_revision", "workflow_release", "workflow_run",
                "audit_event", "run_event", "effect_journal", "external_job", "notification", "message_subscription")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }
}
