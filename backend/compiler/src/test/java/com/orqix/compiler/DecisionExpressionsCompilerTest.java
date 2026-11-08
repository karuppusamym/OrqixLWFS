package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Orqix expressions as decision table input and output entries (S146 part A, doc 29 CG-D04): checks, overlap
 * handling, engine DMN, pinned types in the BPMN, standard DMN export and the simulator.
 */
class DecisionExpressionsCompilerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String KEY = "amount-policy";
    private static final String DMN = "decisions/amount-policy.dmn";
    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\","
            + "\"amount\":6000,\"limit\":5000,\"due\":\"2026-02-01\"}";

    /** The purchase example with a limit (number), a due date and a meeting time, and the given rows. */
    private static ObjectNode workflow(String hitPolicy, String... rows) {
        ObjectNode d = TestSupport.purchase();
        ObjectNode props = (ObjectNode) d.path("inputSchema").path("properties");
        props.putObject("limit").put("type", "number").put("title", "Limit");
        props.putObject("due").put("type", "string").put("format", "date").put("title", "Due");
        props.putObject("meetAt").put("type", "string").put("format", "time").put("title", "Meeting time");
        ObjectNode t = (ObjectNode) d.path("decisions").get(0);
        t.put("hitPolicy", hitPolicy);
        ArrayNode array = t.putArray("rows");
        for (String row : rows) array.add(MAPPER.readTree(row));
        return d;
    }

    private static String row(String when, String route) {
        return "{\"when\":" + when + ",\"then\":{\"route\":" + route + "}}";
    }

    private static String test(String field, String expression) {
        return "{\"fieldRef\":\"" + field + "\",\"expression\":" + MAPPER.writeValueAsString(expression) + "}";
    }

    private static String formula(String expression) {
        return "{\"expression\":" + MAPPER.writeValueAsString(expression) + "}";
    }

    private static final String BIG = "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":5000}";
    private static final String SMALL = "{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":5000}";

    private static List<ValidationIssue> issues(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues();
    }

    private static List<String> codes(ObjectNode d, Severity severity) {
        return issues(d).stream().filter(i -> i.severity() == severity).map(ValidationIssue::code).toList();
    }

    private static ValidationIssue issue(ObjectNode d, String code) {
        return issues(d).stream().filter(i -> i.code().equals(code)).findFirst().orElseThrow(() ->
                new AssertionError("no " + code + " in " + issues(d)));
    }

    private static CompiledWorkflow compile(ObjectNode d) {
        assertThat(codes(d, Severity.BLOCKER)).as(issues(d).toString()).isEmpty();
        try {
            return Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace());
        } catch (CompilationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void expressionEntriesAreCheckedWithPositionsAndTypes() {
        ObjectNode ok = workflow("FIRST", row(test("/input/amount", "input.amount > input.limit"), formula("if input.amount > 10000 then \"cfo\" else \"financeReview\"")),
                row(SMALL, "\"standard\""));
        assertThat(codes(ok, Severity.BLOCKER)).as(issues(ok).toString()).isEmpty();

        ValidationIssue syntax = issue(workflow("FIRST", row(test("/input/amount", "input.amount >"), "\"x\"")), "WF_EXPRESSION_INVALID");
        assertThat(syntax.path()).isEqualTo("/decisions/0/rows/0/when/expression");
        assertThat(syntax.message()).contains("(at character");

        assertThat(issue(workflow("FIRST", row(test("/input/amount", "input.amount + 1"), "\"x\"")), "WF_EXPRESSION_TYPE").path())
                .isEqualTo("/decisions/0/rows/0/when/expression");
        assertThat(issue(workflow("FIRST", row("{\"all\":[" + SMALL + "," + test("/input/amount", "input.nothing > 1") + "]}", "\"x\"")),
                "WF_EXPRESSION_INVALID").path()).isEqualTo("/decisions/0/rows/0/when/all/1/expression");
        assertThat(issue(workflow("FIRST", row(BIG, formula("input.amount"))), "WF_EXPRESSION_TYPE").path())
                .isEqualTo("/decisions/0/rows/0/then/route/expression");
        // The test must use its column; time fields are not available; only single values.
        assertThat(issue(workflow("FIRST", row(test("/input/amount", "input.limit > 1"), "\"x\"")), "WF_DECISION_EXPRESSION_COLUMN")
                .message()).contains("/input/amount");
        assertThat(codes(workflow("FIRST", row(test("/input/meetAt", "input.meetAt = input.meetAt"), "\"x\"")), Severity.BLOCKER))
                .contains("WF_EXPRESSION_INVALID");
        ObjectNode list = workflow("FIRST", row(test("/input/amount", "input.amount > count(input.lines)"), "\"x\""));
        ((ObjectNode) list.path("inputSchema").path("properties")).set("lines", MAPPER.readTree(
                "{\"type\":\"array\",\"title\":\"Lines\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"number\",\"title\":\"Line\"}}"));
        assertThat(codes(list, Severity.BLOCKER)).contains("WF_DECISION_EXPRESSION_FIELD");
        // Engine expressions cannot be smuggled in.
        assertThat(issue(workflow("FIRST", row(BIG, formula("\"#{x}\""))), "WF_EXPRESSION_INVALID").message()).contains("#{");
        // A result of the decision step itself is not available to its table.
        assertThat(codes(workflow("FIRST", row(test("/input/amount", "input.amount > 1 and steps.amountPolicy.route = \"x\""), "\"x\"")),
                Severity.BLOCKER)).isNotEmpty();
    }

    @Test
    void datesWorkInFormulasButNotAsComparisons() {
        ObjectNode d = workflow("UNIQUE", row(test("/input/due", "input.due < date(\"2026-06-30\")"), "\"financeReview\""),
                row(test("/input/due", "input.due >= date(\"2026-06-30\")"), "\"standard\""));
        assertThat(codes(d, Severity.BLOCKER)).as(issues(d).toString()).isEmpty();
        assertThat(codes(d, Severity.WARNING)).contains("WF_DECISION_EXPRESSION_OVERLAP");
        DecisionEvaluator.Result r = Compilers.decisionEvaluator().evaluate(d.toString(), KEY,
                Map.of("/input/due", "2026-03-01"), Compilers.workflowCompiler().decisionTypes(d.toString(),
                        SimulatorFixtures.context().workspace(), KEY));
        assertThat(r.output()).containsEntry("route", "financeReview");
    }

    @Test
    void overlapsWithFormulasAreWarnedNotClaimed() {
        // Exclusive by their comparisons: no warning, no blocker.
        ObjectNode exclusive = workflow("UNIQUE",
                row("{\"all\":[" + BIG + "," + test("/input/limit", "input.limit > 1") + "]}", "\"financeReview\""),
                row(SMALL, "\"standard\""));
        assertThat(codes(exclusive, Severity.BLOCKER)).isEmpty();
        assertThat(codes(exclusive, Severity.WARNING)).doesNotContain("WF_DECISION_EXPRESSION_OVERLAP", "WF_DECISION_OVERLAP");
        // Possibly overlapping: a warning under UNIQUE, nothing under FIRST.
        ObjectNode maybe = workflow("UNIQUE", row(test("/input/amount", "input.amount > input.limit"), "\"financeReview\""),
                row(SMALL, "\"standard\""));
        assertThat(codes(maybe, Severity.BLOCKER)).isEmpty();
        assertThat(issue(maybe, "WF_DECISION_EXPRESSION_OVERLAP").message()).contains("Rules 1 and 2");
        ObjectNode first = workflow("FIRST", row(test("/input/amount", "input.amount > input.limit"), "\"financeReview\""),
                row(SMALL, "\"standard\""));
        assertThat(codes(first, Severity.WARNING)).doesNotContain("WF_DECISION_EXPRESSION_OVERLAP");
        // Literal rows still get the proof: output formulas do not hide an overlap.
        ObjectNode literal = workflow("UNIQUE", row(BIG, formula("\"financeReview\"")), row(BIG, "\"standard\""));
        assertThat(codes(literal, Severity.BLOCKER)).contains("WF_DECISION_OVERLAP");
        // ANY: identical formula results count as the same results.
        ObjectNode any = workflow("ANY", row(BIG, formula("upper(\"x\")")), row(BIG, formula("upper(\"x\")")));
        assertThat(codes(any, Severity.BLOCKER)).isEmpty();
    }

    @Test
    void priorityOutputsAndTierOneRefuseFormulas() {
        ObjectNode d = workflow("PRIORITY", row(BIG, formula("\"financeReview\"")), row(SMALL, "\"standard\""));
        ((ObjectNode) d.path("decisions").get(0)).putObject("priority").put("output", "route").putArray("order")
                .add("financeReview").add("standard");
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_DECISION_EXPRESSION_UNSUPPORTED");
        assertThat(DecisionTables.usesExpressions(d.path("decisions").get(0).toString())).isTrue();
    }

    @Test
    void engineDmnHoldsFormulaResultsAsVariablesAndOldTablesAreUnchanged() {
        ObjectNode plain = workflow("FIRST", row(BIG, "\"financeReview\""));
        String before = compile(plain).dmnResources().get(DMN);
        assertThat(before).doesNotContain("x1", "y1");
        assertThat(compile(plain).bpmnXml()).doesNotContain("typesBase64=\"", "orqix:decision ref=\"amount-policy\" version=\"1\" resource=\"decisions/amount-policy.dmn\" fieldRef=\"/input/amount\" typesBase64");

        ObjectNode d = workflow("FIRST",
                row("{\"all\":[" + BIG + "," + test("/input/due", "input.due < date(\"2026-06-30\")") + "]}", formula("upper(input.costCenter)")),
                row(test("/input/amount", "input.amount > input.limit"), "\"financeReview\""));
        CompiledWorkflow c = compile(d);
        String dmn = c.dmnResources().get(DMN);
        assertThat(dmn).contains("<inputExpression id=\"amount-policy__inexpr1\" typeRef=\"number\"><text>in1</text>",
                "<text>x1</text>", "<text>x2</text>", "<![CDATA[== true]]>", "<![CDATA[#{y1}]]>")
                .doesNotContain("input.due", "date(");
        // The decision runs as its own job (a formula can fail on the data); a plain table stays synchronous.
        assertThat(c.bpmnXml()).containsPattern("id=\"amountPolicy\"[^>]*flowable:async=\"true\"");
        assertThat(compile(plain).bpmnXml()).doesNotContainPattern("id=\"amountPolicy\"[^>]*flowable:async");
        // The BPMN pins the types the formulas were checked with.
        Matcher m = Pattern.compile("typesBase64=\"([^\"]+)\"").matcher(c.bpmnXml().substring(c.bpmnXml().indexOf("orqix:decision")));
        assertThat(m.find()).isTrue();
        JsonNode types = StrictJson.parse(new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8));
        assertThat(types.path("input").path("due").asString()).isEqualTo("date");
        assertThat(types.path("input").path("costCenter").asString()).isEqualTo("string");

        // The engine variables follow the canonical evaluation.
        String table = d.path("decisions").get(0).toString();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("/input/amount", new BigDecimal("6000"));
        values.put("/input/due", "2026-02-01");
        values.put("/input/limit", new BigDecimal("5000"));
        values.put("/input/costCenter", "it");
        DecisionEvaluator.Result r = DecisionTables.evaluate(table, values, types.toString());
        assertThat(r.matchedRow()).isZero();
        assertThat(r.output()).containsEntry("route", "IT");
        assertThat(DecisionTables.engineVariables(table, values, r)).containsEntry("in1", new BigDecimal("6000"))
                .containsEntry("x1", true).containsEntry("x2", true).containsEntry("y1", "IT");
        assertThat(DecisionTables.fields(table)).containsExactly("/input/amount", "/input/costCenter", "/input/due", "/input/limit");
    }

    @Test
    void standardDmnMarksFormulasWithTheOrqixExpressionLanguage() {
        ObjectNode d = workflow("UNIQUE",
                row("{\"any\":[" + test("/input/due", "input.due < date(\"2026-06-30\")") + "," + BIG + "]}", formula("upper(input.costCenter)")));
        String dmn = DecisionTables.exportDmn(d.path("decisions").get(0).toString(), KEY, null);
        assertThat(dmn).contains("hitPolicy=\"ANY\"",
                "expressionLanguage=\"https://orqix.example/expression\"><text>input.due &lt; date(\"2026-06-30\")</text>",
                "<text>upper(input.costCenter)</text>", "<inputExpression id=\"amount-policy_inexpr2\"><text>due</text>");
        ObjectNode collect = workflow("COLLECT", row("{\"any\":[" + test("/input/due", "input.due < today()") + "," + BIG + "]}", "1"));
        ObjectNode t = (ObjectNode) collect.path("decisions").get(0);
        t.put("aggregation", "SUM");
        t.putObject("default").put("route", 0);
        assertThatThrownBy(() -> DecisionTables.exportDmn(t.toString(), KEY, null)).hasMessageContaining("COLLECT");
    }

    @Test
    void theSimulatorEvaluatesFormulaEntries() {
        ObjectNode d = workflow("FIRST", row(test("/input/amount", "input.amount > input.limit"), formula("\"financeReview\"")),
                row(SMALL, "\"standard\""));
        SimulationResult result = Compilers.workflowSimulator().simulate(d.toString(), INPUT, "{}", SimulatorFixtures.context());
        assertThat(result.hasTrace()).as(result.status() + " " + result.blockers()).isTrue();
        JsonNode decision = null;
        for (JsonNode e : StrictJson.parse(result.json()).path("trace")) if (e.path("stepId").asString().equals("amountPolicy")) decision = e;
        assertThat(decision).isNotNull();
        JsonNode details = decision.path("details");
        assertThat(details.path("matchedRow").asInt()).isEqualTo(1);
        assertThat(details.path("rows").get(0).path("expressions").get(0).path("result").asBoolean()).isTrue();
        assertThat(details.path("rows").get(0).path("outputExpressions").get(0).path("value").asString()).isEqualTo("financeReview");
        assertThat(result.json()).contains("\"stepId\":\"finance\"");

        // A formula result without a value stops the test.
        ObjectNode broken = workflow("FIRST", row(BIG, formula("if input.amount > 1 then null else \"x\"")), row(SMALL, "\"standard\""));
        assertThat(Compilers.workflowSimulator().simulate(broken.toString(), INPUT, "{}", SimulatorFixtures.context()).json())
                .contains("DECISION_FAILED", "gives no value for route");
    }
}
