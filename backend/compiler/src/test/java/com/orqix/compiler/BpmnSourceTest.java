package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * UI03 BPMN source (docs/build/S56): the compiled BPMN of a draft reads back into the same document, edited BPMN is
 * accepted only when the document can represent it exactly, and every refusal names the element and the difference.
 */
class BpmnSourceTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    private static CompiledWorkflow compile(String json) throws CompilationException {
        return COMPILER.compile(json, context(json));
    }

    private static BpmnSource.Check check(String xml, String base) {
        return BpmnSource.check(xml, base, COMPILER, BpmnSourceTest::context);
    }

    /**
     * The onboarding workspace plus, for the given document, its groups, every example connection version and every
     * example workflow as a published child, so that all examples can compile.
     */
    static CompileContext context(String json) {
        CompileContext base = TestSupport.onboardingContext();
        Map<String, CompileContext.GroupInfo> groups = new java.util.LinkedHashMap<>(base.groups());
        JsonNode doc = StrictJson.parse(json);
        for (JsonNode n : doc.path("nodes")) {
            JsonNode c = n.path("config");
            List<String> keys = new ArrayList<>();
            if (c.path("candidateGroupRef").isString()) {
                keys.add(c.path("candidateGroupRef").stringValue());
            }
            c.path("requiredSkills").forEach(k -> keys.add(k.stringValue()));
            keys.forEach(k -> groups.putIfAbsent(k, new CompileContext.GroupInfo(k, k, 3)));
        }
        if (doc.path("nodes").toString().contains("\"candidateGroupRef\":\"nobody-group\"")) {
            groups.remove("nobody-group");
        }
        List<CompileContext.ConnectionVersion> versions = new ArrayList<>();
        java.util.Set<String> enabled = new java.util.HashSet<>();
        Map<String, CompileContext.WorkflowInfo> children = new java.util.LinkedHashMap<>(base.workflows());
        try (Stream<Path> files = Files.list(TestSupport.repoRoot().resolve("examples/connections"))) {
            for (Path f : files.sorted().toList()) {
                String text = Files.readString(f);
                JsonNode def = StrictJson.parse(text);
                versions.add(new CompileContext.ConnectionVersion(def.get("key").stringValue(),
                        def.get("version").intValue(), text, CanonicalJson.sha256Hex(text)));
                enabled.add(def.get("key").stringValue());
            }
            try (Stream<Path> examples = Files.list(TestSupport.repoRoot().resolve("examples"))) {
                for (Path f : examples.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    String text = Files.readString(f);
                    JsonNode def = StrictJson.parse(text);
                    String key = def.path("workflowKey").stringValue();
                    children.putIfAbsent(key, CompileContext.WorkflowInfo.of(key, def.path("name").stringValue(),
                            "00000000-0000-7000-8000-0000000000b1", 1, "0".repeat(64), text, java.util.Set.of(), 0));
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new CompileContext(base.selfApprovalAllowed(), groups, versions, enabled, children);
    }

    private static String purchaseBpmn() throws CompilationException {
        return compile(TestSupport.purchaseJson()).bpmnXml();
    }

    private static List<String> kinds(BpmnSource.Check c) {
        return c.problems().stream().map(BpmnSource.Problem::kind).distinct().toList();
    }

    private static String messages(BpmnSource.Check c) {
        return String.join("\n", c.problems().stream().map(BpmnSource.Problem::message).toList());
    }

    // ---------------------------------------------------------------- round trips of real documents

    /** Every example and compiler fixture that compiles either round-trips exactly or reports why; never silently. */
    @Test
    void examplesRoundTripOrReportTheExactDifference() throws Exception {
        Map<String, String> documents = new TreeMap<>();
        try (Stream<Path> files = Files.list(TestSupport.repoRoot().resolve("examples"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                documents.put(f.getFileName().toString(), Files.readString(f));
            }
        }
        JsonNode cases = TestSupport.json("examples/fixtures/compiler-cases.json");
        for (JsonNode c : cases.get("cases")) {
            if (c.get("expectBlockers").isEmpty()) {
                documents.put("case:" + c.get("name").stringValue(),
                        TestSupport.text(TestSupport.patch(TestSupport.json(cases.get("base").stringValue()),
                                c.get("patch"))));
            }
        }
        List<String> identical = new ArrayList<>();
        Map<String, List<String>> differing = new TreeMap<>();
        int compiled = 0;
        for (Map.Entry<String, String> doc : documents.entrySet()) {
            CompiledWorkflow w;
            try {
                w = compile(doc.getValue());
            } catch (CompilationException e) {
                System.out.println("[bpmn-source] not compiled (blockers): " + doc.getKey());
                continue;
            }
            compiled++;
            BpmnSource.Export export = BpmnSource.export(doc.getValue(), w);
            BpmnSource.Check again = check(export.bpmnXml(), doc.getValue());
            System.out.println("[bpmn-source] " + doc.getKey() + ": " + (export.identical() ? "identical"
                    : "differs: " + export.differences().stream().map(BpmnSource.Difference::path).toList()));
            if (export.identical()) {
                identical.add(doc.getKey());
                assertThat(again.applicable()).as(doc.getKey() + "\n" + messages(again)).isTrue();
                assertThat(again.changes()).as(doc.getKey()).isEmpty();
                assertThat(again.notes()).as(doc.getKey()).isEmpty();
                assertThat(CanonicalJson.canonicalize(again.definitionJson()))
                        .as(doc.getKey()).isEqualTo(CanonicalJson.canonicalize(doc.getValue()));
                // Applying the unchanged BPMN produces byte-identical BPMN again.
                assertThat(compile(again.definitionJson()).bpmnXml()).isEqualTo(export.bpmnXml());
            } else {
                differing.put(doc.getKey(), export.differences().stream().map(BpmnSource.Difference::path).toList());
                assertThat(export.differences()).as(doc.getKey()).isNotEmpty()
                        .allSatisfy(d -> assertThat(d.message()).contains("after the BPMN round trip"));
                assertThat(again.applicable()).as(doc.getKey()).isFalse();
                assertThat(kinds(again)).containsExactly("draftRoundTrip");
            }
        }
        assertThat(compiled).isGreaterThanOrEqualTo(20);
        assertThat(identical).contains("purchase-approval.json", "new-hire-onboarding.json",
                "banking-kyc-onboarding.json", "vendor-onboarding-check.json", "document-review.json", "case:valid-base");
        // Settings the compiled BPMN does not carry: each is reported with its exact place in the document.
        assertThat(differing).containsEntry("banking-card-claim.json",
                List.of("/nodes/1/config/collectionMapping", "/nodes/1/config/forEach"));
        assertThat(differing).containsEntry("banking-credit-approval.json", List.of("/nodes/3/config/repeat"));
        assertThat(differing).containsEntry("order-payment-wait.json",
                List.of("/nodes/1/config/payload/0/title", "/nodes/1/config/payload/1/title"));
        assertThat(differing).containsEntry("start-date-onboarding.json", List.of("/nodes/2/config/atTime"));
    }

    @Test
    void notCarriedApprovalSettingsAreReportedAndBlockApply() throws Exception {
        ObjectNode doc = TestSupport.purchase();
        ObjectNode finance = node(doc, "finance");
        ((ObjectNode) finance.get("config")).putArray("separateFrom").add("manager");
        String json = TestSupport.text(doc);
        BpmnSource.Export export = BpmnSource.export(json, compile(json));
        assertThat(export.identical()).isFalse();
        assertThat(export.differences()).extracting(BpmnSource.Difference::path)
                .anySatisfy(p -> assertThat(p).endsWith("/config/separateFrom"));
        assertThat(export.differences().get(0).message()).contains("Step 'finance'").contains("separateFrom");
        BpmnSource.Check c = check(export.bpmnXml(), json);
        assertThat(c.applicable()).isFalse();
        assertThat(messages(c)).contains("separateFrom");
    }

    @Test
    void keptSectionsAreListed() throws Exception {
        BpmnSource.Export export = BpmnSource.export(TestSupport.purchaseJson(), compile(TestSupport.purchaseJson()));
        assertThat(export.diagram()).isTrue();
        assertThat(export.keptFromDraft()).contains("inputSchema", "decisions", "execution", "security", "dependencies",
                "outputMapping").doesNotContain("nodes", "transitions", "name", "uiLayout.positions");
        ObjectNode noLayout = TestSupport.purchase();
        noLayout.remove("uiLayout");
        assertThat(BpmnSource.keptFromDraft(TestSupport.text(noLayout), false)).doesNotContain("uiLayout.positions");
        ObjectNode partial = TestSupport.purchase();
        ((ObjectNode) partial.get("uiLayout").get("positions")).remove("start");
        String partialJson = TestSupport.text(partial);
        BpmnSource.Export noDiagram = BpmnSource.export(partialJson, compile(partialJson));
        assertThat(noDiagram.diagram()).isFalse();
        assertThat(noDiagram.identical()).isTrue();
        assertThat(noDiagram.keptFromDraft()).contains("uiLayout.positions");
    }

    // ---------------------------------------------------------------- accepted edits

    @Test
    void renamingAStepIsAppliedWithTheChangeListedAndHashesRecomputed() throws Exception {
        String xml = purchaseBpmn().replace("name=\"Review request\"", "name=\"Manager review\"");
        BpmnSource.Check c = check(xml, TestSupport.purchaseJson());
        assertThat(c.applicable()).as(messages(c)).isTrue();
        assertThat(c.changes()).extracting(BpmnSource.Change::what).containsExactly("Step 'manager' (Manager review)");
        assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("release hash"));
        JsonNode applied = StrictJson.parse(c.definitionJson());
        assertThat(node((ObjectNode) applied, "manager").get("label").stringValue()).isEqualTo("Manager review");
        assertThat(applied.get("inputSchema")).isEqualTo(TestSupport.purchase().get("inputSchema"));
    }

    @Test
    void movingAShapeChangesTheCanvasPositionAndRecomputesLines() throws Exception {
        String xml = purchaseBpmn();
        String shape = "bpmnElement=\"notify\">";
        int at = xml.indexOf(shape);
        int bounds = xml.indexOf("x=\"", at);
        int end = xml.indexOf('"', bounds + 3);
        String moved = xml.substring(0, bounds + 3) + "1234" + xml.substring(end);
        BpmnSource.Check c = check(moved, TestSupport.purchaseJson());
        assertThat(c.applicable()).as(messages(c)).isTrue();
        assertThat(c.changes()).extracting(BpmnSource.Change::what).containsExactly("Canvas position of 1 step");
        assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("Diagram lines"));
        JsonNode x = StrictJson.parse(c.definitionJson()).path("uiLayout").path("positions").path("notify").path("x");
        assertThat(x.decimalValue()).isEqualByComparingTo("1174"); // 1234 + 50 (half shape) - 110 (half card)
    }

    @Test
    void addingAStepAndRewiringIsAppliedWhenTheDocumentCanExpressIt() throws Exception {
        String xml = purchaseBpmn()
                .replace("<sequenceFlow id=\"t9\" sourceRef=\"procure\" targetRef=\"notify\"/>",
                        "<sequenceFlow id=\"t9\" sourceRef=\"procure\" targetRef=\"pause\"/>\n"
                                + "    <sequenceFlow id=\"t99\" sourceRef=\"pause\" targetRef=\"notify\"/>")
                .replace("<endEvent id=\"fulfilled\"",
                        "<intermediateCatchEvent id=\"pause\" name=\"Cool-off\"><extensionElements>"
                                + "<orqix:wait duration=\"PT2H\"/></extensionElements><timerEventDefinition>"
                                + "<timeDuration>PT2H</timeDuration></timerEventDefinition></intermediateCatchEvent>\n"
                                + "    <endEvent id=\"fulfilled\"")
                .replace("</bpmndi:BPMNPlane>", "<bpmndi:BPMNShape id=\"pause__di\" bpmnElement=\"pause\">"
                        + "<omgdc:Bounds x=\"900\" y=\"500\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>"
                        + "</bpmndi:BPMNPlane>");
        BpmnSource.Check c = check(xml, TestSupport.purchaseJson());
        assertThat(c.applicable()).as(messages(c)).isTrue();
        assertThat(c.changes()).extracting(BpmnSource.Change::what).contains("Step 'pause' (Cool-off)",
                "Connection 't99' (pause to notify, next)", "Connection 't9' (procure to pause, next)");
        assertThat(c.changes()).extracting(BpmnSource.Change::kind).doesNotContain("removed");
        ObjectNode applied = (ObjectNode) StrictJson.parse(c.definitionJson());
        assertThat(node(applied, "pause").get("config").toString()).isEqualTo("{\"duration\":\"PT2H\"}");
    }

    @Test
    void elementOrderAndPrefixesDoNotMatter() throws Exception {
        String xml = purchaseBpmn().replace("xmlns:orqix=", "xmlns:o2=").replaceAll("orqix:", "o2:");
        BpmnSource.Check c = check(xml, TestSupport.purchaseJson());
        assertThat(c.applicable()).as(messages(c)).isTrue();
        assertThat(c.changes()).isEmpty();
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void unsupportedConstructsAreNamedNeverDropped() throws Exception {
        String base = TestSupport.purchaseJson();
        String xml = purchaseBpmn();
        assertThat(problem(check(xml.replace("<startEvent", "<scriptTask id=\"s1\" name=\"Script\"/>\n    <startEvent"),
                base), "unsupported", "s1")).contains("bpmn:scriptTask is not an Orqix step type");
        assertThat(problem(check(xml.replace("<startEvent", "<laneSet id=\"ls\"><lane id=\"l1\" name=\"Managers\"/>"
                + "</laneSet>\n    <startEvent"), base), "unsupported", "ls")).contains("Lanes");
        assertThat(problem(check(xml.replace("<startEvent", "<!-- note -->\n    <startEvent"), base), "unsupported",
                null)).contains("XML comment");
        assertThat(problem(check(xml.replace("<startEvent", "<subProcess id=\"sub\" name=\"Sub\"/><startEvent"), base),
                "unsupported", "sub")).contains("Sub-processes");
        assertThat(problem(check(xml.replace("<definitions ", "<definitions xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" ")
                .replace("<userTask id=\"manager\"", "<userTask id=\"manager\" camunda:assignee=\"bob\""), base),
                "notRepresentable", "manager")).contains("assignee").contains("would be lost");
        assertThat(problem(check(xml.replace("flowable:async=\"true\" flowable:exclusive=\"true\" "
                + "flowable:delegateExpression=\"${orqixHttpTask}\"", "flowable:exclusive=\"true\" "
                + "flowable:delegateExpression=\"${orqixHttpTask}\""), base), "notRepresentable", "procure"))
                .contains("flowable:async").contains("missing");
        assertThat(problem(check(xml.replace("${orqixRoute.condition(execution, 't5')}", "${amount > 5000}"), base),
                "unsupported", "t5")).contains("free expressions");
        assertThat(problem(check(xml.replace("flowable:delegateExpression=\"${orqixNotificationTask}\"",
                "flowable:class=\"com.example.Notify\""), base), "unsupported", "notify")).contains("not an Orqix step");
    }

    @Test
    void unsafeOrBrokenXmlIsRefusedBeforeReading() throws Exception {
        String base = TestSupport.purchaseJson();
        assertThat(problem(check(TestSupport.read("examples/migration/xxe-attack.bpmn"), base), "xml", null))
                .contains("DOCTYPE");
        assertThat(problem(check("<definitions", base), "xml", null)).contains("not well-formed");
        assertThat(problem(check(" ", base), "xml", null)).contains("empty");
        assertThat(problem(check("<x xmlns=\"urn:other\"/>", base), "unsupported", null)).contains("bpmn:definitions");
    }

    @Test
    void valuesOwnedByOtherSectionsCannotBeEditedInBpmn() throws Exception {
        String base = TestSupport.purchaseJson();
        String xml = purchaseBpmn();
        assertThat(problem(check(xml.replace("connectionVersion=\"1\"", "connectionVersion=\"2\""), base),
                "otherSection", "procure")).contains("connection dependencies").contains("must be '1'");
        assertThat(problem(check(xml.replace("R3/PT30S", "R9/PT30S"), base), "otherSection", "procure"))
                .contains("execution settings");
        assertThat(problem(check(xml.replace("resource=\"decisions/amount-policy.dmn\"", "resource=\"x.dmn\""), base),
                "otherSection", "amountPolicy")).contains("decision table");
    }

    @Test
    void identityAndBlockersRefuse() throws Exception {
        String base = TestSupport.purchaseJson();
        String xml = purchaseBpmn();
        assertThat(problem(check(xml.replace("process id=\"purchase-approval\"", "process id=\"other\""), base),
                "identity", "other")).contains("cannot be changed");
        BpmnSource.Check blocked = check(xml.replace("flowable:candidateGroups=\"managers\"",
                "flowable:candidateGroups=\"nobody-group\""), base);
        assertThat(blocked.applicable()).isFalse();
        assertThat(blocked.blockers()).isNotEmpty();
        assertThat(kinds(blocked)).containsExactly("blocker");
        assertThat(blocked.changes()).extracting(BpmnSource.Change::what).contains("Step 'manager' (Review request)");
    }

    @Test
    void diagramMustCoverEveryStepOrBeLeftOut() throws Exception {
        String base = TestSupport.purchaseJson();
        String xml = purchaseBpmn();
        int start = xml.indexOf("<bpmndi:BPMNShape id=\"notify__di\"");
        int end = xml.indexOf("</bpmndi:BPMNShape>", start) + "</bpmndi:BPMNShape>".length();
        assertThat(problem(check(xml.substring(0, start) + xml.substring(end), base), "unsupported", "notify"))
                .contains("has no shape");
        int d0 = xml.indexOf("  <bpmndi:BPMNDiagram");
        int d1 = xml.indexOf("</bpmndi:BPMNDiagram>") + "</bpmndi:BPMNDiagram>".length();
        BpmnSource.Check withoutDiagram = check(xml.substring(0, d0) + xml.substring(d1), base);
        assertThat(withoutDiagram.applicable()).isFalse();
        assertThat(kinds(withoutDiagram)).containsExactly("notRepresentable");
        assertThat(messages(withoutDiagram)).contains("missing");
    }

    @Test
    void draftsWithoutExactBpmnOrWithBlockersCannotTakeBpmn() throws Exception {
        ObjectNode broken = TestSupport.purchase();
        ((ObjectNode) node(broken, "manager").get("config")).put("candidateGroupRef", "nobody-group");
        BpmnSource.Check c = check(purchaseBpmn(), TestSupport.text(broken));
        assertThat(kinds(c)).containsExactly("draftRoundTrip");
        assertThat(messages(c)).contains("blocking issues");
    }

    @Test
    void parallelJoinsAreFoundAgainAndHelpersAreChecked() throws Exception {
        String onboarding = TestSupport.onboardingJson();
        String xml = compile(onboarding).bpmnXml();
        BpmnSource.Check c = check(xml, onboarding);
        assertThat(c.applicable()).as(messages(c)).isTrue();
        assertThat(problem(check(xml.replace("<startEvent", "<exclusiveGateway id=\"ghost__route\" name=\"x\"/>"
                + "<startEvent"), onboarding), "unsupported", "ghost__route")).contains("reserved '__'");
    }

    // ---------------------------------------------------------------- helpers

    private static String problem(BpmnSource.Check c, String kind, String elementId) {
        assertThat(c.applicable()).isFalse();
        assertThat(c.definitionJson()).isNull();
        BpmnSource.Problem found = c.problems().stream()
                .filter(p -> p.kind().equals(kind) && (elementId == null || elementId.equals(p.elementId())))
                .findFirst().orElse(null);
        assertThat(found).as("problem " + kind + "/" + elementId + " in\n" + c.problems()).isNotNull();
        return found.message();
    }

    private static ObjectNode node(ObjectNode doc, String id) {
        for (JsonNode n : (ArrayNode) doc.get("nodes")) {
            if (id.equals(n.get("id").stringValue())) {
                return (ObjectNode) n;
            }
        }
        throw new IllegalArgumentException(id);
    }
}
