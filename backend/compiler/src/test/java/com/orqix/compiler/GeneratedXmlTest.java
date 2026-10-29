package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.orqix.compiler.internal.bpmn.BpmnGenerator;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/** Part of T-SEC-06 and T-COMP-03: generated XML is well formed, uses only allowlisted expressions, IDs line up. */
class GeneratedXmlTest {

    private static final Pattern ANY_EXPRESSION = Pattern.compile("[$#]\\{");
    private static final Pattern ALLOWED = Pattern.compile(
            "\\$\\{orqixRoute\\.(approved|rejected|condition)\\(execution, '[A-Za-z][A-Za-z0-9_-]{0,63}'\\)\\}"
                    + "|\\$\\{orqixDecisionTask\\}|\\$\\{orqixHttpTask\\}|\\$\\{orqixNotificationTask\\}"
                    + "|\\$\\{orqixChildTask\\}|\\$\\{orqixCompensationTask\\}"
                    + "|\\$\\{orqixRoute\\.(exited|markExit)\\(execution, '[A-Za-z][A-Za-z0-9_-]{0,63}',"
                    + " '[A-Za-z][A-Za-z0-9_-]{0,63}'\\)\\}"
                    + "|\\$\\{orqixWait\\.until\\(execution, '[A-Za-z][A-Za-z0-9_-]{0,63}'\\)\\}");

    static String everythingJson() throws IOException {
        try (InputStream in = GeneratedXmlTest.class.getResourceAsStream("/workflows/everything.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<CompiledWorkflow> samples() throws Exception {
        List<CompiledWorkflow> list = new ArrayList<>();
        list.add(Compilers.workflowCompiler().compile(TestSupport.purchaseJson(), TestSupport.defaultContext()));
        list.add(Compilers.workflowCompiler().compile(everythingJson(), TestSupport.defaultContext()));
        list.add(Compilers.workflowCompiler().compile(TestSupport.onboardingJson(), TestSupport.onboardingContext()));
        list.add(Compilers.workflowCompiler().compile(TestSupport.read("examples/start-date-onboarding.json"),
                TestSupport.defaultContext()));
        // Sections, handlers, errors, signals and automatic undo (S59).
        for (var scoped : List.of(ScopesAndEventsTest.section(), ScopesAndEventsTest.handlers(), ScopesAndEventsTest.undo())) {
            list.add(Compilers.workflowCompiler().compile(scoped.toString(), SimulatorFixtures.context().workspace()));
        }
        for (CompilerCasesTest.Case c : CompilerCasesTest.cases()) {
            if (Compilers.workflowCompiler().validate(c.definition(), c.context()).publishable()) {
                list.add(Compilers.workflowCompiler().compile(c.definition(), c.context()));
            }
        }
        return list;
    }

    @TestFactory
    Stream<DynamicTest> everyCompiledSampleIsSafe() throws Exception {
        List<CompiledWorkflow> samples = samples();
        assertThat(samples).hasSizeGreaterThanOrEqualTo(4);
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            CompiledWorkflow w = samples.get(i);
            tests.add(DynamicTest.dynamicTest("sample " + i + " " + w.processId(), () -> check(w)));
        }
        return tests.stream();
    }

    private static void check(CompiledWorkflow w) throws Exception {
        onlyAllowedExpressions(w.bpmnXml());
        Document bpmn = parse(w.bpmnXml());
        assertThat(bpmn.getDocumentElement().getNamespaceURI()).isEqualTo(BpmnGenerator.BPMN_NS);
        assertThat(w.bpmnXml()).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<definitions ")
                .endsWith("</definitions>\n").doesNotContain("\r").doesNotContain("\t");
        Set<String> ids = new HashSet<>();
        NodeList all = bpmn.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.hasAttribute("id")) {
                assertThat(ids.add(e.getAttribute("id"))).as("unique id %s", e.getAttribute("id")).isTrue();
            }
        }
        JsonNode map = StrictJson.parse(w.sourceMapJson());
        for (String section : List.of("nodes", "transitions")) {
            for (Map.Entry<String, JsonNode> e : map.get(section).properties()) {
                for (JsonNode bpmnId : e.getValue()) {
                    assertThat(ids).as("%s %s", section, bpmnId).contains(bpmnId.stringValue());
                    assertThat(map.get("bpmnToCanonical").get(bpmnId.stringValue()).stringValue())
                            .isEqualTo(e.getKey());
                }
            }
        }
        NodeList process = bpmn.getElementsByTagNameNS(BpmnGenerator.BPMN_NS, "process");
        NodeList flowElements = ((Element) process.item(0)).getChildNodes();
        for (int i = 0; i < flowElements.getLength(); i++) {
            if (flowElements.item(i) instanceof Element e && e.hasAttribute("id")) {
                assertThat(map.get("bpmnToCanonical").has(e.getAttribute("id"))).as(e.getAttribute("id")).isTrue();
            }
        }
        for (Map.Entry<String, String> dmn : w.dmnResources().entrySet()) {
            assertThat(dmn.getKey()).matches("^decisions/[a-z][a-z0-9-]*\\.dmn$");
            Document d = parse(dmn.getValue());
            assertThat(d.getDocumentElement().getNamespaceURI())
                    .isEqualTo("https://www.omg.org/spec/DMN/20191111/MODEL/");
            assertThat(ANY_EXPRESSION.matcher(dmn.getValue()).find()).isFalse();
            assertThat(d.getElementsByTagNameNS("*", "decisionTable").item(0).getAttributes()
                    .getNamedItem("hitPolicy").getNodeValue()).isEqualTo("FIRST");
        }
    }

    static void onlyAllowedExpressions(String xml) {
        Matcher any = ANY_EXPRESSION.matcher(xml);
        int count = 0;
        while (any.find()) {
            count++;
            Matcher allowed = ALLOWED.matcher(xml);
            assertThat(allowed.find(any.start()) && allowed.start() == any.start())
                    .as("expression at %d: %s", any.start(), xml.substring(any.start(),
                            Math.min(xml.length(), any.start() + 60)))
                    .isTrue();
        }
        assertThat(count).isPositive();
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void userTextIsEscapedAndNeverInsideExpressions() throws Exception {
        CompiledWorkflow w = Compilers.workflowCompiler().compile(everythingJson(), TestSupport.defaultContext());
        Document bpmn = parse(w.bpmnXml());
        Element process = (Element) bpmn.getElementsByTagNameNS(BpmnGenerator.BPMN_NS, "process").item(0);
        assertThat(process.getAttribute("name")).isEqualTo("Everything & more <test> \"quoted\"");
        Element manager = byId(bpmn, "manager");
        assertThat(manager.getAttribute("name")).isEqualTo("Prüfung ✓ \"manager\" & co");
        assertThat(byId(bpmn, "manager__route").getAttribute("name")).isEqualTo("Prüfung ✓ \"manager\" & co: outcome");
        assertThat(byId(bpmn, "t5").getAttribute("name")).isEqualTo("Escalated fast lane");
        Element message = (Element) byId(bpmn, "notifyDone").getElementsByTagNameNS(BpmnGenerator.ORQIX_NS,
                "notification").item(0);
        assertThat(message.getAttribute("message")).isEqualTo("Done: it's <ok> & \"fine\" second line");
        assertThat(w.bpmnXml()).contains("<flowable:failedJobRetryTimeCycle>R1/PT5S</flowable:failedJobRetryTimeCycle>")
                .contains("<orqix:condition fieldRef=\"/steps/risk/escalate\" operator=\"eq\" valueType=\"boolean\""
                        + " value=\"true\"/>")
                .contains("<orqix:condition fieldRef=\"/input/quantity\" operator=\"lt\" valueType=\"number\""
                        + " value=\"10\"/>")
                .contains("<orqix:condition fieldRef=\"/steps/risk/level\" operator=\"gte\" valueType=\"number\""
                        + " value=\"1.5\"/>")
                .contains("<exclusiveGateway id=\"size\" name=\"Large order?\" default=\"t9\"/>")
                .doesNotContain("BPMNDiagram");
        String dmn = w.dmnResources().get("decisions/risk-policy.dmn");
        assertThat(dmn).contains("id=\"dmn_risk-policy_v3\"")
                .contains("<inputExpression id=\"risk-policy__inexpr\" typeRef=\"string\"><text>in1</text>")
                .contains("<output id=\"risk-policy__out_escalate\" name=\"escalate\" typeRef=\"boolean\"/>")
                .contains("<output id=\"risk-policy__out_lane\" name=\"lane\" typeRef=\"string\"/>")
                .contains("<output id=\"risk-policy__out_level\" name=\"level\" typeRef=\"number\"/>")
                .contains("<text><![CDATA[== \"high\"]]></text>")
                .contains("<outputEntry id=\"risk-policy__r1_level\"><text><![CDATA[2.5]]></text></outputEntry>")
                .contains("<outputEntry id=\"risk-policy__r2_escalate\"><text><![CDATA[false]]></text>")
                .contains("<outputEntry id=\"risk-policy__default_level\"><text><![CDATA[0]]></text>");
        assertThat(w.warnings()).extracting(ValidationIssue::code)
                .containsExactly("WF_DECISION_DEFAULT_UNREACHABLE");
        assertThat(w.groups()).containsExactly("managers");
    }

    /** A split reached by two routes gets an exclusive merge in front, so the parallel gateway only splits (S115). */
    @Test
    void parallelSplitReachedByTwoRoutesGetsAMergeInFront() throws Exception {
        String approval = "{\"id\":\"%s\",\"type\":\"approval\",\"label\":\"%s\",\"config\":{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true}}";
        String json = "{\"schemaVersion\":\"0.1\",\"workflowKey\":\"split-merge\",\"name\":\"Split merge\",\"metadata\":{\"description\":\"x\"},"
                + "\"inputSchema\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"amount\"],\"properties\":{\"amount\":{\"type\":\"number\",\"title\":\"Amount\"}}},"
                + "\"triggers\":[\"manual\"],\"nodes\":[{\"id\":\"start\",\"type\":\"start\",\"label\":\"Start\",\"config\":{}},"
                + "{\"id\":\"choose\",\"type\":\"branch\",\"label\":\"Choose\",\"config\":{}}," + approval.formatted("review", "Review") + ","
                + "{\"id\":\"split\",\"type\":\"parallelSplit\",\"label\":\"Checks\",\"config\":{\"joinRef\":\"join\"}}," + "{\"id\":\"a1\",\"type\":\"wait\",\"label\":\"One\",\"config\":{\"duration\":\"PT1M\"}},"
                + "{\"id\":\"a2\",\"type\":\"wait\",\"label\":\"Two\",\"config\":{\"duration\":\"PT1M\"}},{\"id\":\"join\",\"type\":\"parallelJoin\",\"label\":\"Join\",\"config\":{}},"
                + "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Done\",\"outcomeKind\":\"success\"}}],"
                + "\"transitions\":[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"choose\",\"on\":\"next\"},"
                + "{\"id\":\"t2\",\"from\":\"choose\",\"to\":\"split\",\"on\":\"condition\",\"condition\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":100}},"
                + "{\"id\":\"t3\",\"from\":\"choose\",\"to\":\"review\",\"on\":\"default\"},{\"id\":\"t4\",\"from\":\"review\",\"to\":\"split\",\"on\":\"approved\"},"
                + "{\"id\":\"t5\",\"from\":\"review\",\"to\":\"end\",\"on\":\"rejected\"},{\"id\":\"t6\",\"from\":\"split\",\"to\":\"a1\",\"on\":\"next\"},"
                + "{\"id\":\"t7\",\"from\":\"split\",\"to\":\"a2\",\"on\":\"next\"},{\"id\":\"t8\",\"from\":\"a1\",\"to\":\"join\",\"on\":\"next\"},"
                + "{\"id\":\"t10\",\"from\":\"a2\",\"to\":\"join\",\"on\":\"next\"},"
                + "{\"id\":\"t12\",\"from\":\"join\",\"to\":\"end\",\"on\":\"next\"}],"
                + "\"execution\":{\"maxConcurrentRuns\":10,\"maxAttempts\":3,\"retryDelaySeconds\":30},\"security\":{\"selfApproval\":\"deny\"},\"dependencies\":{\"connections\":[]},\"decisions\":[],\"outputMapping\":{},\"outputSchema\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}}";
        assertThat(Compilers.workflowCompiler().validate(json, TestSupport.defaultContext()).issues()).filteredOn(i -> i.severity().name().equals("BLOCKER")).isEmpty();
        CompiledWorkflow w = Compilers.workflowCompiler().compile(json, TestSupport.defaultContext());
        Document bpmn = parse(w.bpmnXml());
        assertThat(byId(bpmn, "split__merge").getLocalName()).isEqualTo("exclusiveGateway");
        assertThat(byId(bpmn, "split__merge__out").getAttribute("targetRef")).isEqualTo("split");
        assertThat(byId(bpmn, "t2").getAttribute("targetRef")).isEqualTo("split__merge");
        assertThat(byId(bpmn, "t4").getAttribute("targetRef")).isEqualTo("split__merge");
        // With a layout, the merge gets its own diagram shape next to the split.
        var layout = new tools.jackson.databind.ObjectMapper().readTree(json);
        var positions = ((tools.jackson.databind.node.ObjectNode) layout).putObject("uiLayout").putObject("positions");
        int x = 0;
        for (String id : List.of("start", "choose", "review", "split", "a1", "a2", "join", "end")) positions.putObject(id).put("x", x += 240).put("y", 100);
        String diagram = Compilers.workflowCompiler().compile(layout.toString(), TestSupport.defaultContext()).bpmnXml();
        assertThat(diagram).contains("bpmnElement=\"split__merge\"").contains("bpmnElement=\"split__merge__out\"");
        // A split with one incoming route is unchanged.
        assertThat(Compilers.workflowCompiler().compile(everythingJson(), TestSupport.defaultContext()).bpmnXml()).doesNotContain("__merge\"");
    }

    private static Element byId(Document doc, String id) {
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (id.equals(e.getAttribute("id"))) {
                return e;
            }
        }
        throw new AssertionError("No element " + id);
    }
}
