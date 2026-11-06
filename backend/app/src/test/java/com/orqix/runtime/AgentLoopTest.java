package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.AgentLlmStub;
import com.orqix.support.AgentToolsTestBase;
import com.orqix.support.McpStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-M02/CG-M03 on the real stack (docs/build/S135): a durable agent step runs one model turn per engine job, waits for
 * a person (ask a person, approval before a tool) or a child request as an ordinary engine wait, keeps the
 * conversation only encrypted between turns and clears it at the end, and stops at its limits and at the kill switch.
 */
class AgentLoopTest extends AgentToolsTestBase {

    private static final String LOOKUP = "vendor_risk_lookup";

    @Autowired
    OrqixProperties properties;

    private String server;

    @BeforeEach
    void keyRingAndServer() throws Exception {
        Path ring = properties.secrets().dir().resolve("oauth-token-keyring");
        if (!Files.exists(ring)) {
            byte[] key = new byte[32];
            java.util.Arrays.fill(key, (byte) 11);
            Files.writeString(ring, "{\"active\":\"loop\",\"keys\":{\"loop\":\"" + Base64.getEncoder().encodeToString(key)
                    + "\"}}");
        }
        server = server("loop-registry", false);
        pin(server);
    }

    private String durableAgent(int maxTurns, Consumer<ArrayNode> extraTools) throws Exception {
        return agent("loop-agent", a -> {
            ArrayNode tools = a.putArray("tools");
            tool(tools, server + "." + LOOKUP, "Vendor registry");
            extraTools.accept(tools);
            a.put("instructions", "Assess the vendor with the registry; ask a reviewer when you are unsure; then answer.");
            a.putObject("loop").put("maxTurns", maxTurns).put("maxToolCalls", 6).put("maxDuration", "PT1H")
                    .put("budgetUsd", 1);
        });
    }

    private static void askTool(ArrayNode tools) {
        ObjectNode ask = tool(tools, "ask_reviewer", "Compliance reviewer");
        ask.put("description", "Asks a compliance reviewer one question about the vendor.");
        ObjectNode form = ask.putObject("source").putObject("askPerson").put("group", "compliance-reviewers")
                .putObject("answerSchema");
        form.put("type", "object").put("additionalProperties", false).putArray("required").add("note");
        form.putObject("properties").putObject("note").put("type", "string").put("maxLength", 200);
    }

    private UUID publish(String agentKey) throws Exception {
        String text = Files.readString(Path.of(System.getProperty("orqix.repoRoot", "../.."),
                "examples/vendor-onboarding-check.json")).replace("vendor-risk-assistant", agentKey);
        JsonNode example = json.parse(text);
        return publishPurchase(PURCHASING, d -> {
            String key = d.path("workflowKey").asString();
            d.removeAll();
            example.properties().forEach(e -> d.set(e.getKey(), e.getValue().deepCopy()));
            d.put("workflowKey", key);
            ((ObjectNode) d.path("execution")).put("retryDelaySeconds", 5);
        });
    }

    private UUID startVendor(UUID workflow) throws Exception {
        Response started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", Map.of("vendorName",
                "Acme Ltd", "country", "United Kingdom", "annualSpend", 12000)), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private JsonNode openRequest(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> jdbc.sql(
                "SELECT count(*) FROM agent_human_request WHERE run_id = :run AND status = 'open'").param("run", run)
                .query(Integer.class).single() > 0);
        try {
            for (JsonNode item : api("fin.finance").get("/workspaces/{ws}/agent-requests", PURCHASING).body()) {
                if (item.path("runId").asString().equals(run.toString())) {
                    return item;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("the reviewer does not see the request");
    }

    private String loop(UUID run, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM agent_loop WHERE run_id = :run").param("run", run)
                .query(String.class).optional().orElse(null);
    }

    private void awaitTask(UUID run, String node) {
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(300))
                .until(() -> openTask(PURCHASING, run, node) != null);
    }

    @Test
    void anAgentAsksAPersonWaitsDurablyAndContinuesWithTheAnswer() throws Exception {
        String agent = durableAgent(6, AgentLoopTest::askTool);
        UUID workflow = publish(agent);
        AgentLlmStub.toolCall("c1", server + "__" + LOOKUP, ACME, 0.0001);
        AgentLlmStub.toolCall("c2", "ask_reviewer", "{\"question\":\"Is Acme Ltd on the preferred supplier list?\"}",
                0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);

        UUID run = startVendor(workflow);
        JsonNode request = openRequest(run);

        // Two turns, each its own job; the loop waits with its conversation encrypted.
        assertThat(loop(run, "status")).isEqualTo("waiting");
        assertThat(loop(run, "turns")).isEqualTo("2");
        assertThat(loop(run, "wait_kind")).isEqualTo("ask");
        assertThat(loop(run, "state_cipher")).isNotNull().doesNotContain("Acme").doesNotContain("preferred");
        assertThat(jdbc.sql("SELECT detail_cipher FROM agent_human_request WHERE run_id = :run").param("run", run)
                .query(String.class).single()).doesNotContain("preferred");
        assertThat(request.path("kind").asString()).isEqualTo("ask");
        assertThat(request.path("detail").path("question").asString()).contains("preferred supplier list");
        assertThat(request.path("canAnswer").asBoolean()).isTrue();
        assertThat(McpStub.toolCalls()).hasSize(1);
        // an operator sees that it waits, not what was asked
        JsonNode operatorView = api("olga.operator").get("/workspaces/{ws}/agent-requests", PURCHASING).body();
        for (JsonNode item : operatorView) {
            if (item.path("runId").asString().equals(run.toString())) {
                assertThat(item.path("detail").isNull()).isTrue();
                assertThat(item.path("canAnswer").asBoolean()).isFalse();
            }
        }
        // the answer must fit the form
        assertThat(api("fin.finance").post(Map.of("answer", Map.of("note", 5), "expectedVersion", 1),
                "/workspaces/{ws}/agent-requests/{id}/answer", PURCHASING, request.path("id").asString()).status())
                .isEqualTo(422);

        Response answered = api("fin.finance").post(Map.of("answer", Map.of("note", "Yes, preferred since 2024"),
                "expectedVersion", 1), "/workspaces/{ws}/agent-requests/{id}/answer", PURCHASING,
                request.path("id").asString());
        assertThat(answered.status()).as(answered.toString()).isEqualTo(200);
        assertThat(answered.body().path("status").asString()).isEqualTo("answered");
        assertThat(api("fin.finance").post(Map.of("answer", Map.of("note", "again"), "expectedVersion", 2),
                "/workspaces/{ws}/agent-requests/{id}/answer", PURCHASING, request.path("id").asString()).status())
                .isEqualTo(409);

        awaitTask(run, "compliance");
        assertThat(loop(run, "status")).isEqualTo("succeeded");
        assertThat(loop(run, "state_cipher")).isNull();
        assertThat(jdbc.sql("SELECT coalesce(detail_cipher, '') || coalesce(answer_cipher, '') FROM agent_human_request "
                + "WHERE run_id = :run").param("run", run).query(String.class).single()).isEmpty();
        assertThat(AgentLlmStub.requests()).hasSize(3);
        assertThat(AgentLlmStub.requests().get(2).path("messages").toString()).contains("A person answered")
                .contains("Yes, preferred since 2024");
        String call = jdbc.sql("SELECT to_jsonb(c)::text FROM agent_call c WHERE run_id = :run").param("run", run)
                .query(String.class).single();
        assertThat(json.parse(call).path("turns").asInt()).isEqualTo(3);
        assertThat(call).contains("ask_reviewer").doesNotContain("preferred").doesNotContain("Acme");
        assertThat(jdbc.sql("SELECT state || ' attempts=' || attempts FROM effect_journal WHERE run_id = :run "
                + "AND node_id = 'assess'").param("run", run).query(String.class).single())
                .isEqualTo("succeeded attempts=1");
        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'agent.request.answered' "
                + "AND resource_id = :id").param("id", request.path("id").asString()).query(String.class).single())
                .doesNotContain("preferred");
        // no timeline entry per turn, one per wait
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = 'EFFECT_DEFERRED' "
                + "AND data ->> 'reason' = 'AGENT_TURN'").param("run", run).query(Integer.class).single()).isZero();
    }

    @Test
    void anApprovalReleasesTheCallAndTheRequesterCannotApproveIt() throws Exception {
        String agent = agent("approval-agent", a -> {
            ObjectNode lookup = tool(a.putArray("tools"), server + "." + LOOKUP, "Vendor registry");
            lookup.putObject("requiresApproval").put("group", "compliance-reviewers");
            a.putObject("loop").put("maxTurns", 4).put("maxToolCalls", 4).put("maxDuration", "PT1H").put("budgetUsd", 1);
        });
        UUID workflow = publish(agent);
        AgentLlmStub.toolCall("c1", server + "__" + LOOKUP, ACME, 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);

        UUID run = startVendor(workflow);
        JsonNode request = openRequest(run);
        assertThat(request.path("kind").asString()).isEqualTo("approval");
        assertThat(request.path("detail").path("arguments").path("vendorName").asString()).isEqualTo("Acme Ltd");
        assertThat(McpStub.toolCalls()).isEmpty();

        // Separation of duties: the person whose request the agent works on never approves its calls.
        UUID reviewer = jdbc.sql("SELECT id FROM subject WHERE external_subject = 'kc-fin.finance'").query(UUID.class)
                .single();
        UUID requester = jdbc.sql("SELECT requested_by FROM agent_human_request WHERE run_id = :run").param("run", run)
                .query(UUID.class).single();
        jdbc.sql("UPDATE agent_human_request SET requested_by = :s WHERE run_id = :run").param("s", reviewer)
                .param("run", run).update();
        Response self = api("fin.finance").post(Map.of("decision", "approve", "expectedVersion", 1),
                "/workspaces/{ws}/agent-requests/{id}/answer", PURCHASING, request.path("id").asString());
        assertThat(self.status()).as(self.toString()).isEqualTo(403);
        assertThat(self.body().path("code").asString()).isEqualTo("SEPARATION_OF_DUTIES");
        jdbc.sql("UPDATE agent_human_request SET requested_by = :s WHERE run_id = :run").param("s", requester)
                .param("run", run).update();

        Response approved = api("fin.finance").post(Map.of("decision", "approve", "reason", "Registry is fine",
                "expectedVersion", 1), "/workspaces/{ws}/agent-requests/{id}/answer", PURCHASING,
                request.path("id").asString());
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        awaitTask(run, "compliance");
        assertThat(McpStub.toolCalls()).hasSize(1);

        // A rejection: the call is never made and the model is told.
        AgentLlmStub.toolCall("c2", server + "__" + LOOKUP, ACME, 0.0001);
        AgentLlmStub.answer(answer(100, "high", false), 0.0001);
        UUID second = startVendor(workflow);
        JsonNode next = openRequest(second);
        assertThat(api("fin.finance").post(Map.of("decision", "reject", "expectedVersion", 1),
                "/workspaces/{ws}/agent-requests/{id}/answer", PURCHASING, next.path("id").asString()).status())
                .isEqualTo(200);
        awaitTask(second, "compliance");
        assertThat(McpStub.toolCalls()).hasSize(1);
        assertThat(AgentLlmStub.requests().get(AgentLlmStub.requests().size() - 1).path("messages").toString())
                .contains("a person declined this call");
    }

    @Test
    void theTurnLimitStopsALoopThatNeverAnswers() throws Exception {
        String agent = durableAgent(2, tools -> {
        });
        UUID workflow = publish(agent);
        for (int i = 0; i < 4; i++) {
            AgentLlmStub.toolCall("c" + i, server + "__" + LOOKUP, ACME, 0.0001);
        }

        UUID run = startVendor(workflow);
        awaitLifecycle(PURCHASING, run, "needsAttention", "completed");

        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("attention").path("reason").asString()).as(detail.toString()).isEqualTo("RESPONSE_INVALID");
        assertThat(loop(run, "status")).isEqualTo("failed");
        assertThat(loop(run, "error_code")).isEqualTo("TOO_MANY_TURNS");
        assertThat(loop(run, "state_cipher")).isNull();
        assertThat(AgentLlmStub.requests()).hasSize(3);
    }

    @Test
    void theKillSwitchStopsAWaitingLoopAtItsNextTurn() throws Exception {
        String agent = durableAgent(6, AgentLoopTest::askTool);
        UUID workflow = publish(agent);
        AgentLlmStub.toolCall("c1", "ask_reviewer", "{\"question\":\"Proceed?\"}", 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);
        UUID run = startVendor(workflow);
        JsonNode request = openRequest(run);

        assertThat(api(ADMIN).put(Map.of("active", true, "reason", "Stop this agent"), null,
                "/workspaces/{ws}/agent-kill-switches/agent/{key}", PURCHASING, agent).status()).isEqualTo(200);
        assertThat(api("fin.finance").post(Map.of("answer", Map.of("note", "go"), "expectedVersion", 1),
                "/workspaces/{ws}/agent-requests/{id}/answer", PURCHASING, request.path("id").asString()).status())
                .isEqualTo(200);
        awaitLifecycle(PURCHASING, run, "needsAttention", "completed");

        assertThat(run(PURCHASING, run).path("attention").path("reason").asString()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(loop(run, "error_code")).isEqualTo("AGENT_STOPPED");
        assertThat(AgentLlmStub.requests()).hasSize(1);
    }

    @Test
    void aChildWorkflowIsAToolTheLoopWaitsFor() throws Exception {
        UUID childWorkflow = publishPurchase(PURCHASING, null);
        String childKey = jdbc.sql("SELECT key FROM workflow WHERE id = :id").param("id", childWorkflow)
                .query(String.class).single();
        String agent = durableAgent(6, tools -> {
            ObjectNode purchase = tool(tools, "request_purchase", "Purchase request");
            purchase.put("description", "Starts a purchase request for the vendor's first order and waits for it.");
            purchase.putObject("source").putObject("workflow").put("key", childKey).put("release", 1);
        });
        UUID workflow = publish(agent);
        AgentLlmStub.toolCall("c1", "request_purchase", json.write(json.mapper().valueToTree(input(
                new java.math.BigDecimal("100")))), 0.0001);
        AgentLlmStub.answer(answer(72, "high", false), 0.0001);

        UUID run = startVendor(workflow);
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> loop(run, "wait_ref") != null);
        UUID child = UUID.fromString(loop(run, "wait_ref"));
        assertThat(jdbc.sql("SELECT parent_run_id::text FROM workflow_run WHERE id = :id").param("id", child)
                .query(String.class).single()).isEqualTo(run.toString());
        awaitTask(child, "manager");
        approve("mani.manager", PURCHASING, child, "manager");

        awaitTask(run, "compliance");
        assertThat(lifecycle(PURCHASING, child)).isEqualTo("completed");
        assertThat(AgentLlmStub.requests().get(1).path("messages").toString()).contains("The child request ended")
                .contains("success");
        assertThat(loop(run, "status")).isEqualTo("succeeded");
    }
}
