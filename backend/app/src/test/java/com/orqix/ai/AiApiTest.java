package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.orqix.support.IntegrationTestBase;
import com.orqix.support.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S10 section 8 against a WireMock-stubbed OpenRouter: real PostgreSQL (runtime role), real compiler, real policy;
 * every response validated against contracts/openapi.yaml. The provider replies are scripted per test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiApiTest extends IntegrationTestBase {

    private static final String DRAFTS = "/workspaces/{ws}/ai/drafts";
    private static final String ECONOMY_MODEL = "deepseek/deepseek-v3.2";

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("orqix.ai.api-key", () -> OpenRouterStub.KEY);
        registry.add("orqix.ai.base-url", OpenRouterStub::baseUrl);
        registry.add("orqix.ai.timeout", () -> "3s");
        registry.add("orqix.ai.generation-deadline", () -> "4s");
        registry.add("orqix.ai.profiles.premium.model", () -> "openai/gpt-pricey");
        registry.add("orqix.ai.profiles.premium.label", () -> "Premium");
        registry.add("orqix.ai.profiles.banned.model", () -> "anthropic/claude-opus-9");
        registry.add("orqix.ai.profiles.plain.model", () -> "plain/json-only");
        registry.add("orqix.ai.profiles.plain.label", () -> "Plain JSON");
        // This context only serves AI requests; the shared context runs the job executor.
        registry.add("flowable.async-executor-activate", () -> "false");
    }

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetStub() {
        OpenRouterStub.reset();
    }

    // ---------------------------------------------------------------- success, post-processing, metering, replay

    @Test
    @Order(1)
    void statusListsOnlyUsableProfilesWithPolicyAndBudget() throws Exception {
        Response status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING);
        assertThat(status.status()).as(status.toString()).isEqualTo(200);
        JsonNode body = status.body();
        assertThat(body.path("enabled").asBoolean()).isTrue();
        assertThat(body.path("reason").isNull() || body.path("reason").isMissingNode()).isTrue();
        assertThat(body.path("defaultProfile").asString()).isEqualTo("economy");
        assertThat(body.path("profiles").findValuesAsString("id")).containsExactlyInAnyOrder("economy", "auto", "plain");
        for (JsonNode profile : body.path("profiles")) {
            assertThat(profile.path("variablePrice").asBoolean()).as(profile.toString())
                    .isEqualTo("auto".equals(profile.path("id").asString()));
        }
        assertThat(body.path("excludedModels").toString()).contains("anthropic/claude-opus-*", "*:batch");
        assertThat(body.path("maxPrice").path("promptPerMillion").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(body.path("budget").path("monthlyUsd").decimalValue()).isEqualByComparingTo("10.00");
        // Workspace administrators read it for Settings; requesters without workflow access cannot.
        assertThat(api("wade.admin").get("/workspaces/{ws}/ai/status", PURCHASING).status()).isEqualTo(200);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/ai/status", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/ai/status", PURCHASING).status()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void generatesValidatedDraftWithPostProcessingMeteringAndIdempotentReplay() throws Exception {
        ObjectNode definition = example();
        definition.putObject("security").put("selfApproval", "allow");
        ((ArrayNode) definition.path("dependencies").path("connections")).addObject().put("key", "jira")
                .put("definitionVersion", 1);
        ((ObjectNode) node(definition, "finance").get("config")).put("candidateGroupRef", "legal-reviewers");
        OpenRouterStub.reply(envelope(definition, List.of("Finance reviews purchases of USD 5,000 or more."),
                List.of(Map.of("message", "Create the legal-reviewers group for the review.", "nodeId", "finance"),
                        Map.of("message", "Agree the cost centers with Finance.", "nodeId", "nope"))),
                ECONOMY_MODEL, 0.0021);
        String description = "Secret project Falcon: purchases need a manager approval and a legal review above 5000.";
        Map<String, Object> body = Map.of("description", description, "name", "Falcon purchases");
        String key = UUID.randomUUID().toString();

        Response created = api("dana.designer").post(body, key, DRAFTS, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode result = created.body();
        UUID workflowId = UUID.fromString(result.path("workflow").path("id").asString());
        assertThat(result.path("workflow").path("key").asString()).startsWith("falcon-purchases");
        assertThat(result.path("workflow").path("name").asString()).isEqualTo("Falcon purchases");
        assertThat(result.path("workflow").path("activeRelease").isNull()).isTrue();
        JsonNode draft = result.path("draft");
        assertThat(draft.path("revision").asInt()).isEqualTo(1);
        JsonNode stored = draft.path("definition");
        assertThat(stored.path("security").path("selfApproval").asString()).isEqualTo("deny");
        assertThat(stored.path("dependencies").path("connections").findValuesAsString("key"))
                .containsExactly("procurement");
        assertThat(stored.path("uiLayout").path("positions").has("finance")).isTrue();
        assertThat(result.path("report").path("publishable").asBoolean()).isFalse();
        JsonNode missing = result.path("missingConfiguration");
        assertThat(missing.toString()).contains("WF_GROUP_UNBOUND", "legal-reviewers");
        assertThat(missing.findValuesAsString("nodeId")).contains("finance");
        // The model's duplicate of the group finding is dropped; an item for an unknown step keeps no nodeId.
        assertThat(missing.toString().split("legal-reviewers", -1)).hasSize(2);
        assertThat(missing.toString()).contains("Agree the cost centers");
        assertThat(result.path("assumptions").get(0).asString()).contains("USD 5,000");
        JsonNode generation = result.path("generation");
        assertThat(generation.path("profile").asString()).isEqualTo("economy");
        assertThat(generation.path("model").asString()).isEqualTo(ECONOMY_MODEL);
        assertThat(generation.path("promptVersion").asString()).isEqualTo("draft-v1");
        assertThat(generation.path("attempts").asInt()).isEqualTo(1);
        assertThat(generation.path("escalated").asBoolean()).isFalse();
        assertThat(generation.path("costUsd").decimalValue()).isEqualByComparingTo("0.0021");
        assertThat(generation.path("inputTokens").asLong()).isEqualTo(1200);

        // What reached the provider: price caps, usage accounting, structured envelope, workspace facts only.
        assertThat(OpenRouterStub.requests()).hasSize(1);
        JsonNode sent = OpenRouterStub.requests().get(0);
        assertThat(sent.path("model").asString()).isEqualTo(ECONOMY_MODEL);
        assertThat(sent.path("provider").path("sort").asString()).isEqualTo("throughput");
        assertThat(sent.path("provider").path("max_price").path("prompt").decimalValue()).isEqualByComparingTo("1");
        assertThat(sent.path("provider").path("max_price").path("completion").decimalValue())
                .isEqualByComparingTo("3");
        assertThat(sent.path("usage").path("include").asBoolean()).isTrue();
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_schema");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(8000);
        String system = sent.path("messages").get(0).path("content").asString();
        assertThat(system).contains("workflow-v0", "purchase-approval", "selfApproval");
        String user = sent.path("messages").get(1).path("content").asString();
        assertThat(user).contains(description, "finance-approvers", "createOrder");
        assertThat(user).doesNotContain("localhost:8095", "http://", "secretRef", "members");
        assertThat(OpenRouterStub.headers().get(0).get("authorization")).isEqualTo("Bearer " + OpenRouterStub.KEY);

        // Metering and audit hold no description, prompt or output text.
        String row = jdbc.sql("SELECT to_jsonb(g)::text FROM ai_generation g WHERE workflow_id = :wf")
                .param("wf", workflowId).query(String.class).single();
        assertThat(row).contains("\"outcome\": \"success\"", ECONOMY_MODEL, "draft-v1");
        assertThat(row).doesNotContain("Falcon", "Secret", "legal");
        String audit = jdbc.sql("""
                SELECT metadata::text FROM audit_event WHERE action = 'ai.draft.generated' AND resource_id = :wf
                """).param("wf", workflowId.toString()).query(String.class).single();
        assertThat(audit).contains("\"costUsd\"", "\"model\"", "\"outcome\": \"success\"");
        assertThat(audit).doesNotContain("Falcon", "Secret", "legal", "description");
        assertThat(jdbc.sql("""
                SELECT metadata->>'source' FROM audit_event WHERE action = 'workflow.created' AND resource_id = :wf
                """).param("wf", workflowId.toString()).query(String.class).single()).isEqualTo("ai");

        // Idempotent replay: same workflow, review notes kept, no second provider call.
        Response replay = api("dana.designer").post(body, key, DRAFTS, PURCHASING);
        assertThat(replay.status()).as(replay.toString()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("workflow").path("id").asString()).isEqualTo(workflowId.toString());
        assertThat(replay.body().path("assumptions")).isEqualTo(result.path("assumptions"));
        assertThat(replay.body().path("generation").path("model").asString()).isEqualTo(ECONOMY_MODEL);
        assertThat(OpenRouterStub.requests()).hasSize(1);
        Response reused = api("dana.designer").post(Map.of("description", description + " Changed."), key, DRAFTS,
                PURCHASING);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        Response noKey = api("dana.designer").post(body, null, DRAFTS, PURCHASING);
        assertThat(noKey.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(OpenRouterStub.requests()).hasSize(1);
    }

    @Test
    @Order(3)
    void invalidJsonIsRepairedInASecondTurn() throws Exception {
        OpenRouterStub.reply("{\"definition\": {\"schemaVersion\": \"0.1\", oops", ECONOMY_MODEL, 0.001);
        OpenRouterStub.reply("```json\n" + envelope(example(), List.of(), List.of()) + "\n```", ECONOMY_MODEL, 0.002);
        Response created = api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.body().path("generation").path("attempts").asInt()).isEqualTo(2);
        assertThat(created.body().path("generation").path("costUsd").decimalValue()).isEqualByComparingTo("0.003");
        assertThat(created.body().path("workflow").path("name").asString()).isEqualTo("Purchase approval");
        JsonNode repair = OpenRouterStub.requests().get(1).path("messages");
        assertThat(repair).hasSize(4);
        assertThat(repair.get(2).path("role").asString()).isEqualTo("assistant");
        assertThat(repair.get(3).path("content").asString()).contains("WF_SCHEMA_INVALID", "not valid JSON");
    }

    @Test
    @Order(4)
    void blockersPersistingAfterRepairsEscalateOnceToTheRouter() throws Exception {
        ObjectNode broken = brokenExample();
        for (int i = 0; i < 3; i++) {
            OpenRouterStub.reply(envelope(broken, List.of(), List.of()), ECONOMY_MODEL, 0.001);
        }
        OpenRouterStub.reply(envelope(example(), List.of("Escalated."), List.of()), "openai/gpt-6-luna", 0.012);
        Response created = api("dana.designer").post(Map.of("description", "Purchase approval with finance review.",
                "name", "Escalated purchase"), UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode generation = created.body().path("generation");
        assertThat(generation.path("attempts").asInt()).isEqualTo(4);
        assertThat(generation.path("escalated").asBoolean()).isTrue();
        assertThat(generation.path("profile").asString()).isEqualTo("auto");
        assertThat(generation.path("model").asString()).isEqualTo("openai/gpt-6-luna");
        assertThat(generation.path("costUsd").decimalValue()).isEqualByComparingTo("0.015");
        List<JsonNode> requests = OpenRouterStub.requests();
        assertThat(requests).hasSize(4);
        assertThat(requests.subList(0, 3)).allSatisfy(r -> assertThat(r.path("model").asString()).isEqualTo(ECONOMY_MODEL));
        assertThat(requests.get(3).path("model").asString()).isEqualTo("typesafe/jev-router");
        assertThat(requests.get(1).path("messages").get(3).path("content").asString()).contains("WF_APPROVAL_ROUTES");
        // The escalation carries the original request, the last reply and its problems only.
        assertThat(requests.get(3).path("messages")).hasSize(4);
    }

    @Test
    @Order(5)
    void persistingFailureIs422AndCreatesNothing() throws Exception {
        long before = workflows();
        for (int i = 0; i < 4; i++) {
            OpenRouterStub.reply(envelope(brokenExample(), List.of(), List.of()), i < 3 ? ECONOMY_MODEL : "x/router-pick",
                    0.001);
        }
        Response failed = api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(failed.status()).as(failed.toString()).isEqualTo(422);
        assertThat(failed.code()).isEqualTo("AI_OUTPUT_INVALID");
        assertThat(failed.body().path("issues").findValuesAsString("code")).contains("WF_APPROVAL_ROUTES");
        assertThat(OpenRouterStub.requests()).hasSize(4);
        assertThat(workflows()).isEqualTo(before);
        Map<String, Object> row = jdbc.sql("""
                SELECT outcome, attempts, escalated, cost_usd FROM ai_generation
                WHERE kind = 'draft' ORDER BY created_at DESC LIMIT 1
                """).query().singleRow();
        assertThat(row.get("outcome")).isEqualTo("invalidOutput");
        assertThat(row.get("attempts")).isEqualTo(4);
        assertThat(row.get("escalated")).isEqualTo(true);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.004");
        assertThat(jdbc.sql("""
                SELECT outcome FROM audit_event WHERE action = 'ai.draft.generated' ORDER BY occurred_at DESC LIMIT 1
                """).query(String.class).single()).isEqualTo("failed");
    }

    @Test
    @Order(6)
    void notAWorkflowStopsWithoutRepairs() throws Exception {
        OpenRouterStub.reply("{\"definition\": null, \"assumptions\": [], \"missingConfiguration\": [], "
                + "\"notAWorkflow\": \"The text asks for a poem, not a business process.\"}", ECONOMY_MODEL, 0.0004);
        Response failed = api("dana.designer").post(Map.of("description", "Write me a poem about autumn leaves."),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(failed.code()).as(failed.toString()).isEqualTo("AI_OUTPUT_INVALID");
        assertThat(failed.body().path("detail").asString()).contains("poem");
        assertThat(OpenRouterStub.requests()).hasSize(1);
    }

    // ---------------------------------------------------------------- provider failures

    @Test
    @Order(7)
    void providerRateLimitServerErrorTimeoutAndCreditMapTo503() throws Exception {
        long before = workflows();
        OpenRouterStub.fail(429, Map.of("Retry-After", "17"));
        Response limited = draft("Purchase approval with finance review.");
        assertThat(limited.status()).as(limited.toString()).isEqualTo(503);
        assertThat(limited.code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(limited.header("Retry-After")).isEqualTo("17");

        OpenRouterStub.fail(500, Map.of());
        Response broken = draft("Purchase approval with finance review.");
        assertThat(broken.code()).as(broken.toString()).isEqualTo("AI_PROVIDER_UNAVAILABLE");

        OpenRouterStub.slow(envelope(example(), List.of(), List.of()), 5000);
        Response slow = draft("Purchase approval with finance review.");
        assertThat(slow.code()).as(slow.toString()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(slow.body().path("detail").asString()).contains("in time");

        OpenRouterStub.fail(402, Map.of());
        assertThat(draft("Purchase approval with finance review.").code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(workflows()).isEqualTo(before);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ai_generation WHERE outcome = 'providerError'
                """).query(Long.class).single()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @Order(12)
    void generationDeadlineStopsAttemptsThatCannotFinishInTime() throws Exception {
        long before = workflows();
        // 4 s budget: after a 2.5 s invalid answer, another attempt of that length cannot finish in time.
        OpenRouterStub.delayed(envelope(brokenExample(), List.of(), List.of()), ECONOMY_MODEL, 0.001, 2500);
        Response stopped = draft("Purchase approval with finance review.");
        assertThat(stopped.status()).as(stopped.toString()).isEqualTo(422);
        assertThat(stopped.code()).isEqualTo("AI_OUTPUT_INVALID");
        assertThat(stopped.body().path("detail").asString()).contains("time limit");
        assertThat(stopped.body().path("issues").findValuesAsString("code")).contains("WF_APPROVAL_ROUTES");
        assertThat(OpenRouterStub.requests()).hasSize(1);

        // The repair call is cut by the deadline (2.5 s left < 3 s call timeout): the model already answered, so 422.
        OpenRouterStub.reset();
        OpenRouterStub.delayed(envelope(brokenExample(), List.of(), List.of()), ECONOMY_MODEL, 0.001, 1500);
        OpenRouterStub.delayed(envelope(example(), List.of(), List.of()), ECONOMY_MODEL, 0.001, 6000);
        Response cut = draft("Purchase approval with finance review.");
        assertThat(cut.status()).as(cut.toString()).isEqualTo(422);
        assertThat(cut.body().path("detail").asString()).contains("time limit");
        assertThat(OpenRouterStub.requests()).hasSize(2);
        assertThat(workflows()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- model policy and budget

    @Test
    @Order(8)
    void excludedOverCapAndUnknownProfilesAreRefusedButJsonModeProfilesWork() throws Exception {
        for (String profile : List.of("premium", "banned", "nope")) {
            Response refused = api("dana.designer").post(Map.of("description", "Purchase approval with finance review.",
                    "profile", profile), UUID.randomUUID().toString(), DRAFTS, PURCHASING);
            assertThat(refused.code()).as(profile + " " + refused).isEqualTo("AI_MODEL_NOT_ALLOWED");
        }
        assertThat(OpenRouterStub.requests()).isEmpty();
        OpenRouterStub.reply(envelope(example(), List.of(), List.of()), "plain/json-only", 0.0001);
        Response created = api("dana.designer").post(Map.of("description", "Purchase approval with finance review.",
                "profile", "plain"), UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(OpenRouterStub.requests().get(0).path("response_format").path("type").asString())
                .isEqualTo("json_object");
    }

    @Test
    @Order(9)
    void exhaustedMonthlyBudgetIs429AndDisablesAi() throws Exception {
        UUID rowId = UUID.randomUUID();
        UUID subject = jdbc.sql("SELECT id FROM subject WHERE external_subject = 'kc-dana.designer'")
                .query(UUID.class).single();
        superuser("""
                INSERT INTO orqix.ai_generation (id, workspace_id, subject_id, kind, profile, model, requested_model,
                    prompt_version, attempts, input_tokens, output_tokens, cost_usd, duration_ms, outcome)
                VALUES (?, ?, ?, 'draft', 'economy', 'deepseek/deepseek-v3.2', 'deepseek/deepseek-v3.2', 'draft-v1',
                    1, 1, 1, 10.00, 1, 'success')
                """, rowId, SANDBOX, subject);
        try {
            Response refused = draft("Purchase approval with finance review.");
            assertThat(refused.status()).as(refused.toString()).isEqualTo(429);
            assertThat(refused.code()).isEqualTo("AI_BUDGET_EXCEEDED");
            assertThat(Long.parseLong(refused.header("Retry-After"))).isPositive();
            JsonNode status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING).body();
            assertThat(status.path("enabled").asBoolean()).isFalse();
            assertThat(status.path("reason").asString()).contains("budget");
            assertThat(status.path("budget").path("spentUsd").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.TEN);
            assertThat(OpenRouterStub.requests()).isEmpty();
        } finally {
            superuser("DELETE FROM orqix.ai_generation WHERE id = ?", rowId);
        }
    }

    // ---------------------------------------------------------------- explanation and permissions

    @Test
    @Order(10)
    void explainsADraftRevisionInBusinessLanguage() throws Exception {
        Response created = api("dana.designer").post(Map.of("key", "explain-" + UUID.randomUUID().toString()
                .substring(0, 8), "name", "Explain me", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String workflowId = created.body().path("id").asString();
        OpenRouterStub.reply("{\"summary\": \"Employees ask to buy things; a manager approves.\", \"steps\": ["
                + "{\"nodeId\": \"start\", \"text\": \"The requester fills in the form.\"},"
                + "{\"nodeId\": \"manager\", \"text\": \"Someone in the managers group decides.\"},"
                + "{\"nodeId\": \"bogus\", \"text\": \"Invented.\"}]}", ECONOMY_MODEL, 0.0003);
        Response explained = api("vic.viewer").post(Map.of("revision", 1),
                "/workspaces/{ws}/workflows/{id}/explain", PURCHASING, workflowId);
        assertThat(explained.status()).as(explained.toString()).isEqualTo(200);
        assertThat(explained.body().path("summary").asString()).contains("manager approves");
        assertThat(explained.body().path("steps").findValuesAsString("nodeId")).containsExactly("start", "manager");
        assertThat(explained.body().path("generation").path("promptVersion").asString()).isEqualTo("explain-v1");
        JsonNode sent = OpenRouterStub.requests().get(0);
        String user = sent.path("messages").get(1).path("content").asString();
        assertThat(user).contains("<workflow>", "amount-policy").doesNotContain("uiLayout");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(4000);
        assertThat(jdbc.sql("""
                SELECT metadata->>'revision' FROM audit_event WHERE action = 'ai.workflow.explained'
                  AND resource_id = :wf
                """).param("wf", workflowId).query(String.class).single()).isEqualTo("1");
        // No release yet: explaining the active release is a 422 without a provider call.
        Response noRelease = api("vic.viewer").post(Map.of(), "/workspaces/{ws}/workflows/{id}/explain", PURCHASING,
                workflowId);
        assertThat(noRelease.code()).isEqualTo("NO_ACTIVE_RELEASE");
        assertThat(api("dana.designer").post(Map.of("revision", 7), "/workspaces/{ws}/workflows/{id}/explain",
                PURCHASING, workflowId).status()).isEqualTo(404);
        assertThat(OpenRouterStub.requests()).hasSize(1);
    }

    @Test
    @Order(11)
    void permissionsAndRequestValidationComeBeforeAnyProviderCall() throws Exception {
        Map<String, Object> body = Map.of("description", "Purchase approval with finance review.");
        assertThat(api("vic.viewer").post(body, UUID.randomUUID().toString(), DRAFTS, PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("otto.outsider").post(body, UUID.randomUUID().toString(), DRAFTS, PURCHASING).status())
                .isEqualTo(404);
        assertThat(api("dana.designer").post(Map.of("description", "short"), UUID.randomUUID().toString(), DRAFTS,
                PURCHASING).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(api("dana.designer").post(Map.of("description", "Purchase approval with finance.", "extra", 1),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(api("dana.designer").post(Map.of("description", "Purchase approval with finance.", "profile",
                "Bad Profile"), UUID.randomUUID().toString(), DRAFTS, PURCHASING).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(api("dana.designer").post(Map.of("description", "Purchase approval with finance.", "name",
                "Bad ${name}"), UUID.randomUUID().toString(), DRAFTS, PURCHASING).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(OpenRouterStub.requests()).isEmpty();
    }

    /** Last: rejected credentials disable AI in this context for the re-check interval. */
    @Test
    @Order(99)
    void rejectedCredentialsReportAiAsDisabled() throws Exception {
        OpenRouterStub.fail(401, Map.of());
        Response rejected = draft("Purchase approval with finance review.");
        assertThat(rejected.status()).as(rejected.toString()).isEqualTo(422);
        assertThat(rejected.code()).isEqualTo("AI_NOT_CONFIGURED");
        JsonNode status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING).body();
        assertThat(status.path("enabled").asBoolean()).isFalse();
        assertThat(status.path("reason").asString()).contains("rejected");
        assertThat(draft("Purchase approval with finance review.").code()).isEqualTo("AI_NOT_CONFIGURED");
        assertThat(OpenRouterStub.requests()).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private Response draft(String description) throws Exception {
        return api("dana.designer").post(Map.of("description", description), UUID.randomUUID().toString(), DRAFTS,
                PURCHASING);
    }

    private long workflows() {
        return jdbc.sql("SELECT count(*) FROM workflow").query(Long.class).single();
    }

    private ObjectNode example() throws Exception {
        ObjectNode definition = (ObjectNode) json.parse(Files.readString(Path.of(
                System.getProperty("orqix.repoRoot", "../.."), "examples", "purchase-approval.json")));
        definition.remove("uiLayout");
        return definition;
    }

    /** The example without the manager's rejected route: WF_APPROVAL_ROUTES, which the model must repair. */
    private ObjectNode brokenExample() throws Exception {
        ObjectNode definition = example();
        ArrayNode transitions = (ArrayNode) definition.get("transitions");
        for (int i = 0; i < transitions.size(); i++) {
            if ("t3".equals(transitions.get(i).path("id").asString())) {
                transitions.remove(i);
                break;
            }
        }
        return definition;
    }

    private static ObjectNode node(ObjectNode definition, String id) {
        for (JsonNode node : definition.path("nodes")) {
            if (id.equals(node.path("id").asString())) {
                return (ObjectNode) node;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private String envelope(JsonNode definition, List<String> assumptions, List<Map<String, String>> missing) {
        ObjectNode envelope = json.object();
        envelope.set("definition", definition);
        ArrayNode notes = envelope.putArray("assumptions");
        assumptions.forEach(notes::add);
        ArrayNode items = envelope.putArray("missingConfiguration");
        missing.forEach(m -> items.add(json.toTree(m)));
        envelope.putNull("notAWorkflow");
        return json.write(envelope);
    }

    private static void superuser(String sql, Object... parameters) throws Exception {
        var container = TestDatabase.container();
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }
}
