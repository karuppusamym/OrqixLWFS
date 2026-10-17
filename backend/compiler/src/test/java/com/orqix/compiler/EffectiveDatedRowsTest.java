package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** RL03 effective-dated decision rows (docs/build/S41). */
class EffectiveDatedRowsTest {

    private static final tools.jackson.databind.json.JsonMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final String AS_OF = "$asOf";

    /** Rows with the same condition (amount >= 5000) and the given windows, e.g. "2026-01-01..2026-06-30". */
    private static ObjectNode withWindows(String hitPolicy, String... windows) throws Exception {
        ObjectNode d = TestSupport.purchase();
        ObjectNode table = (ObjectNode) d.path("decisions").get(0);
        ObjectNode template = (ObjectNode) table.path("rows").get(0).deepCopy();
        ArrayNode rows = table.putArray("rows");
        for (String window : windows) {
            ObjectNode row = template.deepCopy();
            String[] parts = window.split(java.util.regex.Pattern.quote(".."), -1);
            ObjectNode effective = row.putObject("effective");
            if (!parts[0].isEmpty()) effective.put("from", parts[0]);
            if (!parts[1].isEmpty()) effective.put("to", parts[1]);
            rows.add(row);
        }
        table.put("hitPolicy", hitPolicy);
        return d;
    }

    private static boolean has(ObjectNode d, String code) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues().stream()
                .anyMatch(i -> i.code().equals(code));
    }

    @Test
    void rowsInDisjointWindowsMayShareACondition() throws Exception {
        assertThat(has(withWindows("UNIQUE", "2026-01-01..2026-06-30", "2026-07-01.."), "WF_DECISION_OVERLAP")).isFalse();
        assertThat(has(withWindows("UNIQUE", "2026-01-01..2026-07-01", "2026-07-01.."), "WF_DECISION_OVERLAP")).as("one shared day").isTrue();
        assertThat(has(withWindows("UNIQUE", "..2026-06-30", "2026-07-01.."), "WF_DECISION_OVERLAP")).as("open ends").isFalse();
        // A row without a window applies always, so it overlaps a dated row with the same condition.
        ObjectNode mixed = withWindows("UNIQUE", "2026-01-01..2026-06-30", "2026-07-01..");
        ((ObjectNode) mixed.path("decisions").get(0).path("rows").get(1)).remove("effective");
        assertThat(has(mixed, "WF_DECISION_OVERLAP")).isTrue();
    }

    @Test
    void theEvaluatorUsesTheRowThatIsInForceOnTheGivenDay() throws Exception {
        String text = withWindows("UNIQUE", "2026-01-01..2026-06-30", "2026-07-01..").toString();
        var eval = Compilers.decisionEvaluator();
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 6000, AS_OF, "2026-03-01")).matchedRow()).isEqualTo(0);
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 6000, AS_OF, LocalDate.of(2026, 7, 1))).matchedRow()).isEqualTo(1);
        assertThat(eval.evaluate(text, "amount-policy", Map.of("/input/amount", 6000, AS_OF, "2025-12-31")).usedDefault())
                .as("before every window the default applies").isTrue();
        assertThatThrownBy(() -> eval.evaluate(text, "amount-policy", Map.of("/input/amount", 6000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(AS_OF);
    }

    @Test
    void theCompiledDmnComparesTheSubmissionDate() throws Exception {
        String text = withWindows("UNIQUE", "2026-01-01..2026-06-30", "2026-07-01..").toString();
        var issues = Compilers.workflowCompiler().validate(text, SimulatorFixtures.context().workspace()).issues();
        assertThat(issues.stream().filter(i -> i.severity() == Severity.BLOCKER)).isEmpty();
        String dmn = Compilers.workflowCompiler().compile(text, SimulatorFixtures.context().workspace())
                .dmnResources().get("decisions/amount-policy.dmn");
        assertThat(dmn).contains("asOf", ">= 20260101", "<= 20260630", ">= 20260701");
        assertThat(dmn.split("<input ", -1).length - 1).as("one amount column and two date columns").isEqualTo(3);
    }

    @Test
    void impossibleWindowsAreRejected() {
        assertThatThrownBy(() -> Compilers.decisionEvaluator().evaluate(withWindows("UNIQUE", "2026-07-01..2026-01-01").toString(),
                "amount-policy", Map.of("/input/amount", 1, AS_OF, "2026-03-01")))
                .isInstanceOf(Exception.class);
    }

    @Test
    void theSimulatorJudgesDatedRowsByTheSimulatedDay() throws Exception {
        // SimulationContext.today() is 2026-01-15 unless a test sets it.
        String text = withWindows("UNIQUE", "2026-01-01..2026-06-30", "2026-07-01..").toString();
        var trace = SimulatorFixtures.run(text,
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":6000}", "{}");
        assertThat(trace.toString()).contains("\"asOf\":\"2026-01-15\"", "2026-01-01 to 2026-06-30");
    }
}
