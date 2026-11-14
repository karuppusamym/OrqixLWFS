package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-E10 (docs/build/S132): "Work by an external worker" may have a failed route taken on a worker business error,
 * or raise an error for its section's error route; failure facts exist only on the failed route.
 */
class WorkerBusinessErrorsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}";
    private static final String CHECK = "{\"id\":\"check\",\"type\":\"externalTask\",\"label\":\"Check the address\","
            + "\"config\":{\"topic\":\"address-check\",\"inputMapping\":{\"purpose\":\"/input/purpose\"},"
            + "\"result\":[{\"name\":\"score\",\"type\":\"number\",\"required\":true}]}}";

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    private static void path(ObjectNode d, String json) {
        ((ArrayNode) d.path("transitions")).add(n(json));
    }

    private static ObjectNode config(ObjectNode d, String id) {
        for (JsonNode x : d.path("nodes")) {
            if (id.equals(x.path("id").asString())) {
                return (ObjectNode) x.path("config");
            }
        }
        throw new AssertionError(id);
    }

    /** start -> check (worker) -> manager ...; the worker step also has a failed route to the rejection notice. */
    static ObjectNode failedRoute() {
        ObjectNode d = withWorker();
        path(d, "{\"id\":\"c2\",\"from\":\"check\",\"to\":\"notifyRejected\",\"on\":\"failed\"}");
        return d;
    }

    static ObjectNode withWorker() {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("nodes")).add(n(CHECK));
        if (d.has("uiLayout")) {
            ((ObjectNode) d.path("uiLayout").path("positions")).set("check", n("{\"x\":50,\"y\":50}"));
        }
        for (JsonNode t : d.path("transitions")) {
            if ("t1".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "check");
            }
        }
        path(d, "{\"id\":\"c1\",\"from\":\"check\",\"to\":\"manager\",\"on\":\"next\"}");
        return d;
    }

    /** The worker step in a section whose error route catches ADDRESS_UNKNOWN, set to raise it on a business error. */
    static ObjectNode sectionRoute() {
        ObjectNode d = withWorker();
        d.putArray("subprocesses").add(n("{\"id\":\"verify\",\"label\":\"Verify\",\"type\":\"embedded\",\"nodes\":[\"check\"],"
                + "\"onError\":{\"to\":\"notifyRejected\",\"errorCode\":\"ADDRESS_UNKNOWN\"}}"));
        config(d, "check").put("raiseErrorOnFailure", "ADDRESS_UNKNOWN");
        return d;
    }

    private static List<String> issues(ObjectNode d, Severity severity) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues()
                .stream().filter(i -> i.severity() == severity).map(ValidationIssue::code).toList();
    }

    private static List<String> blockers(ObjectNode d) {
        return issues(d, Severity.BLOCKER);
    }

    private static String bpmn(ObjectNode d) throws Exception {
        String xml = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace()).bpmnXml();
        GeneratedXmlTest.onlyAllowedExpressions(xml);
        return xml;
    }

    @Test
    void aWorkerStepCanHaveAFailedRouteThatCompilesToAnErrorBoundary() throws Exception {
        ObjectNode d = failedRoute();
        assertThat(blockers(d)).isEmpty();
        String xml = bpmn(d);
        assertThat(xml).contains("<error id=\"error__stepFailed\" errorCode=\"ORQIX_STEP_FAILED\"/>",
                "<boundaryEvent id=\"check__failed\" name=\"Check the address: failed\" attachedToRef=\"check\" cancelActivity=\"true\">",
                "<errorEventDefinition errorRef=\"error__stepFailed\"/>")
                .doesNotContain("raiseOnFailure");
        // Without a failed route nothing changes: no boundary and no error definition.
        assertThat(bpmn(withWorker())).doesNotContain("check__failed", "error__stepFailed", "raiseOnFailure");
    }

    @Test
    void failureFactsExistOnlyOnTheFailedRouteAndReserveTheirNames() {
        // A branch on the failed route may read the worker's error code.
        ObjectNode d = failedRoute();
        ((ArrayNode) d.path("nodes")).add(n("{\"id\":\"why\",\"type\":\"branch\",\"label\":\"Which error?\",\"config\":{}}"));
        for (JsonNode t : d.path("transitions")) {
            if ("c2".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "why");
            }
        }
        path(d, "{\"id\":\"w1\",\"from\":\"why\",\"to\":\"notifyRejected\",\"on\":\"condition\",\"label\":\"Unknown address\","
                + "\"condition\":{\"fieldRef\":\"/steps/check/failureCode\",\"operator\":\"eq\",\"value\":\"ADDRESS_UNKNOWN\"}}");
        path(d, "{\"id\":\"w2\",\"from\":\"why\",\"to\":\"notifyRejected\",\"on\":\"default\"}");
        assertThat(blockers(d)).doesNotContain("WF_FIELDREF_NOT_GUARANTEED", "WF_FIELDREF_UNKNOWN");

        // The same fact read after the step worked is not guaranteed.
        ObjectNode wrong = failedRoute();
        for (JsonNode t : wrong.path("transitions")) {
            if ("t6".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("on", "condition").put("label", "Code").set("condition",
                        n("{\"fieldRef\":\"/steps/check/failureCode\",\"operator\":\"eq\",\"value\":\"X\"}"));
            }
            if ("t5".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("on", "default").remove("condition");
                ((ObjectNode) t).remove("label");
            }
        }
        assertThat(blockers(wrong)).contains("WF_FIELDREF_NOT_GUARANTEED");

        // A declared result named like a failure fact clashes.
        ObjectNode clash = failedRoute();
        ((ArrayNode) config(clash, "check").path("result")).add(n("{\"name\":\"failureCode\",\"type\":\"string\"}"));
        assertThat(blockers(clash)).contains("WF_FAILURE_ROUTE_OUTPUT_CLASH");
    }

    @Test
    void aWorkerStepCanRaiseAnErrorForItsSectionsErrorRoute() throws Exception {
        ObjectNode d = sectionRoute();
        assertThat(blockers(d)).isEmpty();
        assertThat(issues(d, Severity.WARNING)).doesNotContain("WF_SUBPROCESS_ERROR_UNUSED");
        String xml = bpmn(d);
        assertThat(xml).contains("raiseOnFailure=\"ADDRESS_UNKNOWN\"",
                "<error id=\"error__ADDRESS_UNKNOWN\" errorCode=\"ADDRESS_UNKNOWN\"/>",
                "<boundaryEvent id=\"verify__error\"", "<errorEventDefinition errorRef=\"error__ADDRESS_UNKNOWN\"/>")
                .doesNotContain("check__failed");
    }

    @Test
    void theSectionOptInFollowsTheCallRules() {
        ObjectNode conflict = sectionRoute();
        path(conflict, "{\"id\":\"c2\",\"from\":\"check\",\"to\":\"notifyRejected\",\"on\":\"failed\"}");
        assertThat(blockers(conflict)).contains("WF_CALL_FAILURE_ERROR_CONFLICT");

        ObjectNode outside = withWorker();
        config(outside, "check").put("raiseErrorOnFailure", "ADDRESS_UNKNOWN");
        assertThat(blockers(outside)).contains("WF_CALL_FAILURE_ERROR_NOT_CAUGHT");

        ObjectNode other = sectionRoute();
        config(other, "check").put("raiseErrorOnFailure", "SOMETHING_ELSE");
        assertThat(blockers(other)).contains("WF_CALL_FAILURE_ERROR_NOT_CAUGHT");

        ObjectNode reserved = sectionRoute();
        config(reserved, "check").put("raiseErrorOnFailure", "ORQIX_STEP_FAILED");
        assertThat(blockers(reserved)).contains("WF_ERROR_CODE_RESERVED");
    }

    @Test
    void theSimulatorFollowsWorkerBusinessErrors() {
        JsonNode routed = SimulatorFixtures.run(failedRoute().toString(), INPUT,
                "{\"workers\":{\"check\":{\"failure\":\"businessError\",\"errorCode\":\"ADDRESS_UNKNOWN\"}}}");
        assertThat(routed.toString()).contains("reports the business error \\\"ADDRESS_UNKNOWN\\\"",
                "takes its authored failure path", "\"endStepId\":\"rejected\"");

        JsonNode raised = SimulatorFixtures.run(sectionRoute().toString(), INPUT,
                "{\"workers\":{\"check\":{\"failure\":\"businessError\",\"errorCode\":\"ADDRESS_UNKNOWN\"}}}");
        assertThat(raised.toString()).contains("raises the error \\\"ADDRESS_UNKNOWN\\\"", "the section \\\"Verify\\\" stops",
                "\"endStepId\":\"rejected\"");

        JsonNode unrouted = SimulatorFixtures.run(withWorker().toString(), INPUT,
                "{\"workers\":{\"check\":{\"failure\":\"businessError\"}}}");
        assertThat(unrouted.path("outcome").path("status").asString()).isEqualTo("needsAttention");

        JsonNode normal = SimulatorFixtures.run(failedRoute().toString(), INPUT, "{}");
        assertThat(normal.toString()).contains("\"endStepId\":\"fulfilled\"");
    }
}
