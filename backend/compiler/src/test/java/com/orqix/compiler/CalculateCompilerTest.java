package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-X01 and CG-F04 (docs/build/S137): the Calculate step, expression conditions, = mappings of a call, computed form
 * fields and cross-field rules. Type checking with business messages, BPMN with pinned types and only the allowed
 * engine expressions, the BPMN round trip, the simulator and server-side form evaluation.
 */
class CalculateCompilerTest {

    private static final String INPUT = "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}";

    private static JsonNode n(String json) {
        return StrictJson.parse(json);
    }

    private static ObjectNode output(ObjectNode d, String key) {
        for (JsonNode node : d.path("nodes")) {
            if (node.path("id").asString().equals("totals")) {
                for (JsonNode o : node.path("config").path("outputs")) {
                    if (o.path("key").asString().equals(key)) return (ObjectNode) o;
                }
            }
        }
        throw new IllegalArgumentException(key);
    }

    /** start -> totals (Calculate) -> manager; the finance path is chosen by an expression; procure maps with =. */
    static ObjectNode workflow() {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("nodes")).add(n("""
                {"id":"totals","type":"calculate","label":"Work out the totals","config":{"outputs":[
                 {"key":"tax","expression":"round(input.amount * 0.2, 2)","type":"number","scale":2},
                 {"key":"total","expression":"input.amount + tax","type":"number","scale":2,"rounding":"halfEven"},
                 {"key":"payBy","expression":"addWorkingDays(today(), 3)","type":"date"},
                 {"key":"band","expression":"if total >= 5000 then \\"large\\" else \\"small\\"","type":"string"},
                 {"key":"lines","expression":"length(input.title)","type":"integer"}],
                 "calendar":{"zone":"Europe/London","weekdays":[1,2,3,4,5],"holidays":["2026-01-19"]}}}"""));
        ((ObjectNode) d.path("uiLayout").path("positions")).set("totals", n("{\"x\":50,\"y\":50}"));
        for (JsonNode t : d.path("transitions")) {
            ObjectNode o = (ObjectNode) t;
            if ("t1".equals(o.path("id").asString())) o.put("to", "totals");
            if ("t5".equals(o.path("id").asString())) {
                o.set("condition", n("{\"expression\":\"steps.totals.total >= 5000 or steps.amountPolicy.route = \\\"financeReview\\\"\"}"));
            }
        }
        ((ArrayNode) d.path("transitions")).add(n("{\"id\":\"tc\",\"from\":\"totals\",\"to\":\"manager\",\"on\":\"next\"}"));
        for (JsonNode node : d.path("nodes")) {
            if (node.path("id").asString().equals("procure")) {
                ((ObjectNode) node.path("config").path("inputMapping")).put("title", "= upper(input.title) + \" for \" + input.costCenter");
            }
        }
        return d;
    }

    /** The purchase form with a computed field and a cross-field rule. */
    static ObjectNode withFormRules(ObjectNode d) {
        ObjectNode props = (ObjectNode) d.path("inputSchema").path("properties");
        props.set("startDate", n("{\"type\":\"string\",\"format\":\"date\",\"title\":\"Start\"}"));
        props.set("endDate", n("{\"type\":\"string\",\"format\":\"date\",\"title\":\"End\"}"));
        props.set("nights", n("{\"type\":\"integer\",\"title\":\"Nights\",\"minimum\":0,\"maximum\":365}"));
        props.set("total", n("{\"type\":\"number\",\"title\":\"Total\"}"));
        ObjectNode ui = (ObjectNode) d.path("inputUi");
        ui.set("computed", n("{\"nights\":\"daysBetween(input.startDate, input.endDate)\",\"total\":\"input.amount * 1.2\"}"));
        ui.set("rules", n("[{\"id\":\"endAfterStart\",\"check\":\"input.endDate > input.startDate\","
                + "\"message\":\"The end date must be after the start date.\",\"fields\":[\"endDate\"]},"
                + "{\"id\":\"withinBudget\",\"check\":\"total <= 10000\",\"message\":\"x\"}]"));
        return d;
    }

    private static ValidationReport report(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), SimulatorFixtures.context().workspace());
    }

    private static List<String> codes(ObjectNode d, Severity severity) {
        return report(d).issues().stream().filter(i -> i.severity() == severity).map(ValidationIssue::code).toList();
    }

    private static String message(ObjectNode d, String code) {
        return report(d).issues().stream().filter(i -> i.code().equals(code)).map(ValidationIssue::message)
                .findFirst().orElseThrow(() -> new AssertionError("no " + code + " in " + report(d).issues()));
    }

    @Test
    void aCalculateStepCompilesToAnEngineTaskWithPinnedTypesAndRoundTrips() throws Exception {
        ObjectNode d = workflow();
        assertThat(codes(d, Severity.BLOCKER)).isEmpty();
        assertThat(codes(d, Severity.WARNING)).contains("WF_BRANCH_EXPRESSION_ORDER");
        CompiledWorkflow compiled = Compilers.workflowCompiler().compile(d.toString(), SimulatorFixtures.context().workspace());
        String xml = compiled.bpmnXml();
        GeneratedXmlTest.onlyAllowedExpressions(xml);
        assertThat(xml).contains("flowable:delegateExpression=\"${orqixCalculate}\"", "<orqix:calculate configBase64=",
                "<orqix:output key=\"tax\" type=\"number\" expression=\"round(input.amount * 0.2, 2)\"/>",
                "<orqix:condition expression=\"steps.totals.total &gt;= 5000 or steps.amountPolicy.route = &quot;financeReview&quot;\"",
                "fieldRef=\"= upper(input.title) + &quot; for &quot; + input.costCenter\" typesBase64=");
        assertThat(BpmnSource.export(d.toString(), compiled).differences()).isEmpty();
    }

    @Test
    void outputsAreTypeCheckedWithBusinessMessages() {
        ObjectNode d = workflow();
        output(d, "total").put("type", "string");
        assertThat(message(d, "WF_CALCULATE_TYPE")).isEqualTo("The output \"total\" is declared as text, but its"
                + " expression gives a number.");

        d = workflow();
        output(d, "tax").put("expression", "round(input.amount * , 2)");
        assertThat(message(d, "WF_EXPRESSION_INVALID")).startsWith("Expected a value but found \",\".");

        d = workflow();
        output(d, "tax").put("expression", "total * 0.2");
        assertThat(message(d, "WF_EXPRESSION_INVALID")).contains("There is no field or earlier output named total");

        d = workflow();
        output(d, "tax").put("expression", "input.amount + input.title");
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_EXPRESSION_INVALID");

        d = workflow();
        output(d, "band").put("scale", 2);
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_CALCULATE_ROUNDING");

        d = workflow();
        output(d, "band").put("key", "tax");
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_CALCULATE_OUTPUT_DUPLICATE");

        d = workflow();
        output(d, "band").put("expression", "\"${danger}\"");
        assertThat(message(d, "WF_EXPRESSION_INVALID")).contains("${ or #{");

        d = workflow();
        output(d, "tax").put("expression", "steps.manager.decision");
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_FIELDREF_NOT_GUARANTEED");
    }

    @Test
    void expressionConditionsMustGiveTrueOrFalse() {
        ObjectNode d = workflow();
        for (JsonNode t : d.path("transitions")) {
            if ("t5".equals(t.path("id").asString())) {
                ((ObjectNode) t).set("condition", n("{\"expression\":\"steps.totals.total * 2\"}"));
            }
        }
        assertThat(message(d, "WF_EXPRESSION_TYPE")).isEqualTo("This expression must give true or false, but it gives a number.");
    }

    @Test
    void equalsMappingsMustFitTheRequestField() {
        ObjectNode d = workflow();
        for (JsonNode node : d.path("nodes")) {
            if (node.path("id").asString().equals("procure")) {
                ((ObjectNode) node.path("config").path("inputMapping")).put("title", "= input.amount > 5");
            }
        }
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_MAPPING_TYPE");
    }

    @Test
    void theSimulatorRunsTheCalculationAndTheExpressionCondition() {
        SimulationResult result = Compilers.workflowSimulator().simulate(workflow().toString(), INPUT, "{}",
                SimulatorFixtures.context());
        assertThat(result.hasTrace()).as(result.status() + " " + result.blockers()).isTrue();
        JsonNode json = StrictJson.parse(result.json());
        JsonNode calc = null;
        for (JsonNode e : json.path("trace")) if (e.path("stepId").asString().equals("totals")) calc = e;
        assertThat(calc).isNotNull();
        // 2026-01-15 is a Thursday; Monday 19 January is a holiday in this calendar.
        assertThat(calc.path("details").path("outputs").toString())
                .isEqualTo("{\"tax\":20.00,\"total\":120.00,\"payBy\":\"2026-01-21\",\"band\":\"small\",\"lines\":6}");
        assertThat(calc.path("summary").asString()).startsWith("\"Work out the totals\": tax = ");
        assertThat(result.json()).contains("\"stepId\":\"procure\"").doesNotContain("\"stepId\":\"finance\"");

        SimulationResult big = Compilers.workflowSimulator().simulate(workflow().toString(),
                INPUT.replace("\"amount\":100", "\"amount\":4500"), "{}", SimulatorFixtures.context());
        assertThat(big.json()).contains("\"stepId\":\"finance\"");
    }

    @Test
    void aMissingRequiredOutputStopsTheTest() {
        ObjectNode d = workflow();
        output(d, "tax").put("expression", "input.amount / 0");
        SimulationResult result = Compilers.workflowSimulator().simulate(d.toString(), INPUT, "{}", SimulatorFixtures.context());
        assertThat(result.json()).contains("CALCULATION_FAILED", "The output tax has no value");
        output(d, "tax").put("optional", true);
        output(d, "total").put("expression", "input.amount + coalesce(tax, 0)");
        assertThat(Compilers.workflowSimulator().simulate(d.toString(), INPUT, "{}", SimulatorFixtures.context()).json())
                .contains("\"tax\":null,\"total\":100.00");
    }

    @Test
    void computedFieldsAndRulesAreCheckedAndEvaluatedOnTheServer() {
        ObjectNode d = withFormRules(workflow());
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_EXPRESSION_INVALID"); // total is not input.total
        ((ObjectNode) d.path("inputUi").path("rules").get(1)).put("check", "input.total <= 10000");
        assertThat(codes(d, Severity.BLOCKER)).isEmpty();

        ObjectNode cyclic = withFormRules(workflow());
        ((ObjectNode) cyclic.path("inputUi").path("rules").get(1)).put("check", "input.total <= 10000");
        ((ObjectNode) cyclic.path("inputUi").path("computed")).put("total", "input.nights * 2");
        ((ObjectNode) cyclic.path("inputUi").path("computed")).put("nights", "input.total / 2");
        assertThat(codes(cyclic, Severity.BLOCKER)).contains("WF_FORM_COMPUTED_CYCLE");

        ObjectNode wrong = withFormRules(workflow());
        ((ObjectNode) wrong.path("inputUi").path("rules").get(1)).put("check", "input.total");
        assertThat(codes(wrong, Severity.BLOCKER)).contains("WF_EXPRESSION_TYPE");

        JsonNode schema = d.path("inputSchema");
        JsonNode ui = d.path("inputUi");
        JsonNode input = n("{\"title\":\"Hotel\",\"purpose\":\"Trip\",\"costCenter\":\"IT\",\"amount\":100.10,"
                + "\"startDate\":\"2026-03-27\",\"endDate\":\"2026-03-30\",\"nights\":99}");
        ObjectNode computed = FormComputation.apply(schema, ui, input, Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(computed.path("nights").toString()).isEqualTo("3");
        assertThat(computed.path("total").decimalValue()).isEqualByComparingTo("120.12");
        FormValidator validator = Compilers.formValidator();
        assertThat(validator.validate(schema.toString(), computed.toString(), ui.toString())).isEmpty();
        ObjectNode backwards = (ObjectNode) computed.deepCopy();
        backwards.put("endDate", "2026-03-20");
        assertThat(validator.validate(schema.toString(), backwards.toString(), ui.toString()))
                .containsExactly(new FormValidator.FieldError("/endDate", "rule", "The end date must be after the start date."));
    }

    @Test
    void formExpressionsAndCalendarsAreChecked() {
        ObjectNode d = withFormRules(workflow());
        ObjectNode ui = (ObjectNode) d.path("inputUi");
        ((ObjectNode) ui.path("rules").get(1)).put("check", "input.total <= 10000").put("id", "endAfterStart");
        ((ObjectNode) ui.path("rules").get(1)).set("fields", n("[\"nowhere\"]"));
        ((ObjectNode) ui.path("computed")).put("ghost", "1").put("title", "upper(input.title)").put("amount", "1");
        ((ObjectNode) d.path("inputSchema").path("properties")).set("tags",
                n("{\"type\":\"array\",\"title\":\"Tags\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"string\",\"title\":\"Tag\"}}"));
        ((ObjectNode) ui.path("computed")).put("tags", "1");
        List<String> blockers = codes(d, Severity.BLOCKER);
        assertThat(blockers).contains("WF_FORM_RULE_INVALID", "WF_FORM_COMPUTED_INVALID");
        assertThat(message(d, "WF_FORM_COMPUTED_REQUIRED")).contains("is required");
        assertThat(report(d).issues().stream().filter(i -> i.code().equals("WF_FORM_COMPUTED_INVALID"))
                .map(ValidationIssue::message).toList()).contains("\"ghost\" is not a question of the request form.",
                "The computed field \"title\" cannot use its own value.");

        ObjectNode c = workflow();
        for (JsonNode node : c.path("nodes")) {
            if (node.path("id").asString().equals("totals")) {
                ((ObjectNode) node.path("config")).set("calendar", n("{\"zone\":\"Mars/Base\",\"weekdays\":[1]}"));
            }
        }
        output(c, "lines").put("scale", 2);
        assertThat(codes(c, Severity.BLOCKER)).contains("WF_CALENDAR_INVALID", "WF_CALCULATE_ROUNDING");

        ObjectNode s = withFormRules(workflow());
        ((ObjectNode) s.path("inputUi").path("rules").get(1)).put("check", "input.total <= 10000");
        ((ObjectNode) s.path("inputUi")).set("layout", n("{\"sections\":[{\"id\":\"dates\",\"title\":\"Dates\","
                + "\"fields\":[\"startDate\",\"endDate\"],\"visibleWhen\":{\"expression\":\"input.amount > \\\"x\\\"\"}}]}"));
        assertThat(codes(s, Severity.BLOCKER)).contains("WF_EXPRESSION_INVALID");
    }

    @Test
    void expressionsMayDecideWhetherAQuestionIsShown() {
        ObjectNode d = withFormRules(workflow());
        ((ObjectNode) d.path("inputUi").path("rules").get(1)).put("check", "input.total <= 10000");
        ((ObjectNode) d.path("inputUi")).set("visibleWhen", n("{\"endDate\":{\"expression\":\"isWorkingDay(input.startDate)\"}}"));
        assertThat(codes(d, Severity.BLOCKER)).isEmpty();
        JsonNode schema = d.path("inputSchema");
        assertThat(FormVisibility.visible(schema, d.path("inputUi"), n("{\"startDate\":\"2026-03-27\"}"), "endDate")).isTrue();
        assertThat(FormVisibility.visible(schema, d.path("inputUi"), n("{\"startDate\":\"2026-03-28\"}"), "endDate")).isFalse();
        ((ObjectNode) d.path("inputUi")).set("visibleWhen", n("{\"endDate\":{\"expression\":\"input.startDate\"}}"));
        assertThat(codes(d, Severity.BLOCKER)).contains("WF_EXPRESSION_TYPE");
    }
}
