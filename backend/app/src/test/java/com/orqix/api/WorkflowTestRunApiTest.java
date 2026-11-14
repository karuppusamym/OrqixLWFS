package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.orqix.support.IntegrationTestBase;

/**
 * CG-Q01 and CG-Q03 (S148): test runs from a test file and from the shared suite, JUnit XML, coverage and the coverage
 * minimum of the publication gate.
 */
class WorkflowTestRunApiTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    private record Created(UUID id, String key) {
    }

    private Created workflow() throws Exception {
        String key = "runs-" + UUID.randomUUID().toString().substring(0, 8);
        var response = api("dana.designer").post(Map.of("key", key, "name", "Test runs", "templateId", "purchase-approval"),
                "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return new Created(UUID.fromString(response.body().path("id").asString()), key);
    }

    private static Map<String, Object> small(String id, String expect) {
        return Map.of("id", id, "name", "Small purchase " + id, "input", Map.of("title", "Laptop", "purpose", "Replacement",
                "costCenter", "IT", "amount", 1800), "expect", expect);
    }

    private static Map<String, Object> file(String key, List<Map<String, Object>> cases) {
        Map<String, Object> suite = new LinkedHashMap<>();
        suite.put("schemaVersion", "orqix.workflow-tests/v1");
        if (key != null) {
            suite.put("workflowKey", key);
        }
        suite.put("cases", cases);
        return suite;
    }

    private long recorded(UUID workflowId) {
        return jdbc.sql("SELECT count(*) FROM workflow_test_result WHERE workflow_id = :wf").param("wf", workflowId)
                .query(Long.class).single();
    }

    @Test
    void aTestFileRunsForReadersWithJUnitXmlAndCoverageAndStoresNothing() throws Exception {
        Created wf = workflow();
        Map<String, Object> broken = Map.of("id", "broken", "name", "Missing answers", "input", Map.of(), "expect", "Fulfilled",
                "studio", Map.of("values", Map.of()));
        var run = api("vic.viewer").post(Map.of("suite", file(wf.key(), List.of(small("ok", "Fulfilled"),
                small("wrong", "Rejected"), broken))), "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id());
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        var body = run.body();
        assertThat(body.path("suite").asString()).isEqualTo("file");
        assertThat(body.path("recorded").asBoolean()).isFalse();
        assertThat(body.path("passed").asBoolean()).isFalse();
        assertThat(body.path("total").asInt()).isEqualTo(3);
        assertThat(body.path("passedCount").asInt()).isEqualTo(1);
        assertThat(body.path("failedCount").asInt()).isEqualTo(1);
        assertThat(body.path("invalidCount").asInt()).isEqualTo(1);
        assertThat(body.path("results").get(1).path("failures").get(0).path("kind").asString()).isEqualTo("outcome");
        assertThat(body.path("results").get(2).path("status").asString()).isEqualTo("invalid");
        // Coverage of the two cases that ran: the reject route is not taken.
        var coverage = body.path("coverage");
        assertThat(coverage.path("cases").asInt()).isEqualTo(2);
        assertThat(coverage.path("steps").path("total").asInt()).isPositive();
        assertThat(coverage.path("routes").path("covered").asInt()).isLessThan(coverage.path("routes").path("total").asInt());
        assertThat(coverage.path("summary").asString()).startsWith("Together the cases cover ");
        String xml = body.path("junitXml").asString();
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xml).contains("<testsuite name=\"" + wf.key() + "\" tests=\"3\" failures=\"1\" errors=\"1\"");
        assertThat(xml).contains("name=\"Small purchase ok [ok]\" time=\"0\"/>");
        assertThat(xml).contains("<property name=\"draftRevision\" value=\"1\"/>");
        assertThat(recorded(wf.id())).isZero();

        // A minimum in the file: the routes are not all covered, so the run fails with a coverage test case.
        Map<String, Object> withMinimum = new LinkedHashMap<>(file(wf.key(), List.of(small("ok", "Fulfilled"))));
        withMinimum.put("coverageMinimum", Map.of("routes", 100));
        var gated = api("vic.viewer").post(Map.of("suite", withMinimum), "/workspaces/{ws}/workflows/{id}/test-runs",
                PURCHASING, wf.id());
        assertThat(gated.status()).as(gated.toString()).isEqualTo(200);
        assertThat(gated.body().path("casesPassed").asBoolean()).isTrue();
        assertThat(gated.body().path("passed").asBoolean()).isFalse();
        assertThat(gated.body().path("coverageShortfalls").get(0).path("kind").asString()).isEqualTo("routes");
        assertThat(gated.body().path("junitXml").asString()).contains("name=\"Coverage minimum\"");
    }

    @Test
    void aTestFileIsCheckedBeforeAnythingRuns() throws Exception {
        Created wf = workflow();
        var other = api("dana.designer").post(Map.of("suite", file("another-workflow", List.of(small("a", "Fulfilled")))),
                "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id());
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.toString()).contains("/suite/workflowKey");
        var wrongVersion = api("dana.designer").post(Map.of("suite", Map.of("schemaVersion", "orqix.workflow-tests/v0",
                "cases", List.of())), "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id());
        assertThat(wrongVersion.status()).isEqualTo(422);
        assertThat(wrongVersion.toString()).contains("/suite/schemaVersion");
        var duplicate = api("dana.designer").post(Map.of("suite", file(null, List.of(small("a", "Fulfilled"),
                small("a", "Fulfilled")))), "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id());
        assertThat(duplicate.status()).isEqualTo(422);
        assertThat(duplicate.toString()).contains("/suite/cases/1/id");
        var foreign = api("fred.facilities").post(Map.of("suite", file(null, List.of(small("a", "Fulfilled")))),
                "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id());
        assertThat(foreign.status()).isEqualTo(404);
    }

    @Test
    void theSharedSuiteRunIsRecordedAndItsCoverageMinimumGatesPublication() throws Exception {
        Created wf = workflow();
        // Readers cannot run the shared suite (designer samples).
        assertThat(api("vic.viewer").post(Map.of(), "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id())
                .status()).isEqualTo(403);
        var empty = api("dana.designer").post(Map.of(), "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id());
        assertThat(empty.status()).isEqualTo(422);

        var saved = api("dana.designer").put(Map.of("expectedVersion", 0, "cases", List.of(small("small", "Fulfilled")),
                "coverageMinimum", Map.of("routes", 100)), null, "/workspaces/{ws}/workflows/{id}/test-suite", PURCHASING, wf.id());
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        assertThat(saved.body().path("coverageMinimum").path("routes").asInt()).isEqualTo(100);
        var badMinimum = api("dana.designer").put(Map.of("expectedVersion", 1, "cases", List.of(small("small", "Fulfilled")),
                "coverageMinimum", Map.of("routes", 101)), null, "/workspaces/{ws}/workflows/{id}/test-suite", PURCHASING, wf.id());
        assertThat(badMinimum.status()).isIn(400, 422);

        var run = api("dana.designer").post(Map.of(), "/workspaces/{ws}/workflows/{id}/test-runs", PURCHASING, wf.id());
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        assertThat(run.body().path("suite").asString()).isEqualTo("shared");
        assertThat(run.body().path("recorded").asBoolean()).isTrue();
        assertThat(run.body().path("casesPassed").asBoolean()).isTrue();
        assertThat(run.body().path("passed").asBoolean()).isFalse();
        assertThat(run.body().path("results").get(0).path("name").asString()).isEqualTo("Small purchase small");
        assertThat(recorded(wf.id())).isEqualTo(1);
        // What is kept: counts only.
        String stored = jdbc.sql("SELECT coverage::text FROM workflow_test_result WHERE workflow_id = :wf")
                .param("wf", wf.id()).query(String.class).single();
        assertThat(stored).contains("\"routes\"").doesNotContain("Laptop", "description", "notCovered");

        var blocked = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, wf.id());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.toString()).contains("do not cover enough");

        var summary = api("vic.viewer").get("/workspaces/{ws}/workflows/{id}/test-suite/summary", PURCHASING, wf.id());
        assertThat(summary.body().path("coverageMinimum").path("routes").asInt()).isEqualTo(100);
        assertThat(summary.body().path("lastRun").path("coverage").path("routes").path("total").asInt()).isPositive();

        // A reachable minimum: the run passes and publication goes through.
        api("dana.designer").put(Map.of("expectedVersion", 1, "cases", List.of(small("small", "Fulfilled")),
                "coverageMinimum", Map.of("steps", 1)), null, "/workspaces/{ws}/workflows/{id}/test-suite", PURCHASING, wf.id());
        var passing = api("dana.designer").post(Map.of("revision", 1), "/workspaces/{ws}/workflows/{id}/test-runs",
                PURCHASING, wf.id());
        assertThat(passing.body().path("passed").asBoolean()).as(passing.toString()).isTrue();
        var published = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, wf.id());
        assertThat(published.status()).as(published.toString()).isEqualTo(201);

        // The shared suite against the active release is a dry run that is not recorded.
        var onRelease = api("dana.designer").post(Map.of("useActiveRelease", true), "/workspaces/{ws}/workflows/{id}/test-runs",
                PURCHASING, wf.id());
        assertThat(onRelease.status()).as(onRelease.toString()).isEqualTo(200);
        assertThat(onRelease.body().path("recorded").asBoolean()).isFalse();
        assertThat(onRelease.body().path("source").path("releaseNumber").asInt()).isEqualTo(1);
        assertThat(recorded(wf.id())).isEqualTo(2);
    }

    @Test
    void runAllSavedCasesReturnsTheirCoverage() throws Exception {
        Created wf = workflow();
        var run = api("vic.viewer").post(Map.of("revision", 1, "cases", List.of(Map.of("id", "c1", "input",
                Map.of("title", "Laptop", "purpose", "Replacement", "costCenter", "IT", "amount", 1800)))),
                "/workspaces/{ws}/workflows/{id}/simulate-cases", PURCHASING, wf.id());
        assertThat(run.status()).as(run.toString()).isEqualTo(200);
        assertThat(run.body().path("coverage").path("cases").asInt()).isEqualTo(1);
        assertThat(run.body().path("coverage").path("formRules").has("covered")).isTrue();
    }
}
