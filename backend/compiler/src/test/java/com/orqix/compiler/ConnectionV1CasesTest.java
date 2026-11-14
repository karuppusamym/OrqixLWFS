package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.orqix.compiler.internal.schema.SchemaValidation;

import tools.jackson.databind.JsonNode;

/**
 * Connection definition v1 (S130): registration cases and workflow compile cases of
 * examples/fixtures/connection-v1-cases.json, and every v0 example still valid under its own schema with no v1 rule.
 */
class ConnectionV1CasesTest {

    private static final JsonNode FIXTURE = TestSupport.json("examples/fixtures/connection-v1-cases.json");
    private static final SchemaValidation SCHEMAS = new SchemaValidation();
    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    /** @return SCHEMA when the v1 JSON Schema refuses the document, else the CONN_V1_* codes */
    static List<String> codes(JsonNode definition) {
        List<String> problems = SCHEMAS.validateConnection(definition);
        List<String> rules = new ArrayList<>();
        ConnectionContracts.check(definition).forEach(p -> rules.add(p.code()));
        if (!problems.isEmpty() && rules.isEmpty()) {
            return List.of("SCHEMA");
        }
        return rules;
    }

    @TestFactory
    Stream<DynamicTest> registrationCases() {
        JsonNode base = TestSupport.json(FIXTURE.get("base").stringValue());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : FIXTURE.get("registration")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").stringValue(), () -> {
                JsonNode doc = TestSupport.patch(base, c.get("patch"));
                List<String> expected = new ArrayList<>();
                c.get("expect").forEach(e -> expected.add(e.stringValue()));
                List<String> actual = codes(doc);
                if (expected.isEmpty()) {
                    assertThat(SCHEMAS.validateConnection(doc)).as(c.get("name").stringValue()).isEmpty();
                    assertThat(actual).isEmpty();
                } else if (expected.equals(List.of("SCHEMA"))) {
                    assertThat(SCHEMAS.validateConnection(doc)).as(c.get("name").stringValue()).isNotEmpty();
                } else {
                    assertThat(actual).as(c.get("name").stringValue() + " " + ConnectionContracts.check(doc))
                            .containsAll(expected);
                }
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> workflowCases() {
        JsonNode base = TestSupport.patch(TestSupport.json(FIXTURE.get("workflowBase").stringValue()),
                FIXTURE.get("workflowSetup"));
        CompileContext context = TestSupport.context(FIXTURE.get("workflowContext"), null);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : FIXTURE.get("workflows")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").stringValue(), () -> {
                JsonNode doc = TestSupport.patch(base, c.get("patch"));
                ValidationReport report = COMPILER.validate(TestSupport.text(doc), context);
                List<String> blockers = TestSupport.codes(report.issues(), Severity.BLOCKER);
                List<String> warnings = TestSupport.codes(report.issues(), Severity.WARNING);
                List<String> expected = new ArrayList<>();
                c.get("expectBlockers").forEach(e -> expected.add(e.stringValue()));
                List<String> expectedWarnings = new ArrayList<>();
                c.path("expectWarnings").forEach(e -> expectedWarnings.add(e.stringValue()));
                assertThat(blockers).as("%s: %s", c.get("name").stringValue(), report.issues()).containsAll(expected);
                assertThat(warnings).as("%s: %s", c.get("name").stringValue(), report.issues())
                        .containsAll(expectedWarnings);
                if (expected.isEmpty()) {
                    assertThat(blockers).as("%s: %s", c.get("name").stringValue(), report.issues()).isEmpty();
                    assertThat(report.publishable()).isTrue();
                }
            }));
        }
        return tests.stream();
    }

    @Test
    void everyV0ExampleConnectionStaysValidAndHasNoV1Rules() throws IOException {
        int v0 = 0;
        try (Stream<Path> files = Files.list(TestSupport.repoRoot().resolve("examples/connections"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                JsonNode definition = TestSupport.json("examples/connections/" + file.getFileName());
                assertThat(SCHEMAS.validateConnection(definition)).as(file.toString()).isEmpty();
                if (!ConnectionContracts.isV1(definition)) {
                    v0++;
                    assertThat(ConnectionContracts.check(definition)).isEmpty();
                }
            }
        }
        assertThat(v0).isGreaterThanOrEqualTo(8);
    }

    @Test
    void resultPointersParseHeadersProjectionsAndPlainPointers() {
        assertThat(ConnectionContracts.parseResult("/@headers/X-Total-Count").header()).isEqualTo("X-Total-Count");
        ConnectionContracts.ResultPointer rows = ConnectionContracts.parseResult("/result/[*]");
        assertThat(rows.projection()).isTrue();
        assertThat(rows.base()).containsExactly("result");
        assertThat(rows.column()).isEmpty();
        assertThat(ConnectionContracts.parseResult("/result/[*]/number").column()).containsExactly("number");
        assertThat(ConnectionContracts.parseResult("/a/[*]/b/[*]")).isNull();
        assertThat(ConnectionContracts.parseResult("/@headers/a/b")).isNull();
        assertThat(ConnectionContracts.parseResult("/result/0/sys_id").projection()).isFalse();
    }

    @Test
    void pathValuesRefuseTraversalSlashesAndControlCharacters() {
        assertThat(ConnectionContracts.safePathValue("abc123")).isTrue();
        assertThat(ConnectionContracts.safePathValue("..")).isFalse();
        assertThat(ConnectionContracts.safePathValue("a/b")).isFalse();
        assertThat(ConnectionContracts.safePathValue("a\\b")).isFalse();
        assertThat(ConnectionContracts.safePathValue("a\nb")).isFalse();
    }
}
