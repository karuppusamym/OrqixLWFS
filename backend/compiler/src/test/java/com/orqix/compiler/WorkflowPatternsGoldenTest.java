package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.DefaultWorkflowCompiler;

/**
 * Golden files and determinism of the workflow-patterns example (S12): parallel steps, a timeout route, a wait and a
 * child workflow in one definition. Regenerate after an intentional output change (and bump the compiler version):
 * {@code ./mvnw -pl compiler test -Dtest=WorkflowPatternsGoldenTest -Dorqix.updateGolden=true}.
 */
class WorkflowPatternsGoldenTest {

    private static Path goldenDir() {
        String dir = System.getProperty("orqix.goldenDir");
        Path base = dir == null ? Path.of("src/test/resources/golden") : Path.of(dir);
        return base.resolve("new-hire-onboarding");
    }

    @Test
    void newHireOnboardingMatchesGoldenFiles() throws Exception {
        CompiledWorkflow w = Compilers.workflowCompiler().compile(TestSupport.onboardingJson(),
                TestSupport.onboardingContext());
        Map<String, String> files = GoldenFilesTest.artifacts(w);
        assertThat(files).containsKeys("process.bpmn20.xml", "source-map.json", "hashes.json");
        assertThat(w.dmnResources()).isEmpty();
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
            assertThat(e.getValue()).as(e.getKey()).isEqualTo(new String(expected, StandardCharsets.UTF_8));
            assertThat(e.getValue().getBytes(StandardCharsets.UTF_8)).as(e.getKey()).isEqualTo(expected);
        }
    }

    @Test
    void sameDefinitionAndContextGiveByteIdenticalArtifacts() throws Exception {
        WorkflowCompiler compiler = Compilers.workflowCompiler();
        CompiledWorkflow first = compiler.compile(TestSupport.onboardingJson(), TestSupport.onboardingContext());
        assertThat(new DefaultWorkflowCompiler().compile(TestSupport.onboardingJson(), TestSupport.onboardingContext()))
                .isEqualTo(first);
        for (long seed = 1; seed <= 5; seed++) {
            String shuffled = TestSupport.text(TestSupport.shuffleKeys(TestSupport.onboarding(), new Random(seed)));
            assertThat(compiler.compile(shuffled, TestSupport.onboardingContext())).isEqualTo(first);
        }
    }

    @Test
    void manifestFactsPinTheChild() throws Exception {
        CompiledWorkflow w = Compilers.workflowCompiler().compile(TestSupport.onboardingJson(),
                TestSupport.onboardingContext());
        assertThat(w.processId()).isEqualTo("new-hire-onboarding");
        assertThat(w.children()).containsExactly(new CompiledWorkflow.ChildRef("orderEquipment", "purchase-approval",
                TestSupport.PURCHASE_RELEASE_ID, 1, TestSupport.purchaseAsChild(1).semanticHash()));
        assertThat(w.callClosure()).containsExactly("purchase-approval");
        assertThat(w.callHeight()).isEqualTo(1);
        assertThat(w.groups()).containsExactly("it-desk", "managers", "facilities-team");
        assertThat(w.connections()).isEmpty();
        assertThat(w.warnings()).isEmpty();
    }

    @Test
    void repinningTheChildChangesTheBpmn() throws Exception {
        WorkflowCompiler compiler = Compilers.workflowCompiler();
        CompiledWorkflow first = compiler.compile(TestSupport.onboardingJson(), TestSupport.onboardingContext());
        CompileContext base = TestSupport.onboardingContext();
        Map<String, CompileContext.WorkflowInfo> workflows = new java.util.LinkedHashMap<>(base.workflows());
        workflows.put("purchase-approval", TestSupport.purchaseAsChild(2));
        CompileContext repinned = new CompileContext(base.selfApprovalAllowed(), base.groups(),
                base.connectionVersions(), base.enabledConnectionBindings(), workflows);
        CompiledWorkflow second = compiler.compile(TestSupport.onboardingJson(), repinned);
        assertThat(second.bpmnXml()).isNotEqualTo(first.bpmnXml()).contains("releaseNumber=\"2\"");
        assertThat(second.semanticHash()).isEqualTo(first.semanticHash());
    }

    private static byte[] read(Path file) throws IOException {
        return Files.readAllBytes(file);
    }
}
