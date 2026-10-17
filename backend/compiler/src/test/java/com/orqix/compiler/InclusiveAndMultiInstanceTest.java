package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** EX06 slice: inclusive split/join and several-approver approvals (docs/build/S51). */
class InclusiveAndMultiInstanceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"%s\",\"amount\":%s}";

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    private static String note(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"notification\",\"label\":\"" + id
                + "\",\"config\":{\"channel\":\"inApp\",\"recipient\":\"initiator\",\"message\":\"Hello\"}}";
    }

    /** manager -> review (inclusive) -> up to three notices -> joined -> amountPolicy. */
    private static ObjectNode inclusive() {
        ObjectNode d = TestSupport.purchase();
        d.remove("uiLayout");
        ArrayNode nodes = (ArrayNode) d.path("nodes");
        nodes.add(n("{\"id\":\"review\",\"type\":\"parallelSplit\",\"label\":\"Notify who applies\","
                + "\"config\":{\"joinRef\":\"joined\",\"mode\":\"inclusive\"}}"));
        nodes.add(n(note("noteBig")));
        nodes.add(n(note("noteIT")));
        nodes.add(n(note("noteStd")));
        nodes.add(n("{\"id\":\"joined\",\"type\":\"parallelJoin\",\"label\":\"Notices done\",\"config\":{}}"));
        ArrayNode ts = (ArrayNode) d.path("transitions");
        for (JsonNode t : ts) {
            if ("t2".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "review");
            }
        }
        ts.add(n("{\"id\":\"i1\",\"from\":\"review\",\"to\":\"noteBig\",\"on\":\"condition\",\"label\":\"5,000 or more\","
                + "\"condition\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":5000}}"));
        ts.add(n("{\"id\":\"i2\",\"from\":\"review\",\"to\":\"noteIT\",\"on\":\"condition\",\"label\":\"IT\","
                + "\"condition\":{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"IT\"}}"));
        ts.add(n("{\"id\":\"i3\",\"from\":\"review\",\"to\":\"noteStd\",\"on\":\"default\",\"label\":\"Otherwise\"}"));
        for (String id : List.of("noteBig", "noteIT", "noteStd")) {
            ts.add(n("{\"id\":\"j_" + id + "\",\"from\":\"" + id + "\",\"to\":\"joined\",\"on\":\"next\"}"));
        }
        ts.add(n("{\"id\":\"j_out\",\"from\":\"joined\",\"to\":\"amountPolicy\",\"on\":\"next\"}"));
        return d;
    }

    private static List<String> codes(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues()
                .stream().filter(i -> i.severity() == Severity.BLOCKER).map(ValidationIssue::code).toList();
    }

    private static JsonNode node(ObjectNode d, String id) {
        for (JsonNode x : d.path("nodes")) {
            if (id.equals(x.path("id").asString())) {
                return x;
            }
        }
        throw new AssertionError(id);
    }

    private static void removeTransition(ObjectNode d, String id) {
        ArrayNode ts = (ArrayNode) d.path("transitions");
        for (int i = 0; i < ts.size(); i++) {
            if (id.equals(ts.get(i).path("id").asString())) {
                ts.remove(i);
                return;
            }
        }
    }

    @Test
    void anInclusiveSplitCompilesToInclusiveGatewaysWithADefaultFlow() throws Exception {
        ObjectNode d = inclusive();
        assertThat(codes(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace())
                .bpmnXml();
        assertThat(bpmn).contains("<inclusiveGateway id=\"review\"", "default=\"i3\"", "<inclusiveGateway id=\"joined\"",
                "orqixRoute.condition(execution, 'i1')");
        assertThat(bpmn).doesNotContain("parallelGateway");
        // Without the inclusive mode the conditional paths are not allowed on a plain parallel split.
        ((ObjectNode) node(d, "review").path("config")).remove("mode");
        assertThat(codes(d)).isNotEmpty();
    }

    @Test
    void anInclusiveSplitNeedsOneDefaultAndAConditionAndNoPlainPaths() {
        ObjectNode noDefault = inclusive();
        removeTransition(noDefault, "i3");
        removeTransition(noDefault, "j_noteStd");
        assertThat(codes(noDefault)).contains("WF_INCLUSIVE_DEFAULT");

        ObjectNode plain = inclusive();
        for (JsonNode t : plain.path("transitions")) {
            if ("i2".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("on", "next");
                ((ObjectNode) t).remove("condition");
                ((ObjectNode) t).remove("label");
            }
        }
        assertThat(codes(plain)).contains("WF_OUTGOING_INVALID");

        ObjectNode untyped = inclusive();
        for (JsonNode t : untyped.path("transitions")) {
            if ("i1".equals(t.path("id").asString())) {
                ((ObjectNode) t.path("condition")).put("fieldRef", "/input/missing");
            }
        }
        assertThat(codes(untyped)).isNotEmpty();
    }

    @Test
    void theConditionsOfAnInclusiveSplitMayOverlap() {
        // 6,000 in IT satisfies both conditions; that is the point of an inclusive split.
        assertThat(codes(inclusive())).doesNotContain("WF_BRANCH_OVERLAP");
    }

    @Test
    void theSimulatorStartsOnlyThePathsWhoseConditionsApplyOrTheDefault() {
        String text = inclusive().toString();
        JsonNode both = SimulatorFixtures.run(text, INPUT.formatted("IT", 6000), "{}");
        assertThat(both.toString()).contains("starts 2 of 3 paths", "noteBig", "noteIT", "started branches have finished");
        assertThat(both.toString()).doesNotContain("only the default path");
        JsonNode one = SimulatorFixtures.run(text, INPUT.formatted("OPS", 6000), "{}");
        assertThat(one.toString()).contains("starts 1 of 3 paths");
        JsonNode none = SimulatorFixtures.run(text, INPUT.formatted("OPS", 100), "{}");
        assertThat(none.toString()).contains("only the default path");
    }

    // ---------------------------------------------------------------- several approvers

    private static ObjectNode multi(int approvers) {
        ObjectNode d = TestSupport.purchase();
        ((ObjectNode) node(d, "finance").path("config")).set("multiInstance", n("{\"approvers\":" + approvers + "}"));
        return d;
    }

    @Test
    void anApprovalWithSeveralApproversCompilesToAParallelMultiInstanceTask() throws Exception {
        ObjectNode d = multi(3);
        assertThat(codes(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace())
                .bpmnXml();
        assertThat(bpmn).contains("<multiInstanceLoopCharacteristics isSequential=\"false\">",
                "<loopCardinality>3</loopCardinality>",
                "nrOfCompletedInstances == nrOfInstances || orqixRoute.rejected(execution, 'finance')");
    }

    @Test
    void severalApproversCannotBeCombinedWithSingleTaskSettings() {
        ObjectNode timeout = multi(2);
        ((ObjectNode) node(timeout, "finance").path("config")).put("timeoutAfter", "PT24H");
        assertThat(codes(timeout)).contains("WF_MULTI_UNSUPPORTED");

        // Out of bounds counts are rejected by the schema.
        assertThat(codes(multi(1))).isNotEmpty();
        assertThat(codes(multi(6))).isNotEmpty();
    }

    @Test
    void theSimulatorExplainsAndRoutesSeveralApprovers() {
        String text = multi(3).toString();
        JsonNode approved = SimulatorFixtures.run(text, INPUT.formatted("IT", 9000), "{}");
        assertThat(approved.toString()).contains("goes to 3 different people", "all 3 approve");
        JsonNode rejected = SimulatorFixtures.run(text, INPUT.formatted("IT", 9000),
                "{\"approvals\":{\"finance\":{\"outcome\":\"reject\",\"reason\":\"No\"}}}");
        assertThat(rejected.toString()).contains("the first approver rejects it");
    }
}
