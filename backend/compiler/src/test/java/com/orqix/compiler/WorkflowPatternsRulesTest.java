package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validation rules of the workflow patterns (S12): parallel steps, waits and approval time limits, and starting
 * another workflow. Each case patches {@code examples/new-hire-onboarding.json}; expected issues are "B|W CODE PATH".
 * <p>
 * Node indexes of the example: 0 start, 1 setup (split), 2 it, 3 itEscalation, 4 itReady, 5 facilities, 6 setupDone
 * (join), 7 leadTime (wait), 8 orderEquipment (child), 9 equipmentOutcome, 10 notifyReady, 11 ready,
 * 12 notifyEquipmentRejected, 13 equipmentRejected, 14 notifyRejected, 15 rejected. Transition t1..t19 are indexes 0..18.
 */
class WorkflowPatternsRulesTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    // ---------------------------------------------------------------- the example itself

    @Test
    void theExampleIsValidWithoutWarnings() {
        ValidationReport report = COMPILER.validate(TestSupport.onboardingJson(), TestSupport.onboardingContext());
        assertThat(found(report)).isEmpty();
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void theExampleNeedsItsWorkspaceFacts() {
        // Without the groups and the published child the example reports exactly what is missing.
        ValidationReport report = COMPILER.validate(TestSupport.onboardingJson(), TestSupport.defaultContext());
        assertThat(found(report)).contains("B WF_GROUP_UNBOUND /nodes/2/config/candidateGroupRef",
                "B WF_GROUP_UNBOUND /nodes/5/config/candidateGroupRef",
                "B WF_CHILD_UNKNOWN /nodes/8/config/workflowRef");
    }

    // ---------------------------------------------------------------- parallel, timers, timeouts

    static Stream<Arguments> patches() {
        return Stream.of(
                // run in parallel
                c("join does not exist", """
                        [{"op":"replace","path":"/nodes/1/config/joinRef","value":"nowhere"}]""",
                        "B WF_PARALLEL_JOIN_UNKNOWN /nodes/1/config/joinRef", "B WF_PARALLEL_JOIN_ORPHAN /nodes/6"),
                c("join is not a join", """
                        [{"op":"replace","path":"/nodes/1/config/joinRef","value":"leadTime"}]""",
                        "B WF_PARALLEL_JOIN_UNKNOWN /nodes/1/config/joinRef", "B WF_PARALLEL_JOIN_ORPHAN /nodes/6"),
                c("only one branch", """
                        [{"op":"replace","path":"/transitions/2/to","value":"it"}]""",
                        "B WF_PARALLEL_BRANCHES /transitions/2/to", "B WF_UNREACHABLE_NODE /nodes/5"),
                c("branch goes straight to the join", """
                        [{"op":"replace","path":"/transitions/2/to","value":"setupDone"}]""",
                        "B WF_PARALLEL_EMPTY_BRANCH /transitions/2/to", "B WF_UNREACHABLE_NODE /nodes/5"),
                c("approval path skips the join", """
                        [{"op":"replace","path":"/transitions/3/to","value":"leadTime"}]""",
                        "B WF_PARALLEL_JUMP_OUT /transitions/3/to"),
                c("two branches share a step", """
                        [{"op":"replace","path":"/transitions/9/to","value":"itReady"}]""",
                        "B WF_PARALLEL_CROSS_BRANCH /nodes/4"),
                c("a branch enters the join twice", """
                        [{"op":"replace","path":"/transitions/3/to","value":"setupDone"}]""",
                        "B WF_PARALLEL_JOIN_MISMATCH /nodes/6"),
                c("a branch ends the request with success", """
                        [{"op":"replace","path":"/transitions/9/to","value":"ready"}]""",
                        "B WF_PARALLEL_JUMP_OUT /transitions/9/to", "B WF_PARALLEL_BRANCH_OPEN /nodes/5"),
                c("a path from outside enters a branch", """
                        [{"op":"replace","path":"/transitions/0/to","value":"it"}]""",
                        "B WF_PARALLEL_JUMP_IN /transitions/0/to", "B WF_UNREACHABLE_NODE /nodes/1",
                        "B WF_UNREACHABLE_NODE /nodes/5"),
                c("rejection that does not end the request", """
                        [{"op":"replace","path":"/transitions/10/to","value":"notifyReady"}]""",
                        "B WF_PARALLEL_JUMP_OUT /transitions/10/to"),
                // waits and time limits
                c("wait of zero minutes", """
                        [{"op":"replace","path":"/nodes/7/config/duration","value":"PT0M"}]""",
                        "B WF_DURATION_TOO_SHORT /nodes/7/config/duration"),
                c("wait longer than 90 days", """
                        [{"op":"replace","path":"/nodes/7/config/duration","value":"P91D"}]""",
                        "B WF_DURATION_TOO_LONG /nodes/7/config/duration"),
                c("wait of 90 days is fine", """
                        [{"op":"replace","path":"/nodes/7/config/duration","value":"P90D"}]"""),
                c("wait written in minutes is fine", """
                        [{"op":"replace","path":"/nodes/7/config/duration","value":"PT90M"}]"""),
                c("wait that is not a duration", """
                        [{"op":"replace","path":"/nodes/7/config/duration","value":"2 days"}]""",
                        "B WF_SCHEMA_INVALID /nodes/7/config/duration"),
                c("duration with seconds", """
                        [{"op":"replace","path":"/nodes/7/config/duration","value":"PT30S"}]""",
                        "B WF_SCHEMA_INVALID /nodes/7/config/duration"),
                c("time limit too long", """
                        [{"op":"replace","path":"/nodes/2/config/timeoutAfter","value":"P100D"}]""",
                        "B WF_DURATION_TOO_LONG /nodes/2/config/timeoutAfter"),
                c("time limit without a timedOut path", """
                        [{"op":"remove","path":"/transitions/5"}]""",
                        "B WF_TIMEOUT_ROUTE /nodes/2", "B WF_UNREACHABLE_NODE /nodes/3", "B WF_PARALLEL_JUMP_IN /transitions/5/to"),
                c("two timedOut paths", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t20","from":"it","to":"notifyRejected","on":"timedOut"}}]""",
                        "B WF_TIMEOUT_ROUTE /nodes/2"),
                c("timedOut path without a time limit", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t20","from":"facilities","to":"notifyRejected","on":"timedOut"}}]""",
                        "B WF_OUTGOING_INVALID /transitions/19/on"),
                c("approval decision can be timedOut", """
                        [{"op":"replace","path":"/transitions/14/condition","value":{"fieldRef":"/steps/it/decision","operator":"eq","value":"timedOut"}}]"""),
                c("approval without a time limit never times out", """
                        [{"op":"replace","path":"/transitions/14/condition","value":{"fieldRef":"/steps/facilities/decision","operator":"eq","value":"timedOut"}}]""",
                        "B WF_LITERAL_NOT_IN_DOMAIN /transitions/14/condition/value"),
                c("an approver is not recorded on timeout", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/title","value":"/steps/it/decidedBy"}]""",
                        "B WF_FIELDREF_NOT_GUARANTEED /nodes/8/config/inputMapping/title"),
                // field availability with parallel branches
                c("after the join every branch is guaranteed", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/title","value":"/steps/facilities/decision"}]"""),
                c("the escalation happens only on one path", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/title","value":"/steps/itEscalation/decision"}]""",
                        "B WF_FIELDREF_NOT_GUARANTEED /nodes/8/config/inputMapping/title"),
                // start another workflow
                c("child does not exist", """
                        [{"op":"replace","path":"/nodes/8/config/workflowRef","value":"ghost"}]""",
                        "B WF_CHILD_UNKNOWN /nodes/8/config/workflowRef"),
                c("child request field is not mapped", """
                        [{"op":"remove","path":"/nodes/8/config/inputMapping/title"}]""",
                        "B WF_CHILD_MAPPING_MISSING_REQUIRED /nodes/8/config/inputMapping"),
                c("child has no such request field", """
                        [{"op":"add","path":"/nodes/8/config/inputMapping/extra","value":"/input/role"}]""",
                        "B WF_CHILD_MAPPING_UNKNOWN_TARGET /nodes/8/config/inputMapping/extra"),
                c("mapping with the wrong type", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/amount","value":"/input/employeeName"}]""",
                        "B WF_CHILD_MAPPING_TYPE /nodes/8/config/inputMapping/amount"),
                c("free text into a list of choices", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/costCenter","value":"/input/role"}]""",
                        "B WF_CHILD_MAPPING_TYPE /nodes/8/config/inputMapping/costCenter"),
                c("mapping from an unknown field", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/title","value":"/input/ghost"}]""",
                        "B WF_FIELDREF_UNKNOWN /nodes/8/config/inputMapping/title"),
                c("mapping from a step that did not happen yet", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/title","value":"/steps/equipmentOutcome/x"}]""",
                        "B WF_FIELDREF_UNKNOWN /nodes/8/config/inputMapping/title"),
                c("child has no such result field", """
                        [{"op":"replace","path":"/nodes/8/config/resultMapping/orderId","value":"ghostField"}]""",
                        "B WF_CHILD_RESULT_UNKNOWN /nodes/8/config/resultMapping/orderId"),
                c("result named like a built-in output", """
                        [{"op":"add","path":"/nodes/8/config/resultMapping/outcome","value":"procurementId"}]""",
                        "B WF_CHILD_RESULT_RESERVED /nodes/8/config/resultMapping/outcome"),
                c("child result is not always returned", """
                        [{"op":"replace","path":"/outputMapping/equipmentOrderId/required","value":true},
                         {"op":"add","path":"/outputSchema/required","value":["equipmentOrderId"]}]""",
                        "B WF_OUTPUT_NOT_GUARANTEED /outputMapping/equipmentOrderId/fieldRef"),
                c("outcome kind the child never has", """
                        [{"op":"replace","path":"/transitions/14/condition/value","value":"cancelled"}]""",
                        "B WF_LITERAL_NOT_IN_DOMAIN /transitions/14/condition/value"),
                c("outcome label of the child", """
                        [{"op":"replace","path":"/transitions/14/condition","value":{"fieldRef":"/steps/orderEquipment/outcome","operator":"eq","value":"Fulfilled"}}]"""),
                c("outcome label the child never has", """
                        [{"op":"replace","path":"/transitions/14/condition","value":{"fieldRef":"/steps/orderEquipment/outcome","operator":"eq","value":"Delivered"}}]""",
                        "B WF_LITERAL_NOT_IN_DOMAIN /transitions/14/condition/value"),
                c("labels cannot hold expressions", """
                        [{"op":"replace","path":"/nodes/7/label","value":"Wait ${x}"},
                         {"op":"replace","path":"/nodes/1/label","value":"Fork #{y}"}]""",
                        "B WF_LABEL_EXPRESSION /nodes/7/label", "B WF_LABEL_EXPRESSION /nodes/1/label"),
                c("a wait has nothing to use", """
                        [{"op":"replace","path":"/nodes/8/config/inputMapping/title","value":"/steps/leadTime/x"}]""",
                        "B WF_FIELDREF_UNKNOWN /nodes/8/config/inputMapping/title"),
                c("a wait has exactly one next step", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t20","from":"leadTime","to":"notifyReady","on":"next"}}]""",
                        "B WF_OUTGOING_INVALID /nodes/7"),
                c("a join has exactly one next step", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t20","from":"setupDone","to":"notifyReady","on":"next"}}]""",
                        "B WF_OUTGOING_INVALID /nodes/6"));
    }

    private static Arguments c(String name, String patch, String... expected) {
        return Arguments.of(name, patch, List.of(expected));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("patches")
    void ruleCases(String name, String patch, List<String> expected) {
        String doc = TestSupport.text(TestSupport.patch(TestSupport.onboarding(), StrictJson.parse(patch)));
        ValidationReport report = COMPILER.validate(doc, TestSupport.onboardingContext());
        assertThat(found(report)).as(name).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---------------------------------------------------------------- other workflows

    @Test
    void childThatIsNotPublished() {
        CompileContext context = withWorkflow(TestSupport.onboardingContext(), "purchase-approval",
                CompileContext.WorkflowInfo.unpublished("purchase-approval", "Purchase approval"));
        assertThat(found(COMPILER.validate(TestSupport.onboardingJson(), context)))
                .containsExactly("B WF_CHILD_NOT_PUBLISHED /nodes/8/config/workflowRef");
    }

    @Test
    void childThatStartsThisWorkflowIsACycle() {
        CompileContext.WorkflowInfo loop = CompileContext.WorkflowInfo.of("purchase-approval", "Purchase approval",
                TestSupport.PURCHASE_RELEASE_ID, 1, "h", TestSupport.purchaseJson(), Set.of("new-hire-onboarding"), 1);
        CompileContext context = withWorkflow(TestSupport.onboardingContext(), "purchase-approval", loop);
        assertThat(found(COMPILER.validate(TestSupport.onboardingJson(), context)))
                .containsExactly("B WF_CHILD_CYCLE /nodes/8/config/workflowRef");
    }

    @Test
    void aWorkflowCannotStartItself() {
        CompileContext.WorkflowInfo self = CompileContext.WorkflowInfo.of("new-hire-onboarding", "New hire onboarding",
                TestSupport.PURCHASE_RELEASE_ID, 1, "h", TestSupport.purchaseJson(), Set.of(), 0);
        CompileContext context = withWorkflow(TestSupport.onboardingContext(), "new-hire-onboarding", self);
        String doc = TestSupport.text(TestSupport.patch(TestSupport.onboarding(), StrictJson.parse("""
                [{"op":"replace","path":"/nodes/8/config/workflowRef","value":"new-hire-onboarding"}]""")));
        assertThat(found(COMPILER.validate(doc, context))).contains("B WF_CHILD_CYCLE /nodes/8/config/workflowRef");
    }

    @Test
    void callDepthIsBounded() {
        for (int height = 0; height <= 4; height++) {
            CompileContext.WorkflowInfo child = CompileContext.WorkflowInfo.of("purchase-approval",
                    "Purchase approval", TestSupport.PURCHASE_RELEASE_ID, 1, "h", TestSupport.purchaseJson(),
                    Set.of("a", "b", "c"), height);
            ValidationReport report = COMPILER.validate(TestSupport.onboardingJson(),
                    withWorkflow(TestSupport.onboardingContext(), "purchase-approval", child));
            if (height <= 2) {
                assertThat(found(report)).as("height " + height).isEmpty();
            } else {
                assertThat(found(report)).as("height " + height)
                        .containsExactly("B WF_CHILD_DEPTH /nodes/8/config/workflowRef");
            }
        }
    }

    @Test
    void compilingPinsTheClosureAndHeightOfTheChildren() throws Exception {
        CompileContext.WorkflowInfo child = CompileContext.WorkflowInfo.of("purchase-approval", "Purchase approval",
                TestSupport.PURCHASE_RELEASE_ID, 3, "abc", TestSupport.purchaseJson(), Set.of("audit-log"), 1);
        CompiledWorkflow w = COMPILER.compile(TestSupport.onboardingJson(),
                withWorkflow(TestSupport.onboardingContext(), "purchase-approval", child));
        assertThat(w.callClosure()).containsExactly("audit-log", "purchase-approval");
        assertThat(w.callHeight()).isEqualTo(2);
        assertThat(w.children()).extracting(CompiledWorkflow.ChildRef::releaseNumber).containsExactly(3);
        assertThat(w.bpmnXml()).contains("releaseNumber=\"3\" semanticHash=\"abc\"");
    }

    // ---------------------------------------------------------------- nesting and shared joins

    @Test
    void parallelStepsNestOneLevel() {
        assertThat(found(COMPILER.validate(nested(2), TestSupport.onboardingContext()))).isEmpty();
        assertThat(found(COMPILER.validate(nested(1), TestSupport.onboardingContext()))).isEmpty();
    }

    @Test
    void parallelStepsCannotNestThreeDeep() {
        assertThat(found(COMPILER.validate(nested(3), TestSupport.onboardingContext())))
                .containsExactly("B WF_PARALLEL_DEPTH /nodes/5");
    }

    @Test
    void oneJoinCannotServeTwoSplits() {
        ObjectNode doc = (ObjectNode) StrictJson.parse(nested(2));
        // The inner split points at the outer join.
        for (var node : doc.get("nodes")) {
            if ("s2".equals(node.get("id").stringValue())) {
                ((ObjectNode) node.get("config")).put("joinRef", "j1");
            }
        }
        List<String> issues = found(COMPILER.validate(TestSupport.text(doc), TestSupport.onboardingContext()));
        assertThat(issues).contains("B WF_PARALLEL_JOIN_SHARED /nodes/3/config/joinRef");
    }

    @Test
    void rejectedFinishesTerminateWhenThereAreParallelSteps() throws Exception {
        String xml = COMPILER.compile(TestSupport.onboardingJson(), TestSupport.onboardingContext()).bpmnXml();
        assertThat(xml.split("<terminateEventDefinition/>", -1)).as("rejected finishes").hasSize(3);
        assertThat(xml).contains("<endEvent id=\"rejected\"").contains("<endEvent id=\"equipmentRejected\"");
        String ready = xml.substring(xml.indexOf("<endEvent id=\"ready\""), xml.indexOf("</endEvent>",
                xml.indexOf("<endEvent id=\"ready\"")));
        assertThat(ready).doesNotContain("terminate");
        // Workflows without parallel steps are unchanged.
        assertThat(COMPILER.compile(TestSupport.purchaseJson(), TestSupport.defaultContext()).bpmnXml())
                .doesNotContain("terminate");
    }

    @Test
    void generatedXmlOnlyUsesAllowlistedExpressions() throws Exception {
        String xml = COMPILER.compile(TestSupport.onboardingJson(), TestSupport.onboardingContext()).bpmnXml();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{[^}]*}").matcher(xml);
        List<String> expressions = new ArrayList<>();
        while (m.find()) {
            expressions.add(m.group());
        }
        assertThat(expressions).allSatisfy(e -> assertThat(e).matches("\\$\\{(orqixRoute\\.(approved|rejected|condition)"
                + "\\(execution, '[A-Za-z0-9_-]+'\\)|orqixNotificationTask|orqixChildTask)}"));
        assertThat(xml).contains("${orqixChildTask}").contains("flowable:triggerable=\"true\"");
    }

    // ---------------------------------------------------------------- helpers

    private static CompileContext withWorkflow(CompileContext base, String key, CompileContext.WorkflowInfo info) {
        Map<String, CompileContext.WorkflowInfo> workflows = new LinkedHashMap<>(base.workflows());
        workflows.put(key, info);
        return new CompileContext(base.selfApprovalAllowed(), base.groups(), base.connectionVersions(),
                base.enabledConnectionBindings(), workflows);
    }

    private static List<String> found(ValidationReport report) {
        return report.issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    /**
     * A workflow with {@code levels} nested parallel regions. Level i has branches "a{i}" (an approval) and either
     * the next region or, at the deepest level, "b{i}" (another approval). Node order: start, s1, a1, s2, a2, ...
     */
    private static String nested(int levels) {
        ObjectNode root = TestSupport.onboarding();
        ArrayNode nodes = JsonNodeFactory.instance.arrayNode();
        ArrayNode transitions = JsonNodeFactory.instance.arrayNode();
        nodes.add(node("start", "start", "Request submitted", "{}"));
        transitions.add(transition("x0", "start", "s1", "next", null));
        int t = 1;
        for (int i = 1; i <= levels; i++) {
            nodes.add(node("s" + i, "parallelSplit", "Split " + i, "{\"joinRef\":\"j" + i + "\"}"));
            nodes.add(node("a" + i, "approval", "Approve " + i,
                    "{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true}"));
            transitions.add(transition("x" + t++, "s" + i, "a" + i, "next", null));
            transitions.add(transition("x" + t++, "a" + i, "j" + i, "approved", null));
            transitions.add(transition("x" + t++, "a" + i, "rej", "rejected", null));
            if (i < levels) {
                transitions.add(transition("x" + t++, "s" + i, "s" + (i + 1), "next", null));
            } else {
                nodes.add(node("b" + i, "approval", "Approve other " + i,
                        "{\"candidateGroupRef\":\"finance-approvers\",\"rejectReasonRequired\":true}"));
                transitions.add(transition("x" + t++, "s" + i, "b" + i, "next", null));
                transitions.add(transition("x" + t++, "b" + i, "j" + i, "approved", null));
                transitions.add(transition("x" + t++, "b" + i, "rej", "rejected", null));
            }
        }
        for (int i = levels; i >= 1; i--) {
            nodes.add(node("j" + i, "parallelJoin", "Join " + i, "{}"));
            String next = i == 1 ? "done" : "j" + (i - 1);
            transitions.add(transition("x" + t++, "j" + i, next, "next", null));
        }
        nodes.add(node("rej", "notification", "Tell requester it was rejected",
                "{\"channel\":\"inApp\",\"recipient\":\"initiator\",\"message\":\"Rejected.\"}"));
        nodes.add(node("done", "end", "Fulfilled", "{\"businessOutcome\":\"Fulfilled\",\"outcomeKind\":\"success\"}"));
        nodes.add(node("rejected", "end", "Rejected", "{\"businessOutcome\":\"Rejected\",\"outcomeKind\":\"rejected\"}"));
        transitions.add(transition("x" + t, "rej", "rejected", "next", null));
        root.set("nodes", nodes);
        root.set("transitions", transitions);
        root.remove("uiLayout");
        root.set("outputSchema", StrictJson.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        root.set("outputMapping", JsonNodeFactory.instance.objectNode());
        return TestSupport.text(root);
    }

    private static ObjectNode node(String id, String type, String label, String config) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("id", id);
        n.put("type", type);
        n.put("label", label);
        n.set("config", StrictJson.parse(config));
        return n;
    }

    private static ObjectNode transition(String id, String from, String to, String on, String label) {
        ObjectNode t = JsonNodeFactory.instance.objectNode();
        t.put("id", id);
        t.put("from", from);
        t.put("to", to);
        t.put("on", on);
        if (label != null) {
            t.put("label", label);
        }
        return t;
    }
}
