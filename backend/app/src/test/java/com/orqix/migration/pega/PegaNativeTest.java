package com.orqix.migration.pega;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.camunda.SourceException;
import com.orqix.migration.pega.rules.NativeConversion;
import com.orqix.migration.pega.rules.RuleResolution;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** MIG11 decoding, resolution, conversion and refusal on the synthetic native fixtures (docs/build/S112). */
class PegaNativeTest {
    static final Path FIXTURES = Path.of("../../examples/migration/pega-native");
    final Json json = new Json(JsonMapper.builder().build());

    static byte[] fixture(String name) throws Exception { return Files.readAllBytes(FIXTURES.resolve(name + ".zip")); }

    /** Rewrites one archive entry (or removes it when the function returns null). */
    static byte[] edit(byte[] archive, String entry, UnaryOperator<String> change) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var in = new ZipInputStream(new ByteArrayInputStream(archive)); var zip = new ZipOutputStream(out)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (e.getName().equals(entry)) text = change.apply(text);
                if (text == null) continue;
                zip.putNextEntry(new ZipEntry(e.getName())); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    ObjectNode request() {
        ObjectNode r = json.object().put("workflowKey", "purchase-request").put("name", "Purchase request").put("acknowledgeInfo", true).put("acknowledgeReview", true);
        r.putObject("groupMappings").put("Managers", "managers").put("Finance", "finance").put("Procurement", "procurement");
        ObjectNode routes = r.putObject("approvalRoutes");
        routes.putObject("ManagerReview").put("approvedFlowId", "managerApprove").put("rejectedFlowId", "managerReject");
        routes.putObject("FinanceReview").put("approvedFlowId", "financeApprove").put("rejectedFlowId", "financeReject");
        routes.putObject("ConfirmOrder").put("approvedFlowId", "confirm").put("rejectedFlowId", "cancel");
        return r;
    }

    NativeConversion.Plan plan(byte[] archive) {
        var analysis = RuleResolution.analyze(archive);
        assertThat(analysis.startingPoints()).hasSize(1);
        return NativeConversion.plan(analysis, analysis.startingPoints().getFirst(), json);
    }

    NativeConversion.Plan plan(byte[] archive, String start) {
        var analysis = RuleResolution.analyze(archive);
        return NativeConversion.plan(analysis, analysis.startingPoints().stream().filter(s -> s.rule().equals(start)).findFirst().orElseThrow(), json);
    }

    ObjectNode advancedRequest() {
        ObjectNode r = request().put("workflowKey", "purchase-order");
        r.putObject("groupMappings").put("Managers", "managers").put("Finance", "finance").put("Procurement", "procurement");
        ObjectNode children = r.putObject("childWorkflowMappings");
        for (String call : List.of("Checks_1", "Checks_2", "Record"))
            children.putObject(call).put("workflowRef", "pega-child").set("inputMapping", json.object());
        children.properties().forEach(e -> ((ObjectNode) e.getValue()).set("resultMapping", json.object()));
        return r;
    }

    static Set<String> codes(Report.CompatibilityReport report) {
        Set<String> codes = new TreeSet<>();
        report.issues().stream().filter(Report.Issue::blocksPublishing).forEach(i -> codes.add(i.reasonCode()));
        return codes;
    }

    @Test void resolutionMatchesTheIndependentPythonImplementation() throws Exception {
        for (String name : List.of("purchase-request", "refusals", "purchase-advanced")) {
            JsonNode expected = json.parse(Files.readString(FIXTURES.resolve("expected/" + name + ".json")));
            assertThat(RuleResolution.analyze(fixture(name)).expectation(json)).as(name).isEqualTo(expected);
        }
    }

    @Test void purchaseRequestConvertsToACompilingStagedWorkflow() throws Exception {
        var plan = plan(fixture("purchase-request"));
        assertThat(plan.blocked()).as(plan.report().issues().stream().filter(Report.Issue::blocksPublishing).toList().toString()).isFalse();
        assertThat(plan.report().requiredMappings().groups()).containsExactlyInAnyOrder("Managers", "Finance", "Procurement");
        assertThat(plan.report().requiredMappings().approvalTasks()).containsExactlyInAnyOrder("ManagerReview", "FinanceReview", "ConfirmOrder");
        var byRule = new HashMap<String, NativeConversion.RuleResult>();
        plan.rules().forEach(r -> byRule.put(r.rule(), r));
        assertThat(byRule.get("decisions/ApprovalLevel-01-01-01.xml").conversion()).isEqualTo("converted");
        assertThat(byRule.get("decisions/ApprovalLevel-01-01-01.xml").targetSha256()).hasSize(64);
        assertThat(byRule.get("decisions/ApprovalLevel-01-01-05.xml").conversion()).isEqualTo("shadowed");
        assertThat(byRule.get("decisions/ApprovalLevel-01-01-05.xml").resolution()).isEqualTo("notAvailable");
        assertThat(byRule.get("flows/ApproveRequest-sandbox.xml").resolution()).isEqualTo("rulesetNotInApplication");
        assertThat(byRule.get("sections/RequestDetails-Request-01-01-02.xml").resolution()).isEqualTo("maskedByWithdrawn");
        assertThat(byRule.get("properties/VendorName.xml").targets()).containsExactly("/input/vendorName");
        assertThat(byRule.get("properties/InternalNote.xml").conversion()).isEqualTo("notUsed");
        assertThat(byRule.get("activities/SendNotice.xml").conversion()).isEqualTo("notUsed");
        assertThat(byRule.get("transforms/pyDefault.xml").conversion()).isEqualTo("review");
        assertThat(byRule.values()).allSatisfy(r -> assertThat(r.sourceSha256()).hasSize(64));

        var unacknowledged = NativeConversion.convert(plan, UUID.randomUUID(), request().put("acknowledgeInfo", false), json);
        assertThat(unacknowledged.report().publishable()).isFalse();
        var result = NativeConversion.convert(plan, UUID.randomUUID(), request(), json);
        assertThat(result.definition()).as(result.report().issues().stream().filter(i -> !Report.ACKNOWLEDGED.equals(i.resolutionStatus()) && !Report.SUPPORTED.equals(i.severity())).toList().toString()).isNotNull();
        assertThat(result.report().publishable()).isTrue();
        ObjectNode def = result.definition();
        var compiled = Compilers.workflowCompiler().validate(json.write(def), new CompileContext(false, Map.of(), List.of(), Set.of()));
        // Group existence is checked against the workspace at draft creation; the API test covers that.
        assertThat(compiled.issues()).as(def.toString()).filteredOn(i -> !i.code().equals("WF_GROUP_UNBOUND")).isEmpty();
        assertThat(def.path("stages").toString()).contains("\"review\"").contains("\"P3D\"").contains("\"fulfilment\"");
        assertThat(def.path("inputSchema").path("properties").properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("amount", "description", "category", "neededBy", "vendorName", "vendorPreferred");
        assertThat(def.path("inputSchema").path("properties").path("category").path("description").asString()).isEqualTo("Source default: Hardware");
        assertThat(def.path("inputUi").path("layout").path("sections").size()).isEqualTo(2);
        assertThat(def.path("inputUi").path("widgets").path("amount").asString()).isEqualTo("money");
        String nodes = def.path("nodes").toString();
        assertThat(nodes).contains("\"dueAfterHours\":24").contains("\"dueAfterMinutes\":150").contains("\"outcomeKind\":\"rejected\"");
        String table = def.path("decisions").get(0).path("key").asString();
        for (var c : Map.of("4999.99", "Standard", "5000", "Finance", "5000.01", "Finance").entrySet())
            assertThat(Compilers.decisionEvaluator().evaluate(json.write(def), table, Map.of("/input/amount", new BigDecimal(c.getKey()))).output())
                    .as(c.getKey()).containsEntry("result", c.getValue());
        assertThat(result.sourceMap()).containsKey("flows/ApproveRequest.xml#/pyShapes/3");
    }

    @Test void referencedUnsupportedBehaviorBlocksWithSourcePaths() throws Exception {
        var plan = plan(fixture("refusals"));
        assertThat(plan.blocked()).isTrue();
        assertThat(codes(plan.report())).contains("PEGA_NATIVE_ENTRY_UNDECODED", "PEGA_NATIVE_ACTIVITY_NOT_EXECUTED", "PEGA_NATIVE_CONNECTOR_NOT_EXECUTED",
                "PEGA_NATIVE_SLA_BUSINESS_DAYS", "PEGA_NATIVE_RULE_BLOCKED", "PEGA_NATIVE_CIRCUMSTANCE_UNSUPPORTED", "PEGA_NATIVE_REFERENCE_UNRESOLVED",
                "PEGA_NATIVE_DECLARATIVE_UNSUPPORTED");
        assertThat(plan.report().issues()).filteredOn(Report.Issue::blocksPublishing).allSatisfy(i -> assertThat(i.sourcePath()).isNotBlank());
        var byRule = new HashMap<String, NativeConversion.RuleResult>();
        plan.rules().forEach(r -> byRule.put(r.rule(), r));
        assertThat(byRule.get("activities/SendNotice.xml").conversion()).isEqualTo("blocked");
        assertThat(byRule.get("connectors/CreditCheck.xml").conversion()).isEqualTo("blocked");
        assertThat(byRule.get("decisions/Tier-org.xml").conversion()).isEqualTo("blocked");
    }

    @Test void advancedCaseConvertsVariantsSubflowsSplitJoinCalendarAndEscalation() throws Exception {
        byte[] archive = fixture("purchase-advanced");
        var plan = plan(archive, "casetypes/PurchaseOrder.xml");
        assertThat(plan.blocked()).as(plan.report().issues().stream().filter(Report.Issue::blocksPublishing).toList().toString()).isFalse();
        assertThat(plan.report().requiredMappings().childWorkflows()).containsExactlyInAnyOrder("Checks_1", "Checks_2", "Record");
        assertThat(plan.subflows()).extracting(NativeConversion.Subflow::flow).containsExactlyInAnyOrder("BudgetCheck", "VendorScreen", "RecordOrder");
        var byRule = new HashMap<String, NativeConversion.RuleResult>();
        plan.rules().forEach(r -> byRule.put(r.rule(), r));
        assertThat(byRule.get("decisions/ApprovalLevel-Services.xml").resolution()).isEqualTo("variant");
        assertThat(byRule.get("decisions/ApprovalLevel-Services.xml").circumstance()).isEqualTo(".Category = Services");
        assertThat(byRule.get("decisions/ApprovalLevel.xml").delegatedTo()).containsExactly("Purchasing:Managers");
        assertThat(byRule.get("flows/BudgetCheck.xml").targetKind()).isEqualTo("childWorkflow");
        assertThat(plan.report().issues()).extracting(Report.Issue::reasonCode).contains("PEGA_NATIVE_DELEGATION_RECORDED", "PEGA_NATIVE_ESCALATION_AS_NOTICES", "PEGA_NATIVE_SUBFLOW_AS_CHILD");
        assertThat(byRule.get("sla/BusinessSLA.xml").reasons()).contains("PEGA_NATIVE_ESCALATION_AS_NOTICES", "PEGA_NATIVE_SLA_BUSINESS_DAYS_AS_HOURS", "PEGA_NATIVE_SLA_GOAL_NOT_REPRESENTED");
        assertThat(NativeConversion.convert(plan, UUID.randomUUID(), request(), json).report().publishable()).as("child mappings are required").isFalse();
        var result = NativeConversion.convert(plan, UUID.randomUUID(), advancedRequest(), json);
        assertThat(result.definition()).as(result.report().issues().stream().filter(i -> Report.OPEN.equals(i.resolutionStatus()) && !Report.SUPPORTED.equals(i.severity())).toList().toString()).isNotNull();
        ObjectNode def = result.definition();
        var compiled = Compilers.workflowCompiler().validate(json.write(def), new CompileContext(false, Map.of(), List.of(), Set.of()));
        // Groups and the child workflow are bound to the workspace at draft creation; the API test covers that.
        assertThat(compiled.issues()).as(def.toString()).filteredOn(i -> !i.code().equals("WF_GROUP_UNBOUND") && !i.code().startsWith("WF_CHILD")).isEmpty();
        Map<String, String> types = new HashMap<>();
        def.path("nodes").forEach(n -> types.put(n.path("id").asString(), n.path("type").asString()));
        assertThat(types).containsEntry("Checks", "parallelSplit").containsEntry("Checks_join", "parallelJoin").containsEntry("Checks_1", "startWorkflow")
                .containsEntry("Checks_2", "startWorkflow").containsEntry("Record", "startWorkflow").containsEntry("VendorCheck_v1", "branch").containsEntry("VendorCheck_base", "branch");
        JsonNode manager = null;
        for (JsonNode n : def.path("nodes")) if (n.path("id").asString().equals("ManagerReview")) manager = n.path("config");
        assertThat(manager.path("calendar").path("zone").asString()).isEqualTo("America/New_York");
        assertThat(manager.path("calendar").path("weekdays").toString()).isEqualTo("[1,2,3,4,5]");
        assertThat(manager.path("dueAfterHours").asInt()).isEqualTo(16);
        assertThat(manager.path("escalation").toString()).isEqualTo("{\"intervalHours\":4,\"maxNotices\":4}");
        String level = null, tier = null;
        for (JsonNode t : def.path("decisions")) {
            if (t.toString().contains("Services")) level = t.path("key").asString();
            if (t.toString().contains("effective")) tier = t.path("key").asString();
        }
        assertThat(level).isNotNull(); assertThat(tier).isNotNull();
        record Case(String amount, String category, String expected) { }
        for (Case c : List.of(new Case("1999.99", "Services", "Standard"), new Case("2000", "Services", "Finance"), new Case("4999.99", "Hardware", "Standard"), new Case("5000", "Hardware", "Finance")))
            assertThat(Compilers.decisionEvaluator().evaluate(json.write(def), level, Map.of("/input/amount", new BigDecimal(c.amount()), "/input/category", c.category())).output())
                    .as(c.toString()).containsEntry("result", c.expected());
        for (var c : Map.of("2020-06-01", "Express", "2020-12-30", "Express", "2020-12-31", "Standard", "2026-10-29", "Standard").entrySet())
            assertThat(Compilers.decisionEvaluator().evaluate(json.write(def), tier, Map.of("/input/amount", new BigDecimal("300"), "$asOf", c.getKey())).output())
                    .as(c.getKey()).containsEntry("result", c.getValue());
        assertThat(result.sourceMap()).containsKey("flows/Review.xml#/pyShapes/5");

        var quick = plan(archive, "casetypes/QuickOrder.xml");
        assertThat(quick.blocked()).isFalse();
        assertThat(quick.report().issues()).extracting(Report.Issue::reasonCode).contains("PEGA_NATIVE_CASE_SLA_AS_STAGE");
        ObjectNode quickRequest = json.object().put("workflowKey", "quick-order").put("name", "Quick order").put("acknowledgeInfo", true).put("acknowledgeReview", true);
        quickRequest.putObject("groupMappings").put("Procurement", "procurement");
        quickRequest.putObject("approvalRoutes").putObject("ConfirmOrder").put("approvedFlowId", "confirm").put("rejectedFlowId", "cancel");
        quickRequest.putObject("childWorkflowMappings").set("Record", advancedRequest().path("childWorkflowMappings").path("Record").deepCopy());
        var quickResult = NativeConversion.convert(quick, UUID.randomUUID(), quickRequest, json);
        assertThat(quickResult.definition().path("stages").get(0).path("sla").asString()).isEqualTo("P5D");
    }

    @Test void constructsWithoutASoundTargetStayRefused() throws Exception {
        byte[] base = fixture("purchase-advanced");
        record Mutation(String code, String entry, UnaryOperator<String> change, String start) { }
        String order = "casetypes/PurchaseOrder.xml";
        List<Mutation> mutations = List.of(
            new Mutation("PEGA_NATIVE_SPINOFF_UNSUPPORTED", "flows/Fulfil.xml", s -> s.replace("<pyFlowName>RecordOrder</pyFlowName>", "<pyFlowName>RecordOrder</pyFlowName>\n        <pySpinOff>true</pySpinOff>"), order),
            new Mutation("PEGA_NATIVE_SPINOFF_UNSUPPORTED", "flows/Fulfil.xml", s -> s.replace("<pyShapeType>Subprocess</pyShapeType>", "<pyShapeType>Spinoff</pyShapeType>"), order),
            new Mutation("PEGA_NATIVE_JOIN_UNSUPPORTED", "flows/Review.xml", s -> s.replace("<pyJoinCondition>All</pyJoinCondition>", "<pyJoinCondition>Any</pyJoinCondition>"), order),
            new Mutation("PEGA_NATIVE_CURRENCY_UNSUPPORTED", "sections/RequestDetails.xml", s -> s.replace("<pyCurrency>USD</pyCurrency>", "<pyCurrency>EUR</pyCurrency>"), order),
            new Mutation("PEGA_NATIVE_ALTERNATE_STAGE_UNSUPPORTED", order, s -> s.replace("<pyNewHarness>", "<pyAlternateStages REPEATINGTYPE=\"PageList\"><rowdata REPEATINGINDEX=\"1\"><pyStageID>withdrawn</pyStageID></rowdata></pyAlternateStages>\n  <pyNewHarness>"), order),
            new Mutation("PEGA_NATIVE_OPTIONAL_PROCESS_UNSUPPORTED", order, s -> s.replace("<pyFlowName>Fulfil</pyFlowName>", "<pyFlowName>Fulfil</pyFlowName><pyOptional>true</pyOptional>"), order),
            new Mutation("PEGA_NATIVE_CASE_SLA_UNSUPPORTED", order, s -> s.replace("<pyNewHarness>", "<pyServiceLevelName>CaseSLA</pyServiceLevelName>\n  <pyNewHarness>"), order),
            new Mutation("PEGA_NATIVE_SLA_ESCALATION", "sla/BusinessSLA.xml", s -> s.replace("NotifyAssignee", "TransferToManager"), order),
            new Mutation("PEGA_NATIVE_SLA_ESCALATION", "sla/BusinessSLA.xml", s -> s.replace("<pyUseBusinessDays>", "<pyGoalEscalation>NotifyAssignee</pyGoalEscalation>\n  <pyUseBusinessDays>"), order),
            new Mutation("PEGA_NATIVE_CALENDAR_UNRESOLVED", "sla/BusinessSLA.xml", s -> s.replace("ACMEBusiness", "OtherCalendar"), order),
            new Mutation("PEGA_NATIVE_GRAMMAR_UNSUPPORTED", "data/ACMEBusiness.xml", s -> s.replace("America/New_York", "Mars/Olympus"), order),
            new Mutation("PEGA_NATIVE_SLA_BUSINESS_DAYS", "sla/CaseSLA.xml", s -> s.replace("<pyUseBusinessDays>false", "<pyBusinessCalendar>ACMEBusiness</pyBusinessCalendar><pyUseBusinessDays>true"), "casetypes/QuickOrder.xml"),
            new Mutation("PEGA_NATIVE_GRAMMAR_UNSUPPORTED", "decisions/OrderTier-2020.xml", s -> s.replace("20200101T000000.000", "20200101T120000.000"), order),
            new Mutation("PEGA_NATIVE_CIRCUMSTANCE_UNSUPPORTED", "when/NeedsVendorCheck-Software.xml", s -> s.replace("<pyCircumstanceProp>.Category</pyCircumstanceProp>\n  <pyCircumstanceVal>Software</pyCircumstanceVal>",
                    "<pyStartDateTime>20200101T000000.000 GMT</pyStartDateTime>"), order),
            new Mutation("PEGA_NATIVE_GRAMMAR_UNSUPPORTED", "decisions/ApprovalLevel-Services.xml", s -> s.replace("<pyCircumstanceProp>.Category</pyCircumstanceProp>\n  <pyCircumstanceVal>Services</pyCircumstanceVal>",
                    "<pyCircumstanceDateProp>.NeededBy</pyCircumstanceDateProp>\n  <pyCircumstanceDate>20270101</pyCircumstanceDate>"), order),
            new Mutation("PEGA_NATIVE_CIRCUMSTANCE_UNSUPPORTED", "flows/RecordOrder.xml", s -> s.replace("<pyRuleAvailable>Yes</pyRuleAvailable>", "<pyRuleAvailable>Yes</pyRuleAvailable>\n  <pyCircumstanceProp>.Category</pyCircumstanceProp>\n  <pyCircumstanceVal>Services</pyCircumstanceVal>")
                    .replace("#20261001T090000.000", "#20261001T090900.000"), order));
        for (Mutation m : mutations) {
            byte[] archive = m.entry().equals("flows/RecordOrder.xml")
                    ? add(base, "flows/RecordOrder-Services.xml", m.change().apply(text(base, "flows/RecordOrder.xml")).replace("<pyIsStartingFlow>true</pyIsStartingFlow>", "<pyIsStartingFlow>false</pyIsStartingFlow>"))
                    : edit(base, m.entry(), m.change());
            var plan = plan(archive, m.start());
            assertThat(plan.blocked()).as(m.code() + " " + m.entry()).isTrue();
            assertThat(codes(plan.report())).as(m.code() + " " + m.entry()).contains(m.code());
        }
    }

    static String text(byte[] archive, String entry) throws Exception {
        try (var in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) if (e.getName().equals(entry)) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException(entry);
    }

    static byte[] add(byte[] archive, String entry, String content) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var in = new ZipInputStream(new ByteArrayInputStream(archive)); var zip = new ZipOutputStream(out)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) { zip.putNextEntry(new ZipEntry(e.getName())); zip.write(in.readAllBytes()); zip.closeEntry(); }
            zip.putNextEntry(new ZipEntry(entry)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test void grammarOutsideTheSubsetIsRefusedNotGuessed() throws Exception {
        byte[] base = fixture("purchase-request");
        Map<String, Map.Entry<String, UnaryOperator<String>>> cases = new LinkedHashMap<>();
        cases.put("PEGA_NATIVE_GRAMMAR_UNSUPPORTED", Map.entry("flows/ApproveRequest.xml", s -> s.replace("<pyShapes ", "<pyLocalAction>Escalate</pyLocalAction>\n  <pyShapes ")));
        cases.put("PEGA_NATIVE_ROUTING_UNSUPPORTED", Map.entry("flows/Fulfil.xml", s -> s.replace("ToWorkbasket", "ToWorklist")));
        cases.put("PEGA_NATIVE_SLA_ESCALATION", Map.entry("sla/OrderSLA.xml", s -> s.replace("<pyUseBusinessDays>", "<pyDeadlineEscalation>NotifyManager</pyDeadlineEscalation>\n  <pyUseBusinessDays>")));
        cases.put("PEGA_NATIVE_STATUS_UNSUPPORTED", Map.entry("flows/Fulfil.xml", s -> s.replace("Resolved-Rejected", "Resolved-Withdrawn")));
        cases.put("PEGA_NATIVE_TRANSFORM_UNSUPPORTED", Map.entry("transforms/pyDefault.xml", s -> s.replace("\"Hardware\"", "@String.toUpperCase(.Category)")));
        cases.put("PEGA_NATIVE_PROPERTY_MODE", Map.entry("properties/Description.xml", s -> s.replace("Single Value", "Value List")));
        cases.put("PEGA_NATIVE_FLOWACTION_PROCESSING", Map.entry("flowactions/Approve.xml", s -> s.replace("<pySection>", "<pyPostActivity>UpdateLedger</pyPostActivity>\n  <pySection>")));
        cases.put("PEGA_NATIVE_REFERENCE_UNRESOLVED", Map.entry("sections/VendorDetails.xml", s -> null));
        cases.put("PEGA_NATIVE_FORK_ORDER", Map.entry("flows/ApproveRequest.xml", s -> s.replace("  </pyConnectors>", "    <rowdata REPEATINGINDEX=\"10\"><pyConnectorID>again</pyConnectorID><pyFrom>VendorCheck</pyFrom>"
                + "<pyTo>FinanceReview</pyTo><pyConditionType>When</pyConditionType><pyWhenName>NeedsVendorCheck</pyWhenName></rowdata>\n  </pyConnectors>")));
        cases.put("PEGA_NATIVE_DECISION_ELSE", Map.entry("flows/ApproveRequest.xml", s -> s.replace("<pyTo>Approved</pyTo>\n      <pyConditionType>Else</pyConditionType>",
                "<pyTo>Approved</pyTo>\n      <pyConditionType>When</pyConditionType>\n      <pyWhenName>NeedsVendorCheck</pyWhenName>")));
        for (var c : cases.entrySet()) {
            byte[] changed = edit(base, c.getValue().getKey(), c.getValue().getValue());
            var analysis = RuleResolution.analyze(changed);
            var plan = NativeConversion.plan(analysis, analysis.startingPoints().getFirst(), json);
            assertThat(plan.blocked()).as(c.getKey()).isTrue();
            assertThat(codes(plan.report())).as(c.getKey()).contains(c.getKey());
        }
        var mixed = RuleResolution.analyze(edit(base, "when/NeedsVendorCheck-Request.xml", s -> s.replace("A AND B", "A AND B OR A")));
        assertThat(codes(NativeConversion.plan(mixed, mixed.startingPoints().getFirst(), json).report())).contains("PEGA_NATIVE_GRAMMAR_UNSUPPORTED");
    }

    @Test void archivesThatCannotBeResolvedAreReportedNotConverted() throws Exception {
        assertThatThrownBy(() -> RuleResolution.analyze("not a zip".getBytes(StandardCharsets.UTF_8))).isInstanceOf(SourceException.class);
        var noApp = RuleResolution.analyze(edit(fixture("purchase-request"), "application/Purchasing.xml", s -> null));
        assertThat(noApp.startingPoints()).isEmpty();
        assertThat(codes(NativeConversion.archiveReport(noApp))).contains("PEGA_NATIVE_APPLICATION_MISSING");
        var xxe = RuleResolution.analyze(edit(fixture("purchase-request"), "harness/New.xml", s -> "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><pagedata>&e;</pagedata>"));
        assertThat(xxe.entries().stream().filter(e -> e.path().equals("harness/New.xml")).findFirst().orElseThrow().reason()).isEqualTo("PEGA_NATIVE_XML_INVALID");
    }
}
