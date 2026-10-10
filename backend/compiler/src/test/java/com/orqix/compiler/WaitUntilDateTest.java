package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Wait until a date" (S12 section 10): the date form field type, the wait step's {@code untilField} and
 * {@code atTime}, their validation rules, the generated timer and its golden files. Node indexes of
 * {@code examples/start-date-onboarding.json}: 0 start, 1 confirm, 2 untilStart (wait), 3 notifyStarted, 4 started,
 * 5 notifyRejected, 6 declined.
 */
class WaitUntilDateTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final String SAMPLE = "examples/start-date-onboarding.json";

    private static ObjectNode sample() {
        return (ObjectNode) TestSupport.json(SAMPLE);
    }

    private static ObjectNode config(ObjectNode doc, int node) {
        return (ObjectNode) doc.get("nodes").get(node).get("config");
    }

    private static ObjectNode field(ObjectNode doc, String key) {
        return (ObjectNode) doc.get("inputSchema").get("properties").get(key);
    }

    private static JsonNode parse(String json) {
        return StrictJson.parse(json);
    }

    private static List<String> issues(Consumer<ObjectNode> patch) {
        ObjectNode doc = sample();
        patch.accept(doc);
        ValidationReport report = COMPILER.validate(TestSupport.text(doc), TestSupport.defaultContext());
        return report.issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    private static void durationWait(ObjectNode d, String duration) {
        config(d, 2).remove("untilField");
        config(d, 2).remove("atTime");
        config(d, 2).put("duration", duration);
    }

    @Test
    void theSampleIsValidAndPublishable() {
        ValidationReport report = COMPILER.validate(TestSupport.read(SAMPLE), TestSupport.defaultContext());
        assertThat(report.issues()).isEmpty();
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void exactlyOneOfDurationAndDate() {
        assertThat(issues(d -> config(d, 2).put("duration", "PT1H")))
                .containsExactly("B WF_WAIT_CHOICE /nodes/2/config/untilField");
        assertThat(issues(d -> {
            config(d, 2).remove("untilField");
            config(d, 2).remove("atTime");
        })).containsExactly("B WF_WAIT_CHOICE /nodes/2/config/duration");
        // A time of day without a date is a schema error.
        assertThat(issues(d -> config(d, 2).remove("untilField")))
                .contains("B WF_SCHEMA_INVALID /nodes/2/config");
    }

    @Test
    void aDurationWaitIsUnchanged() {
        assertThat(issues(d -> durationWait(d, "P2D"))).isEmpty();
        assertThat(issues(d -> durationWait(d, "P91D")))
                .containsExactly("B WF_DURATION_TOO_LONG /nodes/2/config/duration");
    }

    @Test
    void theFieldMustBeAnExistingDateThatAlwaysHasAValue() {
        assertThat(issues(d -> config(d, 2).put("untilField", "/input/employeeName")))
                .containsExactly("B WF_WAIT_FIELD_TYPE /nodes/2/config/untilField");
        assertThat(issues(d -> config(d, 2).put("untilField", "/input/missing")))
                .containsExactly("B WF_FIELDREF_UNKNOWN /nodes/2/config/untilField");
        assertThat(issues(d -> config(d, 2).put("untilField", "/steps/confirm/decidedAt")))
                .containsExactly("B WF_WAIT_FIELD_TYPE /nodes/2/config/untilField");
        assertThat(issues(d -> ((ArrayNode) d.get("inputSchema").get("required")).remove(1)))
                .containsExactly("B WF_WAIT_FIELD_OPTIONAL /nodes/2/config/untilField");
    }

    @Test
    void theTimeOfDayIsCheckedBySchema() {
        for (String bad : List.of("25:00", "9:00", "09:60", "09:00:00")) {
            assertThat(issues(d -> config(d, 2).put("atTime", bad))).as(bad)
                    .containsExactly("B WF_SCHEMA_INVALID /nodes/2/config/atTime");
        }
        assertThat(issues(d -> config(d, 2).put("atTime", "23:59"))).isEmpty();
        assertThat(issues(d -> config(d, 2).remove("atTime"))).isEmpty();
    }

    @Test
    void dateFieldRules() {
        // format needs a string; bounds need a date format; bounds must be real days in order.
        assertThat(issues(d -> field(d, "startDate").put("type", "number")))
                .contains("B WF_SCHEMA_INVALID /inputSchema/properties/startDate/type");
        assertThat(issues(d -> field(d, "employeeName").put("formatMinimum", "2026-01-01")))
                .contains("B WF_SCHEMA_INVALID /inputSchema/properties/employeeName");
        assertThat(issues(d -> field(d, "startDate").put("formatMinimum", "2026-02-30")))
                .containsExactly("B WF_FORM_INVALID /inputSchema/properties/startDate/formatMinimum");
        assertThat(issues(d -> {
            field(d, "startDate").put("formatMinimum", "2026-12-31");
            field(d, "startDate").put("formatMaximum", "2026-01-01");
        })).containsExactly("B WF_FORM_INVALID /inputSchema/properties/startDate/formatMinimum");
        assertThat(issues(d -> {
            field(d, "startDate").put("formatMinimum", "2026-01-01");
            field(d, "startDate").put("formatMaximum", "2026-12-31");
        })).isEmpty();
        assertThat(issues(d -> field(d, "startDate").put("maxLength", 10)))
                .containsExactly("B WF_FORM_INVALID /inputSchema/properties/startDate/maxLength");
        assertThat(issues(d -> field(d, "startDate").put("minimum", 1)))
                .containsExactly("B WF_FORM_INVALID /inputSchema/properties/startDate/minimum");
    }

    @Test
    void aDateCannotBeComparedInACondition() {
        assertThat(issues(d -> {
            ((ArrayNode) d.get("nodes")).add(parse("{\"id\":\"route\",\"type\":\"branch\","
                    + "\"label\":\"Starts on a known day?\",\"config\":{}}"));
            ArrayNode transitions = (ArrayNode) d.get("transitions");
            ((ObjectNode) transitions.get(1)).put("to", "route");
            transitions.add(parse("{\"id\":\"t7\",\"from\":\"route\",\"to\":\"untilStart\",\"on\":\"condition\","
                    + "\"label\":\"Known day\",\"condition\":{\"fieldRef\":\"/input/startDate\",\"operator\":\"eq\","
                    + "\"value\":\"2026-10-14\"}}"));
            transitions.add(parse("{\"id\":\"t8\",\"from\":\"route\",\"to\":\"untilStart\",\"on\":\"default\"}"));
        })).contains("B WF_OPERATOR_TYPE /transitions/6/condition/operator");
    }

    @Test
    void generatesATimerWithAComputedDueDate() throws Exception {
        String xml = COMPILER.compile(TestSupport.read(SAMPLE), TestSupport.defaultContext()).bpmnXml();
        assertThat(xml).contains("<orqix:wait untilField=\"/input/startDate\" atTime=\"09:00\"/>")
                .contains("<timeDate><![CDATA[${orqixWait.until(execution, 'untilStart')}]]></timeDate>")
                .doesNotContain("timeDuration");
        // The time of day defaults to 09:00 in the generated step even when the definition leaves it out.
        ObjectNode doc = sample();
        config(doc, 2).remove("atTime");
        assertThat(COMPILER.compile(TestSupport.text(doc), TestSupport.defaultContext()).bpmnXml())
                .contains("atTime=\"09:00\"");
    }

    @Test
    void theOnlyNewExpressionIsTheWaitBean() throws Exception {
        String xml = COMPILER.compile(TestSupport.read(SAMPLE), TestSupport.defaultContext()).bpmnXml();
        var m = Pattern.compile("[$#]\\{[^}]*}").matcher(xml);
        int count = 0;
        while (m.find()) {
            count++;
            assertThat(m.group()).matches("\\$\\{(orqixRoute\\.(approved|rejected)\\(execution, '[A-Za-z0-9_-]+'\\)"
                    + "|orqixWait\\.until\\(execution, '[A-Za-z][A-Za-z0-9_-]*'\\)|orqixNotificationTask)}");
        }
        assertThat(count).isEqualTo(5);
    }

    // ---------------------------------------------------------------- golden files and determinism

    private static Path goldenDir() {
        String dir = System.getProperty("orqix.goldenDir");
        Path base = dir == null ? Path.of("src/test/resources/golden") : Path.of(dir);
        return base.resolve("start-date-onboarding");
    }

    /** Regenerate with {@code -Dorqix.updateGolden=true} after an intentional output change. */
    @Test
    void matchesGoldenFiles() throws Exception {
        CompiledWorkflow w = COMPILER.compile(TestSupport.read(SAMPLE), TestSupport.defaultContext());
        Map<String, String> files = GoldenFilesTest.artifacts(w);
        Path dir = goldenDir();
        if (Boolean.getBoolean("orqix.updateGolden")) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path file = dir.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            byte[] expected = Files.readAllBytes(dir.resolve(e.getKey()));
            assertThat(e.getValue().getBytes(StandardCharsets.UTF_8)).as(e.getKey()).isEqualTo(expected);
        }
    }

    @Test
    void keyOrderDoesNotChangeTheArtifacts() throws Exception {
        CompiledWorkflow first = COMPILER.compile(TestSupport.read(SAMPLE), TestSupport.defaultContext());
        for (long seed = 1; seed <= 5; seed++) {
            String shuffled = TestSupport.text(TestSupport.shuffleKeys(sample(), new Random(seed)));
            assertThat(COMPILER.compile(shuffled, TestSupport.defaultContext())).isEqualTo(first);
        }
    }

    // ---------------------------------------------------------------- the form validator

    private static final String FORM = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"day\"],"
            + "\"properties\":{\"day\":{\"type\":\"string\",\"format\":\"date\",\"title\":\"Day\","
            + "\"formatMinimum\":\"2026-10-01\",\"formatMaximum\":\"2026-12-31\"},"
            + "\"free\":{\"type\":\"string\",\"format\":\"date\",\"title\":\"Free\"}}}";

    private static List<FormValidator.FieldError> validate(String json) {
        return Compilers.formValidator().validate(FORM, json);
    }

    @Test
    void validatesCalendarDates() {
        assertThat(validate("{\"day\":\"2026-10-14\"}")).isEmpty();
        assertThat(validate("{\"day\":\"2026-10-01\",\"free\":\"1999-01-31\"}")).isEmpty();
        assertThat(validate("{\"day\":\"2026-12-31\"}")).isEmpty();
        assertThat(validate("{\"day\":\"2026-09-30\"}")).containsExactly(new FormValidator.FieldError("/day",
                "formatMinimum", "Choose a date on or after 1 Oct 2026."));
        assertThat(validate("{\"day\":\"2027-01-01\"}")).containsExactly(new FormValidator.FieldError("/day",
                "formatMaximum", "Choose a date on or before 31 Dec 2026."));
        for (String bad : List.of("2026-02-30", "2026-13-01", "14/10/2026", "2026-10-14T09:00:00Z", "2026-1-5", "",
                "tomorrow", " 2026-10-14", "2027-02-29")) {
            assertThat(validate("{\"day\":\"" + bad + "\"}")).as(bad).containsExactly(new FormValidator.FieldError(
                    "/day", "format", "Enter a date in the form YYYY-MM-DD."));
        }
        assertThat(validate("{\"day\":20261014}")).extracting(FormValidator.FieldError::code).containsExactly("type");
        assertThat(validate("{\"free\":\"2026-10-14\"}")).extracting(FormValidator.FieldError::code)
                .containsExactly("required");
        assertThat(validate("{\"day\":\"2028-02-29\"}")).extracting(FormValidator.FieldError::code)
                .containsExactly("formatMaximum");
    }
}
