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
    @Autowired
    private com.orqix.ai.internal.AiJobStore jobStore;
    @Autowired
    private com.orqix.identity.PolicyService policy;

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

        Draft created = postDraft("dana.designer", body, key);
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

        // Idempotent replay: the same job (now succeeded), review notes kept, no second provider call.
        Response replay = api("dana.designer").post(body, key, DRAFTS, PURCHASING);
        assertThat(replay.status()).as(replay.toString()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("id").asString()).isEqualTo(created.jobId());
        assertThat(replay.body().path("status").asString()).isEqualTo("succeeded");
        assertThat(replay.body().path("result").path("workflow").path("id").asString()).isEqualTo(workflowId.toString());
        assertThat(replay.body().path("result").path("assumptions")).isEqualTo(result.path("assumptions"));
        assertThat(replay.body().path("result").path("missingConfiguration")).isEqualTo(result.path("missingConfiguration"));
        assertThat(replay.body().path("result").path("generation").path("model").asString()).isEqualTo(ECONOMY_MODEL);
        assertThat(OpenRouterStub.requests()).hasSize(1);
        Response reused = api("dana.designer").post(Map.of("description", description + " Changed."), key, DRAFTS, PURCHASING);
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
        Draft created = postDraft("dana.designer", Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString());
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
        Draft created = postDraft("dana.designer", Map.of("description", "Purchase approval with finance review.",
                "name", "Escalated purchase"), UUID.randomUUID().toString());
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
        Draft failed = postDraft("dana.designer", Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString());
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
        Draft failed = postDraft("dana.designer", Map.of("description", "Write me a poem about autumn leaves."),
                UUID.randomUUID().toString());
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
        Draft limited = draft("Purchase approval with finance review.");
        assertThat(limited.status()).as(limited.toString()).isEqualTo(503);
        assertThat(limited.code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");

        OpenRouterStub.fail(500, Map.of());
        Draft broken = draft("Purchase approval with finance review.");
        assertThat(broken.code()).as(broken.toString()).isEqualTo("AI_PROVIDER_UNAVAILABLE");

        OpenRouterStub.slow(envelope(example(), List.of(), List.of()), 5000);
        Draft slow = draft("Purchase approval with finance review.");
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
        Draft stopped = draft("Purchase approval with finance review.");
        assertThat(stopped.status()).as(stopped.toString()).isEqualTo(422);
        assertThat(stopped.code()).isEqualTo("AI_OUTPUT_INVALID");
        assertThat(stopped.body().path("detail").asString()).contains("time limit");
        assertThat(stopped.body().path("issues").findValuesAsString("code")).contains("WF_APPROVAL_ROUTES");
        assertThat(OpenRouterStub.requests()).hasSize(1);

        // The repair call is cut by the deadline (2.5 s left < 3 s call timeout): the model already answered, so 422.
        OpenRouterStub.reset();
        OpenRouterStub.delayed(envelope(brokenExample(), List.of(), List.of()), ECONOMY_MODEL, 0.001, 1500);
        OpenRouterStub.delayed(envelope(example(), List.of(), List.of()), ECONOMY_MODEL, 0.001, 6000);
        Draft cut = draft("Purchase approval with finance review.");
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
            Draft refused = postDraft("dana.designer", Map.of("description", "Purchase approval with finance review.",
                    "profile", profile), UUID.randomUUID().toString());
            assertThat(refused.code()).as(profile + " " + refused).isEqualTo("AI_MODEL_NOT_ALLOWED");
        }
        assertThat(OpenRouterStub.requests()).isEmpty();
        OpenRouterStub.reply(envelope(example(), List.of(), List.of()), "plain/json-only", 0.0001);
        Draft created = postDraft("dana.designer", Map.of("description", "Purchase approval with finance review.",
                "profile", "plain"), UUID.randomUUID().toString());
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
            Draft refused = draft("Purchase approval with finance review.");
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
        assertThat(explained.body().path("generation").path("promptVersion").asString()).isEqualTo("explain-v2");
        assertThat(explained.body().path("validation").has("publishable")).isTrue();
        JsonNode sent = OpenRouterStub.requests().get(0);
        String user = sent.path("messages").get(1).path("content").asString();
        assertThat(user).contains("<workflow>", "amount-policy", "<validation>", "publishable").doesNotContain("uiLayout");
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

    // ---------------------------------------------------------------- form suggestions (S90, G4)

    private String formWorkflow() throws Exception {
        Response created = api("dana.designer").post(Map.of("key", "form-" + UUID.randomUUID().toString()
                .substring(0, 8), "name", "Form help", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body().path("id").asString();
    }

    @Test
    @Order(20)
    void suggestsValidatedFormChangesWithoutApplyingStoringOrLoggingContent() throws Exception {
        String workflowId = formWorkflow();
        JsonNode form = example().get("inputSchema");
        String before = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflowId).body()
                .path("definition").toString();
        OpenRouterStub.reply("""
                {"changes": [
                  {"op": "add", "key": "costCode", "field": {"type": "string", "title": "Cost code", "maxLength": 7,
                    "pattern": "^[A-Z]{2}-\\\\d{4}$", "patternMessage": "Enter a code like AB-1234."}, "required": true,
                    "reason": "Finance books purchases by cost code."},
                  {"op": "update", "key": "amount", "field": {"type": "number", "title": "Amount (USD)", "minimum": 10,
                    "maximum": 1, "multipleOf": 0.01}, "required": null, "reason": "Raise the minimum."}],
                 "notes": ["Assumed codes have two letters."], "notAForm": null}""", ECONOMY_MODEL, 0.0004);
        String description = "Secret Falcon: add a cost code question like AB-1234 and raise the minimum amount.";
        Response suggested = api("dana.designer").post(Map.of("description", description, "inputSchema", form),
                "/workspaces/{ws}/workflows/{id}/ai/form-suggestions", PURCHASING, workflowId);
        assertThat(suggested.status()).as(suggested.toString()).isEqualTo(200);
        JsonNode body = suggested.body();
        assertThat(body.path("changes")).hasSize(1);
        assertThat(body.path("changes").get(0).path("key").asString()).isEqualTo("costCode");
        assertThat(body.path("changes").get(0).path("field").path("pattern").asString()).isEqualTo("^[A-Z]{2}-\\d{4}$");
        assertThat(body.path("rejected").get(0).path("key").asString()).isEqualTo("amount");
        assertThat(body.path("rejected").get(0).path("reasons").toString()).contains("minimum greater than its maximum");
        assertThat(body.path("notes").get(0).asString()).contains("two letters");
        assertThat(body.path("generation").path("promptVersion").asString()).isEqualTo("form-v1");
        // The prompt holds the form and the fenced description; nothing else from the workspace.
        JsonNode sent = OpenRouterStub.requests().get(0);
        String user = sent.path("messages").get(1).path("content").asString();
        assertThat(user).contains("<form>", "costCenter", "<description>", "Secret Falcon");
        // Never applied: the draft is unchanged.
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflowId).body()
                .path("definition").toString()).isEqualTo(before);
        // Metering and audit hold counts only, never the description, form or suggested fields.
        String audit = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'ai.form.suggested' AND resource_id = :wf")
                .param("wf", workflowId).query(String.class).single();
        assertThat(audit).contains("\"changesSuggested\": 1", "\"changesRejected\": 1").doesNotContain("Falcon", "costCode",
                "AB-1234");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_generation WHERE kind = 'form' AND workflow_id = :wf")
                .param("wf", UUID.fromString(workflowId)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Order(21)
    void formSuggestionsNeedEditingAccessAndAValidBodyBeforeAnyProviderCall() throws Exception {
        String workflowId = formWorkflow();
        JsonNode form = example().get("inputSchema");
        String path = "/workspaces/{ws}/workflows/{id}/ai/form-suggestions";
        Map<String, Object> body = Map.of("description", "Add a cost code question.", "inputSchema", form);
        assertThat(api("vic.viewer").post(body, path, PURCHASING, workflowId).status()).isEqualTo(403);
        assertThat(api("otto.outsider").post(body, path, PURCHASING, workflowId).status()).isEqualTo(404);
        assertThat(api("dana.designer").post(body, path, PURCHASING, UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(api("dana.designer").post(Map.of("description", "short", "inputSchema", form), path, PURCHASING,
                workflowId).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(api("dana.designer").post(Map.of("description", "Add a cost code question."), path, PURCHASING,
                workflowId).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(api("dana.designer").post(Map.of("description", "Add a cost code question.", "inputSchema", "x"),
                path, PURCHASING, workflowId).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(OpenRouterStub.requests()).isEmpty();
        // Unusable model output twice: 422 AI_OUTPUT_INVALID, nothing changed; a provider outage: 503.
        OpenRouterStub.reply("not json", ECONOMY_MODEL, 0.0001);
        OpenRouterStub.reply("still not json", ECONOMY_MODEL, 0.0001);
        assertThat(api("dana.designer").post(body, path, PURCHASING, workflowId).code()).isEqualTo("AI_OUTPUT_INVALID");
        OpenRouterStub.fail(500, Map.of());
        OpenRouterStub.fail(500, Map.of());
        Response outage = api("dana.designer").post(body, path, PURCHASING, workflowId);
        assertThat(outage.status()).as(outage.toString()).isEqualTo(503);
    }

    @Test
    @Order(11)
    void permissionsAndRequestValidationComeBeforeAnyProviderCall() throws Exception {
        Map<String, Object> body = Map.of("description", "Purchase approval with finance review.");
        assertThat(postDraft("vic.viewer", body, UUID.randomUUID().toString()).status())
                .isEqualTo(403);
        assertThat(postDraft("otto.outsider", body, UUID.randomUUID().toString()).status())
                .isEqualTo(404);
        assertThat(postDraft("dana.designer", Map.of("description", "short"), UUID.randomUUID().toString()).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(postDraft("dana.designer", Map.of("description", "Purchase approval with finance.", "extra", 1),
                UUID.randomUUID().toString()).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(postDraft("dana.designer", Map.of("description", "Purchase approval with finance.", "profile",
                "Bad Profile"), UUID.randomUUID().toString()).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(postDraft("dana.designer", Map.of("description", "Purchase approval with finance.", "name",
                "Bad ${name}"), UUID.randomUUID().toString()).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(OpenRouterStub.requests()).isEmpty();
    }

    @Test
    @Order(13)
    void answersWith202AndLocationAndAnyPodCanBePolled() throws Exception {
        OpenRouterStub.delayed(envelope(example(), List.of("One assumption."), List.of()), ECONOMY_MODEL, 0.002, 1200);
        String key = UUID.randomUUID().toString();
        Response accepted = api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                key, DRAFTS, PURCHASING);
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
        String id = accepted.body().path("id").asString();
        assertThat(accepted.header("Location")).endsWith("/ai/generations/" + id);
        assertThat(accepted.body().path("status").asString()).isIn("queued", "running");
        assertThat(accepted.body().path("profile").asString()).isEqualTo("economy");
        assertThat(accepted.body().has("result")).isFalse();
        // A replay while it runs returns the job as it is now (200), without a second provider call.
        Response replay = api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                key, DRAFTS, PURCHASING);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("id").asString()).isEqualTo(id);
        // Polling reads the database: the creator, another designer and nobody else.
        JsonNode done = await("dana.designer", id);
        assertThat(done.path("status").asString()).isEqualTo("succeeded");
        assertThat(done.path("finishedAt").isString()).isTrue();
        assertThat(done.path("result").path("generation").path("costUsd").decimalValue()).isEqualByComparingTo("0.002");
        assertThat(api("dana.designer").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api("vic.viewer").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, id).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, UUID.randomUUID()).status())
                .isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/{ws}/ai/generations/{id}", SANDBOX, id).status()).isEqualTo(404);
        assertThat(OpenRouterStub.requests()).hasSize(1);
        // The job row holds no description and no model output beyond the review notes.
        String row = jdbc.sql("SELECT to_jsonb(j)::text FROM ai_generation_job j WHERE id = :id")
                .param("id", UUID.fromString(id)).query(String.class).single();
        assertThat(row).doesNotContain("finance review").doesNotContain("Purchase approval with");
    }

    @Test
    @Order(14)
    void limitsConcurrentJobsPerPerson() throws Exception {
        OpenRouterStub.slow(envelope(example(), List.of(), List.of()), 1500);
        OpenRouterStub.slow(envelope(example(), List.of(), List.of()), 1500);
        Map<String, Object> body = Map.of("description", "Purchase approval with finance review.");
        Response first = api("dana.designer").post(body, UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        Response second = api("dana.designer").post(Map.of("description", "Another purchase approval with finance."),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        Response third = api("dana.designer").post(Map.of("description", "A third purchase approval with finance."),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(first.status()).isEqualTo(202);
        assertThat(second.status()).isEqualTo(202);
        assertThat(third.status()).as(third.toString()).isEqualTo(429);
        assertThat(third.code()).isEqualTo("RATE_LIMITED");
        assertThat(third.header("Retry-After")).isEqualTo("15");
        assertThat(await("dana.designer", first.body().path("id").asString()).path("status").asString())
                .isEqualTo("succeeded");
        assertThat(await("dana.designer", second.body().path("id").asString()).path("status").asString())
                .isEqualTo("succeeded");
    }

    @Test
    @Order(15)
    void aJobOrphanedByARestartIsFailedAfterItsDeadline() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID subject = jdbc.sql("SELECT id FROM subject WHERE external_subject = 'kc-dana.designer'")
                .query(UUID.class).single();
        superuser("""
                INSERT INTO orqix.ai_generation_job (id, workspace_id, subject_id, status, profile, correlation_id,
                    created_at, started_at, deadline_at)
                VALUES (?, ?, ?, 'running', 'economy', 'corr-orphan-1', now() - interval '10 minutes',
                    now() - interval '9 minutes', now() - interval '1 minute')
                """, jobId, PURCHASING, subject);
        JsonNode job = api("dana.designer").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, jobId).body();
        assertThat(job.path("status").asString()).isEqualTo("failed");
        assertThat(job.path("problem").path("code").asString()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(job.path("problem").path("correlationId").asString()).isEqualTo("corr-orphan-1");
        assertThat(job.path("problem").path("detail").asString()).contains("interrupted");
    }

    @Test
    @Order(16)
    void concurrentSubmissionsRespectPerPersonLimit() throws Exception {
        OpenRouterStub.slow(envelope(example(), List.of(), List.of()), 2500);
        OpenRouterStub.slow(envelope(example(), List.of(), List.of()), 2500);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var submitted = new java.util.ArrayList<java.util.concurrent.Future<Response>>();
            for (int i = 0; i < 4; i++) {
                submitted.add(executor.submit(() -> {
                    start.await();
                    return api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                            UUID.randomUUID().toString(), DRAFTS, PURCHASING);
                }));
            }
            start.countDown();
            var responses = new java.util.ArrayList<Response>();
            for (var future : submitted) responses.add(future.get());
            assertThat(responses.stream().map(Response::status).toList()).containsExactlyInAnyOrder(202, 202, 429, 429);
            for (Response r : responses) if (r.status() == 202) {
                assertThat(await("dana.designer", r.body().path("id").asString()).path("status").asString()).isEqualTo("succeeded");
            }
        }
    }

    @Test
    @Order(17)
    void revokedEditingAccessDuringGenerationCreatesNoDraft() throws Exception {
        OpenRouterStub.slow(envelope(example(), List.of(), List.of()), 1500);
        long before = workflows();
        Response accepted = api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(accepted.status()).isEqualTo(202);
        UUID job = UUID.fromString(accepted.body().path("id").asString());
        UUID binding = UUID.fromString("00000000-0000-7000-8000-000000000302");
        try {
            superuser("UPDATE orqix.role_binding SET role = 'VIEWER' WHERE id = ?", binding);
            policy.evictAll();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                    jdbc.sql("SELECT status FROM ai_generation_job WHERE id = :id").param("id", job)
                            .query(String.class).single().equals("failed"));
            assertThat(workflows()).isEqualTo(before);
        } finally {
            superuser("UPDATE orqix.role_binding SET role = 'WORKFLOW_DESIGNER' WHERE id = ?", binding);
            policy.evictAll();
        }
    }

    @Test
    @Order(18)
    void terminalJobsExpireAfterRetentionWhileDraftsAndLiveReplayJobsRemain() throws Exception {
        OpenRouterStub.reply(envelope(example(), List.of(), List.of()), ECONOMY_MODEL, 0.0001);
        Response accepted = api("dana.designer").post(Map.of("description", "Purchase approval with finance review."),
                UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        String id = accepted.body().path("id").asString();
        JsonNode done = await("dana.designer", id);
        String workflowId = done.path("result").path("workflow").path("id").asString();
        superuser("UPDATE orqix.ai_generation_job SET finished_at = now() - interval '31 days' WHERE id = ?", UUID.fromString(id));
        jobStore.purgeExpired();
        assertThat(jobStore.find(PURCHASING, UUID.fromString(id))).isPresent();
        superuser("UPDATE orqix.idempotency_record SET expires_at = now() - interval '1 second' WHERE resource_id = ?", id);
        jobStore.purgeExpired();
        assertThat(jobStore.find(PURCHASING, UUID.fromString(id))).isEmpty();
        assertThat(api("dana.designer").get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).status()).isEqualTo(200);
    }

    /** Last but one: tripping the breaker pauses the auto profile in this context for the rest of the UTC day. */
    @Test
    @Order(98)
    void aRouterChoosingAnExcludedModelRecordsItAndPausesTheProfile() throws Exception {
        OpenRouterStub.reply(envelope(example(), List.of(), List.of()), "anthropic/claude-opus-9", 0.02);
        Draft routed = postDraft("dana.designer", Map.of("description", "Purchase approval with finance review.",
                "profile", "auto"), UUID.randomUUID().toString());
        // The valid output is kept (the call is already paid) but the violation is recorded on the generation.
        assertThat(routed.status()).as(routed.toString()).isEqualTo(201);
        assertThat(routed.body().path("generation").path("model").asString()).isEqualTo("anthropic/claude-opus-9");
        assertThat(routed.body().path("generation").path("policyViolation").asString()).contains("excluded model");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ai_generation WHERE profile = 'auto' AND policy_violation IS NOT NULL
                """).query(Long.class).single()).isEqualTo(1);
        // The profile is paused: not offered, refused, and Settings can show why.
        JsonNode status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING).body();
        assertThat(status.path("profiles").findValuesAsString("id")).doesNotContain("auto");
        JsonNode disabled = status.path("disabledProfiles").get(0);
        assertThat(disabled.path("id").asString()).isEqualTo("auto");
        assertThat(disabled.path("model").asString()).isEqualTo("anthropic/claude-opus-9");
        assertThat(disabled.path("reason").asString()).contains("excluded model", "00:00 UTC");
        assertThat(disabled.path("until").isString()).isTrue();
        Response refused = api("dana.designer").post(Map.of("description", "Purchase approval with finance review.",
                "profile", "auto"), UUID.randomUUID().toString(), DRAFTS, PURCHASING);
        assertThat(refused.code()).as(refused.toString()).isEqualTo("AI_MODEL_NOT_ALLOWED");
        // The default profile keeps working, and escalation no longer reaches the paused router.
        for (int i = 0; i < 3; i++) {
            OpenRouterStub.reply(envelope(brokenExample(), List.of(), List.of()), ECONOMY_MODEL, 0.001);
        }
        int before = OpenRouterStub.requests().size();
        Draft failed = draft("Purchase approval with finance review.");
        assertThat(failed.code()).isEqualTo("AI_OUTPUT_INVALID");
        assertThat(OpenRouterStub.requests().size() - before).isEqualTo(3);
        assertThat(OpenRouterStub.requests().stream().skip(before).map(r -> r.path("model").asString()))
                .containsOnly(ECONOMY_MODEL);
    }

    /** Last: rejected credentials disable AI in this context for the re-check interval. */
    @Test
    @Order(12)
    void explanationIncludesCompilerReadinessForMissingGroups() throws Exception {
        var definition = example();
        ((ObjectNode) node(definition, "finance").path("config")).put("candidateGroupRef", "legal-reviewers");
        OpenRouterStub.reply(envelope(definition, List.of(), List.of()), ECONOMY_MODEL, 0.0001);
        Draft created = draft("Purchase approval with finance review for large purchases.");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String id = created.body().path("workflow").path("id").asString();
        OpenRouterStub.reply("{\"summary\":\"All groups are ready.\",\"steps\":[]}", ECONOMY_MODEL, 0.0001);
        var explained = api("vic.viewer").post(Map.of("revision", 1), "/workspaces/{ws}/workflows/{id}/explain", PURCHASING, id);
        assertThat(explained.status()).as(explained.toString()).isEqualTo(200);
        assertThat(explained.body().path("validation").path("publishable").asBoolean(true)).isFalse();
        assertThat(explained.body().path("validation").path("issues").toString()).contains("legal-reviewers");
        String prompt = OpenRouterStub.requests().getLast().path("messages").get(1).path("content").asString();
        assertThat(prompt).contains("<validation>", "\"publishable\":false", "legal-reviewers");
    }

    @Test
    @Order(99)
    void rejectedCredentialsReportAiAsDisabled() throws Exception {
        OpenRouterStub.fail(401, Map.of());
        Draft rejected = draft("Purchase approval with finance review.");
        assertThat(rejected.status()).as(rejected.toString()).isEqualTo(422);
        assertThat(rejected.code()).isEqualTo("AI_NOT_CONFIGURED");
        JsonNode status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING).body();
        assertThat(status.path("enabled").asBoolean()).isFalse();
        assertThat(status.path("reason").asString()).contains("rejected");
        assertThat(draft("Purchase approval with finance review.").code()).isEqualTo("AI_NOT_CONFIGURED");
        assertThat(OpenRouterStub.requests()).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    /** A draft request as a client sees it: POST, then poll the job until it ends. Mirrors the old synchronous shape. */
    private final class Draft {
        private final Response accepted;
        private final JsonNode job;

        Draft(Response accepted, JsonNode job) {
            this.accepted = accepted;
            this.job = job;
        }

        String jobId() {
            return job == null ? null : job.path("id").asString();
        }

        /** 201 when the job succeeded, the problem status when it failed, the POST status when it was refused. */
        int status() {
            if (job == null) {
                return accepted.status();
            }
            return "succeeded".equals(job.path("status").asString()) ? 201
                    : "failed".equals(job.path("status").asString()) ? job.path("problem").path("status").asInt() : -1;
        }

        JsonNode body() {
            if (job == null) {
                return accepted.body();
            }
            return "succeeded".equals(job.path("status").asString()) ? job.path("result") : job.path("problem");
        }

        String code() {
            return body().path("code").asString(null);
        }

        String header(String name) {
            return accepted.header(name);
        }

        @Override
        public String toString() {
            return job == null ? accepted.toString() : job.toString();
        }
    }

    private Draft postDraft(String user, Object body, String key) throws Exception {
        Response accepted = api(user).post(body, key, DRAFTS, PURCHASING);
        if (accepted.status() != 202) {
            return new Draft(accepted, null);
        }
        assertThat(accepted.header("Location")).contains("/ai/generations/" + accepted.body().path("id").asString());
        return new Draft(accepted, await(user, accepted.body().path("id").asString()));
    }

    private JsonNode await(String user, String jobId) throws Exception {
        long until = System.currentTimeMillis() + 40_000;
        while (true) {
            Response poll = api(user).get("/workspaces/{ws}/ai/generations/{id}", PURCHASING, jobId);
            assertThat(poll.status()).as(poll.toString()).isEqualTo(200);
            String state = poll.body().path("status").asString();
            if ("succeeded".equals(state) || "failed".equals(state)) {
                return poll.body();
            }
            assertThat(System.currentTimeMillis()).as("job " + jobId + " still " + state).isLessThan(until);
            Thread.sleep(100);
        }
    }

    private Draft draft(String description) throws Exception {
        return postDraft("dana.designer", Map.of("description", description), UUID.randomUUID().toString());
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
