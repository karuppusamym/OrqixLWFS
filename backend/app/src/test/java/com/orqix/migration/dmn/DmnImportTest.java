package com.orqix.migration.dmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.migration.camunda.Limits;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.camunda.SourceException;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DMN import (Wave 2.4): hit policies UNIQUE and FIRST, literal FEEL unary tests, boundary behaviour through the compiler's
 * table evaluation, and every unsupported construct blocked with its own reason code. The fixtures are synthetic.
 */
class DmnImportTest {

    private static final Json JSON = new Json(JsonMapper.builder().build());

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("../../examples/migration/" + name));
    }

    private static DmnAnalysis analyze(String name) throws Exception {
        return DmnAnalyzer.analyze(fixture(name), Limits.DEFAULT, JSON);
    }

    /** The hosting workflow of a converted table, exactly as the importer would create it. */
    private static ObjectNode host(DmnTable table) {
        return DmnConverter.workflow(table, "dmn-host", "Host", JSON);
    }

    /** Compiler decision evaluation of the hosted table for one input value. */
    private static Map<String, Object> decide(DmnTable table, Object value) {
        ObjectNode definition = host(table);
        var result = Compilers.decisionEvaluator().evaluate(JSON.write(definition), DmnConverter.tableKey(table.decisionId()),
                Map.of("/input/" + table.inputKey(), value));
        return result.output();
    }

    private static void assertDecides(DmnTable table, Object value, Object... keyValuePairs) {
        Map<String, Object> actual = decide(table, value);
        assertThat(actual).as("input %s", value).hasSize(keyValuePairs.length / 2);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object expected = keyValuePairs[i + 1];
            Object got = actual.get((String) keyValuePairs[i]);
            if (expected instanceof Number n) {
                assertThat(new BigDecimal(got.toString())).as("%s for input %s", keyValuePairs[i], value)
                        .isEqualByComparingTo(new BigDecimal(n.toString()));
            } else {
                assertThat(got).as("%s for input %s", keyValuePairs[i], value).isEqualTo(expected);
            }
        }
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static List<Report.Issue> issues(DmnAnalysis a) {
        return a.report().issues();
    }

    @Test
    void compilerValidatesTheHostedDecisionOfEveryConvertibleFixture() throws Exception {
        for (String name : List.of("dmn-approval-level.dmn", "dmn-first-hit.dmn", "dmn-customer-tier.dmn", "dmn-integer-ranges.dmn")) {
            DmnAnalysis analysis = analyze(name);
            assertThat(analysis.tables()).as(name).hasSize(1);
            DmnTable table = analysis.tables().values().iterator().next();
            var report = Compilers.workflowCompiler().validate(JSON.write(host(table)),
                    new CompileContext(false, Map.of(), List.of(), Set.of()));
            assertThat(report.issues()).as("%s issues", name).isEmpty();
        }
    }

    @Test
    void uniqueTableWithABoundedMiddleRangeKeepsEveryThreshold() throws Exception {
        DmnAnalysis analysis = analyze("dmn-approval-level.dmn");
        DmnTable table = analysis.table("approvalLevel");
        assertThat(table.hitPolicy()).isEqualTo("UNIQUE");
        assertThat(table.inputKey()).isEqualTo("amount");
        var plan = table.plan();
        assertThat(plan.rows()).extracting(r -> r.op().canonical() + " " + r.value()).containsExactly("lt 1000", "gte 5000");
        assertThat(plan.defaultRuleId()).isEqualTo("ruleStandard");
        assertThat(plan.defaultUnreachable()).isFalse();
        // Hand-written expectations at, below and above every threshold.
        assertDecides(table, d("-5"), "level", "auto", "approvers", 0);
        assertDecides(table, d("0"), "level", "auto", "approvers", 0);
        assertDecides(table, d("999.99"), "level", "auto", "approvers", 0);
        assertDecides(table, d("1000"), "level", "manager", "approvers", 1);
        assertDecides(table, d("1000.00"), "level", "manager", "approvers", 1);
        assertDecides(table, d("1000.01"), "level", "manager", "approvers", 1);
        assertDecides(table, d("4999.99"), "level", "manager", "approvers", 1);
        assertDecides(table, d("5000"), "level", "finance", "approvers", 2);
        assertDecides(table, d("5000.01"), "level", "finance", "approvers", 2);
        assertDecides(table, d("1000000"), "level", "finance", "approvers", 2);
    }

    @Test
    void generatedBoundaryCasesAreRunAndReported() throws Exception {
        DmnAnalysis analysis = analyze("dmn-approval-level.dmn");
        var cases = analysis.report().testCases();
        assertThat(cases).isNotEmpty().allMatch(c -> "passed".equals(c.status()));
        assertThat(cases).extracting(c -> ((BigDecimal) c.input().get("amount")).toPlainString())
                .contains("999.99", "1000", "1000.01", "4999.99", "5000", "5000.01");
        // Source semantics and the compiler's evaluation agree on a case chosen by hand.
        var at5000 = cases.stream().filter(c -> c.name().equals("amount = 5000")).findFirst().orElseThrow();
        assertThat(at5000.expected()).containsEntry("level", "finance");
        assertThat(at5000.actual()).containsEntry("level", "finance");
        assertThat(at5000.matchedRow()).isEqualTo(1);
        var at1000 = cases.stream().filter(c -> c.name().equals("amount = 1000")).findFirst().orElseThrow();
        assertThat(at1000.matchedRow()).as("the default applied").isNull();
    }

    @Test
    void firstHitPolicyIsFoldedIntoExclusiveRows() throws Exception {
        DmnTable table = analyze("dmn-first-hit.dmn").table("reviewRoute");
        assertThat(table.hitPolicy()).isEqualTo("FIRST");
        assertThat(table.plan().rows()).extracting(r -> r.op().canonical() + " " + r.value()).containsExactly("gte 10000", "lt 5000");
        assertDecides(table, d("0"), "route", "manager");
        assertDecides(table, d("4999.99"), "route", "manager");
        assertDecides(table, d("5000"), "route", "finance");
        assertDecides(table, d("9999.99"), "route", "finance");
        assertDecides(table, d("10000"), "route", "director");
        assertDecides(table, d("10000.01"), "route", "director");
    }

    @Test
    void textListsBooleansAndACatchAllDefaultConvert() throws Exception {
        DmnAnalysis analysis = analyze("dmn-customer-tier.dmn");
        DmnTable table = analysis.table("customerDiscount");
        assertThat(table.inputType()).isEqualTo("string");
        assertThat(table.plan().rows()).extracting(r -> r.op().canonical() + " " + r.value())
                .containsExactly("eq gold", "eq platinum", "eq silver");
        assertThat(table.plan().defaultRuleId()).isEqualTo("ruleOther");
        assertDecides(table, "gold", "discount", d("0.20"));
        assertDecides(table, "platinum", "discount", d("0.20"));
        assertDecides(table, "silver", "discount", d("0.10"));
        assertDecides(table, "bronze", "discount", 0);
        assertDecides(table, "", "discount", 0);
        // The unused yes/no column is dropped and said so.
        assertThat(issues(analysis)).anySatisfy(i -> {
            assertThat(i.reasonCode()).isEqualTo("DMN_INPUT_UNUSED");
            assertThat(i.sourceElementId()).isEqualTo("vipColumn");
            assertThat(i.severity()).isEqualTo(Report.INFO);
        });
    }

    @Test
    void integerRangesAreInclusiveAndNormalised() throws Exception {
        DmnTable table = analyze("dmn-integer-ranges.dmn").table("batchSize");
        assertThat(table.inputType()).isEqualTo("integer");
        assertThat(table.plan().rows()).extracting(r -> r.op().canonical() + " " + r.value()).containsExactly("lte 5", "gte 11");
        for (var expected : new TreeMap<>(Map.of(-3, "small", 0, "small", 5, "small", 6, "medium", 10, "medium", 11, "large", 25, "large")).entrySet()) {
            assertDecides(table, BigDecimal.valueOf(expected.getKey()), "sizeClass", expected.getValue());
        }
    }

    @Test
    void reportCarriesReviewItemsThatNeverBlockAndInfoItemsThatNeedAcknowledgement() throws Exception {
        DmnAnalysis analysis = analyze("dmn-approval-level.dmn");
        var report = analysis.report();
        assertThat(report.sourceSystem()).isEqualTo("dmn");
        assertThat(report.adapterVersion()).isEqualTo(DmnAnalyzer.ADAPTER_VERSION);
        assertThat(report.elementCounts().review()).isGreaterThan(0).isEqualTo((int) report.issues().stream()
                .filter(i -> Report.REVIEW.equals(i.severity())).count());
        assertThat(report.elementCounts().blocker()).isZero();
        assertThat(report.issues()).extracting(Report.Issue::reasonCode).contains("DMN_DECISION_CONVERTED", "DMN_HIT_POLICY_MAPPED",
                "DMN_INPUT_MAPPED", "DMN_OUTPUT_MAPPED", "DMN_RULE_CONVERTED", "SETTING_NOT_IMPORTED");
        // camunda:historyTimeToLive is an info item: the report is not ready until it is acknowledged; review items never matter.
        assertThat(report.publishable()).isFalse();
        var acknowledged = analysis.report(Set.of("approvalLevel"), true, false);
        assertThat(acknowledged.publishable()).isTrue();
        assertThat(acknowledged.issues()).filteredOn(i -> Report.REVIEW.equals(i.severity()))
                .allMatch(i -> Report.OPEN.equals(i.resolutionStatus()));
        assertThat(analysis.report(Set.of("approvalLevel"), true, true).issues()).filteredOn(i -> Report.REVIEW.equals(i.severity()))
                .allMatch(i -> Report.ACKNOWLEDGED.equals(i.resolutionStatus()));
        // The rule items preserve the original cell text.
        assertThat(report.issues()).filteredOn(i -> i.sourceElementId().equals("ruleStandard")).singleElement()
                .satisfies(i -> assertThat(i.explanation()).contains("[1000..5000)", "manager", "default result"));
    }

    /** Reason codes expected per decision of the unsupported fixture. */
    private static final Map<String, String> BLOCKED = Map.ofEntries(
            Map.entry("collectPolicy", "DMN_HIT_POLICY_NOT_SUPPORTED"),
            Map.entry("ruleOrderPolicy", "DMN_HIT_POLICY_NOT_SUPPORTED"),
            Map.entry("expressionEntry", "DMN_ENTRY_NOT_SUPPORTED"),
            Map.entry("computedOutput", "DMN_OUTPUT_EXPRESSION_NOT_SUPPORTED"),
            Map.entry("twoInputs", "DMN_MULTIPLE_INPUTS_NOT_SUPPORTED"),
            Map.entry("dateInput", "DMN_TYPE_NOT_SUPPORTED"),
            Map.entry("propertyInput", "DMN_INPUT_EXPRESSION_NOT_SUPPORTED"),
            Map.entry("uniqueOverlap", "DMN_UNIQUE_OVERLAP"),
            Map.entry("gapWithoutCatchAll", "DMN_NO_MATCH_NOT_REPRESENTABLE"),
            Map.entry("twoBoundedRanges", "DMN_RULE_NOT_REPRESENTABLE"),
            Map.entry("literalOnly", "DMN_DECISION_TYPE_NOT_SUPPORTED"),
            Map.entry("chained", "DMN_DRD_NOT_SUPPORTED"),
            Map.entry("scriptEntry", "DMN_ENTRY_NOT_SUPPORTED"));

    @Test
    void everyUnsupportedConstructIsABlockerWithItsOwnReasonCode() throws Exception {
        DmnAnalysis analysis = analyze("dmn-unsupported.dmn");
        var report = analysis.report();
        assertThat(analysis.tables()).as("nothing in the unsupported fixture converts").isEmpty();
        assertThat(analysis.decisionNames().keySet()).containsExactlyInAnyOrderElementsOf(BLOCKED.keySet());
        assertThat(report.publishable()).isFalse();
        for (var expected : BLOCKED.entrySet()) {
            var blockers = analysis.blockersOf(expected.getKey());
            assertThat(blockers).as("blockers of %s", expected.getKey()).extracting(Report.Issue::reasonCode).contains(expected.getValue())
                    .contains("DMN_DECISION_NOT_CONVERTIBLE");
        }
        assertThat(report.issues()).filteredOn(i -> Report.BLOCKER.equals(i.severity())).allMatch(i -> Report.OPEN.equals(i.resolutionStatus()));
        // Acknowledging cannot unblock anything.
        assertThat(analysis.report(null, true, true).publishable()).isFalse();
        assertThat(analysis.report(null, true, true).testCases()).isEmpty();
    }

    @Test
    void blockedDecisionsOfOtherTablesDoNotBlockTheSelectedOne() throws Exception {
        String merged = fixture("dmn-approval-level.dmn").replace("</definitions>", """
                <decision id="broken"><decisionTable id="brokenTable" hitPolicy="COLLECT"><input id="bi"><inputExpression typeRef="number"><text>x</text></inputExpression></input>
                <output id="bo" name="y" typeRef="number"/><rule id="br"><inputEntry><text>&gt; 1</text></inputEntry><outputEntry><text>1</text></outputEntry></rule></decisionTable></decision></definitions>""");
        DmnAnalysis analysis = DmnAnalyzer.analyze(merged, Limits.DEFAULT, JSON);
        assertThat(analysis.table("approvalLevel")).isNotNull();
        assertThat(analysis.table("broken")).isNull();
        assertThat(analysis.report().publishable()).isFalse();
        var selected = analysis.report(Set.of("approvalLevel"), true, true);
        assertThat(selected.publishable()).isTrue();
        assertThat(selected.issues()).filteredOn(i -> i.sourceElementId().equals("br")).allMatch(i -> Report.NOT_NEEDED.equals(i.resolutionStatus()));
        assertThat(selected.elementCounts().blocker()).as("blockers stay visible in the counts").isGreaterThan(0);
    }

    @Test
    void everyDecisionTableAndRuleHasADisposition() throws Exception {
        for (String name : List.of("dmn-approval-level.dmn", "dmn-first-hit.dmn", "dmn-customer-tier.dmn", "dmn-integer-ranges.dmn",
                "dmn-unsupported.dmn")) {
            String xml = fixture(name);
            DmnAnalysis analysis = DmnAnalyzer.analyze(xml, Limits.DEFAULT, JSON);
            Set<String> reported = issues(analysis).stream().map(Report.Issue::sourceElementId).collect(Collectors.toSet());
            var matcher = java.util.regex.Pattern.compile("<(decision|decisionTable|input|output|rule) [^>]*?id=\"([^\"]+)\"").matcher(xml);
            while (matcher.find()) {
                assertThat(reported).as("%s: %s %s", name, matcher.group(1), matcher.group(2)).contains(matcher.group(2));
            }
        }
    }

    @Test
    void notDmnAndHostileFilesAreRejectedBeforeAnyAnalysis() {
        assertThatThrownBy(() -> DmnAnalyzer.analyze("<definitions xmlns=\"urn:other\"/>", Limits.DEFAULT, JSON))
                .isInstanceOf(SourceException.class).hasMessageContaining("not a DMN");
        assertThatThrownBy(() -> DmnAnalyzer.analyze("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><definitions>&e;</definitions>",
                Limits.DEFAULT, JSON)).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> DmnAnalyzer.analyze("not xml", Limits.DEFAULT, JSON)).isInstanceOf(SourceException.class);
    }

    @Test
    void aFileWithoutDecisionsIsABlocker() {
        var analysis = DmnAnalyzer.analyze("<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\"/>", Limits.DEFAULT, JSON);
        assertThat(analysis.report().issues()).extracting(Report.Issue::reasonCode).contains("DMN_NO_DECISION");
        assertThat(analysis.report().publishable()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------ entries

    @ParameterizedTest
    @ValueSource(strings = {">= 5000", "> 5000", "< 5000", "<= 5000", "5000", "-5000.5", "[1..2]", "(1..2)", "]1..2[", "[1..2)", "(1..2]",
            "1, 2, 3", "< 0, > 10", "[1..2], 5", "not(5)", "not(1, 2)", "not([1..2])", "-", ""})
    void numberEntriesParse(String entry) throws Exception {
        assertThat(DmnEntries.parseInput(entry, "number", null)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", ">= limit", "> 1 + 2", "max(1,2)", "date(\"2026-01-01\")", "null", "[1..]", "[a..b]", "\"text\"", "true",
            "? > 5", "> 5000 and < 10", "[5..1]", "1,", "not()", "= 5", "5 .. 6"})
    void computedOrMistypedNumberEntriesAreRejectedWithAReason(String entry) {
        assertThatThrownBy(() -> DmnEntries.parseInput(entry, "number", null)).isInstanceOf(DmnEntries.EntryProblem.class)
                .satisfies(e -> assertThat(((DmnEntries.EntryProblem) e).code()).startsWith("DMN_"));
    }

    @Test
    void textBooleanAndOutputEntries() throws Exception {
        assertThat(DmnEntries.parseInput("\"a\", \"b,c\"", "string", null).members()).containsExactly("a", "b,c");
        assertThat(DmnEntries.parseInput("not(\"a\")", "string", null).isComplement()).isTrue();
        assertThat(DmnEntries.parseInput("true", "boolean", Set.of(true, false)).members()).containsExactly(true);
        assertThat(DmnEntries.parseInput("not(true)", "boolean", new java.util.LinkedHashSet<>(List.of(true, false))).members()).containsExactly(false);
        assertThatThrownBy(() -> DmnEntries.parseInput("a", "string", null)).isInstanceOf(DmnEntries.EntryProblem.class);
        assertThatThrownBy(() -> DmnEntries.parseInput("\"a;b\"", "string", null)).extracting(e -> ((DmnEntries.EntryProblem) e).code())
                .isEqualTo("DMN_LITERAL_NOT_ALLOWED");
        assertThatThrownBy(() -> DmnEntries.parseInput("\"c\"", "string", new java.util.LinkedHashSet<>(List.of("a", "b"))))
                .extracting(e -> ((DmnEntries.EntryProblem) e).code()).isEqualTo("DMN_ENTRY_OUTSIDE_INPUT_VALUES");
        assertThatThrownBy(() -> DmnEntries.parseInput("1.5", "integer", null)).extracting(e -> ((DmnEntries.EntryProblem) e).code())
                .isEqualTo("DMN_LITERAL_TYPE_MISMATCH");
        assertThat(DmnEntries.parseOutput("\"x\"", "string")).isEqualTo("x");
        assertThat(DmnEntries.parseOutput("12.50", "number")).isEqualTo(d("12.50"));
        assertThat(DmnEntries.parseOutput("true", "boolean")).isEqualTo(true);
        for (String[] bad : new String[][] {{"", "string"}, {"x", "string"}, {"x + 1", "number"}, {"1", "boolean"}}) {
            assertThatThrownBy(() -> DmnEntries.parseOutput(bad[0], bad[1])).isInstanceOf(DmnEntries.EntryProblem.class);
        }
    }

    // ------------------------------------------------------------------------------------------------ fuzz

    /** An independent, deliberately naive reading of the entry grammar used by the fuzz generator. */
    private static boolean oracleMatches(String entry, BigDecimal v) {
        String text = entry.strip();
        boolean negate = false;
        if (text.startsWith("not(")) {
            negate = true;
            text = text.substring(4, text.length() - 1);
        }
        boolean hit = false;
        for (String item : text.split(",")) {
            String t = item.strip();
            boolean m;
            if (t.equals("-") || t.isEmpty()) {
                m = true;
            } else if (t.startsWith(">=")) {
                m = v.compareTo(d(t.substring(2).strip())) >= 0;
            } else if (t.startsWith("<=")) {
                m = v.compareTo(d(t.substring(2).strip())) <= 0;
            } else if (t.startsWith(">")) {
                m = v.compareTo(d(t.substring(1).strip())) > 0;
            } else if (t.startsWith("<")) {
                m = v.compareTo(d(t.substring(1).strip())) < 0;
            } else if (t.contains("..")) {
                String[] ends = t.substring(1, t.length() - 1).split("\\.\\.");
                BigDecimal lo = d(ends[0].strip());
                BigDecimal hi = d(ends[1].strip());
                int a = v.compareTo(lo);
                int b = v.compareTo(hi);
                m = (t.startsWith("[") ? a >= 0 : a > 0) && (t.endsWith("]") ? b <= 0 : b < 0);
            } else {
                m = v.compareTo(d(t)) == 0;
            }
            hit |= m;
        }
        return negate != hit;
    }

    private static String randomEntry(Random random, boolean integer) {
        int k = random.nextInt(11);
        int j = k + 1 + random.nextInt(4);
        return switch (random.nextInt(integer ? 10 : 12)) {
            case 0 -> "-";
            case 1 -> ">= " + k;
            case 2 -> "> " + k;
            case 3 -> "< " + k;
            case 4 -> "<= " + k;
            case 5 -> String.valueOf(k);
            case 6 -> "[" + k + ".." + j + "]";
            case 7 -> "[" + k + ".." + j + ")";
            case 8 -> "(" + k + ".." + j + "]";
            case 9 -> k + ", " + j;
            case 10 -> "not(" + k + ")";
            default -> "(" + k + ".." + j + ")";
        };
    }

    private static String randomTable(Random random, boolean integer, boolean first, List<String> entries, List<Integer> outs) {
        StringBuilder xml = new StringBuilder("<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"fuzz\"><decision id=\"fuzz\">"
                + "<decisionTable id=\"t\" hitPolicy=\"" + (first ? "FIRST" : "UNIQUE") + "\"><input id=\"i\"><inputExpression typeRef=\""
                + (integer ? "integer" : "number") + "\"><text>x</text></inputExpression></input><output id=\"o\" name=\"r\" typeRef=\"string\"/>");
        List<String> generated = new ArrayList<>();
        int mode = random.nextInt(3);
        if (mode == 0 && !first) {
            // A partition of the number line by sorted thresholds: exclusive by construction, usually exhaustive.
            int thresholds = 1 + random.nextInt(4);
            java.util.TreeSet<Integer> points = new java.util.TreeSet<>();
            while (points.size() < thresholds) {
                points.add(random.nextInt(11));
            }
            List<Integer> sorted = new ArrayList<>(points);
            generated.add("< " + sorted.get(0));
            for (int i = 0; i + 1 < sorted.size(); i++) {
                generated.add("[" + sorted.get(i) + ".." + sorted.get(i + 1) + ")");
            }
            generated.add(">= " + sorted.get(sorted.size() - 1));
            java.util.Collections.shuffle(generated, random);
        } else {
            int rules = 1 + random.nextInt(5);
            for (int r = 0; r < rules; r++) {
                generated.add(randomEntry(random, integer));
            }
            if (mode == 1 && first) {
                generated.add("-");
            }
        }
        for (int r = 0; r < generated.size(); r++) {
            String entry = generated.get(r);
            entries.add(entry);
            int out = random.nextInt(3);
            outs.add(out);
            xml.append("<rule id=\"r").append(r).append("\"><inputEntry><text>").append(entry.replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</text></inputEntry><outputEntry><text>\"out").append(out).append("\"</text></outputEntry></rule>");
        }
        return xml.append("</decisionTable></decision></definitions>").toString();
    }

    @Test
    void randomTablesNeverChangeWhichRuleFiresAndOverlapsAndGapsAreBlocked() {
        Random random = new Random(20261010L);
        int converted = 0;
        int blocked = 0;
        for (int round = 0; round < 600; round++) {
            boolean integer = round % 3 == 0;
            boolean first = round % 2 == 0;
            List<String> entries = new ArrayList<>();
            List<Integer> outs = new ArrayList<>();
            String xml = randomTable(random, integer, first, entries, outs);
            DmnAnalysis analysis = DmnAnalyzer.analyze(xml, Limits.DEFAULT, JSON);
            DmnTable table = analysis.table("fuzz");
            // Independent oracle on a grid that covers every threshold from below, at and above.
            List<BigDecimal> grid = new ArrayList<>();
            for (int quarter = -8; quarter <= 64; quarter++) {
                if (!integer || quarter % 4 == 0) {
                    grid.add(BigDecimal.valueOf(quarter).divide(BigDecimal.valueOf(4)));
                }
            }
            boolean overlap = false;
            boolean gap = false;
            Map<BigDecimal, Integer> expectedRule = new LinkedHashMap<>();
            for (BigDecimal v : grid) {
                List<Integer> hits = new ArrayList<>();
                for (int r = 0; r < entries.size(); r++) {
                    if (oracleMatches(entries.get(r), v)) {
                        hits.add(r);
                    }
                }
                if (hits.isEmpty()) {
                    gap = true;
                } else if (hits.size() > 1 && !first) {
                    overlap = true;
                }
                expectedRule.put(v, hits.isEmpty() ? -1 : hits.get(0));
            }
            if (table == null) {
                blocked++;
                assertThat(analysis.report().publishable()).as(xml).isFalse();
                continue;
            }
            converted++;
            assertThat(overlap).as("an overlapping UNIQUE table must not convert: %s %s", entries, xml).isFalse();
            assertThat(gap).as("a table with unmatched values must not convert: %s", entries).isFalse();
            for (BigDecimal v : grid) {
                String expected = "out" + outs.get(expectedRule.get(v));
                assertThat(decide(table, v).get("r")).as("input %s of %s (%s)", v, entries, first ? "FIRST" : "UNIQUE").isEqualTo(expected);
            }
            assertThat(analysis.testCases()).allMatch(c -> "passed".equals(c.status()));
        }
        assertThat(converted).as("the generator must produce convertible tables, not only blockers").isGreaterThan(80);
        assertThat(blocked).isGreaterThan(80);
    }

    @Test
    void theSampleTablesStayConvertedWhenAnInfoItemIsAcknowledgedAndNothingElseChanges() throws Exception {
        JsonNode workflow = host(analyze("dmn-approval-level.dmn").table("approvalLevel"));
        assertThat(workflow.path("decisions").get(0).path("rows")).hasSize(2);
        assertThat(workflow.path("outputMapping").propertyNames()).containsExactly("level", "approvers");
        assertThat(workflow.path("nodes").get(1).path("type").asString()).isEqualTo("decision");
    }
}
