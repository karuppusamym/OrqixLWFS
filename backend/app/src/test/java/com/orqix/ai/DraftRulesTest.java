package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.orqix.ai.internal.DraftRules;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.shared.json.Json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Pure post-processing and classification rules (no Spring, no provider). */
class DraftRulesTest {

    private final Json json = new Json(JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build());
    private final DraftRules rules = new DraftRules(json, Compilers.workflowCompiler());

    @Test
    void keysAreDerivedFromNames() {
        assertThat(DraftRules.keyFromName("Expense claim (EMEA)")).isEqualTo("expense-claim-emea");
        assertThat(DraftRules.keyFromName("Überprüfung der Rechnung")).isEqualTo("uberprufung-der-rechnung");
        assertThat(DraftRules.keyFromName("2026 budget")).isEqualTo("wf-2026-budget");
        assertThat(DraftRules.keyFromName("!!!")).isEqualTo("ai-draft");
        assertThat(DraftRules.keyFromName("a".repeat(80))).hasSize(56);
    }

    @Test
    void postProcessingForcesDenyRemovesUnknownDependenciesAndClassifiesBindingIssues() throws Exception {
        ObjectNode definition = example();
        definition.putObject("security").put("selfApproval", "allow");
        ArrayNode connections = (ArrayNode) definition.path("dependencies").path("connections");
        connections.addObject().put("key", "jira").put("definitionVersion", 1);
        ((ObjectNode) connections.get(0)).put("definitionVersion", 9);
        definition.remove("execution");
        definition.remove("triggers");
        ((ObjectNode) definition.path("nodes").get(1).path("config")).put("dueAfterHours", -1);
        ((ObjectNode) definition.path("inputSchema").path("properties").path("title")).put("maxLength", -1).put("minLength", -1);
        ObjectNode envelope = json.object();
        envelope.set("definition", definition);
        envelope.putArray("assumptions").add("  One   assumption \n with spaces ").add("");

        DraftRules.Evaluation evaluation = rules.evaluate("Here you go:\n```json\n" + json.write(envelope) + "\n```",
                "Spend request", workspace(false));

        assertThat(evaluation.status()).isEqualTo(DraftRules.Status.VALID);
        ObjectNode result = evaluation.definition();
        assertThat(result.path("security").path("selfApproval").asString()).isEqualTo("deny");
        assertThat(result.path("workflowKey").asString()).isEqualTo("spend-request");
        assertThat(result.path("dependencies").path("connections").toString())
                .isEqualTo("[{\"key\":\"procurement\",\"definitionVersion\":1}]");
        assertThat(result.path("execution").path("maxAttempts").asInt()).isEqualTo(3);
        assertThat(result.path("nodes").get(1).path("config").has("dueAfterHours")).isFalse();
        assertThat(result.path("inputSchema").path("properties").path("title").has("maxLength")).isFalse();
        assertThat(result.path("inputSchema").path("properties").path("title").has("minLength")).isFalse();
        assertThat(result.has("uiLayout")).isFalse();
        assertThat(evaluation.assumptions()).containsExactly("One assumption with spaces");
        // The procurement binding is not enabled in this context: a missing-configuration item, not a repair.
        assertThat(evaluation.missing()).extracting(DraftRules.Missing::code).containsExactly("WF_CONNECTION_UNBOUND");
        assertThat(evaluation.missing().get(0).nodeId()).isEqualTo("procure");
    }

    @Test
    void unparseableAndBrokenRepliesNeedRepair() throws Exception {
        DraftRules.Evaluation garbage = rules.evaluate("I cannot do that", null, workspace(true));
        assertThat(garbage.status()).isEqualTo(DraftRules.Status.REPAIR);
        assertThat(garbage.repairable().get(0).code()).isEqualTo("WF_SCHEMA_INVALID");

        ObjectNode definition = example();
        ((ObjectNode) definition.path("nodes").get(1)).put("label", "Approve ${evil}");
        ObjectNode envelope = json.object();
        envelope.set("definition", definition);
        DraftRules.Evaluation broken = rules.evaluate(json.write(envelope), null, workspace(true));
        assertThat(broken.status()).isEqualTo(DraftRules.Status.REPAIR);
        assertThat(broken.repairable()).extracting(i -> i.code()).contains("WF_LABEL_EXPRESSION");
        assertThat(DraftRules.repairMessage(broken.repairable())).contains("WF_LABEL_EXPRESSION", "step manager");

        DraftRules.Evaluation declined = rules.evaluate("{\"definition\": null, \"notAWorkflow\": \"A poem.\"}", null,
                workspace(true));
        assertThat(declined.status()).isEqualTo(DraftRules.Status.NOT_A_WORKFLOW);
        assertThat(declined.notAWorkflow()).isEqualTo("A poem.");
    }

    @Test
    void workspaceContextHasSchemasButNoBindingsOrMembers() throws Exception {
        String context = rules.workspaceContext(workspace(true));
        JsonNode parsed = json.parse(context);
        assertThat(parsed.path("groups").findValuesAsString("key")).containsExactly("managers", "finance-approvers");
        assertThat(context).contains("createOrder", "requestSchema", "responseSchema");
        assertThat(context).doesNotContain("\"path\"", "\"method\"", "http");
    }

    private DraftRules.Workspace workspace(boolean bound) throws Exception {
        JsonNode connection = json.parse(Files.readString(repo().resolve("examples/connections/procurement.connection.json")));
        CompileContext context = new CompileContext(false,
                Map.of("managers", new CompileContext.GroupInfo("managers", "Managers", 2),
                        "finance-approvers", new CompileContext.GroupInfo("finance-approvers", "Finance approvers", 1)),
                List.of(new CompileContext.ConnectionVersion("procurement", 1, json.write(connection),
                        Json.canonicalSha256(json.write(connection)))),
                bound ? Set.of("procurement") : Set.of());
        return new DraftRules.Workspace(List.of(new DraftRules.GroupFact("managers", "Managers"),
                new DraftRules.GroupFact("finance-approvers", "Finance approvers")),
                List.of(new DraftRules.ConnectionFact("procurement", 1, "Procurement service", connection)), context);
    }

    private ObjectNode example() throws Exception {
        ObjectNode definition = (ObjectNode) json.parse(Files.readString(repo().resolve("examples/purchase-approval.json")));
        definition.remove("uiLayout");
        return definition;
    }

    private static Path repo() {
        return Path.of(System.getProperty("orqix.repoRoot", "../.."));
    }
}
