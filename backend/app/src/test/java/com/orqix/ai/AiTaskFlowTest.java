package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.AiTaskStub;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S134 end to end on the real engine and PostgreSQL, AI answers from {@link AiTaskStub}: a skill version written by a
 * designer and approved by someone else holding ai.approve-skill; an evaluation suite and a passing run (the publish
 * gate); shadow mode and the agreement dashboard; promotions (four eyes); the workspace policy (low-risk labels,
 * regulated, residency); routing per label, lowConfidence and aiUnavailable; the decision record in run detail and
 * the evidence pack; overrides becoming evaluation candidates.
 */
class AiTaskFlowTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private String skill;
    private String suite;

    @BeforeEach
    void grants() throws Exception {
        skill = "triage-" + UUID.randomUUID().toString().substring(0, 8);
        suite = skill + "-golden";
        for (String permission : List.of("ai.approve-skill", "ai.evaluate")) {
            jdbc.sql("""
                    INSERT INTO permission_grant (id, workspace_id, permission, principal_type, principal_value, created_at)
                    VALUES (:id, :ws, :p, 'idpGroup', 'orqix-purchasing-admins', now()) ON CONFLICT DO NOTHING
                    """).param("id", UUID.randomUUID()).param("ws", PURCHASING).param("p", permission).update();
        }
        resetPolicy();
    }

    private int policyVersion() throws Exception {
        return api("wade.admin").get("/workspaces/{ws}/ai-policy", PURCHASING).body().path("version").asInt();
    }

    private void resetPolicy() throws Exception {
        Response reset = api("wade.admin").put(Map.of("expectedVersion", policyVersion(), "regulated", false,
                "residency", List.of(), "lowRiskLabels", List.of(), "inputRetention", "digest"), "\"r1\"",
                "/workspaces/{ws}/ai-policy", PURCHASING);
        assertThat(reset.status()).as(reset.toString()).isEqualTo(200);
    }

    @AfterEach
    void revoke() throws Exception {
        resetPolicy();
        jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND permission IN ('ai.approve-skill', 'ai.evaluate')")
                .param("ws", PURCHASING).update();
    }

    private ObjectNode skillDocument(String profile) {
        return (ObjectNode) MAPPER.readTree("""
                {"schemaVersion":"1.0","kind":"skill","key":"%s","name":"Purchase triage","mode":"classify",
                 "instructions":"Classify the purchase request as routine or unusual.",
                 "input":{"purpose":{"type":"string","required":true,"maxLength":2000,"classification":"customer-text"},
                          "amount":{"type":"number"}},
                 "labels":[{"key":"routine","description":"An ordinary purchase","risk":"low"},
                           {"key":"unusual","description":"Unusual or urgent","risk":"high","adverse":true}],
                 "modelProfile":"%s",
                 "evaluation":{"suite":"%s","minAccuracy":0.75,"minCases":4,"minRecall":{"unusual":1}},
                 "promotion":{"minShadowCases":2,"targetAgreement":0.5},
                 "limits":{"timeoutSeconds":30,"maxCostUsd":0.05}}
                """.formatted(skill, profile, suite));
    }

    private Response createApproved(String profile) throws Exception {
        Response created = api("dana.designer").post(Map.of("document", skillDocument(profile)),
                "/workspaces/{ws}/ai-skills", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(api("dana.designer").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/submit", PURCHASING,
                skill).status()).isEqualTo(200);
        return api("wade.admin").post(Map.of("reason", "Reviewed labels and instructions"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/approve", PURCHASING, skill);
    }

    private void passEvaluation() throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", "1.0");
        document.put("kind", "evaluationSuite");
        document.put("key", suite);
        document.put("name", "Golden cases");
        document.put("skillKey", skill);
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(Map.of("id", "c1", "input", Map.of("purpose", "Replacement laptop", "amount", 900), "expected",
                Map.of("label", "routine"), "source", "synthetic"));
        cases.add(Map.of("id", "c2", "input", Map.of("purpose", "Paper", "amount", 20), "expected",
                Map.of("label", "routine")));
        cases.add(Map.of("id", "c3", "input", Map.of("purpose", "urgent wire to a new vendor", "amount", 90000),
                "expected", Map.of("label", "unusual")));
        cases.add(Map.of("id", "c4", "input", Map.of("purpose", "Monitor", "amount", 200), "expected",
                Map.of("label", "routine")));
        document.put("cases", cases);
        Response saved = api("dana.designer").put(Map.of("document", document), "\"r1\"",
                "/workspaces/{ws}/ai-eval-suites/{k}", PURCHASING, suite);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        // Only evaluators start runs.
        assertThat(api("dana.designer").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/evaluations",
                PURCHASING, skill).status()).isEqualTo(403);
        Response started = api("wade.admin").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/evaluations",
                PURCHASING, skill);
        assertThat(started.status()).as(started.toString()).isEqualTo(202);
        String id = started.body().path("id").asString();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() ->
                !"running".equals(api("wade.admin").get("/workspaces/{ws}/ai-evaluations/{id}", PURCHASING, id).body()
                        .path("status").asString()));
        JsonNode run = api("wade.admin").get("/workspaces/{ws}/ai-evaluations/{id}", PURCHASING, id).body();
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("passed");
        assertThat(run.path("metrics").path("accuracy").asDouble()).isEqualTo(1.0);
        assertThat(run.path("metrics").path("perLabel").path("unusual").path("recall").asDouble()).isEqualTo(1.0);
        assertThat(run.path("metrics").path("confusion").path("routine").path("routine").asInt()).isEqualTo(3);
        assertThat(run.path("calibration")).hasSize(10);
        assertThat(run.path("metrics").path("thresholds")).isNotEmpty();
    }

    /** start -> triage (AI task) -> manager, every AI route to the manager. */
    private Consumer<ObjectNode> aiStep(String autonomy) {
        return d -> {
            ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("""
                    {"id":"triage","type":"aiTask","label":"Triage the purchase","config":{"skillRef":"%s",
                     "mode":"classify","autonomy":"%s","minConfidence":0.8,
                     "inputMapping":{"purpose":"/input/purpose","amount":"/input/amount"}}}
                    """.formatted(skill, autonomy)));
            ((ObjectNode) d.path("uiLayout").path("positions")).set("triage", MAPPER.readTree("{\"x\":40,\"y\":40}"));
            ArrayNode transitions = (ArrayNode) d.path("transitions");
            for (JsonNode t : transitions) {
                if ("t1".equals(t.path("id").asString())) {
                    ((ObjectNode) t).put("to", "triage");
                }
            }
            if ("shadow".equals(autonomy)) {
                transitions.add(MAPPER.readTree("{\"id\":\"a0\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"next\"}"));
                return;
            }
            if (!"suggest".equals(autonomy)) {
                transitions.add(MAPPER.readTree("{\"id\":\"a1\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"aiLabel\",\"aiLabel\":\"routine\"}"));
                transitions.add(MAPPER.readTree("{\"id\":\"a2\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"aiLabel\",\"aiLabel\":\"unusual\"}"));
            }
            transitions.add(MAPPER.readTree("{\"id\":\"a3\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"lowConfidence\"}"));
            transitions.add(MAPPER.readTree("{\"id\":\"a4\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"needsReview\"}"));
            transitions.add(MAPPER.readTree("{\"id\":\"a5\",\"from\":\"triage\",\"to\":\"manager\",\"on\":\"aiUnavailable\"}"));
        };
    }

    private UUID startWith(UUID workflow, String purpose) throws Exception {
        Map<String, Object> input = input(new BigDecimal("120"));
        input.put("purpose", purpose);
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        UUID run = UUID.fromString(started.body().path("id").asString());
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200))
                .until(() -> openTask(PURCHASING, run, "manager") != null);
        return run;
    }

    private JsonNode decision(UUID run) throws Exception {
        Response list = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/ai-decisions", PURCHASING, run);
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        assertThat(list.body().path("items")).hasSize(1);
        return list.body().path("items").get(0);
    }

    private void promote(String to) throws Exception {
        Response requested = api("dana.designer").post(Map.of("toAutonomy", to, "reason", "Shadow results are good"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/promotions", PURCHASING, skill);
        assertThat(requested.status()).as(requested.toString()).isEqualTo(201);
        String id = requested.body().path("id").asString();
        Response decided = api("wade.admin").post(Map.of("decision", "approve", "reason", "Evidence reviewed"),
                "/workspaces/{ws}/ai-skill-promotions/{id}/decision", PURCHASING, id);
        assertThat(decided.status()).as(decided.toString()).isEqualTo(200);
        assertThat(decided.body().path("status").asString()).isEqualTo("approved");
    }

    private Response publish(String autonomy) throws Exception {
        String key = "ai-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = api("dana.designer").post(Map.of("key", key, "name", "AI " + key, "templateId",
                "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, id).body().path("definition");
        aiStep(autonomy).accept(definition);
        assertThat(api("dana.designer").put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id).status()).isEqualTo(200);
        return api("paul.publisher").post(Map.of("draftRevision", 2, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, id);
    }

    @Test
    void aSkillIsApprovedByAnotherPersonAndGatedByItsEvaluation() throws Exception {
        Response created = api("dana.designer").post(Map.of("document", skillDocument("task-openai")),
                "/workspaces/{ws}/ai-skills", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.body().path("versions").get(0).path("status").asString()).isEqualTo("draft");
        // Contract problems are 422 with pointers; a duplicate key is 409.
        ObjectNode broken = skillDocument("task-openai");
        broken.put("mode", "score");
        assertThat(api("dana.designer").post(Map.of("document", broken), "/workspaces/{ws}/ai-skills", PURCHASING)
                .status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("document", skillDocument("task-openai")),
                "/workspaces/{ws}/ai-skills", PURCHASING).status()).isEqualTo(409);
        // Not submitted yet: nothing to approve; the author can never approve, even holding the grant.
        assertThat(api("wade.admin").post(Map.of("reason", "x"), "/workspaces/{ws}/ai-skills/{k}/versions/1/approve",
                PURCHASING, skill).status()).isEqualTo(409);
        api("dana.designer").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/submit", PURCHASING, skill);
        assertThat(api("dana.designer").post(Map.of("reason", "mine"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/approve", PURCHASING, skill).status()).isEqualTo(403);
        jdbc.sql("UPDATE ai_skill_version SET author_id = (SELECT id FROM subject WHERE external_subject = 'kc-wade.admin')"
                + " WHERE skill_id = (SELECT id FROM ai_skill WHERE key = :k)").param("k", skill).update();
        Response self = api("wade.admin").post(Map.of("reason", "mine"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/approve", PURCHASING, skill);
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.code()).isEqualTo("SELF_APPROVAL_DENIED");
        jdbc.sql("UPDATE ai_skill_version SET author_id = (SELECT id FROM subject WHERE external_subject = 'kc-dana.designer')"
                + " WHERE skill_id = (SELECT id FROM ai_skill WHERE key = :k)").param("k", skill).update();
        Response approved = api("wade.admin").post(Map.of("reason", "Reviewed"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/approve", PURCHASING, skill);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        assertThat(approved.body().path("status").asString()).isEqualTo("approved");
        assertThat(approved.body().path("evaluationGate").path("passing").asBoolean()).isFalse();
        // Approved versions never change.
        assertThat(api("dana.designer").put(Map.of("document", skillDocument("task-openai")), "\"r1\"",
                "/workspaces/{ws}/ai-skills/{k}/versions/1", PURCHASING, skill).status()).isEqualTo(409);

        // Publish gate: without a passing evaluation run an AI task that routes cannot be published.
        Response blocked = publish("suggest");
        assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        assertThat(blocked.toString()).contains("WF_AI_EVALUATION_REQUIRED");
        passEvaluation();
        Response published = publish("suggest");
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        // The release evidence pack names the pinned version and its evaluation.
        String workflowId = published.body().path("workflowId").asString(null);
        JsonNode manifest = MAPPER.readTree(jdbc.sql("SELECT manifest::text FROM workflow_release ORDER BY published_at DESC LIMIT 1")
                .query(String.class).single());
        assertThat(manifest.path("aiSkills").get(0).path("skillKey").asString()).isEqualTo(skill);
        assertThat(manifest.path("aiSkills").get(0).path("evaluationRunId").isString()).isTrue();
        assertThat(workflowId == null || !workflowId.isEmpty()).isTrue();
    }

    @Test
    void shadowModePromotionAndPolicyDecideTheRoute() throws Exception {
        assertThat(createApproved("task-openai").status()).isEqualTo(200);
        // Shadow mode needs no evaluation and never changes the path.
        Response shadow = publish("shadow");
        assertThat(shadow.status()).as(shadow.toString()).isEqualTo(201);
        UUID shadowWorkflow = UUID.fromString(shadow.body().path("workflowId").asString(
                jdbc.sql("SELECT workflow_id::text FROM workflow_release ORDER BY published_at DESC LIMIT 1")
                        .query(String.class).single()));
        UUID s1 = startWith(shadowWorkflow, "Replacement laptop");
        UUID s2 = startWith(shadowWorkflow, "Another monitor");
        for (UUID run : List.of(s1, s2)) {
            JsonNode d = decision(run);
            assertThat(d.path("status").asString()).isEqualTo("shadow");
            assertThat(d.path("route").asString()).isEqualTo("next");
            assertThat(d.path("output").path("label").asString()).isEqualTo("routine");
            Response accepted = api("olga.operator").post(Map.of("outcome", "accepted"),
                    "/workspaces/{ws}/runs/{run}/ai-decisions/{id}/outcome", PURCHASING, run, d.path("id").asString());
            assertThat(accepted.status()).as(accepted.toString()).isEqualTo(200);
        }
        JsonNode dashboard = api("dana.designer").get("/workspaces/{ws}/ai-skills/{k}/versions/1/shadow", PURCHASING,
                skill).body();
        assertThat(dashboard.path("compared").asInt()).isEqualTo(2);
        assertThat(dashboard.path("agreement").asDouble()).isEqualTo(1.0);
        assertThat(dashboard.path("shadowDecisions").asInt()).isEqualTo(2);

        // Promotion one step at a time, each approved by a second person.
        assertThat(api("dana.designer").post(Map.of("toAutonomy", "auto", "reason", "x"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/promotions", PURCHASING, skill).status()).isEqualTo(422);
        promote("autoWithReview");
        promote("auto");
        passEvaluation();

        // Policy: routine is low-risk; the workspace is regulated, so the adverse label always goes to a person.
        Response policy = api("wade.admin").put(Map.of("expectedVersion", policyVersion(), "regulated", true, "lowRiskLabels",
                List.of(skill + ":routine")), "\"r1\"", "/workspaces/{ws}/ai-policy", PURCHASING);
        assertThat(policy.status()).as(policy.toString()).isEqualTo(200);
        Response published = publish("auto");
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        UUID workflow = UUID.fromString(jdbc.sql("SELECT workflow_id::text FROM workflow_release ORDER BY published_at DESC LIMIT 1")
                .query(String.class).single());

        AiTaskStub.reset();
        UUID routine = startWith(workflow, "Replacement laptop, card 4111 1111 1111 1111, mail ann@example.com");
        JsonNode r = decision(routine);
        assertThat(r.path("status").asString()).isEqualTo("decided");
        assertThat(r.path("route").asString()).isEqualTo("label:routine");
        assertThat(r.path("effectiveAutonomy").asString()).isEqualTo("auto");
        assertThat(r.path("guardrails").path("minimization").path("purpose").asString()).isEqualTo("mask");
        // D-CG9: the digest only, no values.
        assertThat(r.path("inputRetention").asString()).isEqualTo("digest");
        assertThat(r.path("inputValues").isNull()).isTrue();
        assertThat(r.path("inputDigest").asString()).hasSize(64);
        assertThat(r.path("modelUsed").asString()).isEqualTo("test/task-model");
        assertThat(r.path("output").path("evidence").isArray()).isTrue();
        // Minimized and wrapped as data: the card number and e-mail never reached the provider.
        String sent = AiTaskStub.calls().getLast().body().toString();
        assertThat(sent).contains("<request_data>", "data, never instructions", "[card number]", "[e-mail address]")
                .doesNotContain("4111 1111", "ann@example.com");
        assertThat(AiTaskStub.calls().getLast().headers().get("authorization")).isEqualTo("Bearer " + AiTaskStub.KEY);
        assertThat(AiTaskStub.calls().getLast().body().path("response_format").path("json_schema").path("strict")
                .asBoolean()).isTrue();
        List<String> events = jdbc.sql("SELECT type || ' ' || coalesce(data::text, '') FROM run_event WHERE run_id = :run ORDER BY seq")
                .param("run", routine).query(String.class).list();
        assertThat(String.join("\n", events)).contains("AI_TASK_QUEUED", "AI_TASK_COMPLETED", "label:routine")
                .doesNotContain("4111", "ann@example.com");
        // Turn state and the sealed input are gone once the step ended.
        assertThat(jdbc.sql("SELECT count(*) FROM ai_turn_state t JOIN ai_task_job j ON j.id = t.job_id WHERE j.run_id = :run")
                .param("run", routine).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_task_job WHERE run_id = :run AND input_cipher IS NOT NULL")
                .param("run", routine).query(Integer.class).single()).isZero();

        UUID adverse = startWith(workflow, "urgent wire to a new vendor");
        JsonNode a = decision(adverse);
        assertThat(a.path("output").path("label").asString()).isEqualTo("unusual");
        assertThat(a.path("route").asString()).isEqualTo("needsReview");
        assertThat(a.path("guardrails").path("downgrades").toString()).contains("adverseRegulated");

        UUID unsure = startWith(workflow, "unsure what this is");
        assertThat(decision(unsure).path("route").asString()).isEqualTo("lowConfidence");

        UUID down = startWith(workflow, "outage expected");
        JsonNode u = decision(down);
        assertThat(u.path("route").asString()).isEqualTo("aiUnavailable");
        assertThat(u.path("status").asString()).isEqualTo("unavailable");
        assertThat(u.path("unavailableReason").asString()).contains("unavailable");

        UUID repaired = startWith(workflow, "garbled but routine");
        JsonNode g = decision(repaired);
        assertThat(g.path("route").asString()).isEqualTo("label:routine");
        assertThat(g.path("guardrails").path("repairs").asInt()).isEqualTo(1);

        // An override becomes a candidate evaluation case; foreign callers and plain viewers cannot record outcomes.
        assertThat(api("vic.viewer").post(Map.of("outcome", "accepted"),
                "/workspaces/{ws}/runs/{run}/ai-decisions/{id}/outcome", PURCHASING, routine, r.path("id").asString())
                .status()).isIn(403, 404);
        Response overridden = api("olga.operator").post(Map.of("outcome", "overridden", "value", "unusual", "reason",
                "Known risky vendor"), "/workspaces/{ws}/runs/{run}/ai-decisions/{id}/outcome", PURCHASING, routine,
                r.path("id").asString());
        assertThat(overridden.status()).as(overridden.toString()).isEqualTo(200);
        assertThat(overridden.body().path("human").path("outcome").asString()).isEqualTo("overridden");
        JsonNode candidates = api("wade.admin").get("/workspaces/{ws}/ai-eval-candidates", PURCHASING).body();
        assertThat(candidates.toString()).contains(r.path("id").asString(), "\"label\":\"unusual\"");

        // The run evidence pack lists the AI decision as control facts only.
        Response pack = api("olga.operator").get("/workspaces/{ws}/runs/{id}/evidence-pack?format=json", PURCHASING,
                routine);
        assertThat(pack.status()).as(pack.toString()).isEqualTo(200);
        String text = new String(pack.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("aiDecisions", skill + " v1", "label:routine").doesNotContain("4111", "Replacement laptop, card");
    }

    @Test
    void residencyRefusesEndpointsOutsideTheRule() throws Exception {
        ObjectNode document = skillDocument("task-gemini");
        Response created = api("dana.designer").post(Map.of("document", document), "/workspaces/{ws}/ai-skills",
                PURCHASING);
        assertThat(created.status()).isEqualTo(201);
        api("dana.designer").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/submit", PURCHASING, skill);
        api("wade.admin").post(Map.of("reason", "ok"), "/workspaces/{ws}/ai-skills/{k}/versions/1/approve", PURCHASING,
                skill);
        // EU-only: the Gemini endpoint is tagged us, so an evaluation cannot even start.
        assertThat(api("wade.admin").put(Map.of("expectedVersion", policyVersion(), "residency", List.of("eu")), "\"r1\"",
                "/workspaces/{ws}/ai-policy", PURCHASING).status()).isEqualTo(200);
        Response refused = api("wade.admin").post(Map.of("suiteKey", suite),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/evaluations", PURCHASING, skill);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("AI_MODEL_NOT_ALLOWED");
        assertThat(refused.toString()).contains("residency");
        JsonNode policy = api("wade.admin").get("/workspaces/{ws}/ai-policy", PURCHASING).body();
        assertThat(policy.path("endpoints").toString()).contains("stub-gemini", "vertex");
    }
}
