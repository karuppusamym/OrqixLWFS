package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * STU04 (docs/build/S97): the downloadable BPMN has a complete diagram - a shape with Bounds for every flow node, an
 * edge with waypoints for every sequence flow - in the shared shape sizes, with the process itself unchanged.
 */
class BpmnInterchangeTest {

    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String DI = "http://www.omg.org/spec/BPMN/20100524/DI";
    private static final String DC = "http://www.omg.org/spec/DD/20100524/DC";
    private static final String OMGDI = "http://www.omg.org/spec/DD/20100524/DI";
    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final Set<String> EVENTS = Set.of("startEvent", "endEvent", "intermediateCatchEvent",
            "intermediateThrowEvent", "boundaryEvent");
    private static final Set<String> GATEWAYS = Set.of("exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway");
    private static final Set<String> TASKS = Set.of("task", "userTask", "serviceTask", "scriptTask", "sendTask",
            "receiveTask", "manualTask", "businessRuleTask", "callActivity");

    private static Document dom(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private record Box(double x, double y, double w, double h) {
    }

    /** Every example and every compiler fixture, by name. */
    private static Map<String, String> documents() throws Exception {
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
        return documents;
    }

    private static CompiledWorkflow compile(String json) throws CompilationException {
        return COMPILER.compile(json, BpmnSourceTest.context(json));
    }

    private static Set<String> allKinds() {
        Set<String> all = new HashSet<>(EVENTS);
        all.addAll(GATEWAYS);
        all.addAll(TASKS);
        all.add("subProcess");
        return all;
    }

    /** Asserts the diagram is complete and returns the shapes by element id. */
    private static Map<String, Box> assertComplete(String name, String file) throws Exception {
        Document doc = dom(file);
        Set<String> nodeIds = new HashSet<>();
        Set<String> flowIds = new HashSet<>();
        Map<String, String> kind = new HashMap<>();
        for (String local : allKinds()) {
            NodeList list = doc.getElementsByTagNameNS(BPMN, local);
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                nodeIds.add(e.getAttribute("id"));
                kind.put(e.getAttribute("id"), local);
            }
        }
        NodeList flows = doc.getElementsByTagNameNS(BPMN, "sequenceFlow");
        for (int i = 0; i < flows.getLength(); i++) {
            flowIds.add(((Element) flows.item(i)).getAttribute("id"));
        }
        assertThat(doc.getElementsByTagNameNS(DI, "BPMNDiagram").getLength()).as(name).isEqualTo(1);
        assertThat(doc.getElementsByTagNameNS(DI, "BPMNPlane").getLength()).as(name).isEqualTo(1);
        Element plane = (Element) doc.getElementsByTagNameNS(DI, "BPMNPlane").item(0);
        Element process = (Element) doc.getElementsByTagNameNS(BPMN, "process").item(0);
        assertThat(plane.getAttribute("bpmnElement")).as(name).isEqualTo(process.getAttribute("id"));

        Map<String, Box> shapes = new HashMap<>();
        NodeList shapeList = doc.getElementsByTagNameNS(DI, "BPMNShape");
        Set<String> diIds = new HashSet<>();
        for (int i = 0; i < shapeList.getLength(); i++) {
            Element s = (Element) shapeList.item(i);
            assertThat(diIds.add(s.getAttribute("id"))).as(name + " unique DI id " + s.getAttribute("id")).isTrue();
            Element b = (Element) s.getElementsByTagNameNS(DC, "Bounds").item(0);
            Box box = new Box(Double.parseDouble(b.getAttribute("x")), Double.parseDouble(b.getAttribute("y")),
                    Double.parseDouble(b.getAttribute("width")), Double.parseDouble(b.getAttribute("height")));
            assertThat(shapes.put(s.getAttribute("bpmnElement"), box)).as(name + " one shape per element").isNull();
            String local = kind.get(s.getAttribute("bpmnElement"));
            assertThat(local).as(name + " shape for a known element " + s.getAttribute("bpmnElement")).isNotNull();
            if (EVENTS.contains(local)) {
                assertThat(box.w()).as(name + " event width").isEqualTo(48);
                assertThat(box.h()).as(name + " event height").isEqualTo(48);
            } else if (GATEWAYS.contains(local)) {
                assertThat(box.w()).as(name + " gateway width").isEqualTo(64);
                assertThat(box.h()).as(name + " gateway height").isEqualTo(64);
            } else if (TASKS.contains(local)) {
                assertThat(box.w()).as(name + " task width").isEqualTo(200);
                assertThat(box.h()).as(name + " task height").isEqualTo(80);
            } else {
                assertThat(box.w()).as(name + " sub-process is as large as its content").isGreaterThan(200);
            }
        }
        assertThat(shapes.keySet()).as(name + " a shape for every flow node").isEqualTo(nodeIds);

        Set<String> edges = new HashSet<>();
        NodeList edgeList = doc.getElementsByTagNameNS(DI, "BPMNEdge");
        for (int i = 0; i < edgeList.getLength(); i++) {
            Element e = (Element) edgeList.item(i);
            assertThat(diIds.add(e.getAttribute("id"))).as(name + " unique DI id " + e.getAttribute("id")).isTrue();
            assertThat(edges.add(e.getAttribute("bpmnElement"))).as(name + " one edge per flow").isTrue();
            assertThat(e.getElementsByTagNameNS(OMGDI, "waypoint").getLength()).as(name + " waypoints")
                    .isGreaterThanOrEqualTo(2);
        }
        assertThat(edges).as(name + " an edge for every sequence flow").isEqualTo(flowIds);
        return shapes;
    }

    @Test
    void everyExampleExportsWithACompleteDiagramWhetherOrNotPositionsAreSaved() throws Exception {
        int exported = 0;
        int withSubProcess = 0;
        int withBoundary = 0;
        for (Map.Entry<String, String> doc : documents().entrySet()) {
            CompiledWorkflow compiled;
            try {
                compiled = compile(doc.getValue());
            } catch (CompilationException e) {
                continue;
            }
            String file = BpmnSource.downloadFile(compiled.bpmnXml(), doc.getValue(), "draft test revision 1");
            assertThat(file).as(doc.getKey()).startsWith("<?xml").contains("<!--")
                    .contains("Orqix compiler version: " + compiled.compilerVersion()).contains(compiled.semanticHash());
            assertComplete(doc.getKey(), file);
            // Byte-for-byte determinism.
            assertThat(BpmnSource.downloadFile(compiled.bpmnXml(), doc.getValue(), "draft test revision 1"))
                    .isEqualTo(file);
            // The same document without any saved position still gets a complete (computed) diagram.
            ObjectNode bare = (ObjectNode) com.orqix.compiler.internal.json.StrictJson.parse(doc.getValue());
            bare.remove("uiLayout");
            String bareJson = TestSupport.text(bare);
            CompiledWorkflow bareCompiled = compile(bareJson);
            String bareFile = BpmnSource.downloadFile(bareCompiled.bpmnXml(), bareJson, "draft test revision 1");
            assertComplete(doc.getKey() + " (no positions)", bareFile);
            assertThat(bareFile).contains("the left-to-right layout was computed");
            Document d = dom(file);
            if (d.getElementsByTagNameNS(BPMN, "subProcess").getLength() > 0) {
                withSubProcess++;
            }
            if (d.getElementsByTagNameNS(BPMN, "boundaryEvent").getLength() > 0) {
                withBoundary++;
            }
            exported++;
        }
        assertThat(exported).isGreaterThanOrEqualTo(20);
        assertThat(withSubProcess).isGreaterThanOrEqualTo(0);
        assertThat(withBoundary).as("examples with boundary events").isGreaterThanOrEqualTo(1);
    }

    @Test
    void theProcessIsUnchangedAndSavedPositionsAreTheTopLeftOfTheShapes() throws Exception {
        String json = TestSupport.purchaseJson();
        CompiledWorkflow compiled = compile(json);
        String file = BpmnSource.downloadFile(compiled.bpmnXml(), json, "release 1 (version 1)");
        // Everything outside the header comment and the diagram is the compiled text, byte for byte.
        String diagram = "(?s)  <bpmndi:BPMNDiagram.*?</bpmndi:BPMNDiagram>\\n";
        String stripped = file.replaceAll("(?s)<!--.*?-->\\n", "").replaceAll(diagram, "");
        String original = compiled.bpmnXml().replaceAll(diagram, "");
        assertThat(stripped).isEqualTo(original);
        assertThat(file).contains("Source: release 1 (version 1)").contains("orqix:approval")
                .contains("attributes in the Flowable namespace");
        assertThat(file).containsOnlyOnce("<!--");

        JsonNode positions = TestSupport.json("examples/purchase-approval.json").path("uiLayout").path("positions");
        assertThat(positions.size()).isGreaterThan(0);
        assertThat(file).contains("the saved canvas positions");
        Map<String, Box> shapes = assertComplete("purchase", file);
        positions.properties().forEach(en -> {
            Box b = shapes.get(en.getKey());
            assertThat(b).as(en.getKey()).isNotNull();
            assertThat(b.x()).as(en.getKey() + " x").isEqualTo(en.getValue().path("x").asDouble());
            assertThat(b.y()).as(en.getKey() + " y").isEqualTo(en.getValue().path("y").asDouble());
        });
    }

    @Test
    void oneStepWithoutAPositionComputesTheWholeLayoutLeftToRight() throws Exception {
        ObjectNode doc = (ObjectNode) TestSupport.json("examples/purchase-approval.json");
        ((ObjectNode) doc.path("uiLayout").path("positions")).remove("manager");
        String json = TestSupport.text(doc);
        CompiledWorkflow compiled = compile(json);
        String file = BpmnSource.downloadFile(compiled.bpmnXml(), json, "draft test revision 2");
        assertThat(file).contains("the left-to-right layout was computed");
        Map<String, Box> shapes = assertComplete("partial", file);
        // Left to right: along every sequence flow of this acyclic process the target starts right of the source.
        Document d = dom(file);
        NodeList flows = d.getElementsByTagNameNS(BPMN, "sequenceFlow");
        for (int i = 0; i < flows.getLength(); i++) {
            Element f = (Element) flows.item(i);
            Box s = shapes.get(f.getAttribute("sourceRef"));
            Box t = shapes.get(f.getAttribute("targetRef"));
            assertThat(t.x()).as(f.getAttribute("id")).isGreaterThanOrEqualTo(s.x());
        }
        assertThat(shapes.values().stream().mapToDouble(Box::x).min().orElseThrow()).isGreaterThanOrEqualTo(40);
        assertThat(overlaps(shapes, d)).as("no two shapes overlap").isEmpty();
    }

    /** Pairs of shapes that overlap (boundary events sit on an edge by design; sections contain shapes). */
    private static List<String> overlaps(Map<String, Box> shapes, Document d) {
        Set<String> skip = new HashSet<>();
        for (String local : List.of("boundaryEvent", "subProcess")) {
            NodeList list = d.getElementsByTagNameNS(BPMN, local);
            for (int i = 0; i < list.getLength(); i++) {
                skip.add(((Element) list.item(i)).getAttribute("id"));
            }
        }
        List<String> ids = shapes.keySet().stream().filter(id -> !skip.contains(id)).sorted().toList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                Box a = shapes.get(ids.get(i));
                Box b = shapes.get(ids.get(j));
                if (a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h()) {
                    out.add(ids.get(i) + " x " + ids.get(j));
                }
            }
        }
        return out;
    }

    @Test
    void computedLayoutsOfTheExamplesNeverOverlapShapes() throws Exception {
        int checked = 0;
        for (Map.Entry<String, String> doc : documents().entrySet()) {
            ObjectNode bare = (ObjectNode) com.orqix.compiler.internal.json.StrictJson.parse(doc.getValue());
            bare.remove("uiLayout");
            String json = TestSupport.text(bare);
            CompiledWorkflow compiled;
            try {
                compiled = compile(json);
            } catch (CompilationException e) {
                continue;
            }
            String file = BpmnSource.downloadFile(compiled.bpmnXml(), json, "draft x");
            Map<String, Box> shapes = assertComplete(doc.getKey(), file);
            assertThat(overlaps(shapes, dom(file))).as(doc.getKey()).isEmpty();
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(20);
    }

    @Test
    void theBpmnSourceViewStillUsesTheCompiledTextAndTheDownloadParses() throws Exception {
        String json = TestSupport.purchaseJson();
        CompiledWorkflow compiled = compile(json);
        assertThat(BpmnSource.export(json, compiled).identical()).isTrue();
        assertThat(dom(BpmnSource.downloadFile(compiled.bpmnXml(), json, "draft a revision 1"))).isNotNull();
    }

    /** Sections, handlers, signals, undo and adapter steps (S59/S71/S73): complete diagrams in both layout modes. */
    @Test
    void sectionsHandlersAndSignalsGetCompleteDiagramsInBothLayoutModes() throws Exception {
        int withSubProcess = 0;
        int withBoundary = 0;
        for (Map.Entry<String, ObjectNode> doc : BpmnSourceScopesTest.documents().entrySet()) {
            for (boolean saved : new boolean[] {true, false}) {
                ObjectNode copy = doc.getValue().deepCopy();
                if (!saved) {
                    copy.remove("uiLayout");
                }
                String json = TestSupport.text(copy);
                CompiledWorkflow compiled = COMPILER.compile(json, BpmnSourceScopesTest.CONTEXT.apply(json));
                String file = BpmnSource.downloadFile(compiled.bpmnXml(), json, "draft s revision 1");
                String name = doc.getKey() + (saved ? " (saved positions)" : " (computed)");
                Map<String, Box> shapes = assertComplete(name, file);
                Document d = dom(file);
                assertThat(file).as(name).contains(saved ? "the saved canvas positions" : "was computed");
                NodeList subs = d.getElementsByTagNameNS(BPMN, "subProcess");
                withSubProcess += subs.getLength();
                withBoundary += d.getElementsByTagNameNS(BPMN, "boundaryEvent").getLength();
                // A section contains the shapes of its own steps.
                for (int i = 0; i < subs.getLength(); i++) {
                    Element sub = (Element) subs.item(i);
                    Box frame = shapes.get(sub.getAttribute("id"));
                    for (org.w3c.dom.Node n = sub.getFirstChild(); n != null; n = n.getNextSibling()) {
                        if (n instanceof Element child && BPMN.equals(child.getNamespaceURI())
                                && shapes.containsKey(child.getAttribute("id"))
                                && !"boundaryEvent".equals(child.getLocalName())) {
                            Box b = shapes.get(child.getAttribute("id"));
                            assertThat(b.x()).as(name + " " + child.getAttribute("id")).isGreaterThanOrEqualTo(frame.x());
                            assertThat(b.y()).as(name + " " + child.getAttribute("id")).isGreaterThanOrEqualTo(frame.y());
                            assertThat(b.x() + b.w()).as(name + " " + child.getAttribute("id"))
                                    .isLessThanOrEqualTo(frame.x() + frame.w());
                            assertThat(b.y() + b.h()).as(name + " " + child.getAttribute("id"))
                                    .isLessThanOrEqualTo(frame.y() + frame.h());
                        }
                    }
                }
            }
        }
        assertThat(withSubProcess).isGreaterThanOrEqualTo(4);
        assertThat(withBoundary).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shapeSizesComeFromTheContractFileTheCanvasAlsoReads() throws Exception {
        // S106: one source for the canvas and the export (contracts/bpmn-shape-sizes.json).
        JsonNode file = com.orqix.compiler.internal.json.StrictJson.parse(
                Files.readString(TestSupport.repoRoot().resolve("contracts/bpmn-shape-sizes.json")));
        assertThat(com.orqix.compiler.internal.bpmn.BpmnShapeSizes.EVENT).isEqualTo(file.at("/event/width").intValue()).isEqualTo(48);
        assertThat(com.orqix.compiler.internal.bpmn.BpmnShapeSizes.GATEWAY).isEqualTo(file.at("/gateway/width").intValue()).isEqualTo(64);
        assertThat(com.orqix.compiler.internal.bpmn.BpmnShapeSizes.TASK_WIDTH).isEqualTo(file.at("/task/width").intValue()).isEqualTo(200);
        assertThat(com.orqix.compiler.internal.bpmn.BpmnShapeSizes.TASK_HEIGHT).isEqualTo(file.at("/task/height").intValue()).isEqualTo(80);
        // The canvas constant imports the same file instead of repeating the numbers.
        String canvas = Files.readString(TestSupport.repoRoot().resolve(
                "frontend/src/features/studio/model/shapeSizes.ts"));
        assertThat(canvas).contains("contracts/bpmn-shape-sizes.json").doesNotContainPattern("width: [0-9]");
    }
}
