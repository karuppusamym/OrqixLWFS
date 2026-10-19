package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** S73 "Send through an adapter": schema, allowlist and kind rules, typing, BPMN and simulation. */
class AdapterStepRulesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Map<String, CompileContext.AdapterInfo> ADAPTERS = Map.of(
            "ops-mail", new CompileContext.AdapterInfo("ops-mail", "email", "Operations mail", true,
                    "{\"from\":\"noreply@example.com\",\"allowedRecipientDomains\":[\"example.com\"],\"maxRecipients\":2}"),
            "orders", new CompileContext.AdapterInfo("orders", "kafka", "Orders topic", true,
                    "{\"allowedTopics\":[\"orders.created\"]}"),
            "lookup", new CompileContext.AdapterInfo("lookup", "jdbc", "Customer lookup", true,
                    "{\"statement\":\"SELECT id FROM c WHERE cc = :costCenter\",\"parameters\":[\"costCenter\"],\"maxRows\":100,\"timeoutSeconds\":10}"),
            "chat", new CompileContext.AdapterInfo("chat", "teams", "Team channel", false, "{\"baseUrl\":\"https://teams.example.com\"}"));

    /** start -> send -> manager ...; {@code config} is the adapter step's config. */
    private static ObjectNode definition(String config) throws Exception {
        ObjectNode d = TestSupport.purchase();
        ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("{\"id\":\"send\",\"type\":\"sendAdapter\",\"label\":\"Tell operations\","
                + "\"config\":" + config + "}"));
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        for (JsonNode t : transitions) {
            if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                ((ObjectNode) t).put("to", "send");
            }
        }
        transitions.add(MAPPER.readTree("{\"id\":\"s1\",\"from\":\"send\",\"to\":\"manager\",\"on\":\"next\"}"));
        return d;
    }

    private static CompileContext context() {
        return SimulatorFixtures.context().workspace().withAdapters(ADAPTERS);
    }

    private static List<String> codes(ObjectNode d) {
        return codes(d, context());
    }

    private static List<String> codes(ObjectNode d, CompileContext context) {
        return Compilers.workflowCompiler().validate(d.toString(), context).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).map(ValidationIssue::code).toList();
    }

    private static final String EMAIL = "{\"adapter\":\"ops-mail\",\"kind\":\"email\",\"to\":[\"ops@example.com\"],"
            + "\"subject\":\"New request {{title}}\",\"body\":\"Amount {{amount}} for {{purpose}}\","
            + "\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\",\"purpose\":\"/input/purpose\"}}";

    @Test
    void anEmailStepToAnApprovedRecipientCompilesToAnAsyncJournaledServiceTask() throws Exception {
        ObjectNode d = definition(EMAIL);
        assertThat(codes(d)).isEmpty();
        String bpmn = Compilers.workflowCompiler().compile(d.toString(), context()).bpmnXml();
        assertThat(bpmn).contains("delegateExpression=\"${orqixAdapterTask}\"", "flowable:async=\"true\"",
                "adapter=\"ops-mail\"", "kind=\"email\"", "<orqix:to address=\"ops@example.com\"",
                "<orqix:input name=\"amount\" fieldRef=\"/input/amount\"", "subjectBase64=");
        assertThat(bpmn).doesNotContain("flowable:triggerable");
    }

    @Test
    void recipientsAndTopicsMustBeFixedAndApprovedByTheAdapter() throws Exception {
        // A domain the adapter does not approve, a value from the request and too many recipients are all refused.
        assertThat(codes(definition(EMAIL.replace("ops@example.com", "ops@evil.example.org")))).contains("WF_ADAPTER_RECIPIENT");
        assertThat(codes(definition(EMAIL.replace("ops@example.com", "{{title}}@example.com")))).contains("WF_ADAPTER_RECIPIENT");
        assertThat(codes(definition(EMAIL.replace("\"ops@example.com\"", "\"a@example.com\",\"b@example.com\",\"c@example.com\""))))
                .contains("WF_ADAPTER_RECIPIENT");
        assertThat(codes(definition(EMAIL.replace("\"ops@example.com\"", "\"a@example.com\",\"A@example.com\""))))
                .contains("WF_ADAPTER_RECIPIENT");
        // There is no way to take a recipient from the request: "to" holds addresses only (schema).
        assertThat(codes(definition(EMAIL.replace("[\"ops@example.com\"]", "\"/input/title\"")))).isNotEmpty();

        String kafka = "{\"adapter\":\"orders\",\"kind\":\"kafka\",\"topic\":\"orders.created\",\"key\":\"{{title}}\","
                + "\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\"}}";
        assertThat(codes(definition(kafka))).isEmpty();
        assertThat(codes(definition(kafka.replace("orders.created", "payroll")))).contains("WF_ADAPTER_TOPIC");
        assertThat(codes(definition(kafka.replace("orders.created", "orders.*")))).isNotEmpty();
    }

    @Test
    void theAdapterMustExistBeEnabledAndHaveTheStatedKind() throws Exception {
        assertThat(codes(definition(EMAIL), SimulatorFixtures.context().workspace())).contains("WF_ADAPTER_UNKNOWN");
        assertThat(codes(definition(EMAIL.replace("\"kind\":\"email\"", "\"kind\":\"kafka\"")))).contains("WF_ADAPTER_KIND");
        assertThat(codes(definition("{\"adapter\":\"chat\",\"kind\":\"teams\",\"text\":\"Hello\",\"inputMapping\":{}}")))
                .contains("WF_ADAPTER_DISABLED");
        // Members of another kind are refused rather than ignored.
        assertThat(codes(definition("{\"adapter\":\"orders\",\"kind\":\"kafka\",\"topic\":\"orders.created\",\"subject\":\"x\","
                + "\"inputMapping\":{\"title\":\"/input/title\"}}"))).contains("WF_ADAPTER_MESSAGE");
        assertThat(codes(definition(EMAIL.replace("New request {{title}}", "Line\\nbreak")))).contains("WF_ADAPTER_MESSAGE");
    }

    @Test
    void placeholdersAndMappedValuesFollowTheNormalAvailabilityRules() throws Exception {
        assertThat(codes(definition(EMAIL.replace("{{purpose}}", "{{missing}}")))).contains("WF_ADAPTER_PLACEHOLDER");
        assertThat(codes(definition(EMAIL.replace("{{purpose}}", "${purpose}")))).contains("WF_ADAPTER_PLACEHOLDER");
        assertThat(codes(definition(EMAIL.replace("/input/purpose", "/input/nope")))).contains("WF_ADAPTER_INPUT_UNKNOWN");
        assertThat(codes(definition(EMAIL.replace("/input/purpose", "/steps/manager/decision")))).isNotEmpty();
    }

    @Test
    void databaseReadsMapExactlyTheDeclaredParametersAndReturnARowCount() throws Exception {
        String jdbc = "{\"adapter\":\"lookup\",\"kind\":\"jdbc\",\"inputMapping\":{\"costCenter\":\"/input/costCenter\"}}";
        assertThat(codes(definition(jdbc))).isEmpty();
        assertThat(codes(definition(jdbc.replace("{\"costCenter\":\"/input/costCenter\"}",
                "{\"costCenter\":\"/input/costCenter\",\"extra\":\"/input/title\"}")))).contains("WF_ADAPTER_PARAMETERS");
        // rowCount is a typed output of a database read; an email step has only an outcome.
        ObjectNode branchUse = definition(jdbc);
        ((ArrayNode) branchUse.path("nodes")).add(MAPPER.readTree("{\"id\":\"tell\",\"type\":\"sendAdapter\",\"label\":\"Report rows\","
                + "\"config\":{\"adapter\":\"ops-mail\",\"kind\":\"email\",\"to\":[\"ops@example.com\"],\"subject\":\"Rows\","
                + "\"body\":\"{{rows}} rows, {{how}}\",\"inputMapping\":{\"rows\":\"/steps/send/rowCount\",\"how\":\"/steps/send/outcome\"}}}"));
        for (JsonNode t : branchUse.path("transitions")) {
            if ("s1".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "tell");
            }
        }
        ((ArrayNode) branchUse.path("transitions")).add(MAPPER.readTree("{\"id\":\"s2\",\"from\":\"tell\",\"to\":\"manager\",\"on\":\"next\"}"));
        assertThat(codes(branchUse)).isEmpty();
        String wrong = branchUse.toString().replace("/steps/send/rowCount", "/steps/send/rows");
        assertThat(codes((ObjectNode) MAPPER.readTree(wrong))).contains("WF_ADAPTER_INPUT_UNKNOWN");
    }

    @Test
    void theSimulatorDescribesTheMessageAndSendsNothing() throws Exception {
        SimulationContext base = SimulatorFixtures.context();
        SimulationContext withAdapters = new SimulationContext(base.workspace().withAdapters(ADAPTERS), base.childDefinitions(),
                base.today());
        SimulationResult result = Compilers.workflowSimulator().simulate(definition(EMAIL).toString(),
                "{\"title\":\"Laptop\",\"purpose\":\"Replacement\",\"costCenter\":\"IT\",\"amount\":100}", "{}", withAdapters);
        assertThat(result.hasTrace()).as(result.blockers().toString()).isTrue();
        assertThat(result.json()).contains("This test sends nothing", "an email to 1 fixed recipient", "New request Laptop",
                "\"outcome\":\"stubbed\"");
    }
}
