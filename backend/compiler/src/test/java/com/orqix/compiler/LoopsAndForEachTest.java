package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-E01 bounded loops and CG-E06 for each on any step (docs/build/S138): compiler rules, generated BPMN, the BPMN
 * round trip and the simulator.
 */
class LoopsAndForEachTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    // ---------------------------------------------------------------- fixtures

    /**
     * Purchase approval where finance can send the request back to the manager at most twice; the third rejection
     * takes the limitReached path to the rejection notice.
     */
    static ObjectNode sendBack(int maxTraversals) {
        ObjectNode d = TestSupport.purchase();
        for (JsonNode t : d.get("transitions")) {
            if (t.get("id").stringValue().equals("t8")) {
                ((ObjectNode) t).put("to", "manager").put("maxTraversals", maxTraversals).put("label", "Send back");
            }
        }
        ((ArrayNode) d.get("transitions")).add(StrictJson.parse(
                "{\"id\":\"t12\",\"from\":\"finance\",\"to\":\"notifyRejected\",\"on\":\"limitReached\"}"));
        return d;
    }

    /** Purchase approval whose order call runs once per order line (a list of rows of up to {@code max} lines). */
    static ObjectNode perLine(int listMax, String forEachExtra) {
        ObjectNode d = TestSupport.purchase();
        ((ObjectNode) d.path("inputSchema").path("properties")).set("lines", StrictJson.parse("{\"type\":\"array\","
                + "\"title\":\"Order lines\",\"minItems\":1,\"maxItems\":" + listMax + ",\"items\":{\"type\":\"object\","
                + "\"additionalProperties\":false,\"required\":[\"title\",\"amount\"],\"properties\":{"
                + "\"title\":{\"type\":\"string\",\"title\":\"Item\",\"minLength\":1,\"maxLength\":120},"
                + "\"amount\":{\"type\":\"number\",\"title\":\"Amount\",\"minimum\":0.01,\"maximum\":1000000,"
                + "\"multipleOf\":0.01}}}}"));
        for (JsonNode n : d.get("nodes")) {
            if (n.get("id").stringValue().equals("procure")) {
                ObjectNode c = (ObjectNode) n.get("config");
                ((ObjectNode) c.get("inputMapping")).remove(List.of("title", "amount"));
                c.set("forEach", StrictJson.parse("{\"itemsRef\":\"/input/lines\",\"itemMapping\":{\"title\":\"title\","
                        + "\"amount\":\"amount\"},\"maxItems\":" + listMax + ",\"collect\":{\"orderIds\":\"procurementId\"}"
                        + forEachExtra + "}"));
            }
        }
        ((ObjectNode) d.get("outputMapping")).removeAll();
        return d;
    }

    private static ValidationReport validate(ObjectNode d) {
        return COMPILER.validate(d.toString(), BpmnSourceTest.context(d.toString()));
    }

    private static List<String> blockers(ObjectNode d) {
        return TestSupport.codes(validate(d).issues(), Severity.BLOCKER);
    }

    private static String bpmn(ObjectNode d) throws CompilationException {
        String xml = COMPILER.compile(d.toString(), BpmnSourceTest.context(d.toString())).bpmnXml();
        GeneratedXmlTest.onlyAllowedExpressions(xml);
        return xml;
    }

    private static ObjectNode node(ObjectNode d, String id) {
        for (JsonNode n : d.get("nodes")) {
            if (id.equals(n.get("id").stringValue())) {
                return (ObjectNode) n;
            }
        }
        throw new AssertionError(id);
    }

    private static ObjectNode transition(ObjectNode d, String id) {
        for (JsonNode t : d.get("transitions")) {
            if (id.equals(t.get("id").stringValue())) {
                return (ObjectNode) t;
            }
        }
        throw new AssertionError(id);
    }

    // ---------------------------------------------------------------- CG-E01 more rules

    @Test
    void anApprovalCanSendTheRequestBackToItself() throws Exception {
        ObjectNode d = TestSupport.purchase();
        transition(d, "t8").put("to", "finance").put("maxTraversals", 1);
        ((ArrayNode) d.get("transitions")).add(StrictJson.parse(
                "{\"id\":\"t12\",\"from\":\"finance\",\"to\":\"notifyRejected\",\"on\":\"limitReached\"}"));
        assertThat(blockers(d)).isEmpty();
        assertThat(bpmn(d)).contains("sourceRef=\"t8__loop\" targetRef=\"finance\"");
    }

    @Test
    void aStepSendsBackOnOnePathOnlyAndTheLimitPathHasNoLimit() {
        ObjectNode d = sendBack(2);
        transition(d, "t7").put("to", "manager").put("maxTraversals", 1);
        assertThat(blockers(d)).contains("WF_LOOP_ONE_PER_STEP");
        ObjectNode limited = sendBack(2);
        transition(limited, "t12").put("maxTraversals", 2);
        assertThat(blockers(limited)).contains("WF_LOOP_LIMIT_ROUTE");
    }

    @Test
    void stepsThatKeepOneRowPerStepCannotBeInsideALoop() {
        ObjectNode d = sendBack(2);
        // A document generated between the manager and the policy check runs on every visit: refused.
        ((ArrayNode) d.get("nodes")).add(StrictJson.parse("{\"id\":\"memo\",\"type\":\"generateDocument\",\"label\":"
                + "\"Write memo\",\"config\":{\"format\":\"pdf\",\"fileName\":\"memo.pdf\",\"template\":\"<p>Memo</p>\","
                + "\"inputMapping\":{}}}"));
        transition(d, "t2").put("to", "memo");
        ((ArrayNode) d.get("transitions")).add(StrictJson.parse(
                "{\"id\":\"t13\",\"from\":\"memo\",\"to\":\"amountPolicy\",\"on\":\"next\"}"));
        assertThat(blockers(d)).contains("WF_LOOP_STEP_KIND");
    }

    @Test
    void aLoopStaysInsideOneSection() {
        ObjectNode d = sendBack(2);
        ((ArrayNode) d.putArray("subprocesses")).add(StrictJson.parse("{\"id\":\"review\",\"type\":\"embedded\","
                + "\"label\":\"Finance review\",\"nodes\":[\"finance\"]}"));
        assertThat(blockers(d)).contains("WF_LOOP_SCOPE");
    }

    @Test
    void theSimulatorEntryOfTheLimitSaysSo() {
        String input = "{\"title\":\"Server\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":9000}";
        JsonNode r = SimulatorFixtures.run(sendBack(1).toString(), input,
                "{\"approvals\":{\"finance\":{\"outcome\":\"reject\",\"reason\":\"No\"}}}");
        boolean limit = false;
        for (JsonNode e : r.get("trace")) {
            limit |= e.path("details").path("limitReached").asBoolean(false);
        }
        assertThat(limit).isTrue();
    }

    // ---------------------------------------------------------------- CG-E06 more rules

    @Test
    void theItemSettingMustFitTheList() {
        ObjectNode rows = perLine(30, "");
        ObjectNode f = (ObjectNode) node(rows, "procure").get("config").get("forEach");
        f.remove("itemMapping");
        f.put("itemInput", "title");
        assertThat(blockers(rows)).contains("WF_FOR_EACH_ITEM");

        ObjectNode clash = perLine(30, "");
        ((ObjectNode) node(clash, "procure").get("config").get("inputMapping")).put("title", "/input/title");
        assertThat(blockers(clash)).contains("WF_FOR_EACH_INPUT");
    }

    @Test
    void collectedNamesAreCheckedAndBatchSizeIsOnlyForParallel() {
        ObjectNode reserved = perLine(30, "");
        ((ObjectNode) node(reserved, "procure").get("config").get("forEach").get("collect")).put("itemCount", "procurementId");
        assertThat(blockers(reserved)).contains("WF_FOR_EACH_COLLECT");
        ObjectNode unknown = perLine(30, "");
        ((ObjectNode) node(unknown, "procure").get("config").get("forEach").get("collect")).put("other", "nothing");
        assertThat(blockers(unknown)).contains("WF_FOR_EACH_COLLECT");
        ObjectNode batch = perLine(30, ",\"batchSize\":5");
        assertThat(TestSupport.codes(validate(batch).issues(), Severity.WARNING)).contains("WF_FOR_EACH_BATCH_UNUSED");
    }

    @Test
    void workersRunTheirItemsOneAtATime() throws Exception {
        ObjectNode d = perLine(30, "");
        ObjectNode procure = node(d, "procure");
        procure.put("type", "externalTask");
        procure.set("config", StrictJson.parse("{\"topic\":\"order-line\",\"inputMapping\":{},\"result\":[{\"name\":"
                + "\"orderId\",\"type\":\"string\",\"required\":true}],\"forEach\":{\"itemsRef\":\"/input/lines\","
                + "\"itemMapping\":{\"title\":\"title\"},\"maxItems\":30,\"collect\":{\"orderIds\":\"orderId\"}}}"));
        ((ArrayNode) d.path("dependencies").path("connections")).removeAll();
        assertThat(blockers(d)).isEmpty();
        String xml = bpmn(d);
        assertThat(xml).contains("${orqixExternalTask}", "<multiInstanceLoopCharacteristics isSequential=\"true\"",
                "fieldRef=\"/input/lines/*/title\"");
        assertRoundTrip(d);
        ((ObjectNode) procure.get("config").get("forEach")).put("mode", "parallel");
        assertThat(blockers(d)).contains("WF_FOR_EACH_PARALLEL");
    }

    @Test
    void aListOfSingleValuesFillsOneInput() throws Exception {
        ObjectNode d = TestSupport.purchase();
        ((ObjectNode) d.path("inputSchema").path("properties")).set("titles", StrictJson.parse("{\"type\":\"array\","
                + "\"title\":\"Titles\",\"minItems\":1,\"maxItems\":60,\"items\":{\"type\":\"string\",\"title\":\"Title\","
                + "\"minLength\":1,\"maxLength\":120}}"));
        ObjectNode c = (ObjectNode) node(d, "procure").get("config");
        ((ObjectNode) c.get("inputMapping")).remove("title");
        c.set("forEach", StrictJson.parse("{\"itemsRef\":\"/input/titles\",\"itemInput\":\"title\",\"maxItems\":60,"
                + "\"mode\":\"parallel\",\"batchSize\":10,\"completion\":{\"when\":\"atLeast\",\"count\":2}}"));
        ((ObjectNode) d.get("outputMapping")).removeAll();
        assertThat(blockers(d)).isEmpty();
        assertThat(bpmn(d)).contains("fieldRef=\"/input/titles/*\"", "procure__batches");
        assertRoundTrip(d);
        String input = "{\"title\":\"Office\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":900,"
                + "\"titles\":[\"Desk\",\"Chair\",\"Lamp\"]}";
        JsonNode r = SimulatorFixtures.run(d.toString(), input, null);
        assertThat(r.path("outcome").path("businessOutcome").asString()).isEqualTo("Fulfilled");
        assertThat(r.toString()).contains("\"completedCount\":2");
    }

    // ---------------------------------------------------------------- CG-E01 compiler rules

    @Test
    void aSendBackPathWithALimitAndALimitRouteIsPublishable() {
        assertThat(blockers(sendBack(2))).isEmpty();
    }

    @Test
    void aLoopWithoutALimitIsStillRefused() {
        ObjectNode d = sendBack(2);
        for (JsonNode t : d.get("transitions")) {
            if (t.get("id").stringValue().equals("t8")) {
                ((ObjectNode) t).remove("maxTraversals");
            }
        }
        assertThat(blockers(d)).contains("WF_CYCLE", "WF_LOOP_LIMIT_ROUTE");
    }

    @Test
    void theLimitRouteIsRequiredAndUnique() {
        ObjectNode d = sendBack(2);
        ((ArrayNode) d.get("transitions")).remove(((ArrayNode) d.get("transitions")).size() - 1);
        assertThat(blockers(d)).contains("WF_LOOP_LIMIT_ROUTE");
        ObjectNode twice = sendBack(2);
        ((ArrayNode) twice.get("transitions")).add(StrictJson.parse(
                "{\"id\":\"t13\",\"from\":\"finance\",\"to\":\"procure\",\"on\":\"limitReached\"}"));
        assertThat(blockers(twice)).contains("WF_LOOP_LIMIT_ROUTE");
    }

    @Test
    void aLimitOnAForwardPathIsRefused() {
        ObjectNode d = TestSupport.purchase();
        for (JsonNode t : d.get("transitions")) {
            if (t.get("id").stringValue().equals("t9")) {
                ((ObjectNode) t).put("maxTraversals", 3);
            }
        }
        ((ArrayNode) d.get("transitions")).add(StrictJson.parse(
                "{\"id\":\"t12\",\"from\":\"procure\",\"to\":\"notify\",\"on\":\"limitReached\"}"));
        assertThat(blockers(d)).contains("WF_LOOP_NOT_BACK");
    }

    @Test
    void theLimitIsBetweenOneAndTen() {
        assertThat(validate(sendBack(11)).publishable()).isFalse();
        assertThat(validate(sendBack(0)).publishable()).isFalse();
        assertThat(blockers(sendBack(10))).isEmpty();
    }

    @Test
    void aLimitRouteWithoutALoopIsRefused() {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.get("transitions")).add(StrictJson.parse(
                "{\"id\":\"t12\",\"from\":\"finance\",\"to\":\"procure\",\"on\":\"limitReached\"}"));
        assertThat(blockers(d)).contains("WF_LOOP_LIMIT_ROUTE");
    }

    @Test
    void outputsOfStepsInsideTheLoopAreOnlyGuaranteedAfterThem() {
        ObjectNode d = sendBack(2);
        // The manager step uses the decision of the amount policy, which happens after it on the first visit.
        for (JsonNode n : d.get("nodes")) {
            if (n.get("id").stringValue().equals("procure")) {
                ((ObjectNode) n.get("config").get("inputMapping")).put("costCenter", "/steps/amountPolicy/route");
            }
        }
        assertThat(blockers(d)).doesNotContain("WF_FIELDREF_NOT_GUARANTEED");
    }

    // ---------------------------------------------------------------- CG-E01 generated BPMN and round trip

    @Test
    void theBpmnGuardsTheSendBackPathAndCountsVisits() throws Exception {
        String xml = bpmn(sendBack(2));
        assertThat(xml).contains("<exclusiveGateway id=\"t8__loop\"", "default=\"t12\"",
                "<orqix:loop path=\"t8\" maxTraversals=\"2\" limitPath=\"t12\"/>",
                "sourceRef=\"t8__loop\" targetRef=\"manager\"",
                "${orqixLoop.again(execution, 't8', 2)}", "${orqixLoop.traversed(execution, 't8')}",
                "${orqixLoop.entered(execution, 'manager')}", "${orqixLoop.entered(execution, 'finance')}",
                "id=\"t12\" sourceRef=\"t8__loop\" targetRef=\"notifyRejected\"");
        // Steps after the loop are visited once and carry no counter.
        assertThat(xml).doesNotContain("${orqixLoop.entered(execution, 'procure')}");
    }

    @Test
    void workflowsWithoutLoopsGenerateTheSameBpmnAsBefore() throws Exception {
        assertThat(bpmn(TestSupport.purchase())).doesNotContain("orqixLoop", "orqixForEach", "__loop");
    }

    @Test
    void theSendBackPathRoundTripsThroughBpmn() throws Exception {
        assertRoundTrip(sendBack(3));
    }

    // ---------------------------------------------------------------- CG-E01 simulator

    @Test
    void theSimulatorSendsBackUntilTheLimitThenTakesTheLimitPath() {
        String input = "{\"title\":\"Server\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":9000}";
        JsonNode r = SimulatorFixtures.run(sendBack(2).toString(), input,
                "{\"approvals\":{\"finance\":{\"outcome\":\"reject\",\"reason\":\"Too expensive\"}}}");
        List<String> steps = new ArrayList<>();
        r.get("trace").forEach(e -> steps.add(e.get("stepId").stringValue()
                + (e.has("occurrence") ? "#" + e.get("occurrence").intValue() : "")));
        assertThat(steps).containsSubsequence("manager", "finance", "finance", "manager#2", "finance#2", "finance#2",
                "manager#3", "finance#3", "finance#3", "notifyRejected", "rejected");
        assertThat(r.path("outcome").path("businessOutcome").asString()).isEqualTo("Rejected");
    }

    @Test
    void theSimulatorUsesOneApprovalOutcomePerVisit() {
        String input = "{\"title\":\"Server\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":9000}";
        JsonNode r = SimulatorFixtures.run(sendBack(2).toString(), input,
                "{\"approvals\":{\"finance\":{\"outcomes\":[\"reject\",\"approve\"],\"reason\":\"Fix the quote\"}}}");
        assertThat(r.path("outcome").path("businessOutcome").asString()).isEqualTo("Fulfilled");
        long financeVisits = 0;
        for (JsonNode e : r.get("trace")) {
            if (e.get("stepId").stringValue().equals("finance") && e.path("details").path("maxTraversals").isMissingNode()) {
                financeVisits++;
            }
        }
        assertThat(financeVisits).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CG-E06 compiler rules

    @Test
    void aCallCanRunForEachRowOfAListOfUpTo500() {
        assertThat(blockers(perLine(30, ""))).isEmpty();
        assertThat(blockers(perLine(500, ",\"mode\":\"parallel\",\"batchSize\":10"))).isEmpty();
        assertThat(validate(perLine(501, "")).publishable()).isFalse();
    }

    @Test
    void theLimitMustCoverTheList() {
        ObjectNode d = perLine(30, "");
        for (JsonNode n : d.get("nodes")) {
            if (n.get("id").stringValue().equals("procure")) {
                ((ObjectNode) n.get("config").get("forEach")).put("maxItems", 20);
            }
        }
        assertThat(blockers(d)).contains("WF_FOR_EACH_BOUND");
    }

    @Test
    void itemsAreTypeCheckedLikeAnyInput() {
        ObjectNode d = perLine(30, "");
        for (JsonNode n : d.get("nodes")) {
            if (n.get("id").stringValue().equals("procure")) {
                ((ObjectNode) n.get("config").get("forEach").get("itemMapping")).put("amount", "title");
            }
        }
        assertThat(blockers(d)).contains("WF_MAPPING_TYPE");
        ObjectNode unknown = perLine(30, "");
        for (JsonNode n : unknown.get("nodes")) {
            if (n.get("id").stringValue().equals("procure")) {
                ((ObjectNode) n.get("config").get("forEach").get("itemMapping")).put("amount", "price");
            }
        }
        assertThat(blockers(unknown)).contains("WF_FIELDREF_UNKNOWN");
    }

    @Test
    void completionNeedsAConsistentCount() {
        assertThat(blockers(perLine(30, ",\"completion\":{\"when\":\"atLeast\",\"count\":3}"))).isEmpty();
        assertThat(blockers(perLine(30, ",\"completion\":{\"when\":\"atLeast\"}"))).contains("WF_FOR_EACH_COMPLETION");
        assertThat(blockers(perLine(30, ",\"completion\":{\"when\":\"atLeast\",\"count\":31}")))
                .contains("WF_FOR_EACH_COMPLETION");
        assertThat(blockers(perLine(30, ",\"completion\":{\"when\":\"firstSuccess\"}"))).isEmpty();
    }

    @Test
    void onlyCollectedResultsCanBeUsedAfterTheStep() {
        ObjectNode d = perLine(30, "");
        ((ObjectNode) d.get("outputMapping")).set("procurementId",
                StrictJson.parse("{\"fieldRef\":\"/steps/procure/procurementId\",\"required\":false}"));
        assertThat(blockers(d)).contains("WF_FIELDREF_UNKNOWN");
        ObjectNode counts = perLine(30, "");
        ((ObjectNode) counts.path("outputSchema").path("properties")).set("orderCount",
                StrictJson.parse("{\"type\":\"integer\",\"title\":\"Orders\"}"));
        ((ObjectNode) counts.get("outputMapping")).set("orderCount",
                StrictJson.parse("{\"fieldRef\":\"/steps/procure/completedCount\",\"required\":false}"));
        assertThat(blockers(counts)).isEmpty();
    }

    @Test
    void forEachIsRefusedOnAPollingCall() {
        ObjectNode d = perLine(30, "");
        ObjectNode bad = (ObjectNode) StrictJson.parse(d.toString());
        for (JsonNode n : bad.get("nodes")) {
            if (n.get("id").stringValue().equals("procure")) {
                ((ObjectNode) n.get("config").get("forEach")).put("itemsRef", "/input/title");
            }
        }
        assertThat(blockers(bad)).contains("WF_FOR_EACH_LIST");
    }

    // ---------------------------------------------------------------- CG-E06 generated BPMN and round trip

    @Test
    void sequentialItemsUseMultiInstanceOnTheStepItself() throws Exception {
        String xml = bpmn(perLine(30, ",\"completion\":{\"when\":\"firstSuccess\"}"));
        assertThat(xml).contains("<multiInstanceLoopCharacteristics isSequential=\"true\"",
                "flowable:collection=\"${orqixForEach.items(execution, 'procure')}\"",
                "flowable:elementVariable=\"orqix_forEachItem\"", "${orqixForEach.done(execution, 'procure')}",
                "fieldRef=\"/input/lines/*/amount\"", "<orqix:forEach itemsRef=\"/input/lines\"",
                "${orqixForEach.collect(execution, 'procure')}");
        assertThat(xml).doesNotContain("procure__batches");
    }

    @Test
    void parallelItemsAboveTheBatchSizeRunInBatches() throws Exception {
        String xml = bpmn(perLine(100, ",\"mode\":\"parallel\",\"batchSize\":20"));
        assertThat(xml).contains("<subProcess id=\"procure__batches\"",
                "${orqixForEach.batches(execution, 'procure')}", "batchSize=\"20\"",
                "sourceRef=\"procure__batches\"", "targetRef=\"procure__batches\"");
    }

    @Test
    void forEachRoundTripsThroughBpmn() throws Exception {
        assertRoundTrip(perLine(30, ",\"completion\":{\"when\":\"atLeast\",\"count\":2}"));
        assertRoundTrip(perLine(100, ",\"mode\":\"parallel\",\"batchSize\":20"));
    }

    // ---------------------------------------------------------------- CG-E06 simulator

    @Test
    void theSimulatorRunsTheCallOncePerItemAndCollectsResults() {
        String input = "{\"title\":\"Office\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":900,\"lines\":["
                + "{\"title\":\"Desk\",\"amount\":300},{\"title\":\"Chair\",\"amount\":200},{\"title\":\"Lamp\",\"amount\":40}]}";
        JsonNode r = SimulatorFixtures.run(perLine(30, "").toString(), input, null);
        int calls = 0;
        JsonNode summary = null;
        for (JsonNode e : r.get("trace")) {
            if (e.get("stepId").stringValue().equals("procure")) {
                if (e.has("itemIndex")) {
                    calls++;
                } else {
                    summary = e;
                }
            }
        }
        assertThat(calls).isEqualTo(3);
        assertThat(summary).isNotNull();
        assertThat(summary.path("wrote").path("itemCount").intValue()).isEqualTo(3);
        assertThat(summary.path("wrote").path("orderIds").size()).isEqualTo(3);
        assertThat(r.path("outcome").path("businessOutcome").asString()).isEqualTo("Fulfilled");
    }

    @Test
    void theSimulatorStopsAtTheFirstSuccess() {
        String input = "{\"title\":\"Office\",\"purpose\":\"p\",\"costCenter\":\"IT\",\"amount\":900,\"lines\":["
                + "{\"title\":\"Desk\",\"amount\":300},{\"title\":\"Chair\",\"amount\":200}]}";
        JsonNode r = SimulatorFixtures.run(perLine(30, ",\"completion\":{\"when\":\"firstSuccess\"}").toString(),
                input, null);
        JsonNode summary = null;
        for (JsonNode e : r.get("trace")) {
            if (e.get("stepId").stringValue().equals("procure") && !e.has("itemIndex")) {
                summary = e;
            }
        }
        assertThat(summary.path("wrote").path("completedCount").intValue()).isEqualTo(1);
        assertThat(summary.path("wrote").path("stoppedEarly").booleanValue()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private static void assertRoundTrip(ObjectNode d) throws Exception {
        String json = d.toString();
        CompiledWorkflow w = COMPILER.compile(json, BpmnSourceTest.context(json));
        BpmnSource.Export export = BpmnSource.export(json, w);
        assertThat(export.differences()).as("round trip").isEmpty();
        assertThat(export.identical()).isTrue();
        BpmnSource.Check again = BpmnSource.check(export.bpmnXml(), json, COMPILER, BpmnSourceTest::context);
        assertThat(again.applicable()).as(again.problems().toString()).isTrue();
        assertThat(CanonicalJson.canonicalize(again.definitionJson())).isEqualTo(CanonicalJson.canonicalize(json));
    }
}
