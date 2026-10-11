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
 * "Wait for a message" (S16): the schema, the WF_MESSAGE_* rules, field availability after the step, the generated
 * BPMN (message definition, catch event, event-based gateway with a timer) and the golden files. Node indexes of
 * {@code examples/order-payment-wait.json}: 0 start, 1 waitPayment, 2 notifyPaid, 3 fulfilled, 4 notifyCancelled,
 * 5 cancelled; transitions 0 t1, 1 t2 (next), 2 t3 (timedOut).
 */
class MessageEventsTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();
    private static final String SAMPLE = "examples/order-payment-wait.json";

    private static ObjectNode sample() {
        return (ObjectNode) TestSupport.json(SAMPLE);
    }

    private static ObjectNode config(ObjectNode doc, int node) {
        return (ObjectNode) doc.get("nodes").get(node).get("config");
    }

    private static JsonNode parse(String json) {
        return StrictJson.parse(json);
    }

    private static List<String> issues(Consumer<ObjectNode> patch) {
        ObjectNode doc = sample();
        patch.accept(doc);
        return codes(doc);
    }

    private static List<String> codes(ObjectNode doc) {
        ValidationReport report = COMPILER.validate(TestSupport.text(doc), TestSupport.defaultContext());
        return report.issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    /** Removes the time limit, its route and the cancellation steps behind it. */
    private static void noLimit(ObjectNode d) {
        config(d, 1).remove("timeoutAfter");
        ArrayNode transitions = (ArrayNode) d.get("transitions");
        transitions.remove(4);
        transitions.remove(2);
        ((ArrayNode) d.get("nodes")).remove(5);
        ((ArrayNode) d.get("nodes")).remove(4);
        ((ObjectNode) d.get("uiLayout").get("positions")).remove("cancelled");
        ((ObjectNode) d.get("uiLayout").get("positions")).remove("notifyCancelled");
    }

    @Test
    void theSampleIsValidAndPublishable() {
        ValidationReport report = COMPILER.validate(TestSupport.read(SAMPLE), TestSupport.defaultContext());
        assertThat(report.issues()).isEmpty();
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void aWaitWithoutALimitIsValid() {
        assertThat(issues(MessageEventsTest::noLimit)).isEmpty();
    }

    @Test
    void theMessageNameIsAnIdentifier() {
        for (String bad : List.of("Payment-Received", "payment received", "payment_received", "-payment", "payment-",
                "p", "payment--received", "1payment", "payment.received")) {
            assertThat(issues(d -> config(d, 1).put("message", bad))).as(bad)
                    .containsExactly("B WF_SCHEMA_INVALID /nodes/1/config/message");
        }
        for (String good : List.of("payment-received", "paymentReceived", "docs-arrived-2", "ok")) {
            assertThat(issues(d -> config(d, 1).put("message", good))).as(good).isEmpty();
        }
    }

    @Test
    void theRequestFieldMustExist() {
        assertThat(issues(d -> config(d, 1).put("correlationField", "/input/missing")))
                .containsExactly("B WF_MESSAGE_CORRELATION_UNKNOWN /nodes/1/config/correlationField");
        assertThat(issues(d -> config(d, 1).put("correlationField", "/steps/nowhere/x")))
                .containsExactly("B WF_MESSAGE_CORRELATION_UNKNOWN /nodes/1/config/correlationField");
        // A step that exists but produces nothing a message could match.
        assertThat(issues(d -> config(d, 1).put("correlationField", "/steps/start/x")))
                .containsExactly("B WF_MESSAGE_CORRELATION_UNKNOWN /nodes/1/config/correlationField");
    }

    @Test
    void theFieldIsTextOrAWholeNumberThatAlwaysHasAValue() {
        assertThat(issues(d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("amount",
                    parse("{\"type\":\"number\",\"title\":\"Amount\"}"));
            ((ArrayNode) d.get("inputSchema").get("required")).add("amount");
            config(d, 1).put("correlationField", "/input/amount");
        })).containsExactly("B WF_MESSAGE_CORRELATION_TYPE /nodes/1/config/correlationField");
        assertThat(issues(d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("invoiceNo",
                    parse("{\"type\":\"integer\",\"title\":\"Invoice number\"}"));
            ((ArrayNode) d.get("inputSchema").get("required")).add("invoiceNo");
            config(d, 1).put("correlationField", "/input/invoiceNo");
        })).isEmpty();
        assertThat(issues(d -> ((ArrayNode) d.get("inputSchema").get("required")).remove(0)))
                .containsExactly("B WF_MESSAGE_CORRELATION_OPTIONAL /nodes/1/config/correlationField");
    }

    @Test
    void theTimeLimitNeedsExactlyOneTimedOutRoute() {
        assertThat(issues(d -> ((ArrayNode) d.get("transitions")).remove(2)))
                .contains("B WF_MESSAGE_TIMEOUT_ROUTE /nodes/1");
        assertThat(issues(d -> ((ArrayNode) d.get("transitions")).add(parse("{\"id\":\"t6\",\"from\":\"waitPayment\","
                + "\"to\":\"notifyCancelled\",\"on\":\"timedOut\"}"))))
                .containsExactly("B WF_MESSAGE_TIMEOUT_ROUTE /nodes/1");
        // A timedOut route without a time limit is not allowed.
        assertThat(issues(d -> config(d, 1).remove("timeoutAfter")))
                .containsExactly("B WF_OUTGOING_INVALID /transitions/2/on");
        // The step needs its next route.
        assertThat(issues(d -> ((ArrayNode) d.get("transitions")).remove(1)))
                .contains("B WF_OUTGOING_INVALID /nodes/1");
    }

    @Test
    void theTimeLimitUsesTheSameLimitsAsAWait() {
        assertThat(issues(d -> config(d, 1).put("timeoutAfter", "P91D")))
                .containsExactly("B WF_DURATION_TOO_LONG /nodes/1/config/timeoutAfter");
        assertThat(issues(d -> config(d, 1).put("timeoutAfter", "PT0M")))
                .containsExactly("B WF_DURATION_TOO_SHORT /nodes/1/config/timeoutAfter");
        assertThat(issues(d -> config(d, 1).put("timeoutAfter", "soon")))
                .contains("B WF_SCHEMA_INVALID /nodes/1/config/timeoutAfter");
        assertThat(issues(d -> config(d, 1).put("timeoutAfter", "PT1M"))).isEmpty();
        assertThat(issues(d -> config(d, 1).put("timeoutAfter", "P90D"))).isEmpty();
    }

    @Test
    void payloadFieldsAreDeclaredOnce() {
        assertThat(issues(d -> ((ArrayNode) config(d, 1).get("payload")).add(parse(
                "{\"name\":\"amountPaid\",\"type\":\"string\"}"))))
                .containsExactly("B WF_MESSAGE_PAYLOAD_DUPLICATE /nodes/1/config/payload/2/name");
        assertThat(issues(d -> ((ArrayNode) config(d, 1).get("payload")).add(parse(
                "{\"name\":\"Bad-Name\",\"type\":\"string\"}"))))
                .contains("B WF_SCHEMA_INVALID /nodes/1/config/payload/2/name");
        assertThat(issues(d -> ((ArrayNode) config(d, 1).get("payload")).add(parse(
                "{\"name\":\"extra\",\"type\":\"array\"}"))))
                .contains("B WF_SCHEMA_INVALID /nodes/1/config/payload/2/type");
        assertThat(issues(d -> config(d, 1).remove("payload"))).containsExactly(
                "B WF_FIELDREF_UNKNOWN /outputMapping/paymentReference/fieldRef");
    }

    @Test
    void payloadOutputsFollowTheUsualAvailabilityRules() {
        // The payment reference is guaranteed on the next path only: it cannot be a required result when the
        // timed-out path also finishes the request.
        assertThat(issues(d -> ((ObjectNode) d.get("outputMapping").get("paymentReference")).put("required", true)))
                .contains("B WF_OUTPUT_NOT_GUARANTEED /outputMapping/paymentReference/fieldRef");
        // Without a time limit, a required payload field is always there.
        assertThat(issues(d -> {
            noLimit(d);
            ((ObjectNode) d.get("outputMapping").get("paymentReference")).put("required", true);
            ((ArrayNode) d.get("outputSchema").withArray("required")).add("paymentReference");
        })).isEmpty();
        // A payload field that is not required may be missing, so it cannot be a required result either.
        assertThat(issues(d -> {
            noLimit(d);
            ((ObjectNode) d.get("outputSchema").get("properties")).set("amountPaid",
                    parse("{\"type\":\"number\",\"title\":\"Amount paid\"}"));
            ((ObjectNode) d.get("outputMapping")).set("amountPaid",
                    parse("{\"fieldRef\":\"/steps/waitPayment/amountPaid\",\"required\":true}"));
        })).contains("B WF_OUTPUT_NOT_GUARANTEED /outputMapping/amountPaid/fieldRef");
        // A value the message does not declare is unknown.
        assertThat(issues(d -> ((ObjectNode) d.get("outputMapping").get("paymentReference"))
                .put("fieldRef", "/steps/waitPayment/nothing")))
                .containsExactly("B WF_FIELDREF_UNKNOWN /outputMapping/paymentReference/fieldRef");
    }

    @Test
    void aPayloadFieldCannotBeUsedOnThePathWhereTheTimeLimitPassed() {
        // A later branch on the payment reference, reached from both the next and the timed-out path.
        ObjectNode doc = sample();
        ((ArrayNode) doc.get("nodes")).add(parse("{\"id\":\"route\",\"type\":\"branch\",\"label\":\"Which reference?\","
                + "\"config\":{}}"));
        ArrayNode transitions = (ArrayNode) doc.get("transitions");
        ((ObjectNode) transitions.get(2)).put("to", "route");
        transitions.add(parse("{\"id\":\"t6\",\"from\":\"route\",\"to\":\"notifyCancelled\",\"on\":\"condition\","
                + "\"condition\":{\"fieldRef\":\"/steps/waitPayment/paymentReference\",\"operator\":\"eq\","
                + "\"value\":\"x\"}}"));
        transitions.add(parse("{\"id\":\"t7\",\"from\":\"route\",\"to\":\"notifyCancelled\",\"on\":\"default\"}"));
        assertThat(codes(doc)).contains("B WF_FIELDREF_NOT_GUARANTEED /transitions/5/condition/fieldRef");
    }

    // ---------------------------------------------------------------- parallel waits for the same message

    private static ObjectNode parallelWaits(String messageB, String fieldB) {
        return (ObjectNode) parse("""
                {
                  "schemaVersion": "0.1", "workflowKey": "twin-waits", "name": "Twin waits",
                  "metadata": {"description": "Two waits", "tags": ["test"]}, "triggers": ["manual"],
                  "inputSchema": {"type": "object", "additionalProperties": false, "required": ["orderNumber", "invoiceNo"],
                    "properties": {
                      "orderNumber": {"type": "string", "title": "Order number", "minLength": 1, "maxLength": 40},
                      "invoiceNo": {"type": "string", "title": "Invoice", "minLength": 1, "maxLength": 40}}},
                  "outputSchema": {"type": "object", "additionalProperties": false, "properties": {}},
                  "nodes": [
                    {"id": "start", "type": "start", "label": "Start", "config": {}},
                    {"id": "split", "type": "parallelSplit", "label": "Both", "config": {"joinRef": "join"}},
                    {"id": "waitA", "type": "waitForMessage", "label": "Wait A",
                      "config": {"message": "payment-received", "correlationField": "/input/orderNumber"}},
                    {"id": "waitB", "type": "waitForMessage", "label": "Wait B",
                      "config": {"message": "%s", "correlationField": "%s"}},
                    {"id": "join", "type": "parallelJoin", "label": "Both done", "config": {}},
                    {"id": "done", "type": "end", "label": "Done", "config": {"businessOutcome": "Done", "outcomeKind": "success"}}
                  ],
                  "transitions": [
                    {"id": "t1", "from": "start", "to": "split", "on": "next"},
                    {"id": "t2", "from": "split", "to": "waitA", "on": "next"},
                    {"id": "t3", "from": "split", "to": "waitB", "on": "next"},
                    {"id": "t4", "from": "waitA", "to": "join", "on": "next"},
                    {"id": "t5", "from": "waitB", "to": "join", "on": "next"},
                    {"id": "t6", "from": "join", "to": "done", "on": "next"}
                  ],
                  "decisions": [],
                  "execution": {"maxConcurrentRuns": 10, "maxAttempts": 3, "retryDelaySeconds": 30},
                  "security": {"selfApproval": "deny"},
                  "dependencies": {"connections": []},
                  "outputMapping": {}
                }
                """.formatted(messageB, fieldB));
    }

    @Test
    void twoParallelWaitsCannotListenForTheSameMessageAboutTheSameField() {
        assertThat(codes(parallelWaits("payment-received", "/input/orderNumber")))
                .containsExactly("B WF_MESSAGE_DUPLICATE_WAIT /nodes/3/config/message");
        assertThat(codes(parallelWaits("documents-arrived", "/input/orderNumber"))).isEmpty();
        assertThat(codes(parallelWaits("payment-received", "/input/invoiceNo"))).isEmpty();
    }

    @Test
    void theSameMessageInSequenceIsAllowed() {
        ObjectNode doc = sample();
        noLimit(doc);
        ArrayNode nodes = (ArrayNode) doc.get("nodes");
        nodes.add(parse("{\"id\":\"waitAgain\",\"type\":\"waitForMessage\",\"label\":\"Wait again\",\"config\":"
                + "{\"message\":\"payment-received\",\"correlationField\":\"/input/orderNumber\"}}"));
        ArrayNode transitions = (ArrayNode) doc.get("transitions");
        ((ObjectNode) transitions.get(1)).put("to", "waitAgain");
        transitions.add(parse("{\"id\":\"t7\",\"from\":\"waitAgain\",\"to\":\"notifyPaid\",\"on\":\"next\"}"));
        assertThat(codes(doc)).isEmpty();
    }

    @Test
    void aTimedOutPathMayLeaveAParallelBranchLikeARejection() throws Exception {
        ObjectNode doc = parallelWaits("documents-arrived", "/input/orderNumber");
        ((ObjectNode) doc.get("nodes").get(3).get("config")).put("correlationField", "/input/invoiceNo");
        ((ObjectNode) doc.get("nodes").get(3).get("config")).put("timeoutAfter", "P1D");
        ((ArrayNode) doc.get("nodes")).add(parse("{\"id\":\"late\",\"type\":\"end\",\"label\":\"Too late\","
                + "\"config\":{\"businessOutcome\":\"Too late\",\"outcomeKind\":\"rejected\"}}"));
        ((ArrayNode) doc.get("transitions")).add(parse("{\"id\":\"t7\",\"from\":\"waitB\",\"to\":\"late\","
                + "\"on\":\"timedOut\"}"));
        assertThat(codes(doc)).isEmpty();
        String xml = COMPILER.compile(TestSupport.text(doc), TestSupport.defaultContext()).bpmnXml();
        assertThat(xml).contains("<terminateEventDefinition/>");
    }

    // ---------------------------------------------------------------- generated BPMN

    @Test
    void generatesAMessageCatchEventAndATimerOnAnEventGateway() throws Exception {
        String xml = COMPILER.compile(TestSupport.read(SAMPLE), TestSupport.defaultContext()).bpmnXml();
        assertThat(xml)
                .contains("<message id=\"message__payment-received\" name=\"payment-received\"/>")
                .contains("<eventBasedGateway id=\"waitPayment\" name=\"Wait for the payment\"/>")
                .contains("<intermediateCatchEvent id=\"waitPayment__message\"")
                .contains("<orqix:message node=\"waitPayment\" name=\"payment-received\""
                        + " correlationField=\"/input/orderNumber\">")
                .contains("<orqix:field name=\"paymentReference\" type=\"string\" required=\"true\"/>")
                .contains("<orqix:field name=\"amountPaid\" type=\"number\" required=\"false\"/>")
                .contains("<messageEventDefinition messageRef=\"message__payment-received\"/>")
                .contains("<intermediateCatchEvent id=\"waitPayment__timeout\"")
                .contains("<orqix:messageTimeout node=\"waitPayment\" after=\"P2D\"/>")
                .contains("<timeDuration>P2D</timeDuration>")
                .contains("<sequenceFlow id=\"t2\" name=\"Payment arrived\" sourceRef=\"waitPayment__message\""
                        + " targetRef=\"notifyPaid\"/>")
                .contains("<sequenceFlow id=\"t3\" name=\"No payment in 2 days\" sourceRef=\"waitPayment__timeout\""
                        + " targetRef=\"notifyCancelled\"/>")
                .contains("<sequenceFlow id=\"t1\" sourceRef=\"start\" targetRef=\"waitPayment\"/>");
        assertThat(xml.indexOf("<message id=")).isLessThan(xml.indexOf("<process "));
        String map = COMPILER.compile(TestSupport.read(SAMPLE), TestSupport.defaultContext()).sourceMapJson();
        assertThat(map).contains("\"waitPayment\": [\"waitPayment\", \"waitPayment__message\","
                + " \"waitPayment__timeout\", \"waitPayment__toMessage\", \"waitPayment__toTimeout\"]");
    }

    @Test
    void withoutALimitTheStepIsOneCatchEvent() throws Exception {
        ObjectNode doc = sample();
        noLimit(doc);
        String xml = COMPILER.compile(TestSupport.text(doc), TestSupport.defaultContext()).bpmnXml();
        assertThat(xml).contains("<intermediateCatchEvent id=\"waitPayment\" name=\"Wait for the payment\">")
                .doesNotContain("eventBasedGateway").doesNotContain("timerEventDefinition")
                .doesNotContain("waitPayment__message").doesNotContain("waitPayment__timeout")
                .contains("<sequenceFlow id=\"t2\" name=\"Payment arrived\" sourceRef=\"waitPayment\"");
    }

    @Test
    void oneMessageDefinitionPerNameAndNoNewExpressions() throws Exception {
        ObjectNode doc = parallelWaits("documents-arrived", "/input/orderNumber");
        String xml = COMPILER.compile(TestSupport.text(doc), TestSupport.defaultContext()).bpmnXml();
        assertThat(xml).contains("<message id=\"message__documents-arrived\"")
                .contains("<message id=\"message__payment-received\"");
        assertThat(xml.indexOf("message__documents-arrived\" name")).isLessThan(xml.indexOf("message__payment-received\" name"));
        String sampleXml = COMPILER.compile(TestSupport.read(SAMPLE), TestSupport.defaultContext()).bpmnXml();
        var m = Pattern.compile("[$#]\\{[^}]*}").matcher(sampleXml);
        int count = 0;
        while (m.find()) {
            count++;
            assertThat(m.group()).isEqualTo("${orqixNotificationTask}");
        }
        assertThat(count).isEqualTo(2);
    }

    @Test
    void aMessageNameInALabelIsNotAnExpression() {
        assertThat(issues(d -> ((ObjectNode) d.get("nodes").get(1)).put("label", "Wait ${clock}")))
                .contains("B WF_LABEL_EXPRESSION /nodes/1/label");
    }

    // ---------------------------------------------------------------- golden files and determinism

    private static Path goldenDir() {
        String dir = System.getProperty("orqix.goldenDir");
        Path base = dir == null ? Path.of("src/test/resources/golden") : Path.of(dir);
        return base.resolve("order-payment-wait");
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
    @Test
    void correlationTextMustBeBoundedToUsableKeys() {
        assertThat(issues(d -> ((ObjectNode)d.path("inputSchema").path("properties").path("orderNumber")).put("maxLength",201)))
                .anyMatch(v -> v.contains("WF_MESSAGE_CORRELATION_BOUNDS"));
        assertThat(issues(d -> ((ObjectNode)d.path("inputSchema").path("properties").path("orderNumber")).remove("minLength")))
                .anyMatch(v -> v.contains("WF_MESSAGE_CORRELATION_BOUNDS"));
    }
}
