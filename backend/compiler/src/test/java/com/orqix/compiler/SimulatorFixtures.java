package com.orqix.compiler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** The workspace of examples/fixtures/simulation/context.json as a {@link SimulationContext}. */
final class SimulatorFixtures {

    static final String DIR = "examples/fixtures/simulation/";

    private SimulatorFixtures() {
    }

    static SimulationContext context() {
        JsonNode c = TestSupport.json(DIR + "context.json");
        Map<String, CompileContext.GroupInfo> groups = new LinkedHashMap<>();
        for (JsonNode g : c.get("groups")) {
            groups.put(g.get("key").stringValue(), new CompileContext.GroupInfo(g.get("key").stringValue(),
                    g.get("name").stringValue(), 1));
        }
        List<CompileContext.ConnectionVersion> versions = new ArrayList<>();
        Set<String> enabled = new HashSet<>();
        for (JsonNode k : c.get("connections")) {
            String definition = TestSupport.read(k.get("definition").stringValue());
            versions.add(new CompileContext.ConnectionVersion(k.get("key").stringValue(),
                    k.get("definitionVersion").intValue(), definition, CanonicalJson.sha256Hex(definition)));
            if (k.get("bindingEnabled").booleanValue()) {
                enabled.add(k.get("key").stringValue());
            }
        }
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, CompileContext.WorkflowInfo> workflows = new LinkedHashMap<>();
        c.get("workflows").properties().forEach(e -> {
            String json = TestSupport.read(e.getValue().stringValue());
            files.put(e.getKey(), json);
            String name = TestSupport.json(e.getValue().stringValue()).get("name").stringValue();
            workflows.put(e.getKey(), CompileContext.WorkflowInfo.of(e.getKey(), name,
                    "00000000-0000-7000-8000-0000000000a1", 1, "hash", json, Set.of(), 0));
        });
        CompileContext workspace = new CompileContext(false, groups, versions, enabled, workflows);
        return new SimulationContext(workspace, files::get, LocalDate.parse(c.get("today").stringValue()));
    }

    static JsonNode run(String definitionJson, String inputJson, String scenarioJson) {
        SimulationResult result = Compilers.workflowSimulator().simulate(definitionJson, inputJson, scenarioJson,
                context());
        if (!result.hasTrace()) {
            throw new AssertionError("No trace: " + result.status() + " " + result.inputErrors() + " "
                    + result.blockers());
        }
        return com.orqix.compiler.internal.json.StrictJson.parse(result.json());
    }
}
