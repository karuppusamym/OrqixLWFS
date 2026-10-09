package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.node.ObjectNode;

/**
 * Rule catalog coverage beyond compiler-cases.json: each case patches the purchase template and expects issues as
 * "B|W CODE PATH" (codes and paths, not messages).
 */
class RuleCatalogTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    static Stream<Arguments> patches() {
        return Stream.of(
                // 5.1 graph
                c("duplicate transition id", """
                        [{"op":"replace","path":"/transitions/0/id","value":"manager"}]""",
                        "B WF_DUPLICATE_ID /transitions/0/id"),
                c("id equals workflow key", """
                        [{"op":"replace","path":"/transitions/0/id","value":"purchase-approval"}]""",
                        "B WF_DUPLICATE_ID /transitions/0/id"),
                c("node id equals workflow key", """
                        [{"op":"replace","path":"/nodes/9/id","value":"purchase-approval"},
                         {"op":"replace","path":"/transitions/10/to","value":"purchase-approval"},
                         {"op":"remove","path":"/uiLayout"}]""",
                        "B WF_DUPLICATE_ID /nodes/9/id"),
                c("duplicate node id", """
                        [{"op":"replace","path":"/nodes/9/id","value":"notifyRejected"}]""",
                        "B WF_DUPLICATE_ID /nodes/9/id", "B WF_TRANSITION_UNKNOWN_NODE /transitions/10/to",
                        "B WF_LAYOUT_UNKNOWN_NODE /uiLayout/positions/rejected"),
                c("unknown source", """
                        [{"op":"replace","path":"/transitions/0/from","value":"nowhere"}]""",
                        "B WF_TRANSITION_UNKNOWN_NODE /transitions/0/from", "B WF_OUTGOING_INVALID /nodes/0"),
                c("path back to start", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t12","from":"notify","to":"start","on":"next"}}]""",
                        "B WF_START_INCOMING /transitions/11/to", "B WF_OUTGOING_INVALID /nodes/6",
                        "B WF_CYCLE /transitions/11"),
                c("no start", """
                        [{"op":"replace","path":"/nodes/0","value":{"id":"start","type":"notification","label":"Request submitted",
                          "config":{"channel":"inApp","recipient":"initiator","message":"Hello"}}}]""",
                        "B WF_START_COUNT /nodes"),
                c("no end", """
                        [{"op":"replace","path":"/nodes/7","value":{"id":"fulfilled","type":"notification","label":"Fulfilled",
                          "config":{"channel":"inApp","recipient":"initiator","message":"Hello"}}},
                         {"op":"replace","path":"/nodes/9","value":{"id":"rejected","type":"notification","label":"Rejected",
                          "config":{"channel":"inApp","recipient":"initiator","message":"Hello"}}}]""",
                        "B WF_NO_END /nodes", "B WF_OUTGOING_INVALID /nodes/7", "B WF_OUTGOING_INVALID /nodes/9"),
                c("end continues", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t12","from":"fulfilled","to":"rejected","on":"next"}}]""",
                        "B WF_END_OUTGOING /transitions/11/from"),
                c("wrong route kind", """
                        [{"op":"replace","path":"/transitions/0/on","value":"approved"}]""",
                        "B WF_OUTGOING_INVALID /transitions/0/on", "B WF_OUTGOING_INVALID /nodes/0"),
                c("two next routes", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t12","from":"start","to":"amountPolicy","on":"next"}}]""",
                        "B WF_OUTGOING_INVALID /nodes/0"),
                c("two approved routes", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t12","from":"manager","to":"notifyRejected","on":"approved"}}]""",
                        "B WF_APPROVAL_ROUTES /nodes/1"),
                c("branch without condition", """
                        [{"op":"replace","path":"/transitions/4","value":{"id":"t5","from":"route","to":"finance","on":"default"}}]""",
                        "B WF_BRANCH_DEFAULT /nodes/3", "B WF_BRANCH_NO_CONDITION /nodes/3"),
                c("branch to one target", """
                        [{"op":"replace","path":"/transitions/5/to","value":"finance"}]""",
                        "W WF_BRANCH_SINGLE_TARGET /nodes/3"),
                c("self loop and dead end", """
                        [{"op":"replace","path":"/transitions/10/to","value":"notifyRejected"}]""",
                        "B WF_CYCLE /transitions/10", "B WF_DEAD_END /nodes/8", "B WF_UNREACHABLE_NODE /nodes/9"),
                // 5.2 types and decisions
                c("literal of wrong type", """
                        [{"op":"replace","path":"/transitions/4/condition/value","value":5}]""",
                        "B WF_LITERAL_TYPE /transitions/4/condition/value"),
                c("literal outside the domain", """
                        [{"op":"replace","path":"/transitions/4/condition/value","value":"finance"}]""",
                        "B WF_LITERAL_NOT_IN_DOMAIN /transitions/4/condition/value"),
                c("unsafe literal outside the domain", """
                        [{"op":"replace","path":"/transitions/4/condition/value","value":"a'b"}]""",
                        "B WF_LITERAL_UNSAFE /transitions/4/condition/value",
                        "B WF_LITERAL_NOT_IN_DOMAIN /transitions/4/condition/value"),
                c("approval decision domain", """
                        [{"op":"replace","path":"/transitions/4/condition","value":{"fieldRef":"/steps/manager/decision","operator":"eq","value":"approvd"}}]""",
                        "B WF_LITERAL_NOT_IN_DOMAIN /transitions/4/condition/value"),
                c("expressions in labels", """
                        [{"op":"replace","path":"/nodes/1/label","value":"Review ${x}"},
                         {"op":"replace","path":"/name","value":"Purchase #{y}"},
                         {"op":"replace","path":"/transitions/4/label","value":"${a}"},
                         {"op":"replace","path":"/nodes/6/config/message","value":"Hi #{x}"}]""",
                        "B WF_LABEL_EXPRESSION /nodes/1/label", "B WF_LABEL_EXPRESSION /name",
                        "B WF_LABEL_EXPRESSION /transitions/4/label", "B WF_LABEL_EXPRESSION /nodes/6/config/message"),
                c("placeholder labels", """
                        [{"op":"replace","path":"/nodes/6/label","value":"Task 2"},
                         {"op":"replace","path":"/nodes/8/label","value":"OK"}]""",
                        "W WF_LABEL_STYLE /nodes/6/label", "W WF_LABEL_STYLE /nodes/8/label"),
                c("duplicate table key", """
                        [{"op":"add","path":"/decisions/-","value":{"key":"amount-policy","version":2,"hitPolicy":"UNIQUE",
                          "rows":[{"when":{"fieldRef":"/input/amount","operator":"lt","value":1},"then":{"route":"x"}}],
                          "default":{"route":"y"}}}]""",
                        "B WF_DECISION_DUPLICATE_KEY /decisions/1/key"),
                c("output type differs", """
                        [{"op":"replace","path":"/decisions/0/rows/0/then","value":{"route":1}}]""",
                        "B WF_DECISION_OUTPUT_SHAPE /decisions/0/rows/0/then/route"),
                c("output keys differ", """
                        [{"op":"replace","path":"/decisions/0/rows/0/then","value":{"route":"financeReview","extra":"x"}}]""",
                        "B WF_DECISION_OUTPUT_SHAPE /decisions/0/rows/0/then"),
                c("rules cover the domain", """
                        [{"op":"add","path":"/decisions/0/rows/-","value":{"when":{"fieldRef":"/input/amount","operator":"lt","value":5000},"then":{"route":"standard"}}}]""",
                        "W WF_DECISION_DEFAULT_UNREACHABLE /decisions/0/default"),
                c("unused table", """
                        [{"op":"add","path":"/decisions/-","value":{"key":"spare","version":1,"hitPolicy":"UNIQUE",
                          "rows":[{"when":{"fieldRef":"/input/costCenter","operator":"eq","value":"IT"},"then":{"x":true}}],
                          "default":{"x":false}}}]""",
                        "W WF_DECISION_UNUSED /decisions/1"),
                c("decision reads a later step", """
                        [{"op":"replace","path":"/decisions/0/rows/0/when","value":{"fieldRef":"/steps/finance/decision","operator":"eq","value":"approved"}}]""",
                        "B WF_FIELDREF_NOT_GUARANTEED /decisions/0/rows/0/when/fieldRef"),
                c("branch tests two fields", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t12","from":"route","to":"procure","on":"condition",
                          "condition":{"fieldRef":"/input/amount","operator":"gte","value":10}}}]""",
                        "B WF_BRANCH_MIXED_FIELDS /transitions/11/condition/fieldRef"),
                c("branch conditions overlap", """
                        [{"op":"add","path":"/transitions/-","value":{"id":"t12","from":"route","to":"notifyRejected","on":"condition",
                          "condition":{"fieldRef":"/steps/amountPolicy/route","operator":"eq","value":"financeReview"}}}]""",
                        "B WF_BRANCH_OVERLAP /transitions/11/condition"),
                c("condition on a non-branch route", """
                        [{"op":"replace","path":"/transitions/0","value":{"id":"t1","from":"start","to":"manager","on":"condition",
                          "condition":{"fieldRef":"/input/amount","operator":"gte","value":1}}}]""",
                        "B WF_OUTGOING_INVALID /transitions/0/on"),
                c("form keyword problems", """
                        [{"op":"add","path":"/inputSchema/required/-","value":"nope"},
                         {"op":"add","path":"/inputSchema/properties/count","value":{"type":"integer","title":"Count","multipleOf":0.01,"enum":["1"]}},
                         {"op":"add","path":"/inputSchema/properties/note","value":{"type":"string","title":"Note","minimum":1,"maximum":2,"multipleOf":1,"minLength":5,"maxLength":2}},
                         {"op":"add","path":"/inputSchema/properties/flag","value":{"type":"boolean","title":"Flag","minLength":1,"maxLength":3}},
                         {"op":"add","path":"/inputSchema/properties/range","value":{"type":"number","title":"Range","minimum":5,"maximum":1}}]""",
                        "B WF_FORM_INVALID /inputSchema/required/4",
                        "B WF_FORM_INVALID /inputSchema/properties/count/multipleOf",
                        "B WF_FORM_INVALID /inputSchema/properties/count/enum",
                        "B WF_FORM_INVALID /inputSchema/properties/note/minimum",
                        "B WF_FORM_INVALID /inputSchema/properties/note/maximum",
                        "B WF_FORM_INVALID /inputSchema/properties/note/multipleOf",
                        "B WF_FORM_INVALID /inputSchema/properties/note/minLength",
                        "B WF_FORM_INVALID /inputSchema/properties/flag/minLength",
                        "B WF_FORM_INVALID /inputSchema/properties/flag/maxLength",
                        "B WF_FORM_INVALID /inputSchema/properties/range/minimum"),
                c("ui hints", """
                        [{"op":"add","path":"/inputUi/order/-","value":"ghost"},
                         {"op":"add","path":"/inputUi/widgets/ghost","value":"text"},
                         {"op":"replace","path":"/inputUi/widgets/purpose","value":"checkbox"},
                         {"op":"replace","path":"/inputUi/widgets/costCenter","value":"number"},
                         {"op":"add","path":"/inputUi/widgets/title","value":"money"},
                         {"op":"replace","path":"/inputUi/widgets/amount","value":"select"}]""",
                        "W WF_UI_UNKNOWN_FIELD /inputUi/order/4", "W WF_UI_UNKNOWN_FIELD /inputUi/widgets/ghost",
                        "W WF_UI_WIDGET_MISMATCH /inputUi/widgets/purpose",
                        "W WF_UI_WIDGET_MISMATCH /inputUi/widgets/costCenter",
                        "W WF_UI_WIDGET_MISMATCH /inputUi/widgets/title",
                        "W WF_UI_WIDGET_MISMATCH /inputUi/widgets/amount"),
                c("text widget on a number", """
                        [{"op":"replace","path":"/inputUi/widgets/amount","value":"text"}]""",
                        "W WF_UI_WIDGET_MISMATCH /inputUi/widgets/amount"),
                c("output mapping problems", """
                        [{"op":"add","path":"/outputMapping/ghost","value":{"fieldRef":"/input/title","required":false}},
                         {"op":"replace","path":"/outputMapping/procurementId/fieldRef","value":"/input/amount"}]""",
                        "B WF_OUTPUT_MAPPING_UNKNOWN /outputMapping/ghost",
                        "B WF_OUTPUT_MAPPING_UNKNOWN /outputMapping/procurementId/fieldRef"),
                c("output enum target", """
                        [{"op":"add","path":"/outputSchema/properties/center","value":{"type":"string","title":"Center","enum":["OPS"]}},
                         {"op":"add","path":"/outputMapping/center","value":{"fieldRef":"/input/costCenter","required":false}}]""",
                        "B WF_OUTPUT_MAPPING_UNKNOWN /outputMapping/center/fieldRef"),
                c("required output without required mapping", """
                        [{"op":"add","path":"/outputSchema/required","value":["procurementId"]}]""",
                        "B WF_OUTPUT_MAPPING_MISSING /outputSchema/required/0"),
                c("output from an unknown step", """
                        [{"op":"replace","path":"/outputMapping/procurementId/fieldRef","value":"/steps/ghost/x"}]""",
                        "B WF_FIELDREF_UNKNOWN /outputMapping/procurementId/fieldRef"),
                c("optional approval reason as required output", """
                        [{"op":"replace","path":"/outputMapping/procurementId","value":{"fieldRef":"/steps/manager/reason","required":true}}]""",
                        "B WF_OUTPUT_NOT_GUARANTEED /outputMapping/procurementId/fieldRef"),
                c("unknown step outputs", """
                        [{"op":"replace","path":"/nodes/5/config/inputMapping/title","value":"/steps/manager/foo"},
                         {"op":"replace","path":"/nodes/5/config/inputMapping/amount","value":"/steps/notify/x"},
                         {"op":"replace","path":"/nodes/5/config/inputMapping/costCenter","value":"/steps/amountPolicy/foo"},
                         {"op":"replace","path":"/outputMapping/procurementId/fieldRef","value":"/steps/procure/foo"}]""",
                        "B WF_FIELDREF_UNKNOWN /nodes/5/config/inputMapping/title",
                        "B WF_FIELDREF_UNKNOWN /nodes/5/config/inputMapping/amount",
                        "B WF_FIELDREF_UNKNOWN /nodes/5/config/inputMapping/costCenter",
                        "B WF_FIELDREF_UNKNOWN /outputMapping/procurementId/fieldRef"),
                c("own result and optional reason as inputs", """
                        [{"op":"replace","path":"/nodes/5/config/inputMapping/title","value":"/steps/procure/procurementId"},
                         {"op":"add","path":"/nodes/5/config/resultMapping/status","value":"/status"},
                         {"op":"replace","path":"/nodes/5/config/inputMapping/costCenter","value":"/steps/manager/reason"}]""",
                        "B WF_FIELDREF_NOT_GUARANTEED /nodes/5/config/inputMapping/title",
                        "B WF_FIELDREF_NOT_GUARANTEED /nodes/5/config/inputMapping/costCenter",
                        "B WF_MAPPING_TYPE /nodes/5/config/inputMapping/costCenter"),
                // 5.3 bindings
                c("undeclared connection", """
                        [{"op":"replace","path":"/nodes/5/config/connectionRef","value":"erp"}]""",
                        "B WF_CONNECTION_UNDECLARED /nodes/5/config/connectionRef",
                        "W WF_DEPENDENCY_UNUSED /dependencies/connections/0"),
                c("unregistered version", """
                        [{"op":"replace","path":"/dependencies/connections/0/definitionVersion","value":2}]""",
                        "B WF_CONNECTION_UNKNOWN /dependencies/connections/0/definitionVersion"),
                c("connection listed twice", """
                        [{"op":"add","path":"/dependencies/connections/-","value":{"key":"procurement","definitionVersion":1}}]""",
                        "B WF_CONNECTION_UNKNOWN /dependencies/connections/1/key"),
                c("unknown operation", """
                        [{"op":"replace","path":"/nodes/5/config/operation","value":"createOrdr"}]""",
                        "B WF_OPERATION_UNKNOWN /nodes/5/config/operation"),
                c("mapping problems", """
                        [{"op":"add","path":"/nodes/5/config/inputMapping/extra","value":"/input/purpose"},
                         {"op":"replace","path":"/nodes/5/config/inputMapping/amount","value":"/input/title"},
                         {"op":"replace","path":"/nodes/5/config/inputMapping/costCenter","value":"/input/purpose"}]""",
                        "B WF_MAPPING_UNKNOWN_TARGET /nodes/5/config/inputMapping/extra",
                        "B WF_MAPPING_TYPE /nodes/5/config/inputMapping/amount",
                        "B WF_MAPPING_TYPE /nodes/5/config/inputMapping/costCenter"),
                c("result pointers", """
                        [{"op":"replace","path":"/nodes/5/config/resultMapping/procurementId","value":"/nope"},
                         {"op":"add","path":"/nodes/5/config/resultMapping/deep","value":"/id/x"}]""",
                        "B WF_RESULT_POINTER_UNKNOWN /nodes/5/config/resultMapping/procurementId",
                        "B WF_RESULT_POINTER_UNKNOWN /nodes/5/config/resultMapping/deep"),
                c("health operation has no request body", """
                        [{"op":"replace","path":"/nodes/5/config/operation","value":"health"},
                         {"op":"replace","path":"/nodes/5/config/timeoutSeconds","value":5},
                         {"op":"replace","path":"/nodes/5/config/resultMapping","value":{"procurementId":"/status"}}]""",
                        "B WF_MAPPING_UNKNOWN_TARGET /nodes/5/config/inputMapping/title")
        );
    }

    private static Arguments c(String name, String patch, String... expected) {
        return Arguments.of(name, patch, List.of(expected));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("patches")
    void ruleFires(String name, String patch, List<String> expected) {
        String doc = TestSupport.text(TestSupport.patch(TestSupport.purchase(), StrictJson.parse(patch)));
        ValidationReport report = COMPILER.validate(doc, TestSupport.defaultContext());
        assertThat(found(report)).as(name).containsAll(expected);
        report.issues().forEach(i -> assertThat(i.message()).isNotBlank().doesNotContain("null"));
    }

    private static List<String> found(ValidationReport report) {
        return report.issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    @Test
    void emptyGroupAndOptionalResult() {
        CompileContext base = TestSupport.defaultContext();
        ObjectNode definition = (ObjectNode) TestSupport.json("examples/connections/procurement.connection.json");
        ObjectNode response = (ObjectNode) definition.get("operations").get("createOrder").get("responseSchema");
        ((ObjectNode) response.get("properties")).set("meta", StrictJson.parse(
                "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\"}}}"));
        String text = StrictJson.write(definition);
        CompileContext context = new CompileContext(false,
                Map.of("managers", new CompileContext.GroupInfo("managers", "Managers", 0),
                        "finance-approvers", base.groups().get("finance-approvers")),
                List.of(new CompileContext.ConnectionVersion("procurement", 1, text, CanonicalJson.sha256Hex(text))),
                Set.of("procurement"));
        String doc = TestSupport.text(TestSupport.patch(TestSupport.purchase(), StrictJson.parse("""
                [{"op":"add","path":"/nodes/5/config/resultMapping/ref","value":"/meta/ref"}]""")));
        ValidationReport report = COMPILER.validate(doc, context);
        assertThat(found(report)).containsExactly(
                "W WF_GROUP_EMPTY /nodes/1/config/candidateGroupRef",
                "W WF_RESULT_OPTIONAL /nodes/5/config/resultMapping/ref");
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void malformedConnectionDefinitions() {
        for (String broken : List.of("{", "{\"schemaVersion\":\"0.1\"}",
                TestSupport.read("examples/connections/procurement.connection.json")
                        .replace("\"key\": \"procurement\"", "\"key\": \"other\""))) {
            CompileContext context = new CompileContext(false, TestSupport.defaultContext().groups(),
                    List.of(new CompileContext.ConnectionVersion("procurement", 1, broken, "h")),
                    Set.of("procurement"));
            ValidationReport report = COMPILER.validate(TestSupport.purchaseJson(), context);
            assertThat(found(report)).as(broken).containsExactly(
                    "B WF_CONNECTION_UNKNOWN /dependencies/connections/0/definitionVersion");
            assertThat(report.issues().get(0).message()).contains("cannot be used because");
        }
    }

    @Test
    void nullContextMeansAnEmptyWorkspace() {
        ValidationReport report = COMPILER.validate(TestSupport.purchaseJson(), null);
        assertThat(TestSupport.codes(report.issues(), Severity.BLOCKER)).contains("WF_GROUP_UNBOUND",
                "WF_CONNECTION_UNKNOWN");
    }

    @Test
    void compileThrowsWithTheReport() {
        String doc = TestSupport.text(TestSupport.patch(TestSupport.purchase(), StrictJson.parse(
                "[{\"op\":\"replace\",\"path\":\"/security/selfApproval\",\"value\":\"allow\"}]")));
        assertThatThrownBy(() -> COMPILER.compile(doc, TestSupport.defaultContext()))
                .isInstanceOfSatisfying(CompilationException.class, e -> {
                    assertThat(e.report().publishable()).isFalse();
                    assertThat(e.report().issues()).extracting(ValidationIssue::code)
                            .containsExactly("WF_SELF_APPROVAL_NOT_ALLOWED");
                });
    }
}
