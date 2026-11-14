package com.orqix.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orqix.catalog.internal.SuiteCoverage;
import com.orqix.shared.junit.JUnitXml;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CG-Q03 (S148): the coverage of several dry runs together, replayed from the fixture shared with the TypeScript twin
 * ({@code frontend/src/mocks/suiteCoverage.test.ts}), and the JUnit XML writer.
 */
class SuiteCoverageTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void sharedFixtureGivesTheExpectedCoverageAndShortfalls() throws Exception {
        Path root = Path.of(System.getProperty("orqix.repoRoot", "../.."));
        JsonNode fixture = MAPPER.readTree(Files.readString(root.resolve("examples/fixtures/simulation/suite-coverage.json")));
        assertThat(fixture.path("cases").size()).isGreaterThanOrEqualTo(3);
        for (JsonNode c : fixture.path("cases")) {
            List<SuiteCoverage.Run> runs = new ArrayList<>();
            c.path("runs").forEach(r -> runs.add(new SuiteCoverage.Run(r.path("input"), r.path("coverage"))));
            JsonNode actual = SuiteCoverage.of(c.path("definition"), runs);
            if (c.path("expected").isNull()) {
                assertThat(actual).as(c.path("name").asString()).isNull();
            } else {
                assertThat(actual).as(c.path("name").asString()).isEqualTo(c.path("expected"));
            }
            JsonNode shortfalls = MAPPER.valueToTree(SuiteCoverage.shortfalls(actual, c.path("minimum")));
            assertThat(shortfalls).as(c.path("name").asString()).isEqualTo(c.path("shortfalls"));
        }
    }

    @Test
    void noMinimumMeansNoShortfall() {
        assertThat(SuiteCoverage.shortfalls(null, null)).isEmpty();
        assertThat(new SuiteCoverage.Count(0, 0).percent()).isEqualTo(100);
        assertThat(new SuiteCoverage.Count(2, 3).percent()).isEqualTo(66);
    }

    @Test
    void junitXmlEscapesAndCounts() {
        String xml = JUnitXml.write("purchase <&> \"x\"", List.<String[]>of(new String[] {"draftRevision", "3"}), List.of(
                JUnitXml.Case.passed("orqix.workflow.p", "Small [a]"),
                JUnitXml.Case.failed("orqix.workflow.p", "Large [b]", "Outcome: expected \"Approved\", got \"Rejected\"",
                        "line <1>\u0001\n"),
                JUnitXml.Case.error("orqix.workflow.p", "Broken [c]", "The sample request does not fit.", null)));
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites name=\"orqix\" tests=\"3\""
                + " failures=\"1\" errors=\"1\"");
        assertThat(xml).contains("<testsuite name=\"purchase &lt;&amp;&gt; &quot;x&quot;\" tests=\"3\" failures=\"1\""
                + " errors=\"1\" skipped=\"0\" time=\"0\">");
        assertThat(xml).contains("<property name=\"draftRevision\" value=\"3\"/>");
        assertThat(xml).contains("<testcase classname=\"orqix.workflow.p\" name=\"Small [a]\" time=\"0\"/>");
        assertThat(xml).contains("<failure message=\"Outcome: expected &quot;Approved&quot;, got &quot;Rejected&quot;\""
                + " type=\"AssertionFailure\">line &lt;1&gt;\n</failure>");
        assertThat(xml).contains("<error message=\"The sample request does not fit.\" type=\"CaseError\"/>");
        assertThat(xml).doesNotContain("\u0001");
    }
}
