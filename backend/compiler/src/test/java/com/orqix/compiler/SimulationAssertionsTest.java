package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * STU03 assertions over dry-run traces. examples/fixtures/simulation/assertions.json is shared with the studio mock port
 * (frontend/src/mocks/assertions.test.ts); regenerate its expected results with -Dorqix.updateGolden=true.
 */
class SimulationAssertionsTest {

    private static final boolean UPDATE = Boolean.getBoolean("orqix.updateGolden");

    @Test
    void sharedFixtureGivesTheExpectedResults() throws IOException {
        Path file = TestSupport.repoRoot().resolve(SimulatorFixtures.DIR + "assertions.json");
        ObjectNode fixture = (ObjectNode) StrictJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        for (JsonNode c : fixture.get("cases")) {
            String trace = TestSupport.read(SimulatorFixtures.DIR + c.get("trace").stringValue() + ".trace.json");
            JsonNode actual = StrictJson.parse(SimulationAssertions.evaluate(trace, StrictJson.write(c.get("assertions"))));
            if (UPDATE) {
                ((ObjectNode) c).set("expected", actual);
            } else {
                assertThat(actual).as(c.get("id").stringValue()).isEqualTo(c.get("expected"));
            }
        }
        if (UPDATE) {
            Files.writeString(file, StrictJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(fixture)
                    .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        }
    }

    @Test
    void failedAssertionsSayWhatDiffered() {
        String trace = TestSupport.read(SimulatorFixtures.DIR + "purchase-1800.trace.json");
        JsonNode r = StrictJson.parse(SimulationAssertions.evaluate(trace, """
                {"outcome":"Rejected","path":{"steps":["start","manager","amountPolicy","route","finance"],"match":"exact"},
                 "rules":[{"stepId":"amountPolicy","rule":1}]}"""));
        assertThat(r.get("passed").booleanValue()).isFalse();
        assertThat(r.at("/results/0/expected").asString()).isEqualTo("Rejected");
        assertThat(r.at("/results/0/actual").asString()).isEqualTo("Fulfilled");
        assertThat(r.at("/results/1/detail").asString()).isEqualTo(
                "Step 5: expected \"Finance review\" but the run went to \"Create purchase order\".");
        assertThat(r.at("/results/2/actual").asString()).isEqualTo("otherwise");
    }

    @Test
    void noAssertionsPass() {
        String trace = TestSupport.read(SimulatorFixtures.DIR + "purchase-1800.trace.json");
        JsonNode r = StrictJson.parse(SimulationAssertions.evaluate(trace, null));
        assertThat(r.get("passed").booleanValue()).isTrue();
        assertThat(r.get("results")).isEmpty();
    }

    @Test
    void malformedAssertionsAreRefused() {
        for (String bad : new String[] {"[]", "{\"unknown\":1}", "{\"outputs\":[{\"field\":\"a\",\"op\":\"like\"}]}",
            "{\"outputs\":[{\"field\":\"a\",\"op\":\"range\"}]}", "{\"outputs\":[{\"field\":\"a\",\"op\":\"range\",\"min\":5,\"max\":1}]}",
            "{\"path\":{\"steps\":[]}}", "{\"rules\":[{\"stepId\":\"x\",\"rule\":0}]}",
            "{\"approvals\":[{\"stepId\":\"x\",\"decision\":\"maybe\"}]}", "{\"outcome\":5}"}) {
            assertThatThrownBy(() -> SimulationAssertions.validate(StrictJson.parse(bad))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
