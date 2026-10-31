package com.orqix.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.compiler.ConditionMatcher;
import com.orqix.migration.camunda.Analysis;
import com.orqix.migration.camunda.Analyzer;
import com.orqix.migration.camunda.Converter;
import com.orqix.migration.camunda.Limits;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.camunda.SourceException;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wave 1 and 3 conversion behaviour without a database: REVIEW items, grouped service-task mappings, the extended condition
 * operators validated by the real compiler, and multi-instance call activities mapped onto bounded for-each.
 */
class ConverterExpansionTest {

    private static final Json JSON = new Json(JsonMapper.builder().build());

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("../../examples/migration/" + name));
    }

    private static Analysis analyze(String name) throws Exception {
        return Analyzer.analyze(fixture(name), Limits.DEFAULT);
    }

    private static List<Report.Issue> issues(Analysis a) {
        return a.items().stream().map(Analysis.Item::issue).toList();
    }

    private static ObjectNode request(String json) {
        return (ObjectNode) JSON.parse(json);
    }

    private static CompileContext emptyContext() {
        return new CompileContext(false, Map.of(), List.of(), Set.of());
    }

    private static List<String> blockers(JsonNode definition, CompileContext context) {
        return Compilers.workflowCompiler().validate(JSON.write(definition), context).issues().stream()
                .filter(i -> i.severity() == com.orqix.compiler.Severity.BLOCKER).map(i -> i.code() + " " + i.message()).toList();
    }

    // ------------------------------------------------------------------------------------------------ 1. review severity

    private static final String LISTENER_PROCESS = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="urn:test">
             <process id="listener" isExecutable="true"><startEvent id="start"/><endEvent id="end" name="Finished"/>
              <sequenceFlow id="go" sourceRef="start" targetRef="end"/>
              <extensionElements><camunda:executionListener event="start" class="example.Listener"/></extensionElements>
             </process></definitions>""";

    @Test
    void reviewItemsAreCountedAndNeverMakeAReportUnpublishable() {
        String simple = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
                 <process id="plain" isExecutable="true"><startEvent id="start"/><endEvent id="end" name="Finished"/>
                  <sequenceFlow id="go" sourceRef="start" targetRef="end"/></process></definitions>""";
        var analysis = Analyzer.analyze(simple, Limits.DEFAULT);
        var report = Converter.report(analysis, issues(analysis));
        assertThat(report.elementCounts().review()).isEqualTo(2); // target policy and the inferred finish outcome
        assertThat(report.elementCounts().total()).isEqualTo(report.issues().size());
        assertThat(report.issues()).filteredOn(i -> Report.REVIEW.equals(i.severity())).allMatch(i -> Report.OPEN.equals(i.resolutionStatus()))
                .noneMatch(Report.Issue::blocksPublishing);
        assertThat(report.publishable()).as("open review items alone never block").isTrue();
        var converted = Converter.convert(analysis, request("{\"workflowKey\":\"plain-flow\",\"name\":\"Plain\"}"), JSON, Map.of());
        assertThat(converted.definition()).as("converts without acknowledging review items").isNotNull();
        assertThat(converted.report().issues()).filteredOn(i -> Report.REVIEW.equals(i.severity())).allMatch(i -> Report.OPEN.equals(i.resolutionStatus()));
        var acknowledged = Converter.convert(analysis, request("{\"workflowKey\":\"plain-flow\",\"name\":\"Plain\",\"acknowledgeReview\":true}"), JSON, Map.of());
        assertThat(acknowledged.report().issues()).filteredOn(i -> Report.REVIEW.equals(i.severity())).hasSize(2)
                .allMatch(i -> Report.ACKNOWLEDGED.equals(i.resolutionStatus()));
        assertThat(acknowledged.report().elementCounts().review()).isEqualTo(2);
    }

    @Test
    void infoItemsStillNeedAcknowledgementWhileReviewItemsDoNot() {
        var analysis = Analyzer.analyze(LISTENER_PROCESS, Limits.DEFAULT);
        var report = Converter.report(analysis, issues(analysis));
        assertThat(report.issues()).extracting(Report.Issue::reasonCode).contains("LISTENER_DROPPED");
        assertThat(report.issues()).filteredOn(i -> "LISTENER_DROPPED".equals(i.reasonCode())).allMatch(i -> Report.INFO.equals(i.severity()));
        assertThat(report.publishable()).as("dropped executable code needs an explicit acknowledgement").isFalse();
        assertThat(Converter.convert(analysis, request("{\"workflowKey\":\"listener-flow\",\"name\":\"L\"}"), JSON, Map.of()).definition()).isNull();
        assertThat(Converter.convert(analysis, request("{\"workflowKey\":\"listener-flow\",\"name\":\"L\",\"acknowledgeInfo\":true}"), JSON, Map.of())
                .definition()).isNotNull();
    }

    @Test
    void purchaseFixtureReportsEveryConvertedButUncertainConstructAsReview() throws Exception {
        var analysis = analyze("camunda7-purchase.bpmn");
        Map<String, String> review = issues(analysis).stream().filter(i -> Report.REVIEW.equals(i.severity()))
                .collect(Collectors.toMap(i -> i.sourceElementId() + ":" + i.reasonCode(), Report.Issue::severity, (a, b) -> a));
        assertThat(review.keySet()).contains("purchase-import:TARGET_POLICY_REVIEW", "managerOutcome:APPROVAL_ROUTE_INFERRED",
                "financeOutcome:APPROVAL_ROUTE_INFERRED", "amountRoute:GATEWAY_MAPPED_TO_BRANCH", "largePurchase:CONDITION_CONVERTED",
                "fulfilled:END_EVENT_MAPPED", "rejected:END_EVENT_MAPPED");
        assertThat(issues(analysis)).filteredOn(i -> i.sourceElementId().equals("largePurchase"))
                .anySatisfy(i -> assertThat(i.explanation()).contains("${amount >= 5000}", "amount >= 5000"));
        assertThat(issues(analysis)).filteredOn(i -> i.sourceElementId().equals("rejected"))
                .anySatisfy(i -> assertThat(i.explanation()).contains("rejected"));
        // A mapped service call becomes a review item only once the designer's mapping resolved it.
        var request = request("""
                {"workflowKey":"purchase-import","name":"Imported purchase","acknowledgeInfo":true,
                 "groupMappings":{"source-managers":"managers","source-finance":"finance-approvers"},
                 "serviceTaskMappings":{"procure":{"connectionRef":"procurement","operation":"createOrder","inputMapping":{},"resultMapping":{}}}}""");
        var converted = Converter.convert(analysis, request, JSON, Map.of());
        assertThat(converted.definition()).isNotNull();
        assertThat(converted.report().issues()).filteredOn(i -> i.sourceElementId().equals("procure"))
                .singleElement().satisfies(i -> {
                    assertThat(i.severity()).isEqualTo(Report.REVIEW);
                    assertThat(i.reasonCode()).isEqualTo("SERVICE_MAPPED");
                    assertThat(i.targetNodeId()).isEqualTo("procure");
                    assertThat(i.blocksPublishing()).isFalse();
                });
        assertThat(converted.report().elementCounts().review()).isGreaterThanOrEqualTo(8);
        assertThat(converted.report().issues()).noneMatch(Report.Issue::blocksPublishing);
        assertThat(converted.report().issues()).filteredOn(i -> Report.ACTION.equals(i.severity())).allMatch(i -> Report.RESOLVED.equals(i.resolutionStatus()));
    }

    @Test
    void timerAndParallelAndChildMappingsAreReviewItems() throws Exception {
        var analysis = analyze("parallel-timer-child.bpmn");
        assertThat(issues(analysis)).filteredOn(i -> Report.REVIEW.equals(i.severity())).extracting(Report.Issue::reasonCode)
                .contains("DURATION_TIMER_MAPPED", "PARALLEL_SPLIT_MAPPED");
        var converted = Converter.convert(analysis, request("""
                {"workflowKey":"patterns","name":"Patterns","acknowledgeInfo":true,
                 "childWorkflowMappings":{"child":{"workflowRef":"reviewed-child","inputMapping":{},"resultMapping":{}}}}"""), JSON, Map.of());
        assertThat(converted.report().issues()).filteredOn(i -> i.sourceElementId().equals("child")).singleElement()
                .satisfies(i -> assertThat(i.reasonCode()).isEqualTo("CHILD_WORKFLOW_MAPPED"))
                .satisfies(i -> assertThat(i.severity()).isEqualTo(Report.REVIEW));
    }

    // ------------------------------------------------------------------------------------------------ 2. grouped service tasks

    private static final String ALL_THREE_GROUPS = """
            {"workflowKey":"groups","name":"Groups","acknowledgeInfo":true,
             "serviceGroupMappings":{
               "topic:send-mail":{"connectionRef":"procurement","operation":"createOrder","inputMapping":{},"resultMapping":{}},
               "class:com.example.AuditDelegate":{"connectionRef":"procurement","operation":"createOrder","inputMapping":{},"resultMapping":{}},
               "class:com.example.ArchiveDelegate":{"connectionRef":"procurement","operation":"createOrder","inputMapping":{},"resultMapping":{}}}}""";

    @Test
    void tasksWithTheSameWorkerAreOneGroupedItem() throws Exception {
        var analysis = analyze("service-task-groups.bpmn");
        var groups = issues(analysis).stream().filter(i -> "SERVICE_TASK_GROUP".equals(i.reasonCode())).toList();
        assertThat(groups).extracting(Report.Issue::sourceElementId).containsExactly("topic:send-mail", "class:com.example.AuditDelegate");
        assertThat(groups.get(0).explanation()).contains("3 service tasks", "'mailWelcome'", "'mailReminder'", "'mailReceipt'", "serviceGroupMappings");
        assertThat(groups).allMatch(i -> Report.ACTION.equals(i.severity()) && "serviceTaskGroup".equals(i.sourceKind()));
        // Every task still has its own disposition: nothing is hidden behind the group.
        assertThat(issues(analysis).stream().filter(i -> "serviceTask".equals(i.sourceKind())).map(Report.Issue::sourceElementId))
                .containsExactlyInAnyOrder("mailWelcome", "auditStart", "mailReminder", "auditMiddle", "mailReceipt", "archive");
        var report = Converter.report(analysis, issues(analysis));
        assertThat(report.requiredMappings().serviceGroups()).containsOnlyKeys("topic:send-mail", "class:com.example.AuditDelegate",
                "class:com.example.ArchiveDelegate");
        assertThat(report.requiredMappings().serviceGroups().get("topic:send-mail")).containsExactly("mailWelcome", "mailReminder", "mailReceipt");
    }

    @Test
    void oneMappingPerGroupCoversEveryTaskOfThatType() throws Exception {
        var analysis = analyze("service-task-groups.bpmn");
        var converted = Converter.convert(analysis, request(ALL_THREE_GROUPS), JSON, Map.of());
        assertThat(converted.definition()).isNotNull();
        var http = new java.util.ArrayList<String>();
        converted.definition().path("nodes").forEach(n -> {
            if ("http".equals(n.path("type").asString())) http.add(n.path("id").asString());
        });
        assertThat(http).containsExactlyInAnyOrder("mailWelcome", "auditStart", "mailReminder", "auditMiddle", "mailReceipt", "archive");
        assertThat(converted.report().issues()).filteredOn(i -> "SERVICE_TASK_GROUP".equals(i.reasonCode()))
                .allMatch(i -> Report.RESOLVED.equals(i.resolutionStatus()));
        assertThat(converted.report().issues()).filteredOn(i -> "SERVICE_MAPPED".equals(i.reasonCode())).hasSize(6);
        assertThat(converted.report().elementCounts().blocker()).isZero();
    }

    @Test
    void anUnmappedGroupKeepsItsTasksBlockedAndATaskMappingOverridesItsGroup() throws Exception {
        var analysis = analyze("service-task-groups.bpmn");
        var partial = request("""
                {"workflowKey":"groups","name":"Groups","acknowledgeInfo":true,
                 "serviceGroupMappings":{"topic:send-mail":{"connectionRef":"procurement","operation":"createOrder","inputMapping":{},"resultMapping":{}}}}""");
        var blocked = Converter.convert(analysis, partial, JSON, Map.of());
        assertThat(blocked.definition()).isNull();
        assertThat(blocked.report().issues().stream().filter(Report.Issue::blocksPublishing).map(Report.Issue::sourceElementId))
                .contains("class:com.example.AuditDelegate", "auditStart", "auditMiddle", "archive")
                .doesNotContain("topic:send-mail", "mailWelcome", "mailReminder", "mailReceipt");
        var override = (ObjectNode) request(ALL_THREE_GROUPS);
        override.putObject("serviceTaskMappings").putObject("mailReminder").put("connectionRef", "procurement").put("operation", "updateOrder")
                .putObject("inputMapping");
        ((ObjectNode) override.path("serviceTaskMappings").path("mailReminder")).putObject("resultMapping");
        var converted = Converter.convert(analysis, override, JSON, Map.of());
        var operations = new LinkedHashMap<String, String>();
        converted.definition().path("nodes").forEach(n -> {
            if ("http".equals(n.path("type").asString())) operations.put(n.path("id").asString(), n.path("config").path("operation").asString());
        });
        assertThat(operations.get("mailReminder")).isEqualTo("updateOrder");
        assertThat(operations.get("mailWelcome")).isEqualTo("createOrder");
    }

    @Test
    void individualTaskMappingsStillWorkWithoutAnyGroupMapping() throws Exception {
        var analysis = analyze("service-task-groups.bpmn");
        ObjectNode request = request("{\"workflowKey\":\"individual\",\"name\":\"Individual\",\"acknowledgeInfo\":true}");
        ObjectNode mappings = request.putObject("serviceTaskMappings");
        for (String task : List.of("mailWelcome", "auditStart", "mailReminder", "auditMiddle", "mailReceipt", "archive")) {
            mappings.putObject(task).put("connectionRef", "procurement").put("operation", "createOrder").putObject("inputMapping");
            ((ObjectNode) mappings.path(task)).putObject("resultMapping");
        }
        var converted = Converter.convert(analysis, request, JSON, Map.of());
        assertThat(converted.definition()).isNotNull();
        assertThat(converted.report().issues()).filteredOn(i -> "SERVICE_TASK_GROUP".equals(i.reasonCode())).allMatch(i -> Report.RESOLVED.equals(i.resolutionStatus()));
    }

    @Test
    void unknownGroupKeysAreRejectedAndJobTypesAndDelegateExpressionsGroupToo() {
        String xml = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="urn:test">
                 <process id="p" isExecutable="true"><startEvent id="s"/>
                  <serviceTask id="a"><extensionElements><zeebe:taskDefinition type="payments"/></extensionElements></serviceTask>
                  <serviceTask id="b"><extensionElements><zeebe:taskDefinition type="payments"/></extensionElements></serviceTask>
                  <serviceTask id="c"><extensionElements><zeebe:taskDefinition type="=dynamicType"/></extensionElements></serviceTask>
                  <serviceTask id="d" camunda:delegateExpression="${mailer}"/><serviceTask id="e" camunda:delegateExpression="${mailer}"/>
                  <endEvent id="x" name="Done"/>
                  <sequenceFlow id="1" sourceRef="s" targetRef="a"/><sequenceFlow id="2" sourceRef="a" targetRef="b"/><sequenceFlow id="3" sourceRef="b" targetRef="c"/>
                  <sequenceFlow id="4" sourceRef="c" targetRef="d"/><sequenceFlow id="5" sourceRef="d" targetRef="e"/><sequenceFlow id="6" sourceRef="e" targetRef="x"/>
                 </process></definitions>""";
        var analysis = Analyzer.analyze(xml, Limits.DEFAULT);
        var report = Converter.report(analysis, issues(analysis));
        assertThat(report.requiredMappings().serviceGroups()).containsOnlyKeys("jobType:payments", "delegateExpression:${mailer}");
        assertThat(issues(analysis)).filteredOn(i -> "SERVICE_TASK_GROUP".equals(i.reasonCode())).hasSize(2);
        assertThatThrownBy(() -> Converter.convert(analysis, request("""
                {"workflowKey":"x1","name":"X","serviceGroupMappings":{"topic:missing":{"connectionRef":"procurement","operation":"o","inputMapping":{},"resultMapping":{}}}}"""),
                JSON, Map.of())).isInstanceOf(SourceException.class).hasMessageContaining("topic:missing");
    }

    // ------------------------------------------------------------------------------------------------ 3. condition operators

    /** Follows the canonical definition from start with the given request values, using the compiler's own matcher. */
    private static String endOf(JsonNode definition, Map<String, Object> input) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        definition.path("nodes").forEach(n -> nodes.put(n.path("id").asString(), n));
        String current = "start";
        for (int guard = 0; guard < 20; guard++) {
            JsonNode node = nodes.get(current);
            if ("end".equals(node.path("type").asString())) {
                return node.path("config").path("businessOutcome").asString();
            }
            String next = null;
            String fallback = null;
            for (JsonNode t : definition.path("transitions")) {
                if (!current.equals(t.path("from").asString())) continue;
                if ("default".equals(t.path("on").asString()) || "next".equals(t.path("on").asString())) {
                    fallback = t.path("to").asString();
                } else if (next == null && holds(t.path("condition"), input)) {
                    next = t.path("to").asString();
                }
            }
            current = next != null ? next : fallback;
        }
        throw new AssertionError("no end reached");
    }

    private static boolean holds(JsonNode condition, Map<String, Object> input) {
        if (condition.has("all")) {
            for (JsonNode term : condition.path("all")) if (!holds(term, input)) return false;
            return true;
        }
        if (condition.has("any")) {
            for (JsonNode term : condition.path("any")) if (holds(term, input)) return true;
            return false;
        }
        String key = condition.path("fieldRef").asString().substring("/input/".length());
        Object literal = condition.path("value").isNumber() ? condition.path("value").decimalValue()
                : condition.path("value").isBoolean() ? (Object) condition.path("value").booleanValue() : condition.path("value").asString();
        return ConditionMatcher.matches(condition.path("operator").asString(), input.get(key), literal);
    }

    private static Map<String, Object> values(String amount, String region, String riskLevel, String riskScore) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("amount", new BigDecimal(amount));
        in.put("region", region);
        in.put("riskLevel", riskLevel);
        in.put("riskScore", new BigDecimal(riskScore));
        return in;
    }

    @Test
    void juelAndFeelConvertToTheSameCanonicalConditionsThatTheCompilerAccepts() throws Exception {
        Map<String, JsonNode> definitions = new LinkedHashMap<>();
        for (String name : List.of("expression-operators.bpmn", "expression-operators-feel.bpmn")) {
            var analysis = analyze(name);
            var converted = Converter.convert(analysis, request("{\"workflowKey\":\"operators\",\"name\":\"Operators\",\"acknowledgeInfo\":true}"), JSON, Map.of());
            assertThat(converted.definition()).as(name).isNotNull();
            assertThat(blockers(converted.definition(), emptyContext())).as("%s compiler blockers", name).isEmpty();
            definitions.put(name, converted.definition());
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            converted.definition().path("transitions").forEach(t -> byId.put(t.path("id").asString(), t));
            assertThat(byId.get("tierLarge").path("condition").toString())
                    .isEqualTo("{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":10000}");
            assertThat(byId.get("tierMedium").path("condition").toString()).isEqualTo(
                    "{\"all\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":1000},{\"fieldRef\":\"/input/amount\",\"operator\":\"lte\",\"value\":10000}]}");
            assertThat(byId.get("regionOutside").path("condition").toString())
                    .isEqualTo("{\"fieldRef\":\"/input/region\",\"operator\":\"ne\",\"value\":\"EU\"}");
            assertThat(byId.get("riskEscalate").path("condition").toString()).isEqualTo(
                    "{\"any\":[{\"fieldRef\":\"/input/riskLevel\",\"operator\":\"eq\",\"value\":\"high\"},{\"fieldRef\":\"/input/riskScore\",\"operator\":\"gte\",\"value\":80}]}");
            assertThat(byId.get("tierSmall").path("on").asString()).isEqualTo("default");
            // Inferred request fields: the compared values decide their types.
            var props = converted.definition().path("inputSchema").path("properties");
            assertThat(props.path("amount").path("type").asString()).isEqualTo("number");
            assertThat(props.path("region").path("type").asString()).isEqualTo("string");
            assertThat(props.path("riskScore").path("type").asString()).isEqualTo("number");
            // Every converted comparison is a review item that keeps the original text.
            var conditions = converted.report().issues().stream().filter(i -> "CONDITION_CONVERTED".equals(i.reasonCode())).toList();
            assertThat(conditions).extracting(Report.Issue::sourceElementId).containsExactlyInAnyOrder("tierLarge", "tierMedium", "regionOutside", "riskEscalate");
            assertThat(conditions).allMatch(i -> Report.REVIEW.equals(i.severity()) && i.targetNodeId() != null);
        }
        // Behaviour at, below and above every limit, through the compiler's own condition matcher.
        for (JsonNode definition : definitions.values()) {
            assertThat(endOf(definition, values("10000.01", "EU", "low", "1"))).isEqualTo("Large");
            assertThat(endOf(definition, values("10000", "EU", "low", "1"))).isEqualTo("Medium");
            assertThat(endOf(definition, values("1000.01", "EU", "low", "1"))).isEqualTo("Medium");
            assertThat(endOf(definition, values("1000", "EU", "low", "1"))).as("1000 is not > 1000").isEqualTo("Routine");
            assertThat(endOf(definition, values("999.99", "US", "low", "1"))).isEqualTo("Outside");
            assertThat(endOf(definition, values("500", "EU", "high", "1"))).isEqualTo("Escalated");
            assertThat(endOf(definition, values("500", "EU", "low", "80"))).isEqualTo("Escalated");
            assertThat(endOf(definition, values("500", "EU", "low", "79.99"))).isEqualTo("Routine");
        }
    }

    @Test
    void extendedFeelFormsConvertToCanonicalGroupsThatTheCompilerAcceptsAndBehaveAtEveryBoundary() throws Exception {
        var analysis = analyze("expression-feel-extended.bpmn");
        var converted = Converter.convert(analysis, request("{\"workflowKey\":\"feel-ext\",\"name\":\"Feel ext\",\"acknowledgeInfo\":true}"), JSON, Map.of());
        assertThat(converted.definition()).isNotNull();
        assertThat(blockers(converted.definition(), emptyContext())).isEmpty();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        converted.definition().path("transitions").forEach(t -> byId.put(t.path("id").asString(), t));
        assertThat(byId.get("tierLarge").path("condition").toString())
                .isEqualTo("{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":10000}");
        assertThat(byId.get("tierMedium").path("condition").toString()).isEqualTo(
                "{\"all\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":1000},{\"fieldRef\":\"/input/amount\",\"operator\":\"lte\",\"value\":10000}]}");
        assertThat(byId.get("regionOutside").path("condition").toString()).isEqualTo(
                "{\"all\":[{\"fieldRef\":\"/input/region\",\"operator\":\"ne\",\"value\":\"EU\"},{\"fieldRef\":\"/input/region\",\"operator\":\"ne\",\"value\":\"UK\"}]}");
        assertThat(byId.get("riskEscalate").path("condition").toString()).isEqualTo(
                "{\"any\":[{\"fieldRef\":\"/input/riskLevel\",\"operator\":\"eq\",\"value\":\"high\"},{\"fieldRef\":\"/input/riskLevel\",\"operator\":\"eq\",\"value\":\"critical\"},"
                        + "{\"fieldRef\":\"/input/riskScore\",\"operator\":\"gte\",\"value\":80}]}");
        var conditions = converted.report().issues().stream().filter(i -> "CONDITION_CONVERTED".equals(i.reasonCode())).toList();
        assertThat(conditions).extracting(Report.Issue::sourceElementId).containsExactlyInAnyOrder("tierLarge", "tierMedium", "riskEscalate");
        assertThat(conditions).allMatch(i -> Report.REVIEW.equals(i.severity()));
        // not(region in (...)) is rewritten (De Morgan): converted but changed, open until a reviewer verifies it.
        var rewritten = converted.report().issues().stream().filter(i -> "CONDITION_REWRITTEN".equals(i.reasonCode())).toList();
        assertThat(rewritten).extracting(Report.Issue::sourceElementId).containsExactly("regionOutside");
        assertThat(rewritten).allMatch(i -> Report.VERIFY.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus()));
        JsonNode definition = converted.definition();
        assertThat(endOf(definition, values("10000.01", "EU", "low", "1"))).isEqualTo("Large");
        assertThat(endOf(definition, values("10000", "EU", "low", "1"))).isEqualTo("Medium");
        assertThat(endOf(definition, values("1000.01", "EU", "low", "1"))).isEqualTo("Medium");
        assertThat(endOf(definition, values("1000", "EU", "low", "1"))).as("(1000..10000] excludes 1000").isEqualTo("Routine");
        assertThat(endOf(definition, values("999.99", "US", "low", "1"))).isEqualTo("Outside");
        assertThat(endOf(definition, values("999.99", "UK", "low", "1"))).as("UK is listed").isEqualTo("Routine");
        assertThat(endOf(definition, values("500", "EU", "high", "1"))).isEqualTo("Escalated");
        assertThat(endOf(definition, values("500", "EU", "critical", "1"))).isEqualTo("Escalated");
        assertThat(endOf(definition, values("500", "EU", "low", "80"))).isEqualTo("Escalated");
        assertThat(endOf(definition, values("500", "EU", "low", "79.99"))).isEqualTo("Routine");
    }

    @Test
    void everyUnsupportedFeelConstructIsReportedAsABlockerWithItsTextAndNeverConverted() {
        List<String> unsupported = List.of("= amount in (>5, 6)", "= amount in [1..x]", "= amount in [5..1]", "= amount between 1 and amount2",
                "= contains(region, \"E\")", "= amount + 1 > 5", "= if amount > 1 then true else false", "= region in (\"EU\", \"UK\") and amount > 1",
                "= (amount > 1 or amount < 0) and region = \"EU\"", "= some x in items satisfies x > 1", "= amount > 1 and (region = \"a\" or region = \"b\")",
                "= amount = null", "= items[1] > 1", "= not(amount > 5 and region = \"EU\" or amount < 1)");
        for (String condition : unsupported) {
            String escaped = condition.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            var analysis = Analyzer.analyze(gatewayProcess("<sequenceFlow id=\"f1\" sourceRef=\"gw\" targetRef=\"a\"><conditionExpression>" + escaped
                    + "</conditionExpression></sequenceFlow><sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"b\"/>").replace("<exclusiveGateway id=\"gw\"/>",
                    "<exclusiveGateway id=\"gw\" default=\"f2\"/>"), Limits.DEFAULT);
            var items = issues(analysis);
            assertThat(items).as(condition).anySatisfy(i -> {
                assertThat(i.sourceElementId()).isEqualTo("f1");
                assertThat(i.reasonCode()).isEqualTo("EXPRESSION_NOT_SUPPORTED");
                assertThat(i.severity()).isEqualTo(Report.BLOCKER);
                assertThat(i.explanation()).contains(condition);
            });
            assertThat(Converter.report(analysis, items).publishable()).as(condition).isFalse();
            var converted = Converter.convert(analysis, request("{\"workflowKey\":\"blocked\",\"name\":\"Blocked\",\"acknowledgeInfo\":true}"), JSON, Map.of());
            assertThat(converted.definition()).as("%s must not convert", condition).isNull();
        }
    }

    @Test
    void aConditionOnAUserTaskExitIsReportedWithItsSourceTextEvenThoughOutcomesAreMapped() {
        var analysis = Analyzer.analyze("""
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
                 <process id="u" isExecutable="true"><startEvent id="start"/><userTask id="review" name="Review"/>
                  <endEvent id="a" name="Alpha"/><endEvent id="b" name="Beta"/>
                  <sequenceFlow id="f0" sourceRef="start" targetRef="review"/>
                  <sequenceFlow id="f1" sourceRef="review" targetRef="a"><conditionExpression>= amount between 1 and 5</conditionExpression></sequenceFlow>
                  <sequenceFlow id="f2" sourceRef="review" targetRef="b"><conditionExpression>= contains(x, "y")</conditionExpression></sequenceFlow>
                 </process></definitions>""", Limits.DEFAULT);
        var items = issues(analysis);
        for (String flow : List.of("f1", "f2")) {
            assertThat(items).as(flow).anySatisfy(i -> {
                assertThat(i.sourceElementId()).isEqualTo(flow);
                assertThat(i.reasonCode()).isEqualTo("CONDITION_DROPPED");
                assertThat(i.explanation()).contains(flow.equals("f1") ? "amount between 1 and 5" : "contains(x, \"y\")");
            });
        }
    }

    private static String gatewayProcess(String conditions) {
        return """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
                 <process id="g" isExecutable="true"><startEvent id="start"/><exclusiveGateway id="gw"/>
                  <endEvent id="a" name="Alpha"/><endEvent id="b" name="Beta"/><endEvent id="c" name="Gamma"/>
                  <sequenceFlow id="f0" sourceRef="start" targetRef="gw"/>""" + conditions + "</process></definitions>";
    }

    @Test
    void unsupportedExpressionsStayBlockedWithTheOriginalTextAndAReason() {
        for (String condition : List.of("${amount > 1 &amp;&amp; amount &lt; 5 || amount == 9}", "${not (amount &gt; 5 || amount &lt; 9) || amount == 1}", "${customer.score &gt; 5}",
                "${amount * 2 &gt; 5}", "${(amount &gt; 1 and amount &lt; 5) or amount == 9}")) {
            var analysis = Analyzer.analyze(gatewayProcess("<sequenceFlow id=\"f1\" sourceRef=\"gw\" targetRef=\"a\"><conditionExpression>" + condition
                    + "</conditionExpression></sequenceFlow><sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"b\"/>").replace("<exclusiveGateway id=\"gw\"/>",
                    "<exclusiveGateway id=\"gw\" default=\"f2\"/>"), Limits.DEFAULT);
            var items = issues(analysis);
            assertThat(items).as(condition).anySatisfy(i -> {
                assertThat(i.sourceElementId()).isEqualTo("gw");
                assertThat(i.reasonCode()).isEqualTo("EXPRESSION_NOT_SUPPORTED");
                assertThat(i.severity()).isEqualTo(Report.BLOCKER);
            });
            assertThat(items).as(condition).anySatisfy(i -> {
                assertThat(i.sourceElementId()).isEqualTo("f1");
                assertThat(i.reasonCode()).isEqualTo("EXPRESSION_NOT_SUPPORTED");
                assertThat(i.explanation()).contains(condition.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"));
            });
            assertThat(Converter.report(analysis, items).publishable()).isFalse();
        }
    }

    @Test
    void complementaryConditionsWithoutADefaultBecomeADefaultForTheNewOperatorsToo() {
        for (String[] pair : new String[][] {{"${amount &gt; 5}", "${amount &lt;= 5}"}, {"${amount &gt;= 5}", "${amount &lt; 5}"},
                {"${status == 'new'}", "${status != 'new'}"}, {"${flag == true}", "${flag == false}"}}) {
            var analysis = Analyzer.analyze(gatewayProcess("<sequenceFlow id=\"f1\" sourceRef=\"gw\" targetRef=\"a\"><conditionExpression>" + pair[0]
                    + "</conditionExpression></sequenceFlow><sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"b\"><conditionExpression>" + pair[1]
                    + "</conditionExpression></sequenceFlow>"), Limits.DEFAULT);
            var converted = Converter.convert(analysis, request("{\"workflowKey\":\"derived\",\"name\":\"Derived\",\"acknowledgeInfo\":true}"), JSON, Map.of());
            assertThat(converted.definition()).as(pair[0] + " / " + pair[1]).isNotNull();
            assertThat(converted.report().issues()).anySatisfy(i -> {
                assertThat(i.sourceElementId()).isEqualTo("f2");
                assertThat(i.reasonCode()).isEqualTo("DEFAULT_PATH_DERIVED");
                assertThat(i.severity()).isEqualTo(Report.REVIEW);
            });
        }
        // Overlapping conditions are converted as written; the compiler, not the importer, proves them exclusive.
        var overlapping = Analyzer.analyze(gatewayProcess("<sequenceFlow id=\"f1\" sourceRef=\"gw\" targetRef=\"a\"><conditionExpression>${amount &gt; 5}</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"b\"><conditionExpression>${amount &gt; 3}</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"c\"/>").replace("<exclusiveGateway id=\"gw\"/>", "<exclusiveGateway id=\"gw\" default=\"f3\"/>"), Limits.DEFAULT);
        var converted = Converter.convert(overlapping, request("{\"workflowKey\":\"overlap\",\"name\":\"Overlap\",\"acknowledgeInfo\":true}"), JSON, Map.of());
        assertThat(blockers(converted.definition(), emptyContext())).anyMatch(b -> b.startsWith("WF_BRANCH_OVERLAP"));
    }

    // ------------------------------------------------------------------------------------------------ 5. multi-instance

    private static ObjectNode batchSchema() throws Exception {
        return (ObjectNode) JSON.parse(Files.readString(Path.of("../../examples/complex-batch-review.json"))).path("inputSchema");
    }

    private static CompileContext contextWithSimpleReview() throws Exception {
        String child = Files.readString(Path.of("../../examples/simple-review.json"));
        var info = CompileContext.WorkflowInfo.of("review-request", "Simple review request", "00000000-0000-7000-8000-0000000000b1", 1,
                "hash", child, Set.of(), 0);
        return new CompileContext(false, Map.of(), List.of(), Set.of(), Map.of("review-request", info));
    }

    @Test
    void aMultiInstanceCallOverAPlainCollectionBecomesABoundedForEach() throws Exception {
        var analysis = analyze("multi-instance-call.bpmn");
        assertThat(analysis.multiInstance()).containsKey("reviewEach");
        assertThat(issues(analysis)).anySatisfy(i -> {
            assertThat(i.sourceElementId()).isEqualTo("eachItem");
            assertThat(i.reasonCode()).isEqualTo("MULTI_INSTANCE_MAPPED");
            assertThat(i.severity()).isEqualTo(Report.REVIEW);
        });
        assertThat(issues(analysis)).anySatisfy(i -> {
            assertThat(i.sourceElementId()).isEqualTo("reviewEach");
            assertThat(i.reasonCode()).isEqualTo("CHILD_WORKFLOW_MAPPING_REQUIRED");
            assertThat(i.explanation()).contains("maxItems");
        });
        String base = "\"workflowKey\":\"batch\",\"name\":\"Batch\",\"acknowledgeInfo\":true";
        String call = "\"workflowRef\":\"review-request\",\"inputMapping\":{\"purpose\":\"/input/purpose\",\"costCenter\":\"/input/costCenter\",\"amount\":\"/input/amount\"},\"resultMapping\":{}";
        // Without the for-each settings the call stays unresolved: the unbounded source collection is never guessed.
        var unbounded = Converter.convert(analysis, request("{" + base + ",\"childWorkflowMappings\":{\"reviewEach\":{" + call + "}}}"), JSON, Map.of());
        assertThat(unbounded.definition()).isNull();
        assertThat(unbounded.report().issues()).filteredOn(i -> i.sourceElementId().equals("reviewEach")).allMatch(Report.Issue::blocksPublishing);
        ObjectNode withSchema = request("{" + base + ",\"childWorkflowMappings\":{\"reviewEach\":{" + call
                + ",\"forEach\":{\"itemInput\":\"title\",\"maxItems\":5}}}}");
        withSchema.set("inputSchema", batchSchema());
        var converted = Converter.convert(analysis, withSchema, JSON, Map.of());
        assertThat(converted.definition()).isNotNull();
        JsonNode config = converted.definition().path("nodes").findValues("config").stream()
                .filter(c -> c.has("workflowRef")).findFirst().orElseThrow();
        assertThat(config.path("forEach").toString()).isEqualTo("{\"itemsRef\":\"/input/items\",\"itemInput\":\"title\",\"maxItems\":5,\"mode\":\"parallel\"}");
        assertThat(blockers(converted.definition(), contextWithSimpleReview())).as("compiler blockers").isEmpty();
        assertThat(converted.report().issues()).filteredOn(i -> i.sourceElementId().equals("reviewEach")).singleElement()
                .satisfies(i -> assertThat(i.reasonCode()).isEqualTo("CHILD_WORKFLOW_MAPPED"))
                .satisfies(i -> assertThat(i.severity()).isEqualTo(Report.REVIEW));
        // Without an explicit schema the importer builds the bounded array field from the designer's bound and item type.
        ObjectNode inferred = request("{" + base + ",\"childWorkflowMappings\":{\"reviewEach\":{\"workflowRef\":\"review-request\",\"inputMapping\":{},\"resultMapping\":{}"
                + ",\"forEach\":{\"itemInput\":\"title\",\"maxItems\":3,\"itemType\":\"string\"},\"collectionMapping\":{\"outcomeItems\":\"outcome\"}}}}");
        var generated = Converter.convert(analysis, inferred, JSON, Map.of());
        assertThat(generated.definition().path("inputSchema").path("properties").path("items").toString())
                .isEqualTo("{\"type\":\"array\",\"title\":\"items\",\"items\":{\"type\":\"string\",\"title\":\"Item\"},\"minItems\":1,\"maxItems\":3}");
        assertThat(generated.definition().path("inputSchema").path("required").toString()).isEqualTo("[\"items\"]");
        assertThat(generated.definition().path("nodes").findValues("config").stream().filter(c -> c.has("forEach")).findFirst().orElseThrow()
                .path("collectionMapping").toString()).isEqualTo("{\"outcomeItems\":\"outcome\"}");
    }

    @Test
    void forEachIsRejectedOnACallThatIsNotMultiInstance() throws Exception {
        var analysis = analyze("parallel-timer-child.bpmn");
        assertThatThrownBy(() -> Converter.convert(analysis, request("""
                {"workflowKey":"w","name":"W","childWorkflowMappings":{"child":{"workflowRef":"reviewed-child","inputMapping":{},"resultMapping":{},
                 "forEach":{"itemInput":"title","maxItems":2,"itemType":"string"}}}}"""), JSON, Map.of()))
                .isInstanceOf(SourceException.class).hasMessageContaining("not a multi-instance");
    }

    @Test
    void everyOtherMultiInstanceConstructStaysABlockerWithItsReason() throws Exception {
        var analysis = analyze("multi-instance-unsupported.bpmn");
        Map<String, String> expected = Map.of(
                "approveLoop", "Only a call activity", "fixedLoop", "fixed count", "earlyLoop", "completion condition",
                "collectLoop", "output collection", "computedLoop", "not a plain variable");
        for (var entry : expected.entrySet()) {
            assertThat(issues(analysis)).as(entry.getKey()).anySatisfy(i -> {
                assertThat(i.sourceElementId()).isEqualTo(entry.getKey());
                assertThat(i.reasonCode()).isEqualTo("MULTI_INSTANCE_NOT_SUPPORTED");
                assertThat(i.severity()).isEqualTo(Report.BLOCKER);
                assertThat(i.explanation()).contains(entry.getValue());
            });
        }
        // Child elements of a loop (cardinality, completion condition, loop condition) are dispositioned too.
        assertThat(issues(analysis).stream().map(Report.Issue::sourceElementId)).contains("cardinality", "early", "repeatLoop", "repeatCondition");
        assertThat(analysis.multiInstance()).isEmpty();
        assertThat(Converter.report(analysis, issues(analysis)).publishable()).isFalse();
    }
}
