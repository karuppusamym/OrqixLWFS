package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** EX01 "Work by an external worker" step: schema, availability rules, typing, BPMN and simulation (docs/build/S43). */
class ExternalTaskRulesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** start -> check -> second -> manager ... ; {@code secondInput} is the mapping of the second worker step. */
    private static ObjectNode definition(String secondInput) throws Exception {
        ObjectNode d = TestSupport.purchase();
        ArrayNode nodes = (ArrayNode) d.path("nodes");
        nodes.add(MAPPER.readTree("{\"id\":\"check\",\"type\":\"externalTask\",\"label\":\"Check the address\",\"config\":{"
                + "\"topic\":\"address-check\",\"inputMapping\":{\"purpose\":\"/input/purpose\"},"
                + "\"result\":[{\"name\":\"score\",\"type\":\"number\",\"required\":true},{\"name\":\"note\",\"type\":\"string\"}]}}"));
        nodes.add(MAPPER.readTree("{\"id\":\"second\",\"type\":\"externalTask\",\"label\":\"Score the result\",\"config\":{"
                + "\"topic\":\"scoring\",\"inputMapping\":" + secondInput + ",\"result\":[{\"name\":\"ok\",\"type\":\"boolean\",\"required\":true}]}}"));
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        for (JsonNode t : transitions) {
            if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                ((ObjectNode) t).put("to", "check");
            }
        }
        transitions.add(MAPPER.readTree("{\"id\":\"x1\",\"from\":\"check\",\"to\":\"second\",\"on\":\"next\"}"));
        transitions.add(MAPPER.readTree("{\"id\":\"x2\",\"from\":\"second\",\"to\":\"manager\",\"on\":\"next\"}"));
        return d;
    }

    private static java.util.List<String> codes(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).map(ValidationIssue::code).toList();
    }

    @Test
    void aWorkflowWithWorkerStepsCompilesToAnAsyncTriggerableServiceTask() throws Exception {
        ObjectNode d = definition("{\"score\":\"/steps/check/score\"}");
        assertThat(codes(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace()).bpmnXml();
        assertThat(bpmn).contains("delegateExpression=\"${orqixExternalTask}\"", "flowable:triggerable=\"true\"",
                "topic=\"address-check\"", "leaseSeconds=\"120\"", "<orqix:input name=\"purpose\"", "<orqix:field name=\"score\"");
    }

    @Test
    void outputsOfAnEarlierWorkerStepAreTypedAndOptionalOnesCannotBeSent() throws Exception {
        assertThat(codes(definition("{\"score\":\"/steps/check/score\"}"))).isEmpty();
        assertThat(codes(definition("{\"note\":\"/steps/check/note\"}"))).contains("WF_EXTERNAL_INPUT_OPTIONAL");
        assertThat(codes(definition("{\"x\":\"/steps/check/missing\"}"))).contains("WF_EXTERNAL_INPUT_UNKNOWN");
        // A step cannot be fed by a step that comes after it.
        assertThat(codes(definition("{\"x\":\"/steps/manager/decision\"}"))).isNotEmpty();
    }

    @Test
    void resultFieldsMustBeUniqueSingleValues() throws Exception {
        ObjectNode d = definition("{\"score\":\"/steps/check/score\"}");
        for (JsonNode n : d.path("nodes")) {
            if ("check".equals(n.path("id").asString())) {
                ((ArrayNode) n.path("config").path("result")).add(MAPPER.readTree("{\"name\":\"score\",\"type\":\"number\"}"));
            }
        }
        assertThat(codes(d)).contains("WF_EXTERNAL_RESULT");
    }

    @Test
    void theSimulatorTakesTheResultFromTheTestSettingsOrUsesSamples() throws Exception {
        String text = definition("{\"score\":\"/steps/check/score\"}").toString();
        var withSample = SimulatorFixtures.run(text,
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}", "{}");
        assertThat(withSample.toString()).contains("uses sample values", "address-check");
        var withSettings = SimulatorFixtures.run(text,
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}",
                "{\"workers\":{\"check\":{\"result\":{\"score\":42}}}}");
        assertThat(withSettings.toString()).contains("comes from the test settings");
        var ignored = SimulatorFixtures.run(text,
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}",
                "{\"workers\":{\"manager\":{\"result\":{}}}}");
        assertThat(ignored.toString()).contains("SCENARIO_UNKNOWN_STEP");
    }
}
