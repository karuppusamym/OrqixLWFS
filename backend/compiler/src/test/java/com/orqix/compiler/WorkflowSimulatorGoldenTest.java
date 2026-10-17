package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/**
 * Golden traces of the dry run for the purchase and onboarding examples and the bounded-iteration examples. The same
 * files are replayed by the studio mock port (frontend/src/mocks/simulator.test.ts), so both implementations say the
 * same thing. Regenerate after an intentional change:
 * {@code ./mvnw -pl compiler test -Dtest=WorkflowSimulatorGoldenTest -Dorqix.updateGolden=true}.
 */
class WorkflowSimulatorGoldenTest {

    private static final boolean UPDATE = Boolean.getBoolean("orqix.updateGolden");

    @TestFactory
    Stream<DynamicTest> goldenTraces() {
        JsonNode cases = TestSupport.json(SimulatorFixtures.DIR + "cases.json").get("cases");
        return cases.valueStream().map(c -> DynamicTest.dynamicTest(c.get("id").stringValue(), () -> {
            JsonNode actual = SimulatorFixtures.run(TestSupport.read(c.get("definition").stringValue()),
                    StrictJson.write(c.get("input")), StrictJson.write(c.get("scenario")));
            Path file = TestSupport.repoRoot().resolve(SimulatorFixtures.DIR + c.get("id").stringValue()
                    + ".trace.json");
            if (UPDATE) {
                write(file, actual);
            }
            assertThat(Files.exists(file)).as("golden file " + file).isTrue();
            JsonNode golden = StrictJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(actual).isEqualTo(golden);
        }));
    }

    @Test
    void theHeadlineStoriesRead() {
        JsonNode r = SimulatorFixtures.run(TestSupport.purchaseJson(),
                "{\"title\":\"Server\",\"purpose\":\"x\",\"costCenter\":\"IT\",\"amount\":5000}", null);
        assertThat(r.at("/outcome/businessOutcome").asString()).isEqualTo("Fulfilled");
        assertThat(texts(r)).contains("\"Check purchase amount\": rule 1 applies because Amount (USD 5,000) is at"
                + " least USD 5,000, so route = financeReview.");
        assertThat(stepIds(r)).containsExactly("start", "manager", "amountPolicy", "route", "finance", "procure",
                "notify", "fulfilled");
    }

    private static java.util.List<String> texts(JsonNode r) {
        return r.get("trace").valueStream().map(e -> e.get("summary").stringValue()).toList();
    }

    private static java.util.List<String> stepIds(JsonNode r) {
        return r.get("trace").valueStream().map(e -> e.get("stepId").stringValue()).toList();
    }

    private static void write(Path file, JsonNode node) throws IOException {
        Files.writeString(file, StrictJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(node)
                .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void theSameRequestGivesTheSameTrace() {
        // The same request twice gives the same trace (determinism).
        String input = "{\"title\":\"T\",\"purpose\":\"p\",\"costCenter\":\"OPS\",\"amount\":10}";
        assertThat(SimulatorFixtures.run(TestSupport.purchaseJson(), input, "{}"))
                .isEqualTo(SimulatorFixtures.run(TestSupport.purchaseJson(), input, "{}"));
    }
}
