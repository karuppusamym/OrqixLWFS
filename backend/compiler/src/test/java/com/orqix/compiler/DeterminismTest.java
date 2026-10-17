package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.orqix.compiler.internal.DefaultWorkflowCompiler;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.rules.Issues;

/** T-COMP-02: validation output is identical across runs and across member-order permutations of the input. */
class DeterminismTest {

    @TestFactory
    Stream<DynamicTest> validationIsStableAcrossRunsAndKeyOrder() {
        return CompilerCasesTest.cases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            WorkflowCompiler fresh = new DefaultWorkflowCompiler();
            ValidationReport first = Compilers.workflowCompiler().validate(c.definition(), c.context());
            for (int run = 0; run < 3; run++) {
                assertThat(fresh.validate(c.definition(), c.context())).isEqualTo(first);
            }
            for (long seed = 1; seed <= 3; seed++) {
                String shuffled = TestSupport.text(TestSupport.shuffleKeys(StrictJson.parse(c.definition()),
                        new Random(seed)));
                assertThat(shuffled).isNotEqualTo(c.definition());
                assertThat(fresh.validate(shuffled, c.context())).as("seed %s", seed).isEqualTo(first);
            }
            List<ValidationIssue> sorted = new ArrayList<>(first.issues());
            sorted.sort(Issues.ORDER);
            assertThat(first.issues()).isEqualTo(sorted);
        }));
    }

    @Test
    void blockersComeFirstThenPathThenCode() {
        String doc = TestSupport.text(TestSupport.patch(TestSupport.purchase(), StrictJson.parse("""
                [{"op":"replace","path":"/security/selfApproval","value":"allow"},
                 {"op":"replace","path":"/nodes/6/label","value":"Task"},
                 {"op":"replace","path":"/nodes/1/config/candidateGroupRef","value":"nobody"},
                 {"op":"replace","path":"/nodes/9/label","value":"x"}]
                """)));
        ValidationReport report = Compilers.workflowCompiler().validate(doc, TestSupport.defaultContext());
        assertThat(report.issues()).extracting(i -> i.severity().apiValue() + " " + i.code() + " " + i.path())
                .containsExactly(
                        "blocker WF_GROUP_UNBOUND /nodes/1/config/candidateGroupRef",
                        "blocker WF_SELF_APPROVAL_NOT_ALLOWED /security/selfApproval",
                        "warning WF_LABEL_STYLE /nodes/6/label",
                        "warning WF_LABEL_STYLE /nodes/9/label");
    }

    @Test
    void compiledArtifactsDependOnlyOnTheCanonicalForm() throws CompilationException {
        WorkflowCompiler compiler = Compilers.workflowCompiler();
        CompiledWorkflow first = compiler.compile(TestSupport.purchaseJson(), TestSupport.defaultContext());
        CompiledWorkflow again = new DefaultWorkflowCompiler().compile(TestSupport.purchaseJson(),
                TestSupport.defaultContext());
        assertThat(again).isEqualTo(first);
        for (long seed = 1; seed <= 5; seed++) {
            String shuffled = TestSupport.text(TestSupport.shuffleKeys(TestSupport.purchase(), new Random(seed)));
            assertThat(compiler.compile(shuffled, TestSupport.defaultContext())).isEqualTo(first);
        }
    }
}
