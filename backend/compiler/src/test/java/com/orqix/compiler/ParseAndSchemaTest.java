package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Pipeline stages 1-3: limits, duplicate keys, version gate and JSON Schema messages. */
class ParseAndSchemaTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    private static ValidationReport validate(String json) {
        return COMPILER.validate(json, TestSupport.defaultContext());
    }

    private static ValidationIssue single(ValidationReport report) {
        assertThat(report.issues()).hasSize(1);
        assertThat(report.publishable()).isFalse();
        return report.issues().get(0);
    }

    @Test
    void parseFailuresHaveNoSemanticHash() {
        ValidationIssue none = single(validate(null));
        assertThat(none.code()).isEqualTo("WF_SCHEMA_INVALID");

        ValidationReport large = validate("{\"schemaVersion\":\"0.1\",\"x\":\"" + "a".repeat(256 * 1024) + "\"}");
        assertThat(single(large).code()).isEqualTo("WF_PAYLOAD_TOO_LARGE");
        assertThat(large.semanticHash()).isNull();

        ValidationReport deep = validate("{\"schemaVersion\":\"0.1\",\"metadata\":" + "[".repeat(40)
                + "]".repeat(40) + "}");
        ValidationIssue depth = single(deep);
        assertThat(depth.code()).isEqualTo("WF_JSON_DEPTH");
        assertThat(depth.path()).startsWith("/metadata/0/0/0");
        assertThat(deep.semanticHash()).isNull();

        ValidationIssue syntax = single(validate("{\"schemaVersion\":\"0.1\",\n  \"name\": }"));
        assertThat(syntax.code()).isEqualTo("WF_SCHEMA_INVALID");
        assertThat(syntax.path()).isEmpty();
        assertThat(syntax.message()).contains("line 2");

        ValidationIssue dup = single(validate("{\"schemaVersion\":\"0.1\",\"metadata\":{\"a\":1,\"a\":2}}"));
        assertThat(dup.path()).isEqualTo("/metadata/a");
        assertThat(dup.message()).isEqualTo("The property \"a\" appears more than once.");

        ValidationIssue range = single(validate("{\"schemaVersion\":\"0.1\",\"x\":[1e400]}"));
        assertThat(range.path()).isEqualTo("/x/0");
        assertThat(range.message()).contains("too large");
    }

    @Test
    void depthLimitIsThirtyTwo() {
        String ok = "{\"x\":" + "[".repeat(31) + "]".repeat(31) + "}";
        assertThat(validate(ok).issues()).extracting(ValidationIssue::code)
                .doesNotContain("WF_JSON_DEPTH");
        String tooDeep = "{\"x\":" + "[".repeat(32) + "]".repeat(32) + "}";
        assertThat(single(validate(tooDeep)).code()).isEqualTo("WF_JSON_DEPTH");
    }

    @Test
    void versionGate() {
        ValidationReport array = validate("[]");
        assertThat(single(array).code()).isEqualTo("WF_SCHEMA_INVALID");
        assertThat(array.semanticHash()).isEqualTo(CanonicalJson.sha256Hex("[]"));

        ValidationIssue missing = single(validate("{}"));
        assertThat(missing.code()).isEqualTo("WF_SCHEMA_VERSION_UNSUPPORTED");
        assertThat(missing.path()).isEqualTo("/schemaVersion");
        assertThat(missing.message()).contains("does not state");

        ValidationIssue number = single(validate("{\"schemaVersion\":0.1}"));
        assertThat(number.code()).isEqualTo("WF_SCHEMA_VERSION_UNSUPPORTED");
        assertThat(number.message()).contains("0.1");
    }

    @Test
    void schemaMessagesAreBusinessEnglish() {
        String patch = """
                [{"op":"remove","path":"/metadata/description"},
                 {"op":"add","path":"/extra","value":1},
                 {"op":"replace","path":"/workflowKey","value":"Bad_Key"},
                 {"op":"replace","path":"/name","value":""},
                 {"op":"replace","path":"/execution/maxAttempts","value":9},
                 {"op":"replace","path":"/execution/retryDelaySeconds","value":1},
                 {"op":"replace","path":"/triggers","value":["manual","manual"]},
                 {"op":"replace","path":"/uiLayout/viewport/zoom","value":0},
                 {"op":"add","path":"/uiLayout/positions/bad id!","value":{"x":1,"y":2}},
                 {"op":"add","path":"/outputMapping/Bad","value":{"fieldRef":"/input/title","required":false}},
                 {"op":"replace","path":"/decisions/0/default","value":{}},
                 {"op":"replace","path":"/transitions/4/condition/fieldRef","value":"/x"},
                 {"op":"replace","path":"/transitions/3/on","value":"condition"},
                 {"op":"add","path":"/transitions/0/condition","value":{"fieldRef":"/input/amount","operator":"eq","value":1}},
                 {"op":"replace","path":"/inputSchema/properties/amount/multipleOf","value":0.5},
                 {"op":"replace","path":"/metadata/tags","value":[]},
                 {"op":"replace","path":"/inputSchema/properties/title/maxLength","value":5000},
                 {"op":"replace","path":"/nodes/1/config/rejectReasonRequired","value":false},
                 {"op":"replace","path":"/security/selfApproval","value":"maybe"},
                 {"op":"replace","path":"/inputSchema/properties/purpose/description","value":7}]""";
        Map<String, String> messages = messages(patch);
        assertThat(messages).containsEntry("/metadata/description", "The required property \"description\" is missing.")
                .containsEntry("/extra", "The property \"extra\" is not allowed here.")
                .containsEntry("/name", "This text cannot be empty.")
                .containsEntry("/execution/maxAttempts", "This number must be at most 5.")
                .containsEntry("/execution/retryDelaySeconds", "This number must be at least 5.")
                .containsEntry("/triggers", "This list contains the same entry more than once.")
                .containsEntry("/uiLayout/viewport/zoom", "This number must be greater than 0.")
                .containsEntry("/decisions/0/default", "This cannot be empty.")
                .containsEntry("/transitions/3/condition",
                        "A path that continues on a condition (on: condition) needs a condition.")
                .containsEntry("/transitions/0/condition",
                        "Only a path that continues on a condition (on: condition) can have a condition.")
                .containsEntry("/inputSchema/properties/title/maxLength", "This number must be at most 4000.")
                .containsEntry("/nodes/1/config/rejectReasonRequired", "This value must be true.")
                .containsEntry("/inputSchema/properties/purpose/description",
                        "Expected text here, but found a whole number.");
        assertThat(messages.get("/workflowKey")).startsWith("Use 2 to 64 lower-case letters");
        assertThat(messages.get("/uiLayout/positions/bad id!")).contains("is not a valid step ID");
        assertThat(messages.get("/outputMapping/Bad")).contains("The name \"Bad\" is not allowed here");
        assertThat(messages.get("/transitions/4/condition/fieldRef")).startsWith("A field reference must look like");
        assertThat(messages.get("/inputSchema/properties/amount/multipleOf")).contains("0.01");
        assertThat(messages.get("/security/selfApproval")).contains("deny");
        assertThat(messages.get("/metadata/tags")).isNull();
    }

    @Test
    void nodeErrorsAreNarrowedToTheirType() {
        String patch = """
                [{"op":"remove","path":"/nodes/2/config/decisionRef"},
                 {"op":"remove","path":"/nodes/5/config/connectionRef"},
                 {"op":"replace","path":"/nodes/5/config/operation","value":"Bad"},
                 {"op":"replace","path":"/nodes/5/config/resultMapping/procurementId","value":"id"},
                 {"op":"remove","path":"/nodes/6/config/message"},
                 {"op":"replace","path":"/nodes/7/config/businessOutcome","value":"lower"},
                 {"op":"remove","path":"/nodes/9/config/outcomeKind"},
                 {"op":"remove","path":"/nodes/0/label"},
                 {"op":"replace","path":"/nodes/3","value":"branch"},
                 {"op":"remove","path":"/nodes/8/type"},
                 {"op":"replace","path":"/nodes/4/type","value":7},
                 {"op":"remove","path":"/nodes/1/config"}]""";
        Map<String, String> messages = messages(patch);
        assertThat(messages).containsEntry("/nodes/2/config/decisionRef", "Check purchase amount needs a rule table.")
                .containsEntry("/nodes/5/config/connectionRef", "Create purchase order needs a connection.")
                .containsEntry("/nodes/6/config/message", "Tell requester it is ordered needs a message.")
                .containsEntry("/nodes/9/config/outcomeKind",
                        "Rejected needs an outcome kind: success or rejected.")
                .containsEntry("/nodes/0/label", "Every step needs a name.")
                .containsEntry("/nodes/3", "Each step must be an object with id, type, label and config.")
                .containsEntry("/nodes/1/config", "Review request needs its settings (config).");
        assertThat(messages.get("/nodes/5/config/operation")).startsWith("Operation names start");
        assertThat(messages.get("/nodes/5/config/resultMapping/procurementId")).contains("such as /id");
        assertThat(messages.get("/nodes/7/config/businessOutcome")).startsWith("An outcome name starts");
        assertThat(messages.get("/nodes/8/type")).startsWith("This step has no type.");
        assertThat(messages.get("/nodes/4/type")).startsWith("The step type is not supported.");
        ValidationReport report = validate(TestSupport.text(TestSupport.patch(TestSupport.purchase(),
                StrictJson.parse(patch))));
        assertThat(report.issues()).filteredOn(i -> i.path().equals("/nodes/2/config/decisionRef"))
                .extracting(ValidationIssue::nodeId).containsExactly("amountPolicy");
    }

    @Test
    void schemaIssuesAreCappedAtFifty() {
        ObjectNode doc = TestSupport.purchase();
        for (int i = 0; i < 60; i++) {
            doc.put("extra" + i, i);
        }
        ValidationReport report = validate(TestSupport.text(doc));
        assertThat(report.issues()).hasSize(50).allMatch(i -> i.code().equals("WF_SCHEMA_INVALID"));
        assertThat(report.issues().get(0).path()).isEqualTo("/extra0");
        assertThat(report.semanticHash()).isNotNull();
    }

    @Test
    void integersBeyondJavaRangeAreSchemaProblems() {
        Map<String, String> messages = messages("""
                [{"op":"replace","path":"/decisions/0/version","value":3000000000},
                 {"op":"replace","path":"/dependencies/connections/0/definitionVersion","value":3000000000},
                 {"op":"replace","path":"/inputSchema/properties/title/minLength","value":3000000000}]""");
        assertThat(messages).containsEntry("/decisions/0/version", "This number is too large.")
                .containsEntry("/dependencies/connections/0/definitionVersion", "This number is too large.")
                .containsEntry("/inputSchema/properties/title/minLength", "This number is too large.");
    }

    private static Map<String, String> messages(String patch) {
        JsonNode doc = TestSupport.patch(TestSupport.purchase(), StrictJson.parse(patch));
        ValidationReport report = validate(TestSupport.text(doc));
        Map<String, String> byPath = new LinkedHashMap<>();
        for (ValidationIssue i : report.issues()) {
            assertThat(i.code()).isEqualTo("WF_SCHEMA_INVALID");
            byPath.putIfAbsent(i.path(), i.message());
        }
        return byPath;
    }
}
