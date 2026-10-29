package com.orqix.migration.pega;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import com.orqix.cases.CmmnSubsetValidator;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.pega.rules.NativeCaseDefinition;
import com.orqix.migration.pega.rules.NativeConversion;
import com.orqix.migration.pega.rules.RuleResolution;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** MIG11 round 3 (docs/build/S117): connectors, data pages, data transforms, validate rules and case definitions. */
class PegaNativeRound3Test {
    final Json json = new Json(JsonMapper.builder().build());

    static byte[] fixture() throws Exception { return Files.readAllBytes(PegaNativeTest.FIXTURES.resolve("purchase-connected.zip")); }

    NativeConversion.Plan plan(byte[] archive, String start) {
        var analysis = RuleResolution.analyze(archive);
        return NativeConversion.plan(analysis, analysis.startingPoints().stream().filter(s -> s.rule().equals(start)).findFirst().orElseThrow(), json);
    }

    ObjectNode orderRequest() {
        ObjectNode r = json.object().put("workflowKey", "pega-order").put("name", "Order").put("acknowledgeInfo", true).put("acknowledgeReview", true);
        r.putObject("groupMappings").put("Managers", "managers");
        r.putObject("approvalRoutes").putObject("Review").put("approvedFlowId", "orderApprove").put("rejectedFlowId", "orderReject");
        r.putObject("connectionMappings").putObject("PlaceOrder").put("connectionRef", "procurement").put("operation", "createOrder").put("definitionVersion", 1);
        return r;
    }

    ObjectNode caseRequest() {
        ObjectNode r = json.object().put("key", "connected-purchase").put("name", "Connected purchase").put("acknowledgeInfo", true).put("acknowledgeReview", true);
        ObjectNode wf = r.putObject("workflowMappings");
        for (String flow : List.of("Order", "Escalate", "Close", "Withdraw")) wf.put(flow, "pega-" + flow.toLowerCase(Locale.ROOT));
        r.putObject("roleMappings").put("Escalate", "finance-approvers").put("withdrawn", "managers");
        return r;
    }

    @Test void resolutionStillMatchesTheOracleAndListsProcessStartingPoints() throws Exception {
        JsonNode expected = json.parse(Files.readString(PegaNativeTest.FIXTURES.resolve("expected/purchase-connected.json")));
        var analysis = RuleResolution.analyze(fixture());
        assertThat(analysis.expectation(json)).isEqualTo(expected);
        assertThat(analysis.startingPoints()).extracting(RuleResolution.Start::kind).containsExactly("caseType", "process", "process", "process", "process");
    }

    @Test void connectorWithTransformBecomesACallStepOnTheMappedConnection() throws Exception {
        var plan = plan(fixture(), "flows/Order.xml");
        assertThat(plan.blocked()).as(plan.report().issues().stream().filter(Report.Issue::blocksPublishing).toList().toString()).isFalse();
        var call = plan.calls().getFirst();
        assertThat(call.inputMapping()).isEqualTo(Map.of("title", "/input/title", "amount", "/input/amount", "costCenter", "/input/costCenter"));
        assertThat(call.resultMapping()).isEqualTo(Map.of("orderId", "/id"));
        assertThat(call.method()).isEqualTo("POST");
        var byRule = new HashMap<String, NativeConversion.RuleResult>();
        plan.rules().forEach(r -> byRule.put(r.rule(), r));
        assertThat(byRule.get("transforms/OrderRequest.xml").targetKind()).isEqualTo("callInputMapping");
        assertThat(byRule.get("connectors/CreateOrder.xml").reasons()).contains("PEGA_NATIVE_CONNECTOR_AS_CALL");
        ObjectNode unmapped = orderRequest(); unmapped.remove("connectionMappings");
        assertThat(NativeConversion.convert(plan, UUID.randomUUID(), unmapped, json).report().publishable()).as("the connection mapping is explicit").isFalse();
        var result = NativeConversion.convert(plan, UUID.randomUUID(), orderRequest(), json);
        assertThat(result.definition()).as(result.report().issues().stream().filter(i -> Report.OPEN.equals(i.resolutionStatus()) && !Report.SUPPORTED.equals(i.severity())).toList().toString()).isNotNull();
        JsonNode node = null;
        for (JsonNode n : result.definition().path("nodes")) if (n.path("id").asString().equals("PlaceOrder")) node = n;
        assertThat(node.path("type").asString()).isEqualTo("http");
        assertThat(node.path("config").path("connectionRef").asString()).isEqualTo("procurement");
        assertThat(node.path("config").path("inputMapping").path("costCenter").asString()).isEqualTo("/input/costCenter");
        assertThat(node.path("config").path("resultMapping").path("orderId").asString()).isEqualTo("/id");
        var compiled = Compilers.workflowCompiler().validate(json.write(result.definition()), new CompileContext(false, Map.of(), List.of(), Set.of()));
        // Groups and connections are bound to the workspace at draft creation; the API test covers that.
        assertThat(compiled.issues()).filteredOn(i -> i.severity().name().equals("BLOCKER") && !i.code().equals("WF_GROUP_UNBOUND") && !i.code().startsWith("WF_CONNECTION")).isEmpty();
    }

    @Test void dataPageReadOnlyFromAConnectorIsACallStepAndOthersAreRefused() throws Exception {
        byte[] viaPage = PegaNativeTest.edit(fixture(), "flows/Order.xml", s -> s.replace("<pyConnectorName>CreateOrder</pyConnectorName>", "<pyDataPageName>D_OrderPlacement</pyDataPageName>"));
        var plan = plan(viaPage, "flows/Order.xml");
        assertThat(plan.blocked()).isFalse();
        assertThat(plan.calls().getFirst().dataPage()).isEqualTo("D_OrderPlacement");
        Map<String, UnaryOperator<String>> refused = Map.of(
                "editable", s -> s.replace("<pyIsReadOnly>true</pyIsReadOnly>", "<pyIsReadOnly>false</pyIsReadOnly>"),
                "requestor scope", s -> s.replace("<pyDataPageScope>Thread", "<pyDataPageScope>Requestor"),
                "transform source", s -> s.replace("<pySourceType>Connector", "<pySourceType>DataTransform"),
                "refresh", s -> s.replace("<pySourceName>", "<pyRefreshStrategy>Reload</pyRefreshStrategy>\n  <pySourceName>"));
        for (var c : refused.entrySet()) {
            var p = plan(PegaNativeTest.edit(viaPage, "datapages/D_OrderPlacement.xml", c.getValue()), "flows/Order.xml");
            assertThat(PegaNativeTest.codes(p.report())).as(c.getKey()).contains("PEGA_NATIVE_DATA_PAGE_UNSUPPORTED");
        }
        var read = plan(PegaNativeTest.edit(fixture(), "validate/RequestChecks.xml", s -> s.replaceFirst("<pyProperty>.Title</pyProperty>", "<pyProperty>.D_Vendor.Name</pyProperty>")),
                "casetypes/ConnectedPurchase.xml");
        assertThat(PegaNativeTest.codes(read.report())).contains("PEGA_NATIVE_DATA_PAGE_UNSUPPORTED");
    }

    @Test void transformsWithFunctionsLiteralsOrConditionsAndOtherConnectorShapesAreRefused() throws Exception {
        record Mutation(String code, String entry, UnaryOperator<String> change) { }
        for (Mutation m : List.of(
                new Mutation("PEGA_NATIVE_TRANSFORM_UNSUPPORTED", "transforms/OrderRequest.xml", s -> s.replace("<pySource>.Title</pySource>", "<pySource>@String.toUpperCase(.Title)</pySource>")),
                new Mutation("PEGA_NATIVE_TRANSFORM_UNSUPPORTED", "transforms/OrderRequest.xml", s -> s.replace("<pySource>.Title</pySource>", "<pySource>\"Laptops\"</pySource>")),
                new Mutation("PEGA_NATIVE_TRANSFORM_UNSUPPORTED", "transforms/OrderRequest.xml", s -> s.replace("<pyAction>Set</pyAction>", "<pyAction>When</pyAction>")),
                new Mutation("PEGA_NATIVE_TRANSFORM_UNSUPPORTED", "transforms/OrderRequest.xml", s -> s.replace("<pyTarget>.request.title</pyTarget>", "<pyTarget>.Title</pyTarget>")),
                new Mutation("PEGA_NATIVE_GRAMMAR_UNSUPPORTED", "connectors/CreateOrder.xml", s -> s.replace("<pyMethod>", "<pyErrorHandlerFlow>Retry</pyErrorHandlerFlow>\n  <pyMethod>")),
                new Mutation("PEGA_NATIVE_GRAMMAR_UNSUPPORTED", "connectors/CreateOrder.xml", s -> s.replace("<pyJsonPointer>/id</pyJsonPointer>", "<pyJsonPointer>$.id</pyJsonPointer>")),
                new Mutation("PEGA_NATIVE_CONNECTOR_UNSUPPORTED", "flows/Order.xml", s -> s.replace("<pyConnectorName>CreateOrder</pyConnectorName>", "<pyConnectorName>CreateOrder</pyConnectorName>\n        <pyDataPageName>D_OrderPlacement</pyDataPageName>")))) {
            var plan = plan(PegaNativeTest.edit(fixture(), m.entry(), m.change()), "flows/Order.xml");
            assertThat(plan.blocked()).as(m.code() + " " + m.entry()).isTrue();
            assertThat(PegaNativeTest.codes(plan.report())).as(m.code() + " " + m.entry()).contains(m.code());
        }
    }

    @Test void validateOnCreateBecomesRequestFormValidationAndDeclareExpressionsStayRefused() throws Exception {
        byte[] withPattern = PegaNativeTest.edit(fixture(), "validate/RequestChecks.xml", s -> s.replace("</pyConditions>",
                "  <rowdata REPEATINGINDEX=\"6\"><pyProperty>.Title</pyProperty><pyCheck>Pattern</pyCheck><pyValue>^[A-Za-z0-9 ]+$</pyValue><pyMessage>Letters, digits and spaces only.</pyMessage></rowdata>\n  </pyConditions>"));
        // As a workflow the case type is blocked by its optional process and alternate stage, but the request form it builds
        // carries the validation.
        var plan = plan(withPattern, "casetypes/ConnectedPurchase.xml");
        JsonNode props = plan.build().inputSchema().path("properties");
        assertThat(props.path("amount").path("minimum").asInt()).isEqualTo(1);
        assertThat(props.path("amount").path("maximum").asInt()).isEqualTo(1000000);
        assertThat(props.path("title").path("pattern").asString()).isEqualTo("^[A-Za-z0-9 ]+$");
        assertThat(props.path("title").path("patternMessage").asString()).isEqualTo("Letters, digits and spaces only.");
        assertThat(plan.build().inputSchema().path("required").toString()).contains("title").contains("amount");
        assertThat(PegaNativeTest.codes(plan.report())).contains("PEGA_NATIVE_OPTIONAL_PROCESS_UNSUPPORTED", "PEGA_NATIVE_ALTERNATE_STAGE_UNSUPPORTED");
        for (String bad : List.of("<pyCheck>Custom</pyCheck>", "<pyCheck>Pattern</pyCheck><pyValue>(a+)+</pyValue>")) {
            var p = plan(PegaNativeTest.edit(fixture(), "validate/RequestChecks.xml", s -> s.replace("<pyCheck>Required</pyCheck>", bad)), "casetypes/ConnectedPurchase.xml");
            assertThat(p.report().issues()).extracting(Report.Issue::sourcePath).anyMatch(path -> path.startsWith("validate/RequestChecks.xml"));
        }
        byte[] declare = PegaNativeTest.add(fixture(), "declare/Total.xml", "<pagedata><pxObjClass>Rule-Declare-Expressions</pxObjClass><pyClassName>ACME-Purchasing-Work</pyClassName>"
                + "<pyRuleName>Total</pyRuleName><pyRuleSet>Purchasing</pyRuleSet><pyRuleSetVersion>01-01-01</pyRuleSetVersion><pyRuleAvailable>Yes</pyRuleAvailable>"
                + "<pzInsKey>RULE-DECLARE-EXPRESSIONS ACME-PURCHASING-WORK TOTAL</pzInsKey><pyExpression>.Amount * 1.2</pyExpression></pagedata>");
        var declared = plan(declare, "flows/Order.xml");
        assertThat(declared.report().issues()).filteredOn(i -> i.reasonCode().equals("PEGA_NATIVE_DECLARATIVE_UNSUPPORTED")).anyMatch(i -> i.explanation().contains("no computed fields"));
    }

    @Test void caseTypeBecomesACmmnCaseDefinitionTheCaseDesignerAdmits() throws Exception {
        var analysis = RuleResolution.analyze(fixture());
        var start = analysis.startingPoints().getFirst();
        var missing = NativeCaseDefinition.build(analysis, start, json.object().put("key", "connected-purchase"), json);
        assertThat(missing.blocked()).isTrue();
        assertThat(missing.report().issues()).extracting(Report.Issue::reasonCode).contains("PEGA_NATIVE_WORKFLOW_MAPPING_REQUIRED", "PEGA_NATIVE_ROLE_MAPPING_REQUIRED");
        var proposal = NativeCaseDefinition.build(analysis, start, caseRequest(), json);
        assertThat(proposal.blocked()).as(proposal.report().issues().stream().filter(Report.Issue::blocksPublishing).toList().toString()).isFalse();
        assertThat(proposal.report().publishable()).isTrue();
        Set<String> groups = new CmmnSubsetValidator().validate(proposal.sourceXml(), "connected-purchase");
        assertThat(groups).contains("finance-approvers", "managers");
        String xml = proposal.sourceXml();
        assertThat(xml).contains("<discretionaryItem id=\"t_intake_Escalate_item\"").contains("<userEventListener id=\"withdrawn_event\"")
                .contains("<timerExpression>P5D</timerExpression>").contains("<timerExpression>P2D</timerExpression>")
                .contains("externalRef=\"orqixwf:pega-order\"").contains("<exitCriterion id=\"intake_leave_withdrawn_exit\"");
        assertThat(proposal.dataFields()).extracting(f -> f.path("key").asString()).containsExactlyInAnyOrder("title", "amount", "costCenter");
        JsonNode amount = null;
        for (JsonNode f : proposal.dataFields()) if (f.path("key").asString().equals("amount")) amount = f;
        assertThat(amount.path("type").asString()).isEqualTo("number");
        assertThat(amount.path("minimum").asInt()).isEqualTo(1);
        assertThat(amount.path("required").asBoolean()).isTrue();
        assertThat(proposal.workflowTasks()).extracting(t -> t.path("taskId").asString()).containsExactly("t_intake_Order", "t_intake_Escalate", "t_close_Close", "t_withdrawn_Withdraw");
        assertThat(proposal.workflowTasks().get(0).path("inputMapping").path("costCenter").asString()).isEqualTo("costCenter");
        assertThat(proposal.processes()).filteredOn(NativeCaseDefinition.Process::optional).extracting(NativeCaseDefinition.Process::flow).containsExactly("Escalate");
        assertThat(proposal.report().issues()).extracting(Report.Issue::reasonCode).contains("PEGA_NATIVE_SLA_AS_TIMER", "PEGA_NATIVE_CASE_DEFINITION");

        byte[] withPattern = PegaNativeTest.edit(fixture(), "validate/RequestChecks.xml", s -> s.replace("</pyConditions>",
                "  <rowdata REPEATINGINDEX=\"6\"><pyProperty>.Title</pyProperty><pyCheck>Pattern</pyCheck><pyValue>^[A-Z]+$</pyValue></rowdata>\n  </pyConditions>"));
        var patterned = RuleResolution.analyze(withPattern);
        assertThat(NativeCaseDefinition.build(patterned, patterned.startingPoints().getFirst(), caseRequest(), json).report().issues())
                .extracting(Report.Issue::reasonCode).contains("PEGA_NATIVE_CASE_FIELD_CHECK_UNSUPPORTED");
        byte[] businessDays = PegaNativeTest.edit(fixture(), "sla/CaseSLA.xml", s -> s.replace("<pyUseBusinessDays>false", "<pyUseBusinessDays>true"));
        var bd = RuleResolution.analyze(businessDays);
        assertThat(NativeCaseDefinition.build(bd, bd.startingPoints().getFirst(), caseRequest(), json).report().issues())
                .extracting(Report.Issue::reasonCode).contains("PEGA_NATIVE_SLA_TIMER_UNSUPPORTED");
    }
}
