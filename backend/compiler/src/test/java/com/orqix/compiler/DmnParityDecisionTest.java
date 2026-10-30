package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** DMN parity (docs/build/S121): in lists, between ranges, ANY, PRIORITY, COLLECT and the standard DMN export. */
class DmnParityDecisionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String KEY = "amount-policy";
    private static final String DMN = "decisions/amount-policy.dmn";

    private static ObjectNode table(ObjectNode d) {
        return (ObjectNode) d.path("decisions").get(0);
    }

    /** The purchase example with rows {@code [condition, route]} pairs. */
    private static ObjectNode withRows(String hitPolicy, String... pairs) {
        ObjectNode d = TestSupport.purchase();
        ObjectNode t = table(d);
        ArrayNode rows = t.putArray("rows");
        for (int i = 0; i < pairs.length; i += 2) {
            ObjectNode r = rows.addObject();
            r.set("when", MAPPER.readTree(pairs[i]));
            r.putObject("then").put("route", pairs[i + 1]);
        }
        t.put("hitPolicy", hitPolicy);
        return d;
    }

    private static List<ValidationIssue> issues(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace()).issues();
    }

    private static boolean has(ObjectNode d, String code) {
        return issues(d).stream().anyMatch(i -> i.code().equals(code));
    }

    private static CompiledWorkflow compile(ObjectNode d) {
        var blockers = issues(d).stream().filter(i -> i.severity() == Severity.BLOCKER).toList();
        assertThat(blockers).as("blocking issues").isEmpty();
        try {
            return Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace());
        } catch (CompilationException e) {
            throw new AssertionError(e);
        }
    }

    private static DecisionEvaluator.Result eval(ObjectNode d, Map<String, Object> values) {
        return Compilers.decisionEvaluator().evaluate(d.toString(), KEY, values);
    }

    private static final String IT_OR_HR = "{\"fieldRef\":\"/input/costCenter\",\"operator\":\"in\",\"value\":[\"IT\",\"HR\"]}";
    private static final String MID = "{\"fieldRef\":\"/input/amount\",\"operator\":\"between\",\"value\":{\"from\":1000,\"to\":5000,\"includeTo\":false}}";
    private static final String MID_CLOSED = "{\"fieldRef\":\"/input/amount\",\"operator\":\"between\",\"value\":{\"from\":1000,\"to\":5000}}";
    private static final String BIG = "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":5000}";

    @Test
    void listsAndRangesCompileEvaluateAndProveOverlaps() {
        ObjectNode d = withRows("UNIQUE", MID, "managerReview", BIG, "financeReview");
        assertThat(has(d, "WF_DECISION_OVERLAP")).as("[1000..5000) and >= 5000 are exclusive").isFalse();
        assertThat(has(withRows("UNIQUE", MID_CLOSED, "managerReview", BIG, "financeReview"), "WF_DECISION_OVERLAP"))
                .as("[1000..5000] and >= 5000 share 5000").isTrue();

        String dmn = compile(d).dmnResources().get(DMN);
        assertThat(dmn).contains("#{(in1 >= 1000 && in1 < 5000)}", ">= 5000").doesNotContain("between");
        assertThat(eval(d, Map.of("/input/amount", 4999.99)).matchedRow()).isEqualTo(0);
        assertThat(eval(d, Map.of("/input/amount", 5000)).matchedRow()).isEqualTo(1);
        assertThat(eval(d, Map.of("/input/amount", 999)).usedDefault()).isTrue();

        ObjectNode both = withRows("UNIQUE", "{\"all\":[" + IT_OR_HR + "," + MID + "]}", "managerReview", BIG, "financeReview");
        String two = compile(both).dmnResources().get(DMN);
        assertThat(two).contains("#{(in2 == \"IT\" || in2 == \"HR\")}", "#{(in1 >= 1000 && in1 < 5000)}");
        assertThat(eval(both, Map.of("/input/amount", 2000, "/input/costCenter", "HR")).matchedRow()).isEqualTo(0);
        assertThat(eval(both, Map.of("/input/amount", 2000, "/input/costCenter", "OPS")).usedDefault()).isTrue();

        var trace = SimulatorFixtures.run(both.toString(),
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":2000}", "{}");
        assertThat(trace.path("trace").toString()).contains("is one of IT, HR", "is between");
    }

    @Test
    void listValuesMustFitTheFieldAndSetsStayInDecisionRows() {
        assertThat(has(withRows("UNIQUE", "{\"fieldRef\":\"/input/costCenter\",\"operator\":\"in\",\"value\":[\"IT\",\"SALES\"]}", "x"),
                "WF_LITERAL_NOT_IN_DOMAIN")).isTrue();
        assertThat(has(withRows("UNIQUE", "{\"fieldRef\":\"/input/costCenter\",\"operator\":\"between\",\"value\":{\"from\":1,\"to\":2}}", "x"),
                "WF_OPERATOR_TYPE")).isTrue();
        // A route condition may not use a list: the schema refuses it before the compiler reads it.
        ObjectNode d = TestSupport.purchase();
        ObjectNode t5 = (ObjectNode) d.path("transitions").get(4);
        t5.set("condition", MAPPER.readTree("{\"fieldRef\":\"/steps/amountPolicy/route\",\"operator\":\"in\",\"value\":[\"financeReview\"]}"));
        assertThat(issues(d)).anyMatch(i -> i.severity() == Severity.BLOCKER && i.path().startsWith("/transitions/4"));
    }

    @Test
    void anyAllowsOverlapsOnlyWithTheSameResults() {
        String low = "{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":100}";
        String hr = "{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":500}";
        assertThat(has(withRows("ANY", low, "standard", hr, "standard"), "WF_DECISION_OVERLAP")).isFalse();
        assertThat(has(withRows("ANY", low, "standard", hr, "financeReview"), "WF_DECISION_OVERLAP")).isTrue();
        assertThat(has(withRows("UNIQUE", low, "standard", hr, "standard"), "WF_DECISION_OVERLAP")).isTrue();
        ObjectNode d = withRows("ANY", low, "financeReview", hr, "financeReview");
        assertThat(compile(d).dmnResources().get(DMN)).contains("hitPolicy=\"FIRST\"", "amount-policy__default");
    }

    @Test
    void priorityPicksTheHighestRankedMatchingRow() {
        ObjectNode d = withRows("PRIORITY", "{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":1000}", "managerReview",
                BIG, "financeReview");
        table(d).set("priority", MAPPER.readTree("{\"output\":\"route\",\"order\":[\"financeReview\",\"managerReview\",\"standard\"]}"));
        assertThat(eval(d, Map.of("/input/amount", 6000)).matchedRow()).as("finance ranks above manager").isEqualTo(1);
        assertThat(eval(d, Map.of("/input/amount", 2000)).matchedRow()).isEqualTo(0);
        assertThat(eval(d, Map.of("/input/amount", 10)).usedDefault()).isTrue();
        String dmn = compile(d).dmnResources().get(DMN);
        assertThat(dmn).contains("hitPolicy=\"FIRST\"");
        assertThat(dmn.indexOf("amount-policy__r2")).as("rows sorted by priority").isLessThan(dmn.indexOf("amount-policy__r1"));
        assertThat(DecisionTables.ruleOwners(table(d).toString())).containsExactly(1, 0);

        ObjectNode missing = d.deepCopy();
        table(missing).set("priority", MAPPER.readTree("{\"output\":\"route\",\"order\":[\"financeReview\",\"standard\"]}"));
        assertThat(has(missing, "WF_DECISION_PRIORITY_ORDER")).isTrue();
        ObjectNode none = d.deepCopy();
        table(none).remove("priority");
        assertThat(has(none, "WF_DECISION_POLICY_OPTION")).isTrue();
    }

    /** COLLECT tables give one number; the route of the example is replaced by a fee and the branch tests it. */
    private static ObjectNode feeTable(String aggregation) {
        ObjectNode d = TestSupport.purchase();
        ObjectNode t = table(d);
        t.put("hitPolicy", "COLLECT").put("aggregation", aggregation);
        ArrayNode rows = t.putArray("rows");
        rows.addObject().<ObjectNode>set("when", MAPPER.readTree(BIG)).putObject("then").put("fee", new BigDecimal("0.1"));
        rows.addObject().<ObjectNode>set("when", MAPPER.readTree(IT_OR_HR)).putObject("then").put("fee", new BigDecimal("0.2"));
        rows.addObject().<ObjectNode>set("when", MAPPER.readTree("{\"any\":[" + BIG + ",{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"IT\"}]}"))
                .putObject("then").put("fee", 5);
        t.putObject("default").put("fee", 0);
        ((ObjectNode) d.path("transitions").get(4)).set("condition",
                MAPPER.readTree("{\"fieldRef\":\"/steps/amountPolicy/fee\",\"operator\":\"gte\",\"value\":1}"));
        return d;
    }

    @Test
    void collectCombinesEveryMatchingRowExactly() {
        ObjectNode d = feeTable("SUM");
        var r = eval(d, Map.of("/input/amount", 6000, "/input/costCenter", "IT"));
        assertThat(r.matchedRows()).containsExactly(0, 1, 2);
        assertThat((BigDecimal) r.output().get("fee")).isEqualByComparingTo("5.3");
        assertThat(eval(d, Map.of("/input/amount", 10, "/input/costCenter", "OPS")).usedDefault()).isTrue();
        assertThat((BigDecimal) eval(feeTable("COUNT"), Map.of("/input/amount", 6000, "/input/costCenter", "OPS"))
                .output().get("fee")).isEqualByComparingTo("2");
        assertThat((BigDecimal) eval(feeTable("MAX"), Map.of("/input/amount", 6000, "/input/costCenter", "HR"))
                .output().get("fee")).isEqualByComparingTo("5");

        String dmn = compile(d).dmnResources().get(DMN);
        assertThat(dmn).contains("hitPolicy=\"COLLECT\"", "aggregation=\"SUM\"").doesNotContain("__default", "__r3_1");
        assertThat(dmn).as("an OR row is one rule under COLLECT").contains("#{(in1 >= 5000) || (in2 == \"IT\")}");
        assertThat(DecisionTables.ruleOwners(table(d).toString())).containsExactly(0, 1, 2);

        var trace = SimulatorFixtures.run(d.toString(),
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":6000}", "{}");
        assertThat(trace.path("trace").toString()).contains("rules 1, 2, 3 apply, combined as the sum");

        ObjectNode text = feeTable("SUM");
        table(text).putObject("default").put("fee", "none");
        assertThat(has(text, "WF_DECISION_COLLECT_OUTPUT")).isTrue();
        ObjectNode noAggregation = feeTable("SUM");
        table(noAggregation).remove("aggregation");
        assertThat(has(noAggregation, "WF_DECISION_POLICY_OPTION")).isTrue();
    }

    @Test
    void standardExportKeepsTheAuthoredMeaning() throws Exception {
        ObjectNode d = withRows("PRIORITY", "{\"all\":[" + IT_OR_HR + "," + MID + "]}", "managerReview", BIG, "financeReview",
                "{\"any\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":10},{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":900000}]}", "standard");
        table(d).set("priority", MAPPER.readTree("{\"output\":\"route\",\"order\":[\"financeReview\",\"managerReview\",\"standard\"]}"));
        table(d).withArray("rows").get(1).withObject("effective").put("from", "2026-01-01");
        String xml = DecisionTables.exportDmn(table(d).toString(), KEY, "Amount policy");
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertThat(xml).contains("hitPolicy=\"PRIORITY\"", "<text>\"financeReview\",\"managerReview\",\"standard\"</text>",
                "<defaultOutputEntry>", "<text>\"IT\",\"HR\"</text>", "<text>[1000..5000)</text>", "<text>&gt;= 5000</text>",
                "<text>&lt; 10, &gt; 900000</text>", "<text>&gt;= date(\"2026-01-01\")</text>", "<text>submissionDate</text>",
                "<text>costCenter</text>", "typeRef=\"number\"");

        ObjectNode collect = feeTable("SUM");
        String collected = DecisionTables.exportDmn(table(collect).toString(), KEY, null);
        assertThat(collected).contains("hitPolicy=\"COLLECT\"", "aggregation=\"SUM\"", "not(&gt;= 5000)");

        ObjectNode unique = withRows("UNIQUE", "{\"any\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":10},"
                + "{\"fieldRef\":\"/input/costCenter\",\"operator\":\"eq\",\"value\":\"HR\"}]}", "standard");
        assertThat(DecisionTables.exportDmn(table(unique).toString(), KEY, null)).contains("hitPolicy=\"ANY\"", "<description>");
    }

    @Test
    void readerRefusesMalformedSets() {
        assertThatThrownBy(() -> DecisionTables.check("{\"rows\":[{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"in\",\"value\":[1,\"x\"]},\"then\":{\"r\":1}}],\"default\":{\"r\":0}}"))
                .hasMessageContaining("all be texts or all be numbers");
        assertThatThrownBy(() -> DecisionTables.check("{\"rows\":[{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"between\",\"value\":{\"from\":5,\"to\":5}},\"then\":{\"r\":1}}],\"default\":{\"r\":0}}"))
                .hasMessageContaining("smaller than");
        assertThatThrownBy(() -> DecisionTables.check("{\"hitPolicy\":\"RULE ORDER\",\"rows\":[],\"default\":{\"r\":0}}"))
                .hasMessageContaining("hitPolicy");
    }
}
