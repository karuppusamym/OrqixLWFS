package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.JsonNode;

/** T-COMP-01: every case of examples/fixtures/compiler-cases.json yields its expected codes (codes, not messages). */
class CompilerCasesTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    record Case(String name, String definition, CompileContext context, List<String> expectBlockers,
                List<String> expectWarnings) {
    }

    static List<Case> cases() {
        JsonNode fixture = TestSupport.json("examples/fixtures/compiler-cases.json");
        JsonNode base = TestSupport.json(fixture.get("base").stringValue());
        List<Case> cases = new ArrayList<>();
        for (JsonNode c : fixture.get("cases")) {
            JsonNode doc = TestSupport.patch(base, c.get("patch"));
            List<String> blockers = new ArrayList<>();
            c.get("expectBlockers").forEach(b -> blockers.add(b.stringValue()));
            List<String> warnings = new ArrayList<>();
            c.path("expectWarnings").forEach(w -> warnings.add(w.stringValue()));
            cases.add(new Case(c.get("name").stringValue(), TestSupport.text(doc),
                    TestSupport.context(fixture.get("defaultContext"), c.get("context")), blockers, warnings));
        }
        return cases;
    }

    @Test
    void fixtureHasTwentyFiveCases() {
        assertThat(cases()).hasSize(25);
    }

    @TestFactory
    Stream<DynamicTest> everyCaseYieldsItsExpectedCodes() {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            ValidationReport report = COMPILER.validate(c.definition(), c.context());
            List<String> blockers = TestSupport.codes(report.issues(), Severity.BLOCKER);
            List<String> warnings = TestSupport.codes(report.issues(), Severity.WARNING);
            assertThat(blockers).as("blockers of %s: %s", c.name(), report.issues())
                    .containsAll(c.expectBlockers());
            assertThat(warnings).as("warnings of %s: %s", c.name(), report.issues())
                    .containsAll(c.expectWarnings());
            if (c.name().startsWith("valid-") || c.expectBlockers().isEmpty()) {
                assertThat(blockers).as("%s must have no blockers: %s", c.name(), report.issues()).isEmpty();
                assertThat(report.publishable()).isTrue();
            }
            assertThat(report.compilerVersion()).isEqualTo(COMPILER.version());
            for (ValidationIssue issue : report.issues()) {
                assertThat(issue.code()).matches("^WF_[A-Z_]+$");
                assertThat(issue.message()).isNotBlank().doesNotContain("$.");
                assertThat(issue.path()).matches("^(/.*)?$");
            }
        }));
    }

    @Test
    void purchaseTemplateHasNoIssuesInTheDefaultContext() {
        ValidationReport report = COMPILER.validate(TestSupport.purchaseJson(), TestSupport.defaultContext());
        assertThat(report.issues()).isEmpty();
        assertThat(report.publishable()).isTrue();
        assertThat(report.semanticHash()).hasSize(64);
    }

    @Test
    void schemaIssuesCarryBusinessMessagesAndPointers() {
        JsonNode doc = TestSupport.purchase();
        ((tools.jackson.databind.node.ObjectNode) doc.get("nodes").get(4).get("config")).remove("candidateGroupRef");
        ValidationReport report = COMPILER.validate(TestSupport.text(doc), TestSupport.defaultContext());
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("WF_SCHEMA_INVALID");
            assertThat(i.path()).isEqualTo("/nodes/4/config/candidateGroupRef");
            assertThat(i.message()).isEqualTo("Finance review needs an approver group.");
            assertThat(i.nodeId()).isEqualTo("finance");
        });
    }
}
