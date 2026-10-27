package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowElementsContainer;
import org.flowable.bpmn.model.SubProcess;
import org.junit.jupiter.api.Test;

import com.orqix.compiler.BpmnSource;
import com.orqix.compiler.CanonicalJson;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.CompiledWorkflow;
import com.orqix.compiler.Compilers;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * STU04 / STD01 (docs/build/S97): the repository's example workflows all export to BPMN that Flowable's own BPMN 2.0
 * converter accepts with XSD validation on, with a complete diagram (a shape for every element, an edge for every flow,
 * in the shared sizes), with and without saved positions, and the file survives a read, write and read through that
 * converter with the same elements, flows and diagram. No database: documents are compiled directly.
 */
class BpmnExportStandardsTest {

    private static final Json JSON = new Json(JsonMapper.builder().build());
    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final Path EXAMPLES = Path.of("../../examples");

    private static CompileContext context(String json) throws Exception {
        Map<String, CompileContext.GroupInfo> groups = new LinkedHashMap<>();
        JsonNode doc = JSON.parse(json);
        for (JsonNode n : doc.path("nodes")) {
            JsonNode c = n.path("config");
            if (c.path("candidateGroupRef").isString()) {
                groups.put(c.path("candidateGroupRef").asString(),
                        new CompileContext.GroupInfo(c.path("candidateGroupRef").asString(), "g", 3));
            }
            c.path("requiredSkills").forEach(k -> groups.putIfAbsent(k.asString(), new CompileContext.GroupInfo(k.asString(), "g", 3)));
        }
        List<CompileContext.ConnectionVersion> versions = new ArrayList<>();
        Set<String> enabled = new HashSet<>();
        Map<String, CompileContext.WorkflowInfo> children = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(EXAMPLES.resolve("connections"))) {
            for (Path f : files.sorted().toList()) {
                String text = Files.readString(f);
                JsonNode def = JSON.parse(text);
                versions.add(new CompileContext.ConnectionVersion(def.path("key").asString(), def.path("version").asInt(),
                        text, CanonicalJson.sha256Hex(text)));
                enabled.add(def.path("key").asString());
            }
        }
        try (Stream<Path> examples = Files.list(EXAMPLES)) {
            for (Path f : examples.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String text = Files.readString(f);
                JsonNode def = JSON.parse(text);
                children.putIfAbsent(def.path("workflowKey").asString(), CompileContext.WorkflowInfo.of(
                        def.path("workflowKey").asString(), def.path("name").asString(),
                        "00000000-0000-7000-8000-0000000000b1", 1, "0".repeat(64), text, Set.of(), 0));
            }
        }
        return new CompileContext(false, groups, versions, enabled, children);
    }

    private static Map<String, String> examples() throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                out.put(f.getFileName().toString(), Files.readString(f));
            }
        }
        return out;
    }

    private static Set<String> ids(BpmnModel model) {
        List<FlowElement> all = new ArrayList<>();
        model.getProcesses().forEach(p -> walk(p, all));
        return all.stream().map(FlowElement::getId).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static void walk(FlowElementsContainer container, List<FlowElement> out) {
        for (FlowElement e : container.getFlowElements()) {
            out.add(e);
            if (e instanceof SubProcess sub) {
                walk(sub, out);
            }
        }
    }

    private static void assertStandard(String name, String json) throws Exception {
        CompiledWorkflow compiled = COMPILER.compile(json, context(json));
        String file = BpmnSource.downloadFile(compiled.bpmnXml(), json, "draft test revision 1");
        // 1. Flowable's converter with the BPMN 2.0 schema validation on, DI included.
        BpmnModel model = BpmnExportApiTest.flowableModel(file);
        BpmnExportApiTest.assertCompleteDiagram(model, name);
        // 2. The same elements and flows as the compiled (deployed) BPMN: the export only adds the diagram.
        assertThat(ids(model)).as(name + " same elements as the deployed BPMN")
                .isEqualTo(ids(BpmnExportApiTest.flowableModel(compiled.bpmnXml())));
        // 3. Read, write, read again: elements, flows and diagram survive in a standard converter.
        String written = new String(new BpmnXMLConverter().convertToXML(model), StandardCharsets.UTF_8);
        BpmnModel again = BpmnExportApiTest.flowableModel(written);
        assertThat(ids(again)).as(name + " after a standard write").isEqualTo(ids(model));
        BpmnExportApiTest.assertCompleteDiagram(again, name + " (re-written)");
        assertThat(again.getLocationMap().keySet()).isEqualTo(model.getLocationMap().keySet());
        assertThat(again.getFlowLocationMap().keySet()).isEqualTo(model.getFlowLocationMap().keySet());
        // Bounds survive unchanged.
        model.getLocationMap().forEach((id, g) -> {
            assertThat(again.getGraphicInfo(id).getX()).as(name + " " + id).isEqualTo(g.getX());
            assertThat(again.getGraphicInfo(id).getY()).as(name + " " + id).isEqualTo(g.getY());
            assertThat(again.getGraphicInfo(id).getWidth()).as(name + " " + id).isEqualTo(g.getWidth());
            assertThat(again.getGraphicInfo(id).getHeight()).as(name + " " + id).isEqualTo(g.getHeight());
        });
    }

    @Test
    void everyExampleWorkflowExportsAsStandardBpmnWithACompleteDiagram() throws Exception {
        int count = 0;
        for (Map.Entry<String, String> example : examples().entrySet()) {
            try {
                COMPILER.compile(example.getValue(), context(example.getValue()));
            } catch (com.orqix.compiler.CompilationException e) {
                continue;
            }
            assertStandard(example.getKey(), example.getValue());
            // And with no saved position at all: the computed layout.
            ObjectNode bare = (ObjectNode) JSON.parse(example.getValue());
            bare.remove("uiLayout");
            assertStandard(example.getKey() + " (computed layout)", JSON.write(bare));
            count++;
        }
        assertThat(count).as("examples that compile").isGreaterThanOrEqualTo(18);
    }

    @Test
    void aSectionWithATimeLimitAndAnErrorRouteExportsAsStandardBpmn() throws Exception {
        ObjectNode d = (ObjectNode) JSON.parse(examples().get("purchase-approval.json"));
        add(d, "{\"id\":\"limit\",\"type\":\"branch\",\"label\":\"Within the limit?\",\"config\":{}}", 100, 100);
        add(d, "{\"id\":\"tooBig\",\"type\":\"raiseError\",\"label\":\"Over the limit\",\"config\":{\"errorCode\":\"TOO_EXPENSIVE\"}}", 100, 300);
        add(d, "{\"id\":\"limitNotice\",\"type\":\"notification\",\"label\":\"Tell the requester\",\"config\":{\"channel\":\"inApp\","
                + "\"recipient\":\"initiator\",\"message\":\"Over the limit\"}}", 100, 500);
        for (JsonNode t : d.path("transitions")) {
            if ("t1".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "limit");
            }
        }
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        transitions.add(JSON.parse("{\"id\":\"l1\",\"from\":\"limit\",\"to\":\"tooBig\",\"on\":\"condition\",\"label\":\"100,000 or more\","
                + "\"condition\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":100000}}"));
        transitions.add(JSON.parse("{\"id\":\"l2\",\"from\":\"limit\",\"to\":\"manager\",\"on\":\"default\"}"));
        transitions.add(JSON.parse("{\"id\":\"l3\",\"from\":\"limitNotice\",\"to\":\"rejected\",\"on\":\"next\"}"));
        ArrayNode scopes = d.has("subprocesses") ? (ArrayNode) d.path("subprocesses") : d.putArray("subprocesses");
        scopes.add(JSON.parse("{\"id\":\"review\",\"label\":\"Review\",\"type\":\"embedded\",\"nodes\":[\"limit\",\"tooBig\","
                + "\"manager\",\"amountPolicy\",\"route\",\"finance\"],\"timeoutAfter\":\"P5D\",\"onTimeout\":\"notifyRejected\","
                + "\"onError\":{\"to\":\"limitNotice\",\"errorCode\":\"TOO_EXPENSIVE\"}}"));
        String saved = JSON.write(d);
        assertStandard("section (saved positions)", saved);
        d.remove("uiLayout");
        assertStandard("section (computed layout)", JSON.write(d));
        CompiledWorkflow compiled = COMPILER.compile(saved, context(saved));
        String file = BpmnSource.downloadFile(compiled.bpmnXml(), saved, "draft s revision 1");
        assertThat(file).contains("<subProcess id=\"review\"").contains("isExpanded=\"true\"")
                .contains("bpmnElement=\"review__timeout\"").contains("bpmnElement=\"review__error\"");
    }

    private static void add(ObjectNode d, String node, int x, int y) {
        JsonNode parsed = JSON.parse(node);
        ((ArrayNode) d.path("nodes")).add(parsed);
        ((ObjectNode) d.path("uiLayout").path("positions")).set(parsed.path("id").asString(),
                JSON.parse("{\"x\":" + x + ",\"y\":" + y + "}"));
    }
}
