package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** G2 list-driven approvals: one approval task per item of a request list (docs/build/S88). */
class PerItemApprovalTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String LINES = "{\"type\":\"array\",\"title\":\"Line items\",\"minItems\":1,\"maxItems\":%d,"
            + "\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"description\",\"amount\"],"
            + "\"properties\":{\"description\":{\"type\":\"string\",\"title\":\"Description\",\"minLength\":1,\"maxLength\":80},"
            + "\"amount\":{\"type\":\"number\",\"title\":\"Amount\",\"minimum\":0},"
            + "\"approver\":{\"type\":\"string\",\"title\":\"Approver email\",\"maxLength\":200},"
            + "\"urgent\":{\"type\":\"boolean\",\"title\":\"Urgent\"}}}}";
    private static final String OFFICES = "{\"type\":\"array\",\"title\":\"Branch offices\",\"minItems\":1,\"maxItems\":3,"
            + "\"items\":{\"type\":\"string\",\"title\":\"Office\",\"enum\":[\"North\",\"South\",\"East\"]}}";
    private static final String INPUT = "{\"title\":\"Laptops\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\","
            + "\"amount\":9000,\"lines\":%s,\"offices\":[\"North\",\"East\"]}";
    private static final String THREE_LINES = "[{\"description\":\"Laptop\",\"amount\":3000},"
            + "{\"description\":\"Dock\",\"amount\":300},{\"description\":\"Monitor\",\"amount\":700}]";

    private static JsonNode n(String json) {
        return MAPPER.readTree(json);
    }

    private static JsonNode node(ObjectNode d, String id) {
        for (JsonNode x : d.path("nodes")) {
            if (id.equals(x.path("id").asString())) {
                return x;
            }
        }
        throw new AssertionError(id);
    }

    private static ObjectNode config(ObjectNode d) {
        return (ObjectNode) node(d, "finance").path("config");
    }

    /** The purchase template with a required list of rows (and of single values); finance approves per line item. */
    private static ObjectNode perLine(int maxItems) {
        ObjectNode d = TestSupport.purchase();
        ObjectNode schema = (ObjectNode) d.path("inputSchema");
        ((ObjectNode) schema.path("properties")).set("lines", n(LINES.formatted(maxItems)));
        ((ObjectNode) schema.path("properties")).set("offices", n(OFFICES));
        ((ArrayNode) schema.path("required")).add("lines").add("offices");
        config(d).set("perItem", n("{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\"}"));
        return d;
    }

    private static List<String> codes(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues()
                .stream().filter(i -> i.severity() == Severity.BLOCKER).map(ValidationIssue::code).toList();
    }

    @Test
    void aListApprovalCompilesToAParallelMultiInstanceTaskOverTheItemPositions() throws Exception {
        ObjectNode d = perLine(20);
        assertThat(codes(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace())
                .bpmnXml();
        assertThat(bpmn).contains("<orqix:perItem itemsRef=\"/input/lines\" itemLabel=\"description\"/>",
                "flowable:collection=\"${orqixRoute.itemPositions(execution, 'finance')}\"",
                "flowable:elementVariable=\"orqix_item\"",
                "nrOfCompletedInstances == nrOfInstances || orqixRoute.rejected(execution, 'finance')");
        assertThat(bpmn).doesNotContain("loopCardinality", "allowSamePerson");
        // A workflow without the setting generates the same process as before (no multi-instance at all).
        assertThat(Compilers.workflowCompiler().compile(TestSupport.purchase().toString(),
                SimulatorFixtures.context().workspace()).bpmnXml()).doesNotContain("multiInstanceLoopCharacteristics");
    }

    @Test
    void theListMustBeARequiredUnconditionalBoundedRequestList() {
        assertThat(codes(perLine(21))).contains("WF_PER_ITEM_BOUND");

        ObjectNode scalar = perLine(5);
        config(scalar).set("perItem", n("{\"itemsRef\":\"/input/amount\"}"));
        assertThat(codes(scalar)).contains("WF_PER_ITEM_LIST");

        ObjectNode missing = perLine(5);
        config(missing).set("perItem", n("{\"itemsRef\":\"/input/nothing\"}"));
        assertThat(codes(missing)).contains("WF_PER_ITEM_LIST");

        // An optional list may be absent, which would be an empty list: refused at publication.
        ObjectNode optional = perLine(5);
        ArrayNode required = (ArrayNode) optional.path("inputSchema").path("required");
        for (int i = 0; i < required.size(); i++) {
            if ("lines".equals(required.get(i).asString())) {
                required.remove(i);
            }
        }
        assertThat(codes(optional)).contains("WF_PER_ITEM_LIST");

        // A list that is asked for only under a condition can also be missing.
        ObjectNode conditional = perLine(5);
        conditional.set("inputUi", n("{\"order\":[\"title\",\"purpose\",\"costCenter\",\"amount\",\"lines\",\"offices\"],"
                + "\"visibleWhen\":{\"lines\":{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"IT\"}}}"));
        assertThat(codes(conditional)).contains("WF_PER_ITEM_LIST");
    }

    @Test
    void rowsNeedAScalarLabelColumnAndSingleValuesTakeNone() {
        ObjectNode noLabel = perLine(5);
        config(noLabel).set("perItem", n("{\"itemsRef\":\"/input/lines\"}"));
        assertThat(codes(noLabel)).contains("WF_PER_ITEM_LABEL");

        ObjectNode unknown = perLine(5);
        config(unknown).set("perItem", n("{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"colour\"}"));
        assertThat(codes(unknown)).contains("WF_PER_ITEM_LABEL");

        ObjectNode flag = perLine(5);
        config(flag).set("perItem", n("{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"urgent\"}"));
        assertThat(codes(flag)).contains("WF_PER_ITEM_LABEL");

        ObjectNode values = perLine(5);
        config(values).set("perItem", n("{\"itemsRef\":\"/input/offices\"}"));
        assertThat(codes(values)).isEmpty();
        config(values).set("perItem", n("{\"itemsRef\":\"/input/offices\",\"itemLabel\":\"description\"}"));
        assertThat(codes(values)).contains("WF_PER_ITEM_LABEL");
    }

    @Test
    void aListApprovalCannotBeCombinedWithSingleTaskSettingsOrSeveralApprovers() {
        ObjectNode timeout = perLine(5);
        config(timeout).put("timeoutAfter", "PT24H");
        assertThat(codes(timeout)).contains("WF_PER_ITEM_UNSUPPORTED");

        ObjectNode escalation = perLine(5);
        config(escalation).set("escalation", n("{\"maxNotices\":1,\"intervalHours\":4}"));
        assertThat(codes(escalation)).contains("WF_PER_ITEM_UNSUPPORTED");

        ObjectNode corrections = perLine(5);
        config(corrections).set("correctable", n("[\"purpose\"]"));
        assertThat(codes(corrections)).contains("WF_PER_ITEM_UNSUPPORTED");

        ObjectNode both = perLine(5);
        config(both).set("multiInstance", n("{\"approvers\":2}"));
        assertThat(codes(both)).contains("WF_PER_ITEM_UNSUPPORTED");

        // allowSamePerson exists only as true; false is not a second spelling of the default.
        ObjectNode falseFlag = perLine(5);
        config(falseFlag).set("perItem", n("{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\","
                + "\"allowSamePerson\":false}"));
        assertThat(codes(falseFlag)).isNotEmpty();
    }

    @Test
    void approvedCountAndItemDecisionsAreTypedOutputsOnlyOfAListApproval() {
        ObjectNode d = perLine(5);
        ((ObjectNode) d.path("outputSchema").path("properties")).set("approvedItems",
                n("{\"type\":\"integer\",\"title\":\"Approved items\"}"));
        ((ObjectNode) d.path("outputMapping")).set("approvedItems",
                n("{\"fieldRef\":\"/steps/finance/approvedCount\",\"required\":false}"));
        assertThat(codes(d)).isEmpty();

        // A list of decisions is not a single text value.
        ObjectNode wrong = perLine(5);
        ((ObjectNode) wrong.path("outputSchema").path("properties")).set("firstDecision",
                n("{\"type\":\"string\",\"title\":\"Decision\"}"));
        ((ObjectNode) wrong.path("outputMapping")).set("firstDecision",
                n("{\"fieldRef\":\"/steps/finance/itemDecisions\",\"required\":false}"));
        assertThat(codes(wrong)).isNotEmpty();

        // An ordinary approval has neither output.
        ObjectNode plain = TestSupport.purchase();
        ((ObjectNode) plain.path("outputSchema").path("properties")).set("approvedItems",
                n("{\"type\":\"integer\",\"title\":\"Approved items\"}"));
        ((ObjectNode) plain.path("outputMapping")).set("approvedItems",
                n("{\"fieldRef\":\"/steps/finance/approvedCount\",\"required\":false}"));
        assertThat(codes(plain)).contains("WF_FIELDREF_UNKNOWN");
    }

    @Test
    void theSimulatorOpensOneTaskPerItemAndCollectsTheDecisions() {
        String text = perLine(5).toString();
        JsonNode approved = SimulatorFixtures.run(text, INPUT.formatted(THREE_LINES), "{}");
        assertThat(approved.toString()).contains("opens one task for each of the 3 items",
                "each item needs a different person", "all 3 items are approved",
                "\"approvedCount\":3", "\"itemDecisions\":[\"approved\",\"approved\",\"approved\"]");

        JsonNode rejected = SimulatorFixtures.run(text, INPUT.formatted(THREE_LINES),
                "{\"approvals\":{\"finance\":{\"outcome\":\"reject\",\"reason\":\"Too much\",\"rejectedItem\":2}}}");
        assertThat(rejected.toString()).contains("item 2 is rejected", "the open items are cancelled",
                "\"approvedCount\":1", "\"itemDecisions\":[\"approved\",\"rejected\",\"cancelled\"]");
    }

    @Test
    void theBpmnSourceReadsAListApprovalBackExactlyAndRefusesAnEditedCollection() throws Exception {
        for (String perItem : List.of("{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\"}",
                "{\"itemsRef\":\"/input/offices\",\"allowSamePerson\":true}")) {
            ObjectNode d = perLine(5);
            config(d).set("perItem", n(perItem));
            String json = d.toString();
            CompiledWorkflow compiled = Compilers.workflowCompiler().compile(json, BpmnSourceTest.context(json));
            BpmnSource.Export export = BpmnSource.export(json, compiled);
            assertThat(export.identical()).as(perItem + " " + export.differences()).isTrue();
            BpmnSource.Check again = BpmnSource.check(export.bpmnXml(), json, Compilers.workflowCompiler(),
                    BpmnSourceTest::context);
            assertThat(again.applicable()).as(again.problems().toString()).isTrue();

            // Changing the generated iteration is not something the document can hold.
            BpmnSource.Check edited = BpmnSource.check(export.bpmnXml().replace("orqix_item", "other_item"), json,
                    Compilers.workflowCompiler(), BpmnSourceTest::context);
            assertThat(edited.applicable()).isFalse();
        }
    }

    // ---------------------------------------------------------------- S104: quorum, one at a time, proposed person

    /** The per-line approval with the list's minimum raised, so a quorum up to {@code minItems} is reachable. */
    private static ObjectNode perLineAtLeast(int minItems, String perItem) {
        ObjectNode d = perLine(5);
        ((ObjectNode) d.path("inputSchema").path("properties").path("lines")).put("minItems", minItems);
        config(d).set("perItem", n(perItem));
        return d;
    }

    @Test
    void aQuorumOrOneAtATimeStepEndsBySettlingAndWritesItsSettingsOnlyWhenSet() throws Exception {
        ObjectNode d = perLineAtLeast(2, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\","
                + "\"sequential\":true,\"quorum\":2,\"assigneeFrom\":\"approver\"}");
        assertThat(codes(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace())
                .bpmnXml();
        assertThat(bpmn).contains("sequential=\"true\"", "quorum=\"2\"", "assigneeFrom=\"approver\"",
                "isSequential=\"true\"", "nrOfCompletedInstances == nrOfInstances || orqixRoute.itemsSettled(execution, 'finance')");
        assertThat(bpmn).doesNotContain("|| orqixRoute.rejected(execution, 'finance')");

        // Only a quorum: still parallel, still the settle condition.
        String quorumOnly = Compilers.workflowCompiler().compile(perLineAtLeast(2,
                "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"quorum\":2}").toString(),
                SimulatorFixtures.context().workspace()).bpmnXml();
        assertThat(quorumOnly).contains("isSequential=\"false\"", "orqixRoute.itemsSettled(execution, 'finance')")
                .doesNotContain("sequential=\"true\"", "assigneeFrom");
    }

    @Test
    void aQuorumMustBeReachableByEveryRequest() {
        assertThat(codes(perLineAtLeast(1, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"quorum\":2}")))
                .contains("WF_PER_ITEM_QUORUM");
        assertThat(codes(perLineAtLeast(3, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"quorum\":3}")))
                .doesNotContain("WF_PER_ITEM_QUORUM");
        assertThat(codes(perLineAtLeast(1, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"quorum\":0}")))
                .isNotEmpty();
    }

    @Test
    void theProposedPersonComesFromATextColumnOfAListOfRows() {
        ObjectNode values = perLine(3);
        config(values).set("perItem", n("{\"itemsRef\":\"/input/offices\",\"assigneeFrom\":\"office\"}"));
        assertThat(codes(values)).contains("WF_PER_ITEM_ASSIGNEE");
        assertThat(codes(perLineAtLeast(1, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"assigneeFrom\":\"nobody\"}")))
                .contains("WF_PER_ITEM_ASSIGNEE");
        assertThat(codes(perLineAtLeast(1, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"assigneeFrom\":\"amount\"}")))
                .contains("WF_PER_ITEM_ASSIGNEE");
        assertThat(codes(perLineAtLeast(1, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"assigneeFrom\":\"approver\"}")))
                .isEmpty();
    }

    @Test
    void theSimulatorStopsAtTheQuorumAndRejectsOnceItCanNoLongerBeReached() {
        String text = perLineAtLeast(2, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\",\"quorum\":2,"
                + "\"sequential\":true}").toString();
        JsonNode approved = SimulatorFixtures.run(text, INPUT.formatted(THREE_LINES), "{}");
        assertThat(approved.toString()).contains("opens one task at a time, in list order, for each of the 3 items",
                "2 of them must be approved", "the first 2 items are approved",
                "\"approvedCount\":2", "\"itemDecisions\":[\"approved\",\"approved\",\"cancelled\"]");

        JsonNode rejected = SimulatorFixtures.run(text, INPUT.formatted(THREE_LINES),
                "{\"approvals\":{\"finance\":{\"outcome\":\"reject\",\"reason\":\"Too much\",\"rejectedItem\":1}}}");
        assertThat(rejected.toString()).contains("items 1 to 2 are rejected", "2 approvals can no longer be reached",
                "\"approvedCount\":0", "\"itemDecisions\":[\"rejected\",\"rejected\",\"cancelled\"]");
    }

    @Test
    void theBpmnSourceReadsTheNewSettingsBackExactly() throws Exception {
        ObjectNode d = perLineAtLeast(2, "{\"itemsRef\":\"/input/lines\",\"itemLabel\":\"description\","
                + "\"sequential\":true,\"quorum\":2,\"assigneeFrom\":\"approver\"}");
        String json = d.toString();
        CompiledWorkflow compiled = Compilers.workflowCompiler().compile(json, BpmnSourceTest.context(json));
        BpmnSource.Export export = BpmnSource.export(json, compiled);
        assertThat(export.identical()).as(export.differences().toString()).isTrue();
        BpmnSource.Check again = BpmnSource.check(export.bpmnXml(), json, Compilers.workflowCompiler(),
                BpmnSourceTest::context);
        assertThat(again.applicable()).as(again.problems().toString()).isTrue();
        // Switching the generated loop to parallel without the setting is refused.
        BpmnSource.Check edited = BpmnSource.check(export.bpmnXml().replace("isSequential=\"true\"", "isSequential=\"false\""),
                json, Compilers.workflowCompiler(), BpmnSourceTest::context);
        assertThat(edited.applicable()).isFalse();
    }
}
