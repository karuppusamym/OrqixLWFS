package com.orqix.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.flowable.engine.HistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * S152 end to end on the real engine and PostgreSQL, AI answers from {@link com.orqix.support.AiTaskStub}: the AI
 * reviewer of an approval step decides a confident approve or reject (rationale as the reason, AGENT audit, step
 * outputs, decision history), leaves a low-confidence or suggest-only answer with a person (suggestion on the task),
 * never decides a task a person decided or took first, and records the person's decision as the human outcome.
 */
class AiReviewerFlowTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private HistoryService history;

    private String skill;
    private String suite;

    @BeforeEach
    void grants() throws Exception {
        skill = "review-" + UUID.randomUUID().toString().substring(0, 8);
        suite = skill + "-golden";
        for (String permission : List.of("ai.approve-skill", "ai.evaluate")) {
            jdbc.sql("""
                    INSERT INTO permission_grant (id, workspace_id, permission, principal_type, principal_value, created_at)
                    VALUES (:id, :ws, :p, 'idpGroup', 'orqix-purchasing-admins', now()) ON CONFLICT DO NOTHING
                    """).param("id", UUID.randomUUID()).param("ws", PURCHASING).param("p", permission).update();
        }
        resetPolicy();
    }

    @AfterEach
    void revoke() throws Exception {
        resetPolicy();
        jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND permission IN ('ai.approve-skill', 'ai.evaluate')")
                .param("ws", PURCHASING).update();
    }

    private void resetPolicy() throws Exception {
        int version = api("wade.admin").get("/workspaces/{ws}/ai-policy", PURCHASING).body().path("version").asInt();
        Response reset = api("wade.admin").put(Map.of("expectedVersion", version, "regulated", false,
                "residency", List.of(), "lowRiskLabels", List.of(), "inputRetention", "digest"), "\"r1\"",
                "/workspaces/{ws}/ai-policy", PURCHASING);
        assertThat(reset.status()).as(reset.toString()).isEqualTo(200);
    }

    /** An approved, evaluated classify skill answering approve or reject, promoted up to {@code ceiling}. */
    private void reviewSkill(String ceiling) throws Exception {
        ObjectNode document = (ObjectNode) MAPPER.readTree("""
                {"schemaVersion":"1.0","kind":"skill","key":"%s","name":"Purchase review","mode":"classify",
                 "instructions":"Decide whether the purchase request can be approved.",
                 "input":{"purpose":{"type":"string","required":true,"maxLength":2000,"classification":"customer-text"},
                          "amount":{"type":"number"}},
                 "labels":[{"key":"approve","description":"Within policy","risk":"low"},
                           {"key":"reject","description":"Against policy","risk":"high"}],
                 "modelProfile":"task-openai",
                 "evaluation":{"suite":"%s","minAccuracy":0.75,"minCases":4,"minRecall":{"reject":1}},
                 "limits":{"timeoutSeconds":30,"maxCostUsd":0.05}}
                """.formatted(skill, suite));
        Response created = api("dana.designer").post(Map.of("document", document), "/workspaces/{ws}/ai-skills",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(api("dana.designer").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/submit", PURCHASING,
                skill).status()).isEqualTo(200);
        Response approved = api("wade.admin").post(Map.of("reason", "Reviewed labels and instructions"),
                "/workspaces/{ws}/ai-skills/{k}/versions/1/approve", PURCHASING, skill);
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);

        Map<String, Object> suiteDocument = new LinkedHashMap<>();
        suiteDocument.put("schemaVersion", "1.0");
        suiteDocument.put("kind", "evaluationSuite");
        suiteDocument.put("key", suite);
        suiteDocument.put("name", "Golden cases");
        suiteDocument.put("skillKey", skill);
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(Map.of("id", "c1", "input", Map.of("purpose", "Replacement laptop", "amount", 900), "expected",
                Map.of("label", "approve"), "source", "synthetic"));
        cases.add(Map.of("id", "c2", "input", Map.of("purpose", "Paper", "amount", 20), "expected",
                Map.of("label", "approve")));
        cases.add(Map.of("id", "c3", "input", Map.of("purpose", "Vendor on the decline list", "amount", 90000),
                "expected", Map.of("label", "reject")));
        cases.add(Map.of("id", "c4", "input", Map.of("purpose", "Monitor", "amount", 200), "expected",
                Map.of("label", "approve")));
        suiteDocument.put("cases", cases);
        Response saved = api("dana.designer").put(Map.of("document", suiteDocument), "\"r1\"",
                "/workspaces/{ws}/ai-eval-suites/{k}", PURCHASING, suite);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response started = api("wade.admin").post(Map.of(), "/workspaces/{ws}/ai-skills/{k}/versions/1/evaluations",
                PURCHASING, skill);
        assertThat(started.status()).as(started.toString()).isEqualTo(202);
        String id = started.body().path("id").asString();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() ->
                !"running".equals(api("wade.admin").get("/workspaces/{ws}/ai-evaluations/{id}", PURCHASING, id).body()
                        .path("status").asString()));
        assertThat(api("wade.admin").get("/workspaces/{ws}/ai-evaluations/{id}", PURCHASING, id).body()
                .path("status").asString()).isEqualTo("passed");
        // Promotion itself (shadow evidence, four eyes) is covered by AiTaskFlowTest; here the ceiling is set directly.
        jdbc.sql("UPDATE ai_skill_version SET autonomy_ceiling = :c WHERE skill_id = (SELECT id FROM ai_skill"
                + " WHERE workspace_id = :ws AND key = :k)").param("c", ceiling).param("ws", PURCHASING)
                .param("k", skill).update();
    }

    /** The purchase template with the AI reviewer on the manager approval. */
    private UUID publishWithReviewer(String autonomy) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            for (JsonNode n : d.path("nodes")) {
                if ("manager".equals(n.path("id").asString())) {
                    ((ObjectNode) n.path("config")).set("aiReviewer", MAPPER.readTree("""
                            {"skill":{"key":"%s","version":1},"autonomy":"%s","minConfidence":0.8,
                             "inputs":{"purpose":"/input/purpose","amount":"/input/amount"}}
                            """.formatted(skill, autonomy)));
                }
            }
        });
    }

    private UUID startWith(UUID workflow, String purpose) throws Exception {
        Map<String, Object> input = input(new BigDecimal("120"));
        input.put("purpose", purpose);
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private String managerTask(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100)).until(() ->
                jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :run AND node_id = 'manager'")
                        .param("run", run).query(Integer.class).single() > 0);
        return jdbc.sql("SELECT engine_task_id FROM task_context WHERE run_id = :run AND node_id = 'manager'"
                + " ORDER BY created_at LIMIT 1").param("run", run).query(String.class).single();
    }

    private List<String> events(UUID run) {
        return jdbc.sql("SELECT type || ' ' || coalesce(data::text, '') FROM run_event WHERE run_id = :run ORDER BY seq")
                .param("run", run).query(String.class).list();
    }

    private void awaitEvent(UUID run, String type) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200))
                .until(() -> String.join("\n", events(run)).contains(type));
    }

    private JsonNode task(String user, String taskId) throws Exception {
        Response detail = api(user).get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId);
        assertThat(detail.status()).as(detail.toString()).isEqualTo(200);
        return detail.body();
    }

    private JsonNode stepOutput(UUID run) {
        String pid = processInstance(PURCHASING, run);
        Object value = history.createHistoricVariableInstanceQuery().processInstanceId(pid)
                .variableName("orqix_step_manager").singleResult().getValue();
        return MAPPER.readTree(value.toString());
    }

    @Test
    void aConfidentAnswerIsDecidedByTheAiReviewer() throws Exception {
        reviewSkill("autoWithReview");
        UUID workflow = publishWithReviewer("autoWithReview");

        UUID approved = startWith(workflow, "Replacement laptop");
        String approvedTask = managerTask(approved);
        awaitEvent(approved, "TASK_DECIDED_BY_AI");
        var row = jdbc.sql("""
                SELECT actor_subject_id IS NULL AS no_subject, actor_kind, action, reason, ai_skill_key, ai_skill_version,
                       ai_decision_id FROM task_decision WHERE engine_task_id = :task
                """).param("task", approvedTask).query((rs, n) -> Map.of("noSubject", rs.getBoolean("no_subject"),
                        "kind", rs.getString("actor_kind"), "action", rs.getString("action"), "reason",
                        rs.getString("reason"), "skill", rs.getString("ai_skill_key"), "version",
                        rs.getInt("ai_skill_version"), "decision", rs.getString("ai_decision_id"))).single();
        assertThat(row).containsEntry("noSubject", true).containsEntry("kind", "aiReviewer")
                .containsEntry("action", "approve").containsEntry("reason", "Within the cost center budget.")
                .containsEntry("skill", skill).containsEntry("version", 1);
        // The decision record names the task; the audit names the AI reviewer as an agent without a subject.
        assertThat(jdbc.sql("SELECT task_id FROM ai_decision_record WHERE id = CAST(:id AS uuid)")
                .param("id", row.get("decision")).query(String.class).single()).isEqualTo(approvedTask);
        assertThat(jdbc.sql("""
                SELECT actor_type || ':' || coalesce(actor_subject_id::text, '-') FROM audit_event
                WHERE action = 'task.decided-by-ai' AND resource_id = :task
                """).param("task", approvedTask).query(String.class).single()).isEqualTo("agent:-");
        JsonNode output = stepOutput(approved);
        assertThat(output.path("decision").asString()).isEqualTo("approved");
        assertThat(output.path("decidedBy").asString()).isEqualTo("ai-reviewer");
        assertThat(output.path("reason").asString()).isEqualTo("Within the cost center budget.");
        assertThat(output.path("aiReview").path("skill").asString()).isEqualTo(skill);
        assertThat(output.path("aiReview").path("confidence").asDouble()).isEqualTo(0.95);
        assertThat(output.path("aiReview").path("threshold").asDouble()).isEqualTo(0.8);
        // Task detail: the history shows the AI reviewer (no person), and the review says it decided.
        JsonNode detail = task("mani.manager", approvedTask);
        JsonNode decision = detail.path("history").get(0);
        assertThat(decision.path("decidedBy").asString()).isEqualTo("aiReviewer");
        assertThat(decision.path("actor").path("displayName").asString()).isEqualTo("AI reviewer");
        assertThat(decision.path("aiReview").path("skillKey").asString()).isEqualTo(skill);
        assertThat(detail.path("aiReview").path("status").asString()).isEqualTo("decided");
        assertThat(detail.path("aiReview").path("decidedByAi").asBoolean()).isTrue();
        assertThat(detail.path("aiReview").path("recommendation").asString()).isEqualTo("approve");
        assertThat(detail.path("completedBy").path("displayName").asString()).isEqualTo("AI reviewer");
        // The run evidence pack names the AI reviewer and its skill version.
        Response pack = api("olga.operator").get("/workspaces/{ws}/runs/{id}/evidence-pack?format=json", PURCHASING,
                approved);
        assertThat(pack.status()).as(pack.toString()).isEqualTo(200);
        assertThat(new String(pack.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("AI reviewer (" + skill + " v1)");
        // The AI reviewer is no person: it never appears in anyone's completed tasks.
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=completed", PURCHASING).body().toString())
                .doesNotContain(approvedTask);

        // A confident reject: the rationale is the rejection reason.
        UUID rejected = startWith(workflow, "Vendor on the decline list");
        String rejectedTask = managerTask(rejected);
        awaitEvent(rejected, "TASK_DECIDED_BY_AI");
        assertThat(jdbc.sql("SELECT action || ' ' || reason FROM task_decision WHERE engine_task_id = :task")
                .param("task", rejectedTask).query(String.class).single())
                .isEqualTo("reject The vendor is on the decline list.");
        assertThat(stepOutput(rejected).path("decision").asString()).isEqualTo("rejected");
        assertThat(String.join("\n", events(rejected))).contains("AI_REVIEW_QUEUED", "\"decidedBy\": \"aiReviewer\"")
                .doesNotContain("decline list");
    }

    @Test
    void anUnsureOrSuggestOnlyAnswerStaysWithAPersonWhoseDecisionIsTheHumanOutcome() throws Exception {
        reviewSkill("autoWithReview");
        UUID workflow = publishWithReviewer("autoWithReview");

        UUID unsure = startWith(workflow, "unsure what this is");
        String unsureTask = managerTask(unsure);
        awaitEvent(unsure, "AI_REVIEW_SUGGESTED");
        JsonNode review = task("mani.manager", unsureTask).path("aiReview");
        assertThat(review.path("status").asString()).isEqualTo("suggested");
        assertThat(review.path("heldFor").asString()).isEqualTo("lowConfidence");
        assertThat(review.path("recommendation").asString()).isEqualTo("approve");
        assertThat(review.path("confidence").asDouble()).isEqualTo(0.4);
        assertThat(review.path("reasons").toString()).contains("Within the cost center budget.");
        assertThat(review.path("decidedByAi").asBoolean()).isFalse();
        // A person decides otherwise: the suggestion is recorded as overridden with the person's reason.
        assertThat(claim("mani.manager", PURCHASING, unsureTask).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, unsureTask, "reject", "Not a real need").status()).isEqualTo(201);
        assertThat(jdbc.sql("""
                SELECT human_outcome || ' ' || human_value || ' ' || human_reason FROM ai_decision_record
                WHERE task_id = :task""").param("task", unsureTask).query(String.class).single())
                .isEqualTo("overridden reject Not a real need");
        assertThat(jdbc.sql("SELECT actor_kind FROM task_decision WHERE engine_task_id = :task")
                .param("task", unsureTask).query(String.class).single()).isEqualTo("person");

        // Autonomy suggest: a confident answer is only a suggestion; accepting it is recorded as accepted.
        UUID suggestWorkflow = publishWithReviewer("suggest");
        UUID suggested = startWith(suggestWorkflow, "Replacement laptop");
        String suggestedTask = managerTask(suggested);
        awaitEvent(suggested, "AI_REVIEW_SUGGESTED");
        JsonNode s = task("mani.manager", suggestedTask).path("aiReview");
        assertThat(s.path("heldFor").asString()).isEqualTo("suggest");
        assertThat(s.path("confidence").asDouble()).isEqualTo(0.95);
        approve("mani.manager", PURCHASING, suggested, "manager");
        assertThat(jdbc.sql("SELECT human_outcome FROM ai_decision_record WHERE task_id = :task")
                .param("task", suggestedTask).query(String.class).single()).isEqualTo("accepted");
        assertThat(String.join("\n", events(suggested))).doesNotContain("TASK_DECIDED_BY_AI");
    }

    @Test
    void aPersonWhoDecidesOrTakesTheTaskFirstKeepsIt() throws Exception {
        reviewSkill("autoWithReview");
        UUID workflow = publishWithReviewer("autoWithReview");

        // The stub answers "slow" requests after 1.5 seconds; the person decides while AI is still working.
        UUID first = startWith(workflow, "slow but fine");
        String firstTask = managerTask(first);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).until(() ->
                jdbc.sql("SELECT count(*) FROM ai_task_job WHERE task_id = :task AND status = 'running'")
                        .param("task", firstTask).query(Integer.class).single() == 1);
        assertThat(task("mani.manager", firstTask).path("aiReview").path("status").asString()).isEqualTo("pending");
        approve("mani.manager", PURCHASING, first, "manager");
        assertThat(jdbc.sql("SELECT status FROM ai_task_job WHERE task_id = :task").param("task", firstTask)
                .query(String.class).single()).isEqualTo("cancelled");
        // The worker finishes its call and finds the job ended: no second decision, no AI record.
        Thread.sleep(2500);
        assertThat(jdbc.sql("SELECT count(*) FROM task_decision WHERE engine_task_id = :task")
                .param("task", firstTask).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_decision_record WHERE task_id = :task")
                .param("task", firstTask).query(Integer.class).single()).isZero();
        assertThat(String.join("\n", events(first))).doesNotContain("TASK_DECIDED_BY_AI");
        assertThat(task("mani.manager", firstTask).path("aiReview").path("status").asString()).isEqualTo("cancelled");

        // A person who claimed the task while AI was working keeps it; the answer is attached as a suggestion.
        UUID claimed = startWith(workflow, "slow and fine");
        String claimedTask = managerTask(claimed);
        assertThat(claim("mani.manager", PURCHASING, claimedTask).status()).isEqualTo(200);
        awaitEvent(claimed, "AI_REVIEW_SUGGESTED");
        JsonNode review = task("mani.manager", claimedTask).path("aiReview");
        assertThat(review.path("status").asString()).isEqualTo("suggested");
        assertThat(review.path("heldFor").asString()).isEqualTo("personActed");
        assertThat(jdbc.sql("SELECT status FROM task_context WHERE engine_task_id = :task").param("task", claimedTask)
                .query(String.class).single()).isEqualTo("open");
    }
}
