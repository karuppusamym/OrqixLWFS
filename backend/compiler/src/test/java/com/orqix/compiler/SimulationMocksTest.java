package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * STU03 mock answers of integration points (docs/build/S96): calls (status, body checked against the response schema,
 * delay, timeout), external workers, adapters and messages. The purchase-mock-* and order-payment-mock-* golden traces
 * cover the wording; this class covers the classification and the refusals.
 */
class SimulationMocksTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String LAPTOP = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":1800}";

    private static JsonNode run(String definition, String scenario) {
        return SimulatorFixtures.run(definition, LAPTOP, scenario);
    }

    private static List<String> ids(JsonNode r) {
        return r.get("trace").valueStream().map(e -> e.get("stepId").stringValue()).toList();
    }

    private static JsonNode last(JsonNode r) {
        return r.get("trace").get(r.get("trace").size() - 1);
    }

    @Test
    void aValidBodyIsMappedLikeARealAnswer() {
        JsonNode r = run(TestSupport.purchaseJson(), "{\"calls\":{\"procure\":{\"status\":200,\"body\":{\"id\":\"PO-77\",\"status\":\"CREATED\"}}}}");
        assertThat(r.at("/outcome/businessOutcome").asString()).isEqualTo("Fulfilled");
        JsonNode procure = r.get("trace").valueStream().filter(e -> "procure".equals(e.get("stepId").asString())).findFirst().orElseThrow();
        assertThat(procure.at("/wrote/procurementId").asString()).isEqualTo("PO-77");
        assertThat(procure.at("/details/mocked").asBoolean()).isTrue();
        assertThat(procure.at("/details/httpStatus").asInt()).isEqualTo(200);
        assertThat(procure.at("/details/sampleAnswer").asBoolean()).isFalse();
    }

    @Test
    void statusesAreClassifiedLikeTheRuntime() {
        Map<Integer, String> expected = Map.of(400, "EFFECT_REJECTED", 404, "EFFECT_REJECTED", 429, "RETRIES_EXHAUSTED",
                408, "RETRIES_EXHAUSTED", 500, "RETRIES_EXHAUSTED", 302, "EFFECT_UNKNOWN", 204, "EFFECT_UNKNOWN");
        expected.forEach((status, reason) -> {
            JsonNode r = run(TestSupport.purchaseJson(), "{\"calls\":{\"procure\":{\"status\":" + status + "}}}");
            assertThat(r.at("/outcome/status").asString()).as("HTTP " + status).isEqualTo("needsAttention");
            assertThat(r.at("/outcome/attention/reason").asString()).as("HTTP " + status).isEqualTo(reason);
        });
    }

    @Test
    void aNonIdempotentCallThatTimesOutHasAnUnknownOutcome() {
        String nonIdempotent = TestSupport.purchaseJson();
        // The fixture connection is idempotent with a key; an unknown connection is treated as non-idempotent.
        String unknown = nonIdempotent.replace("\"procurement\"", "\"elsewhere\"");
        JsonNode r = run(unknown, "{\"calls\":{\"procure\":{\"failure\":\"timeout\"}}}");
        assertThat(r.at("/outcome/attention/reason").asString()).isEqualTo("EFFECT_UNKNOWN");
        assertThat(last(r).get("summary").asString()).contains("takes longer than the time limit of 20 seconds",
                "Nobody knows whether it was done.");
    }

    @Test
    void aBodyThatDoesNotFitTheResponseSchemaListsItsProblems() {
        JsonNode r = run(TestSupport.purchaseJson(), "{\"calls\":{\"procure\":{\"body\":{\"id\":7}}}}");
        assertThat(last(r).at("/details/schemaProblems").toString()).isEqualTo(
                "[{\"at\":\"/id\",\"keyword\":\"type\"},{\"at\":\"/status\",\"keyword\":\"required\"}]");
    }

    @Test
    void malformedCallMocksAreRefused() {
        for (String bad : new String[] {"{\"status\":99}", "{\"status\":600}", "{\"status\":\"200\"}", "{\"body\":\"text\"}",
            "{\"delaySeconds\":-1}", "{\"delaySeconds\":2592001}", "{\"failure\":\"later\"}"}) {
            assertThatThrownBy(() -> run(TestSupport.purchaseJson(), "{\"calls\":{\"procure\":" + bad + "}}")).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ workers

    private static String withWorker() throws Exception {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("{\"id\":\"check\",\"type\":\"externalTask\",\"label\":\"Check the address\","
                + "\"config\":{\"topic\":\"address-check\",\"inputMapping\":{\"purpose\":\"/input/purpose\"},"
                + "\"result\":[{\"name\":\"score\",\"type\":\"number\",\"required\":true},{\"name\":\"note\",\"type\":\"string\"}]}}"));
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        for (JsonNode t : transitions) {
            if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                ((ObjectNode) t).put("to", "check");
            }
        }
        transitions.add(MAPPER.readTree("{\"id\":\"x1\",\"from\":\"check\",\"to\":\"manager\",\"on\":\"next\"}"));
        return d.toString();
    }

    @Test
    void aWorkerResultIsKeptForLaterStepsAndChecked() throws Exception {
        JsonNode ok = run(withWorker(), "{\"workers\":{\"check\":{\"result\":{\"score\":42,\"note\":\"fine\"},\"delaySeconds\":30}}}");
        JsonNode check = ok.get("trace").get(1);
        assertThat(check.at("/wrote/score").asInt()).isEqualTo(42);
        assertThat(check.get("summary").asString()).contains("comes from the test settings", "after 30 seconds");
        JsonNode wrongType = run(withWorker(), "{\"workers\":{\"check\":{\"result\":{\"score\":\"high\"}}}}");
        assertThat(wrongType.at("/outcome/attention/reason").asString()).isEqualTo("TEST_MOCK_INVALID");
        assertThat(last(wrongType).get("summary").asString()).contains("\"score\" must be a number");
        JsonNode unknownField = run(withWorker(), "{\"workers\":{\"check\":{\"result\":{\"rating\":1}}}}");
        assertThat(last(unknownField).get("summary").asString()).contains("\"rating\" is not a declared field");
        JsonNode failed = run(withWorker(), "{\"workers\":{\"check\":{\"failure\":\"failed\"}}}");
        assertThat(failed.at("/outcome/attention/reason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(ids(failed)).containsExactly("start", "check");
    }

    // ------------------------------------------------------------------ adapters

    private static final Map<String, CompileContext.AdapterInfo> ADAPTERS = Map.of(
            "lookup", new CompileContext.AdapterInfo("lookup", "jdbc", "Customer lookup", true,
                    "{\"statement\":\"SELECT id FROM c WHERE cc = :costCenter\",\"parameters\":[\"costCenter\"],\"maxRows\":100,\"timeoutSeconds\":10}"));

    private static JsonNode runWithAdapter(String scenario) throws Exception {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("{\"id\":\"send\",\"type\":\"sendAdapter\",\"label\":\"Look up\","
                + "\"config\":{\"adapter\":\"lookup\",\"kind\":\"jdbc\",\"inputMapping\":{\"costCenter\":\"/input/costCenter\"}}}"));
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        for (JsonNode t : transitions) {
            if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                ((ObjectNode) t).put("to", "send");
            }
        }
        transitions.add(MAPPER.readTree("{\"id\":\"x1\",\"from\":\"send\",\"to\":\"manager\",\"on\":\"next\"}"));
        SimulationContext base = SimulatorFixtures.context();
        SimulationContext ctx = new SimulationContext(base.workspace().withAdapters(ADAPTERS), base.childDefinitions(), base.today());
        SimulationResult result = Compilers.workflowSimulator().simulate(d.toString(), LAPTOP, scenario, ctx);
        assertThat(result.hasTrace()).as(result.blockers().toString()).isTrue();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.json());
    }

    @Test
    void anAdapterCanReturnRowsOrFail() throws Exception {
        assertThat(runWithAdapter("{\"adapters\":{\"send\":{\"rowCount\":3}}}").at("/trace/1/wrote/rowCount").asInt()).isEqualTo(3);
        JsonNode down = runWithAdapter("{\"adapters\":{\"send\":{\"failure\":\"unavailable\"}}}");
        assertThat(down.at("/outcome/attention/reason").asString()).isEqualTo("RETRIES_EXHAUSTED");
        JsonNode lost = runWithAdapter("{\"adapters\":{\"send\":{\"failure\":\"unknownOutcome\"}}}");
        assertThat(lost.at("/outcome/attention/reason").asString()).isEqualTo("EFFECT_UNKNOWN");
        assertThatThrownBy(() -> runWithAdapter("{\"adapters\":{\"send\":{\"rowCount\":-1}}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runWithAdapter("{\"adapters\":{\"manager\":{}}}").toString()).contains("SCENARIO_UNKNOWN_STEP");
    }
}
