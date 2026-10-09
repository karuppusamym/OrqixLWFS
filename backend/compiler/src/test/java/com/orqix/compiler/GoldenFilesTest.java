package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.Jcs;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.node.ObjectNode;

/**
 * T-COMP-03: the purchase template compiles to byte-identical artifacts that match the golden files.
 * <p>
 * To regenerate after an intentional output change (and bump the compiler version, S4 section 9):
 * {@code ./mvnw -pl compiler test -Dtest=GoldenFilesTest -Dorqix.updateGolden=true}, then review the diff.
 */
class GoldenFilesTest {

    private static Path goldenDir() {
        String dir = System.getProperty("orqix.goldenDir");
        Path base = dir == null ? Path.of("src/test/resources/golden") : Path.of(dir);
        return base.resolve("purchase-approval");
    }

    static Map<String, String> artifacts(CompiledWorkflow w) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("process.bpmn20.xml", w.bpmnXml());
        files.putAll(w.dmnResources());
        files.put("source-map.json", w.sourceMapJson());
        StringBuilder hashes = new StringBuilder("{\n");
        hashes.append("  \"compilerVersion\": ").append(Jcs.quote(w.compilerVersion())).append(",\n");
        hashes.append("  \"sourceHash\": ").append(Jcs.quote(w.sourceHash())).append(",\n");
        hashes.append("  \"semanticHash\": ").append(Jcs.quote(w.semanticHash())).append(",\n");
        hashes.append("  \"bpmnHash\": ").append(Jcs.quote(w.bpmnHash())).append(",\n");
        hashes.append("  \"dmnHashes\": {");
        boolean first = true;
        for (Map.Entry<String, String> e : w.dmnHashes().entrySet()) {
            hashes.append(first ? "\n" : ",\n").append("    ").append(Jcs.quote(e.getKey())).append(": ")
                    .append(Jcs.quote(e.getValue()));
            first = false;
        }
        hashes.append("\n  }\n}\n");
        files.put("hashes.json", hashes.toString());
        return files;
    }

    @Test
    void purchaseApprovalMatchesGoldenFiles() throws Exception {
        CompiledWorkflow w = Compilers.workflowCompiler().compile(TestSupport.purchaseJson(),
                TestSupport.defaultContext());
        Map<String, String> files = artifacts(w);
        assertThat(files).containsKeys("process.bpmn20.xml", "decisions/amount-policy.dmn", "source-map.json",
                "hashes.json");
        Path dir = goldenDir();
        if (Boolean.getBoolean("orqix.updateGolden")) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path file = dir.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            byte[] expected = read(dir.resolve(e.getKey()));
            assertThat(new String(e.getValue().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .as(e.getKey()).isEqualTo(new String(expected, StandardCharsets.UTF_8));
            assertThat(e.getValue().getBytes(StandardCharsets.UTF_8)).as(e.getKey()).isEqualTo(expected);
        }
        assertThat(w.bpmnHash()).isEqualTo(Jcs.sha256Hex(read(dir.resolve("process.bpmn20.xml"))));
        assertThat(w.dmnHashes().get("decisions/amount-policy.dmn"))
                .isEqualTo(Jcs.sha256Hex(read(dir.resolve("decisions/amount-policy.dmn"))));
    }

    @Test
    void manifestFacts() throws CompilationException {
        CompiledWorkflow w = Compilers.workflowCompiler().compile(TestSupport.purchaseJson(),
                TestSupport.defaultContext());
        assertThat(w.compilerVersion()).isEqualTo("0.2.0").isEqualTo(Compilers.workflowCompiler().version());
        assertThat(w.processId()).isEqualTo("purchase-approval");
        assertThat(w.decisions()).containsExactly(new CompiledWorkflow.DecisionRef("amount-policy", 1));
        String definition = TestSupport.read("examples/connections/procurement.connection.json");
        assertThat(w.connections()).containsExactly(
                new CompiledWorkflow.ConnectionRef("procurement", 1, CanonicalJson.sha256Hex(definition)));
        assertThat(w.groups()).containsExactly("managers", "finance-approvers");
        assertThat(w.warnings()).isEmpty();
        assertThat(w.sourceHash()).isEqualTo(CanonicalJson.sha256Hex(TestSupport.purchaseJson()));
    }

    @Test
    void layoutChangesKeepTheSemanticHash() throws CompilationException {
        WorkflowCompiler compiler = Compilers.workflowCompiler();
        CompiledWorkflow original = compiler.compile(TestSupport.purchaseJson(), TestSupport.defaultContext());
        ObjectNode moved = TestSupport.purchase();
        ((ObjectNode) moved.get("uiLayout").get("positions").get("finance")).put("x", 1234.5);
        ((ObjectNode) moved.get("uiLayout").get("viewport")).put("zoom", 1);
        CompiledWorkflow relaid = compiler.compile(TestSupport.text(moved), TestSupport.defaultContext());
        assertThat(relaid.semanticHash()).isEqualTo(original.semanticHash());
        assertThat(relaid.sourceHash()).isNotEqualTo(original.sourceHash());
        assertThat(relaid.dmnResources()).isEqualTo(original.dmnResources());
        assertThat(relaid.bpmnXml()).contains("<omgdc:Bounds x=\"1294.5\"");

        ObjectNode withoutLayout = TestSupport.purchase();
        withoutLayout.remove("uiLayout");
        CompiledWorkflow plain = compiler.compile(TestSupport.text(withoutLayout), TestSupport.defaultContext());
        assertThat(plain.semanticHash()).isEqualTo(original.semanticHash());
        assertThat(plain.bpmnXml()).doesNotContain("BPMNDiagram")
                .isEqualTo(original.bpmnXml().substring(0, original.bpmnXml().indexOf("  <bpmndi:BPMNDiagram"))
                        + "</definitions>\n");

        ObjectNode renamed = TestSupport.purchase();
        renamed.put("name", "Purchase approval (EU)");
        assertThat(compiler.validate(TestSupport.text(renamed), TestSupport.defaultContext()).semanticHash())
                .isNotEqualTo(original.semanticHash());
        assertThat(StrictJson.parse(TestSupport.purchaseJson()).get("uiLayout")).isNotNull();
    }

    private static byte[] read(Path file) throws IOException {
        return Files.readAllBytes(file);
    }
}
