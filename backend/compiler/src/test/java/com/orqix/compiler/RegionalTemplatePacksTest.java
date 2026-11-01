package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/**
 * Regional regulated template packs (docs/regional-template-packs.md): every examples/pack-*.json validates with no
 * blocker in a workspace that has its groups, compiles to BPMN, carries its pack, region and working calendar, and
 * reaches the expected outcome along the expected path for every sample in examples/fixtures/pack-scenarios.json.
 */
class RegionalTemplatePacksTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final WorkflowSimulator SIMULATOR = Compilers.workflowSimulator();

    static List<Path> packFiles() {
        try (Stream<Path> files = Files.list(TestSupport.repoRoot().resolve("examples"))) {
            return files.filter(p -> p.getFileName().toString().matches("pack-[a-z]{2}-[a-z-]+\\.json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String id(Path file) {
        return file.getFileName().toString().replace(".json", "");
    }

    /** A workspace that has every candidate group the template names, no connections, self-approval off. */
    static CompileContext contextFor(JsonNode definition) {
        Map<String, CompileContext.GroupInfo> groups = new LinkedHashMap<>();
        for (JsonNode node : definition.get("nodes")) {
            JsonNode group = node.path("config").get("candidateGroupRef");
            if (group != null) {
                groups.put(group.stringValue(), new CompileContext.GroupInfo(group.stringValue(), group.stringValue(), 2));
            }
        }
        return new CompileContext(false, groups, List.of(), Set.of());
    }

    @Test
    void everyRegionAndPackIsCovered() {
        List<Path> files = packFiles();
        assertThat(files).hasSizeGreaterThanOrEqualTo(20);
        Set<String> regions = new LinkedHashSet<>();
        Set<String> packs = new LinkedHashSet<>();
        for (Path file : files) {
            JsonNode tags = TestSupport.json("examples/" + file.getFileName()).at("/metadata/tags");
            tags.forEach(t -> {
                String tag = t.stringValue();
                if (tag.startsWith("region:")) regions.add(tag.substring(7));
                if (tag.startsWith("pack:")) packs.add(tag.substring(5));
            });
        }
        assertThat(regions).contains("india", "eu", "kenya", "nigeria");
        assertThat(packs).containsExactlyInAnyOrder("banking-mfi", "shared-services", "public-sector");
        JsonNode scenarios = TestSupport.json("examples/fixtures/pack-scenarios.json").get("templates");
        assertThat(scenarios.propertyNames()).containsExactlyInAnyOrderElementsOf(files.stream().map(RegionalTemplatePacksTest::id).toList());
    }

    @TestFactory
    Stream<DynamicTest> everyPackTemplateValidatesAndCompiles() {
        return packFiles().stream().map(file -> DynamicTest.dynamicTest(id(file), () -> {
            String json = TestSupport.read("examples/" + file.getFileName());
            JsonNode definition = StrictJson.parse(json);
            CompileContext context = contextFor(definition);
            ValidationReport report = COMPILER.validate(json, context);
            assertThat(TestSupport.codes(report.issues(), Severity.BLOCKER)).as("blockers: %s", report.issues()).isEmpty();
            assertThat(report.publishable()).isTrue();
            CompiledWorkflow compiled = COMPILER.compile(json, context);
            assertThat(compiled.bpmnXml()).contains("<userTask");
            // Every human step with a due date counts working time on a regional calendar.
            int calendars = 0;
            for (JsonNode node : definition.get("nodes")) {
                JsonNode calendar = node.path("config").get("calendar");
                if (calendar != null) {
                    calendars++;
                    BusinessCalendar parsed = BusinessCalendar.read(calendar);
                    assertThat(parsed.holidays()).as("holidays of %s", node.get("id")).isNotEmpty();
                    assertThat(parsed.zone().getId()).startsWith(zonePrefix(id(file)));
                }
            }
            assertThat(calendars).as("approvals with a working calendar").isPositive();
        }));
    }

    @TestFactory
    Stream<DynamicTest> everySampleReachesItsOutcomeAlongTheExpectedPath() {
        JsonNode templates = TestSupport.json("examples/fixtures/pack-scenarios.json").get("templates");
        List<DynamicTest> tests = new ArrayList<>();
        for (String template : templates.propertyNames()) {
            String json = TestSupport.read("examples/" + template + ".json");
            SimulationContext context = new SimulationContext(contextFor(StrictJson.parse(json)), null, null);
            for (JsonNode sample : templates.get(template)) {
                tests.add(DynamicTest.dynamicTest(template + ": " + sample.get("name").stringValue(), () -> {
                    JsonNode scenario = sample.get("scenario");
                    SimulationResult result = SIMULATOR.simulate(json, sample.get("input").toString(),
                            scenario == null ? null : scenario.toString(), context);
                    assertThat(result.status()).as("%s %s", result.inputErrors(), result.blockers())
                            .isEqualTo(SimulationResult.Status.FINISHED);
                    JsonNode trace = StrictJson.parse(result.json());
                    assertThat(trace.at("/outcome/businessOutcome").stringValue()).isEqualTo(sample.get("outcome").stringValue());
                    Set<String> visited = new LinkedHashSet<>();
                    trace.get("trace").forEach(e -> visited.add(e.get("stepId").stringValue()));
                    sample.get("visits").forEach(v -> assertThat(visited).as("path").contains(v.stringValue()));
                    sample.get("skips").forEach(v -> assertThat(visited).as("path").doesNotContain(v.stringValue()));
                }));
            }
        }
        return tests.stream();
    }

    private static String zonePrefix(String template) {
        return switch (template.substring(5, 7)) {
            case "in" -> "Asia/Kolkata";
            case "eu" -> "Europe/";
            case "ke" -> "Africa/Nairobi";
            case "ng" -> "Africa/Lagos";
            default -> throw new IllegalArgumentException(template);
        };
    }
}
