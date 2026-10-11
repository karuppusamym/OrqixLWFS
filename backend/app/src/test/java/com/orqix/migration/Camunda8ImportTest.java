package com.orqix.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.migration.camunda.Analysis;
import com.orqix.migration.camunda.Analyzer;
import com.orqix.migration.camunda.Converter;
import com.orqix.migration.camunda.FormImport;
import com.orqix.migration.camunda.Limits;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.camunda.SourceException;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Camunda 8 importer (adapter camunda-bpmn-0.5): fixtures, connector hints, FEEL, forms, timers; no database. */
class Camunda8ImportTest {

    private static final Json JSON = new Json(JsonMapper.builder().build());
    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static String read(String name) throws Exception {
        return Files.readString(Path.of("../../examples/migration/" + name));
    }

    private static Analysis analyze(String name) throws Exception {
        return Analyzer.analyze(read(name), Limits.DEFAULT);
    }

    private static List<Report.Issue> issues(Analysis a) {
        return a.items().stream().map(Analysis.Item::issue).toList();
    }

    private static Map<String, Set<String>> reasons(Analysis a) {
        return issues(a).stream().collect(Collectors.groupingBy(Report.Issue::sourceElementId, TreeMap::new,
                Collectors.mapping(Report.Issue::reasonCode, Collectors.toSet())));
    }

    private static ObjectNode request(String json) {
        return (ObjectNode) JSON.parse(json);
    }

    private static List<String> blockers(JsonNode definition, CompileContext context) {
        return Compilers.workflowCompiler().validate(JSON.write(definition), context).issues().stream()
                .filter(i -> i.severity() == com.orqix.compiler.Severity.BLOCKER).map(i -> i.code() + " " + i.message()).toList();
    }

    private static CompileContext groups() {
        return new CompileContext(false, Map.of("managers", new CompileContext.GroupInfo("managers", "Managers", 2),
                "finance-approvers", new CompileContext.GroupInfo("finance-approvers", "Finance", 2)), List.of(), Set.of());
    }

    private static Set<String> bpmnIds(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        var ids = new TreeSet<String>();
        var all = doc.getElementsByTagNameNS(BPMN, "*");
        for (int i = 0; i < all.getLength(); i++) {
            var e = (Element) all.item(i);
            if (e.hasAttribute("id")) {
                ids.add(e.getAttribute("id"));
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------------ dispositions

    @ParameterizedTest
    @ValueSource(strings = {"camunda8-purchase.bpmn", "camunda8-connector-http.bpmn", "camunda8-user-task-form.bpmn",
            "camunda8-feel-gateways.bpmn", "camunda8-timer-callactivity.bpmn", "camunda8-unsupported.bpmn"})
    void everyElementOfEveryCamunda8FixtureHasADisposition(String name) throws Exception {
        String xml = read(name);
        assertThat(reasons(Analyzer.analyze(xml, Limits.DEFAULT)).keySet()).as("elements without a disposition in %s", name)
                .containsAll(bpmnIds(xml));
    }

    @Test
    void versionsAreDetectedAndTheAdapterLimitsAreStated() throws Exception {
        var purchase = analyze("camunda8-purchase.bpmn");
        assertThat(purchase.sourceSystem()).isEqualTo("camunda8");
        assertThat(purchase.sourceVersion()).isEqualTo("8.3.0");
        assertThat(purchase.assumptions()).anyMatch(a -> a.contains("Camunda 8 8.3.0") && a.contains("not a claim of general Camunda 8 compatibility"));
        assertThat(analyze("camunda8-feel-gateways.bpmn").sourceVersion()).isEqualTo("8.6.0");
        var c7 = analyze("camunda7-purchase.bpmn");
        assertThat(c7.sourceSystem()).isEqualTo("camunda7");
        assertThat(c7.assumptions()).anyMatch(a -> a.contains("Camunda 7") && a.contains("not a claim"));
        assertThat(Analyzer.ADAPTER_VERSION).isEqualTo("camunda-bpmn-0.5");
        String platformOnly = "<definitions xmlns=\"" + BPMN + "\" xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\" modeler:executionPlatform=\"Camunda Cloud\" "
                + "modeler:executionPlatformVersion=\"8.2.0\" targetNamespace=\"urn:t\"><process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/></process></definitions>";
        assertThat(Analyzer.analyze(platformOnly, Limits.DEFAULT).sourceSystem()).isEqualTo("camunda8");
    }

    // ------------------------------------------------------------------------------------------------ connectors

    @Test
    void theRestConnectorKeepsOnlyProvenanceAndDerivedSuggestions() throws Exception {
        var analysis = analyze("camunda8-connector-http.bpmn");
        var hint = analysis.hints().services().get("procure");
        assertThat(hint.kind()).isEqualTo("httpJson");
        assertThat(hint.method()).isEqualTo("POST");
        assertThat(hint.urlHost()).isEqualTo("procurement.example.com");
        assertThat(hint.urlPath()).isEqualTo("/orders");
        assertThat(hint.authentication()).isEqualTo("noAuth");
        assertThat(hint.headerNames()).containsExactly("Content-Type");
        assertThat(hint.suggestedOperation()).isEqualTo("createPurchaseOrder");
        assertThat(hint.inputFields()).containsEntry("title", "title").containsEntry("amount", "amount").containsEntry("costCenter", "costCenter");
        assertThat(hint.resultFields()).containsEntry("procurementId", "/id");
        assertThat(hint.resultVariable()).isEqualTo("procurement");
        var item = issues(analysis).stream().filter(i -> i.sourceElementId().equals("procure") && i.reasonCode().equals("CONNECTOR_MAPPING_REQUIRED")).findFirst().orElseThrow();
        assertThat(item.severity()).isEqualTo(Report.BLOCKER);
        assertThat(item.explanation()).contains("POST procurement.example.com/orders").contains("never to the workflow").contains("retries");
        assertThat(analysis.services()).containsKey("procure");
        // Connector input mapping and headers are consumed by the connector item, not reported as separate acknowledgements.
        assertThat(issues(analysis)).noneMatch(i -> i.sourceElementId().equals("procure") && i.reasonCode().equals("IO_MAPPING_NOT_IMPORTED"));
    }

    @Test
    void secretsAndFullUrlsNeverEnterTheReportOrTheWorkflow() {
        String xml = "<definitions xmlns=\"" + BPMN + "\" xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" targetNamespace=\"urn:t\"><process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"call\" name=\"Call\"><extensionElements><zeebe:taskDefinition type=\"io.camunda:http-json:1\"/><zeebe:ioMapping>"
                + "<zeebe:input source=\"https://user:hunter2@api.example.com/v1/items?token=abc123&amp;page=2\" target=\"url\"/>"
                + "<zeebe:input source=\"GET\" target=\"method\"/>"
                + "<zeebe:input source=\"={Authorization: &#34;Bearer {{secrets.API_TOKEN}}&#34;}\" target=\"headers\"/>"
                + "<zeebe:input source=\"bearer\" target=\"authentication.type\"/><zeebe:input source=\"{{secrets.API_TOKEN}}\" target=\"authentication.token\"/>"
                + "</zeebe:ioMapping></extensionElements></serviceTask><endEvent id=\"e\" name=\"Done\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"call\"/><sequenceFlow id=\"f2\" sourceRef=\"call\" targetRef=\"e\"/></process></definitions>";
        var analysis = Analyzer.analyze(xml, Limits.DEFAULT);
        String everything = JSON.write(Converter.report(analysis, issues(analysis)));
        assertThat(everything).doesNotContain("hunter2").doesNotContain("abc123").doesNotContain("API_TOKEN").doesNotContain("Bearer");
        var hint = analysis.hints().services().get("call");
        assertThat(hint.urlHost()).isEqualTo("api.example.com");
        assertThat(hint.urlPath()).isEqualTo("/v1/items");
        assertThat(hint.queryNames()).containsExactly("token", "page");
        assertThat(hint.authentication()).isEqualTo("bearer");
    }

    @Test
    void aComputedConnectorUrlIsShownAsAnExpressionAndNeverEvaluated() {
        String xml = "<definitions xmlns=\"" + BPMN + "\" xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" targetNamespace=\"urn:t\"><process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"call\"><extensionElements><zeebe:taskDefinition type=\"io.camunda:http-json:1\"/><zeebe:ioMapping>"
                + "<zeebe:input source=\"=&#34;https://x.example.com/&#34; + id\" target=\"url\"/><zeebe:input source=\"=verb\" target=\"method\"/>"
                + "<zeebe:input source=\"=payload\" target=\"body\"/></zeebe:ioMapping></extensionElements></serviceTask><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"call\"/><sequenceFlow id=\"f2\" sourceRef=\"call\" targetRef=\"e\"/></process></definitions>";
        var hint = Analyzer.analyze(xml, Limits.DEFAULT).hints().services().get("call");
        assertThat(hint.urlComputed()).isTrue();
        assertThat(hint.urlPath()).isNull();
        assertThat(hint.method()).isNull();
        assertThat(hint.unmapped()).anyMatch(u -> u.startsWith("url:")).anyMatch(u -> u.startsWith("method:")).anyMatch(u -> u.startsWith("body:"));
    }

    @Test
    void aPlainJobWorkerIsBlockedWithTheJobTypeAsSuggestedOperation() throws Exception {
        var analysis = analyze("camunda8-purchase.bpmn");
        var hint = analysis.hints().services().get("procure");
        assertThat(hint.kind()).isEqualTo("jobWorker");
        assertThat(hint.jobType()).isEqualTo("source-procurement-worker");
        assertThat(hint.suggestedOperation()).isEqualTo("createPurchaseOrder");
        assertThat(issues(analysis)).anySatisfy(i -> {
            assertThat(i.sourceElementId()).isEqualTo("procure");
            assertThat(i.reasonCode()).isEqualTo("WORKER_NOT_PORTABLE");
            assertThat(i.severity()).isEqualTo(Report.BLOCKER);
        });
    }

    // ------------------------------------------------------------------------------------------------ FEEL

    @Test
    void feelGatewaysOfTheSupportedSubsetConvertAndCompile() throws Exception {
        var analysis = analyze("camunda8-feel-gateways.bpmn");
        assertThat(analysis.branches()).containsKeys("gAmount", "gStatus", "gApproved", "gNot", "gRange");
        assertThat(issues(analysis)).noneMatch(i -> Report.BLOCKER.equals(i.severity()) || Report.ACTION.equals(i.severity()));
        var converted = Converter.convert(analysis, request("{\"workflowKey\":\"feel-gateways\",\"name\":\"FEEL\",\"acknowledgeInfo\":true,\"acknowledgeReview\":true}"), JSON, Map.of());
        assertThat(converted.definition()).isNotNull();
        assertThat(blockers(converted.definition(), groups())).isEmpty();
        // not(score > 100) became score <= 100, the group became any/all, the bare boolean kept its meaning.
        String definition = converted.definition().toString();
        assertThat(definition).contains("\"operator\":\"lte\"").contains("\"any\"").contains("\"all\"");
        assertThat(converted.definition().path("inputSchema").path("properties").path("approved").path("type").asString()).isEqualTo("boolean");
    }

    @Test
    void unsupportedFeelBecomesAnExpressionBlockerThatShowsTheExpression() throws Exception {
        var analysis = analyze("camunda8-unsupported.bpmn");
        for (String gateway : List.of("gFunction", "gNull", "gIn")) {
            var issue = issues(analysis).stream().filter(i -> i.sourceElementId().equals(gateway) && i.reasonCode().equals("EXPRESSION_NOT_SUPPORTED"))
                    .findFirst().orElseThrow(() -> new AssertionError(gateway));
            assertThat(issue.severity()).isEqualTo(Report.BLOCKER);
        }
        var function = issues(analysis).stream().filter(i -> i.sourceElementId().equals("gFunction") && i.reasonCode().equals("EXPRESSION_NOT_SUPPORTED")).findFirst().orElseThrow();
        assertThat(function.explanation()).contains("contains(name, \"rush\")").contains("function");
        var nullCheck = issues(analysis).stream().filter(i -> i.sourceElementId().equals("gNull") && i.reasonCode().equals("EXPRESSION_NOT_SUPPORTED")).findFirst().orElseThrow();
        assertThat(nullCheck.explanation()).contains("owner = null").contains("null");
    }

    // ------------------------------------------------------------------------------------------------ unsupported fixture

    @Test
    void eachBlockedCamunda8ConstructReportsItsReasonCode() throws Exception {
        var reasons = reasons(analyze("camunda8-unsupported.bpmn"));
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("webhookStart", "CONNECTOR_NOT_SUPPORTED");
        expected.put("messageCatch", "MESSAGE_EVENT_NOT_SUPPORTED");
        expected.put("signalCatch", "SIGNAL_EVENT_NOT_SUPPORTED");
        expected.put("cycleTimer", "TIMER_CYCLE_NOT_SUPPORTED");
        expected.put("fixedDate", "TIMER_DATE_NOT_SUPPORTED");
        expected.put("slack", "CONNECTOR_NOT_SUPPORTED");
        expected.put("mail", "CONNECTOR_NOT_SUPPORTED");
        expected.put("calculate", "TASK_TYPE_NOT_SUPPORTED");
        expected.put("inclusive", "GATEWAY_NOT_SUPPORTED_IN_V0");
        expected.put("computedAssignee", "ASSIGNMENT_NOT_SUPPORTED");
        expected.put("nonInterrupting", "EVENT_NOT_SUPPORTED_IN_V0");
        expected.put("eachItem", "MULTI_INSTANCE_NOT_SUPPORTED");
        expected.put("rules", "DECISION_MAPPING_REQUIRED");
        expected.put("nested", "SUBPROCESS_NOT_SUPPORTED");
        expected.forEach((id, code) -> assertThat(reasons.get(id)).as("reason codes of %s", id).contains(code));
        var analysis = analyze("camunda8-unsupported.bpmn");
        assertThat(analysis.hints().services().get("slack").replacement()).contains("notification step");
        assertThat(analysis.hints().services().get("mail").kind()).isEqualTo("unsupportedConnector");
        assertThat(Converter.report(analysis, issues(analysis)).publishable()).isFalse();
        // The embedded form's components each have their own disposition.
        var formCodes = issues(analysis).stream().filter(i -> i.sourceKind().equals("formComponent")).map(Report.Issue::reasonCode).collect(Collectors.toSet());
        assertThat(formCodes).contains("FORM_COMPONENT_NOT_SUPPORTED", "FORM_OPTIONS_NOT_STATIC", "FORM_EXPRESSION_NOT_SUPPORTED", "FORM_FIELD_MAPPED");
    }

    // ------------------------------------------------------------------------------------------------ forms

    @Test
    void aLinkedStartFormAndAnEmbeddedTaskFormConvertWithDueTime() throws Exception {
        var analysis = analyze("camunda8-user-task-form.bpmn");
        assertThat(analysis.linkedForms()).containsEntry("start", "expense-request");
        assertThat(analysis.embeddedForms()).containsKey("managerReview");
        assertThat(analysis.hints().forms().get("start").state()).isEqualTo("upload");
        assertThat(analysis.hints().forms().get("managerReview").state()).isEqualTo("embedded");
        assertThat(issues(analysis)).anySatisfy(i -> {
            assertThat(i.sourceElementId()).isEqualTo("managerReview");
            assertThat(i.reasonCode()).isEqualTo("DUE_DATE_MAPPED");
        }).anySatisfy(i -> assertThat(i.reasonCode()).isEqualTo("FOLLOW_UP_NOT_IMPORTED")).anySatisfy(i -> assertThat(i.reasonCode()).isEqualTo("TASK_FORM_NOTED"));
        String base = "{\"workflowKey\":\"expense-import\",\"name\":\"Expense\",\"acknowledgeInfo\":true,\"acknowledgeReview\":true,\"groupMappings\":{\"source-managers\":\"managers\"}}";
        // Without the form file the form is acknowledged away (no request fields), with it the fields are imported.
        var without = Converter.convert(analysis, request(base), JSON, Map.of());
        assertThat(without.definition()).isNotNull();
        assertThat(without.definition().path("inputSchema").path("properties").has("employeeName")).isFalse();
        FormImport.Model form = FormImport.parse(read("camunda8-user-task-form.form"), JSON, "camunda8-user-task-form.form");
        assertThat(form.convertible()).isTrue();
        var converted = Converter.convert(analysis, request(base), JSON, Map.of(), Map.of(), Map.of("start", form));
        assertThat(converted.definition()).isNotNull();
        JsonNode props = converted.definition().path("inputSchema").path("properties");
        assertThat(props.path("employeeName").path("maxLength").asInt()).isEqualTo(80);
        assertThat(props.path("amount").path("multipleOf").decimalValue()).isEqualByComparingTo("0.01");
        assertThat(props.path("category").path("enum").toString()).isEqualTo("[\"travel\",\"meals\",\"equipment\"]");
        assertThat(props.path("expenseDate").path("format").asString()).isEqualTo("date");
        assertThat(props.path("receiptAttached").path("type").asString()).isEqualTo("boolean");
        assertThat(converted.definition().path("inputSchema").path("required").toString()).contains("employeeName").contains("amount").doesNotContain("reason");
        assertThat(converted.definition().path("inputUi").path("widgets").path("category").asString()).isEqualTo("select");
        assertThat(converted.definition().path("inputUi").path("widgets").path("paidBy").asString()).isEqualTo("radio");
        assertThat(converted.definition().path("inputUi").path("order").get(0).asString()).isEqualTo("employeeName");
        JsonNode approval = converted.definition().path("nodes").valueStream().filter(n -> n.path("type").asString().equals("approval")).findFirst().orElseThrow();
        assertThat(approval.path("config").path("dueAfterHours").asInt()).isEqualTo(48);
        assertThat(converted.definition().path("metadata").path("stepNotes").path("managerReview").asString()).contains("Comment").contains("review-form");
        assertThat(blockers(converted.definition(), groups())).isEmpty();
        assertThat(converted.report().issues()).anySatisfy(i -> assertThat(i.reasonCode()).isEqualTo("FORM_DEFAULT_NOT_IMPORTED"))
                .anySatisfy(i -> assertThat(i.reasonCode()).isEqualTo("FORM_DISPLAY_NOT_IMPORTED"))
                .anySatisfy(i -> {
                    assertThat(i.reasonCode()).isEqualTo("FORM_MAPPED");
                    assertThat(i.sourceElementId()).isEqualTo("start");
                });
    }

    @Test
    void aMappedFormMustMatchTheFormIdAndAnUnknownElementIsRejected() throws Exception {
        var analysis = analyze("camunda8-user-task-form.bpmn");
        var form = FormImport.parse(read("camunda8-user-task-form.form"), JSON, "f");
        String base = "{\"workflowKey\":\"expense-import\",\"name\":\"Expense\",\"acknowledgeInfo\":true,\"acknowledgeReview\":true,\"groupMappings\":{\"source-managers\":\"managers\"}";
        assertThatThrownBy(() -> Converter.convert(analysis, request(base + ",\"formMappings\":{\"managerReview\":{\"migrationId\":\"x\"}}}"), JSON, Map.of()))
                .isInstanceOf(SourceException.class).hasMessageContaining("managerReview");
        assertThat(form.formId()).isEqualTo("expense-request");
    }

    @Test
    void formComponentsGetDispositionsAndUnsupportedOnesAreBlockers() {
        String form = """
                {"id":"f","type":"default","components":[
                 {"type":"textfield","id":"a","key":"name","label":"Name","validate":{"required":true,"pattern":"^[A-Z]"}},
                 {"type":"number","id":"b","key":"qty","label":"Quantity","decimalDigits":0,"validate":{"min":1}},
                 {"type":"group","id":"g","label":"Group","components":[{"type":"checkbox","id":"c","key":"ok","label":"Ok"}]},
                 {"type":"group","id":"g2","label":"Dynamic group","isDynamic":true,"path":"rows","components":[]},
                 {"type":"datetime","id":"d","key":"at","label":"At","subtype":"datetime"},
                 {"type":"select","id":"s","key":"pick","label":"Pick","values":[{"label":"A b","value":"A&b"}]},
                 {"type":"textfield","id":"k","key":"bad key!","label":"Bad"},
                 {"type":"textfield","id":"k2","key":"name","label":"Duplicate"},
                 {"type":"table","id":"t","key":"rows2","label":"Table"},
                 {"type":"mystery","id":"m","label":"Mystery"},
                 {"type":"separator","id":"sep"}]}""";
        var model = FormImport.parse(form, JSON, "inline");
        Map<String, String> codes = model.issues().stream().filter(i -> i.sourceKind().equals("formComponent"))
                .collect(Collectors.toMap(Report.Issue::sourceElementId, Report.Issue::reasonCode, (x, y) -> x + "," + y, TreeMap::new));
        assertThat(codes).containsEntry("a", "FORM_FIELD_MAPPED_WITH_CHANGES").containsEntry("g", "FORM_GROUP_FLATTENED").containsEntry("c", "FORM_FIELD_MAPPED")
                .containsEntry("g2", "FORM_COMPONENT_NOT_SUPPORTED").containsEntry("d", "FORM_COMPONENT_NOT_SUPPORTED").containsEntry("s", "FORM_OPTION_NOT_SUPPORTED")
                .containsEntry("k", "FORM_KEY_NOT_SUPPORTED").containsEntry("k2", "FORM_KEY_NOT_SUPPORTED").containsEntry("t", "FORM_COMPONENT_NOT_SUPPORTED")
                .containsEntry("m", "FORM_COMPONENT_NOT_SUPPORTED").containsEntry("sep", "FORM_DISPLAY_NOT_IMPORTED");
        assertThat(model.issues()).anyMatch(i -> i.reasonCode().equals("FORM_VALIDATION_NOT_IMPORTED") || i.explanation().contains("pattern"));
        assertThat(model.fields()).extracting(FormImport.Field::fieldKey).containsExactly("name", "qty", "ok");
        assertThat(model.fields().get(1).type()).isEqualTo("integer");
        assertThat(model.convertible()).isFalse();
        var report = FormImport.report(model, "hash", null, "f");
        assertThat(report.sourceSystem()).isEqualTo("camundaForm");
        assertThat(report.publishable()).isFalse();
        assertThat(report.mappingHints().formPreviews().get("f").fields()).hasSize(3);
    }

    @Test
    void formFilesAreParsedWithSafeLimits() {
        assertThatThrownBy(() -> FormImport.parse("not json", JSON, null)).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> FormImport.parse("{\"id\":\"x\"}", JSON, null)).isInstanceOf(SourceException.class).hasMessageContaining("components");
        assertThatThrownBy(() -> FormImport.parse("{\"id\":\"x\",\"type\":\"wizard\",\"components\":[]}", JSON, null)).isInstanceOf(SourceException.class);
        StringBuilder many = new StringBuilder("{\"id\":\"big\",\"type\":\"default\",\"components\":[");
        for (int i = 0; i < 320; i++) {
            many.append(i == 0 ? "" : ",").append("{\"type\":\"textfield\",\"id\":\"c").append(i).append("\",\"key\":\"k").append(i).append("\",\"label\":\"L\"}");
        }
        var model = FormImport.parse(many.append("]}").toString(), JSON, null);
        assertThat(model.issues()).anyMatch(i -> i.reasonCode().equals("FORM_TOO_LARGE"));
        assertThat(model.convertible()).isFalse();
        var missingId = FormImport.parse("{\"type\":\"default\",\"components\":[]}", JSON, null);
        assertThat(missingId.issues()).anyMatch(i -> i.reasonCode().equals("FORM_ID_MISSING"));
    }

    // ------------------------------------------------------------------------------------------------ timers and call activity

    @Test
    void timersBoundaryTimeLimitAndCallActivityMapToTheCompilerSupportedPatterns() throws Exception {
        var analysis = analyze("camunda8-timer-callactivity.bpmn");
        var reasons = reasons(analysis);
        assertThat(reasons.get("leadTime")).contains("DURATION_TIMER_MAPPED");
        assertThat(reasons.get("reviewLate")).contains("BOUNDARY_TIMER_MAPPED");
        assertThat(reasons.get("child")).contains("CHILD_WORKFLOW_MAPPING_REQUIRED", "IO_MAPPING_NOT_IMPORTED").doesNotContain("SETTING_NOT_IMPORTED");
        var call = analysis.hints().calls().get("child");
        assertThat(call.processId()).isEqualTo("reviewed-child");
        assertThat(call.propagateAllChildVariables()).isFalse();
        assertThat(call.inputMapping()).containsEntry("title", "/input/title");
        assertThat(call.resultMapping()).containsEntry("recordReference", "reference");
        // Explicit propagation switched off: no propagate-all information item for this call.
        assertThat(issues(analysis)).noneMatch(i -> i.reasonCode().equals("PROPAGATE_ALL_NOT_SUPPORTED"));
        ObjectNode request = request("{\"workflowKey\":\"timer-call\",\"name\":\"Timer call\",\"acknowledgeInfo\":true,\"acknowledgeReview\":true,"
                + "\"groupMappings\":{\"source-managers\":\"managers\",\"source-finance\":\"finance-approvers\"},"
                + "\"childWorkflowMappings\":{\"child\":{\"workflowRef\":\"review-request\",\"inputMapping\":{\"title\":\"/input/title\",\"purpose\":\"/input/purpose\",\"costCenter\":\"/input/costCenter\",\"amount\":\"/input/amount\"},\"resultMapping\":{}}}}");
        request.set("inputSchema", JSON.parse(Files.readString(Path.of("../../examples/purchase-approval.json"))).path("inputSchema"));
        var converted = Converter.convert(analysis, request, JSON, Map.of());
        assertThat(converted.definition()).as(converted.report().issues().toString()).isNotNull();
        JsonNode nodes = converted.definition().path("nodes");
        assertThat(nodes.valueStream().filter(n -> n.path("type").asString().equals("wait")).findFirst().orElseThrow().path("config").path("duration").asString()).isEqualTo("PT1M");
        assertThat(nodes.valueStream().filter(n -> n.path("id").asString().equals("review")).findFirst().orElseThrow().path("config").path("timeoutAfter").asString()).isEqualTo("PT2H");
        assertThat(converted.definition().path("transitions").valueStream().filter(t -> t.path("on").asString().equals("timedOut")).map(t -> t.path("from").asString() + ">" + t.path("to").asString()).toList())
                .containsExactly("review>escalation");
        assertThat(converted.sourceMap()).containsEntry("reviewLate", "review");
        String child = Files.readString(Path.of("../../examples/simple-review.json"));
        var info = CompileContext.WorkflowInfo.of("review-request", "Simple review request", "00000000-0000-7000-8000-0000000000b1", 1, "hash", child, Set.of(), 0);
        var context = new CompileContext(false, groups().groups(), List.of(), Set.of(), Map.of("review-request", info));
        assertThat(blockers(converted.definition(), context)).isEmpty();
    }

    @Test
    void aDateTimerOnAVariableBecomesARequiredDateFieldWait() {
        String xml = "<definitions xmlns=\"" + BPMN + "\" targetNamespace=\"urn:t\"><process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<intermediateCatchEvent id=\"w\" name=\"Until start date\"><timerEventDefinition><timeDate>=startDate</timeDate></timerEventDefinition></intermediateCatchEvent>"
                + "<endEvent id=\"e\" name=\"Done\"/><sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"w\"/><sequenceFlow id=\"f2\" sourceRef=\"w\" targetRef=\"e\"/></process></definitions>";
        var analysis = Analyzer.analyze(xml, Limits.DEFAULT);
        assertThat(issues(analysis)).anySatisfy(i -> assertThat(i.reasonCode()).isEqualTo("DATE_TIMER_MAPPED"));
        var converted = Converter.convert(analysis, request("{\"workflowKey\":\"date-wait\",\"name\":\"Date wait\",\"acknowledgeInfo\":true,\"acknowledgeReview\":true}"), JSON, Map.of());
        assertThat(converted.definition().path("nodes").valueStream().filter(n -> n.path("type").asString().equals("wait")).findFirst().orElseThrow()
                .path("config").path("untilField").asString()).isEqualTo("/input/startDate");
        assertThat(converted.definition().path("inputSchema").path("properties").path("startDate").path("format").asString()).isEqualTo("date");
        assertThat(blockers(converted.definition(), groups())).isEmpty();
    }

    @Test
    void propagatingAllVariablesIsAnInfoItemPlusTheExplicitMappingRequest() {
        String xml = "<definitions xmlns=\"" + BPMN + "\" xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" targetNamespace=\"urn:t\"><process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<callActivity id=\"c\"><extensionElements><zeebe:calledElement processId=\"kid\"/></extensionElements></callActivity><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"c\"/><sequenceFlow id=\"f2\" sourceRef=\"c\" targetRef=\"e\"/></process></definitions>";
        var analysis = Analyzer.analyze(xml, Limits.DEFAULT);
        assertThat(issues(analysis)).anySatisfy(i -> {
            assertThat(i.reasonCode()).isEqualTo("PROPAGATE_ALL_NOT_SUPPORTED");
            assertThat(i.severity()).isEqualTo(Report.INFO);
        }).anySatisfy(i -> {
            assertThat(i.reasonCode()).isEqualTo("CHILD_WORKFLOW_MAPPING_REQUIRED");
            assertThat(i.explanation()).contains("'kid'");
        });
        assertThat(analysis.hints().calls().get("c").propagateAllChildVariables()).isTrue();
    }

    @Test
    void aBoundaryTimerOnATaskThatCannotBecomeAnApprovalStaysBlocked() throws Exception {
        var analysis = Analyzer.analyze(read("unsupported-features.bpmn"), Limits.DEFAULT);
        assertThat(reasons(analysis).get("reviewTimeout")).containsExactly("EVENT_NOT_SUPPORTED_IN_V0");
    }
}
