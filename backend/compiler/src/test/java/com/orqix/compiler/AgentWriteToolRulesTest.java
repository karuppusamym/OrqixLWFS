package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IN04 (S75): an agent step whose operation declares write tools publishes only when each tool is approved for this
 * workflow, connection version, operation and tool, and when its adapter exists, is enabled, has its kind and approves
 * every fixed recipient. Declaring a tool or having the adapter authorizes nothing.
 */
class AgentWriteToolRulesTest {

    private static final String KEY = "vendor-risk-assistant";
    private static final CompileContext.AdapterInfo MAIL = new CompileContext.AdapterInfo("procurement-mail", "email",
            "Procurement mail", true, "{\"from\":\"noreply@example.com\",\"allowedRecipientDomains\":[\"example.com\"],"
                    + "\"maxRecipients\":2}");
    private static final CompileContext.WriteToolApproval APPROVED = new CompileContext.WriteToolApproval(
            "vendor-onboarding-check", KEY, 1, "assessVendor", "notify_procurement");

    private static ObjectNode connection(String to, String adapter, String kind, String name) {
        ObjectNode definition = (ObjectNode) TestSupport.json("examples/connections/vendor-risk-assistant.connection.json");
        ObjectNode tool = ((ObjectNode) definition.path("operations").path("assessVendor").path("agent"))
                .putArray("writeTools").addObject();
        tool.put("name", name).put("label", "Tell procurement").put("description", "Emails the procurement team a note.")
                .put("adapter", adapter).put("kind", kind);
        if (to != null) {
            tool.putArray("to").add(to);
        }
        return definition;
    }

    private static CompileContext context(ObjectNode connection, Map<String, CompileContext.AdapterInfo> adapters,
            Set<CompileContext.WriteToolApproval> approvals) {
        String text = connection.toString();
        Map<String, CompileContext.GroupInfo> groups = new LinkedHashMap<>();
        for (JsonNode node : workflow().path("nodes")) {
            String group = node.path("config").path("candidateGroupRef").asString(null);
            if (group != null) {
                groups.put(group, new CompileContext.GroupInfo(group, group, 1));
            }
        }
        return new CompileContext(false, groups, List.of(new CompileContext.ConnectionVersion(KEY, 1, text,
                CanonicalJson.sha256Hex(text))), Set.of(KEY), Map.of(), adapters, approvals);
    }

    private static JsonNode workflow() {
        return TestSupport.json("examples/vendor-onboarding-check.json");
    }

    private static List<String> codes(CompileContext context) {
        return Compilers.workflowCompiler().validate(workflow().toString(), context).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).map(ValidationIssue::code).toList();
    }

    @Test
    void anApprovedToolWithAnAllowedFixedRecipientCompiles() {
        ObjectNode connection = connection("procurement@example.com", "procurement-mail", "email", "notify_procurement");
        assertThat(codes(context(connection, Map.of("procurement-mail", MAIL), Set.of(APPROVED)))).isEmpty();
        // The read-only example without write tools needs no approval at all.
        ObjectNode readOnly = (ObjectNode) TestSupport.json("examples/connections/vendor-risk-assistant.connection.json");
        assertThat(codes(context(readOnly, Map.of(), Set.of()))).isEmpty();
    }

    @Test
    void declaringAToolOrHavingTheAdapterAuthorizesNothing() {
        ObjectNode connection = connection("procurement@example.com", "procurement-mail", "email", "notify_procurement");
        assertThat(codes(context(connection, Map.of("procurement-mail", MAIL), Set.of())))
                .containsExactly("WF_AGENT_WRITE_TOOL_UNAPPROVED");
        // An approval for another workflow, connection version or tool does not count.
        for (CompileContext.WriteToolApproval other : List.of(
                new CompileContext.WriteToolApproval("other-flow", KEY, 1, "assessVendor", "notify_procurement"),
                new CompileContext.WriteToolApproval("vendor-onboarding-check", KEY, 2, "assessVendor", "notify_procurement"),
                new CompileContext.WriteToolApproval("vendor-onboarding-check", KEY, 1, "assessVendor", "notify_other"))) {
            assertThat(codes(context(connection, Map.of("procurement-mail", MAIL), Set.of(other))))
                    .containsExactly("WF_AGENT_WRITE_TOOL_UNAPPROVED");
        }
    }

    @Test
    void theAdapterMustExistMatchBeEnabledAndApproveEveryFixedRecipient() {
        Set<CompileContext.WriteToolApproval> approved = Set.of(APPROVED);
        assertThat(codes(context(connection("procurement@example.com", "procurement-mail", "email", "notify_procurement"),
                Map.of(), approved))).containsExactly("WF_AGENT_WRITE_TOOL_ADAPTER");
        CompileContext.AdapterInfo disabled = new CompileContext.AdapterInfo("procurement-mail", "email", "Procurement mail",
                false, MAIL.configJson());
        assertThat(codes(context(connection("procurement@example.com", "procurement-mail", "email", "notify_procurement"),
                Map.of("procurement-mail", disabled), approved))).containsExactly("WF_AGENT_WRITE_TOOL_ADAPTER");
        CompileContext.AdapterInfo teams = new CompileContext.AdapterInfo("procurement-mail", "teams", "Channel", true,
                "{\"baseUrl\":\"https://teams.example.com\"}");
        assertThat(codes(context(connection("procurement@example.com", "procurement-mail", "email", "notify_procurement"),
                Map.of("procurement-mail", teams), approved))).containsExactly("WF_AGENT_WRITE_TOOL_ADAPTER");
        assertThat(codes(context(connection("cfo@elsewhere.example.org", "procurement-mail", "email", "notify_procurement"),
                Map.of("procurement-mail", MAIL), approved))).containsExactly("WF_AGENT_WRITE_TOOL_RECIPIENT");
    }

    @Test
    void theDefinitionFormatKeepsTargetsOutOfTheModelsHands() {
        // A Teams tool may not name recipients; an email tool must; a tool may not share a read tool's name.
        assertThat(codes(context(connection("a@example.com", "procurement-mail", "teams", "notify_procurement"),
                Map.of(), Set.of()))).contains("WF_CONNECTION_UNKNOWN");
        assertThat(codes(context(connection(null, "procurement-mail", "email", "notify_procurement"), Map.of(), Set.of())))
                .contains("WF_CONNECTION_UNKNOWN");
        CompileContext.WriteToolApproval shadow = new CompileContext.WriteToolApproval("vendor-onboarding-check", KEY, 1,
                "assessVendor", "vendor_risk_lookup");
        assertThat(codes(context(connection("procurement@example.com", "procurement-mail", "email", "vendor_risk_lookup"),
                Map.of("procurement-mail", MAIL), Set.of(shadow)))).containsExactly("WF_AGENT_WRITE_TOOL_INVALID");
    }
}
