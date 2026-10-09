package com.orqix.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Helpers for run/task/effect integration tests on the purchase template. */
public abstract class RuntimeTestBase extends IntegrationTestBase {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ManagementService managementService;

    @BeforeEach
    void resetStub() {
        Stub.reset();
        bindStub(PURCHASING);
        bindStub(SANDBOX);
    }

    /** Points the seeded procurement binding at the WireMock stub (enabled, no auth). */
    protected void bindStub(UUID workspaceId) {
        jdbc.sql("""
                UPDATE connection_binding SET base_url = :url, enabled = true, auth_type = 'none', secret_ref = NULL,
                       auth_header_name = NULL
                WHERE workspace_id = :ws AND connection_key = 'procurement'
                """)
                .param("url", Stub.baseUrl()).param("ws", workspaceId).update();
    }

    /** Creates a workflow from the template (optionally patched) and publishes it; returns the workflow id. */
    protected UUID publishPurchase(UUID workspaceId, Consumer<ObjectNode> patch) throws Exception {
        String designer = "dana.designer";
        String publisher = workspaceId.equals(SANDBOX) ? "dana.designer" : "paul.publisher";
        String key = "pa-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = api(designer).post(Map.of("key", key, "name", "Purchase " + key,
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", workspaceId);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        int revision = 1;
        ObjectNode definition = (ObjectNode) api(designer).get("/workspaces/{ws}/workflows/{id}/draft", workspaceId, id)
                .body().path("definition");
        // Fast retries for tests: shortest allowed delay, short HTTP timeout.
        ((ObjectNode) definition.path("execution")).put("retryDelaySeconds", 5);
        for (JsonNode node : definition.path("nodes")) {
            if ("http".equals(node.path("type").asString())) {
                ((ObjectNode) node.path("config")).put("timeoutSeconds", 3);
            }
        }
        if (patch != null) {
            patch.accept(definition);
        }
        Response saved = api(designer).put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", workspaceId, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        revision = 2;
        Response published = api(publisher).post(Map.of("draftRevision", revision, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", workspaceId, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return id;
    }

    protected Map<String, Object> input(BigDecimal amount) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", "Replacement laptop");
        input.put("purpose", "Device failed");
        input.put("costCenter", "OPS");
        input.put("amount", amount);
        return input;
    }

    protected JsonNode start(String user, UUID workspaceId, UUID workflowId, BigDecimal amount) throws Exception {
        Response started = api(user).post(Map.of("workflowId", workflowId, "input", input(amount)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", workspaceId);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return started.body();
    }

    /** Open task of {@code nodeId} in the run (read by the operator). */
    protected JsonNode openTask(UUID workspaceId, UUID runId, String nodeId) throws Exception {
        JsonNode run = api("olga.operator").get("/workspaces/{ws}/runs/{id}", workspaceId, runId).body();
        for (JsonNode task : run.path("tasks")) {
            if (nodeId.equals(task.path("nodeId").asString()) && "open".equals(task.path("status").asString())) {
                return task;
            }
        }
        return null;
    }

    protected Response claim(String user, UUID workspaceId, String taskId) throws Exception {
        JsonNode task = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", workspaceId, taskId).body();
        return api(user).post(Map.of("expectedVersion", task.path("version").asInt()),
                "/workspaces/{ws}/tasks/{id}/claim", workspaceId, taskId);
    }

    protected Response decide(String user, UUID workspaceId, String taskId, String action, String reason)
            throws Exception {
        JsonNode task = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", workspaceId, taskId).body();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", task.path("version").asInt());
        body.put("action", action);
        if (reason != null) {
            body.put("reason", reason);
        }
        return api(user).post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/tasks/{id}/decision", workspaceId,
                taskId);
    }

    /** Claims then decides; asserts both succeed. */
    protected void approve(String user, UUID workspaceId, UUID runId, String nodeId) throws Exception {
        JsonNode task = openTask(workspaceId, runId, nodeId);
        assertThat(task).as("open task " + nodeId).isNotNull();
        Response claimed = claim(user, workspaceId, task.path("id").asString());
        assertThat(claimed.status()).as(claimed.toString()).isEqualTo(200);
        Response decided = decide(user, workspaceId, task.path("id").asString(), "approve", null);
        assertThat(decided.status()).as(decided.toString()).isEqualTo(201);
    }

    protected String lifecycle(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT lifecycle FROM workflow_run WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", runId).query(String.class).single();
    }

    /** Waits until the run reaches one of the lifecycles, fast-forwarding retry timers. */
    protected void awaitLifecycle(UUID workspaceId, UUID runId, String... lifecycles) {
        Set<String> wanted = Set.of(lifecycles);
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            fastForward(workspaceId, runId);
            return wanted.contains(lifecycle(workspaceId, runId));
        });
    }

    protected void fastForward(UUID workspaceId, UUID runId) {
        String pid = processInstance(workspaceId, runId);
        if (pid == null) {
            return;
        }
        for (Job timer : managementService.createTimerJobQuery().processInstanceId(pid).list()) {
            try {
                managementService.moveTimerToExecutableJob(timer.getId());
            } catch (RuntimeException ignored) {
                // picked up concurrently
            }
        }
    }

    protected String processInstance(UUID workspaceId, UUID runId) {
        return jdbc.sql("SELECT engine_process_instance_id FROM workflow_run WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", runId).query(String.class).optional().orElse(null);
    }

    protected int notifications(UUID workspaceId, UUID runId, String kind) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE workspace_id = :ws AND run_id = :run AND kind = :kind")
                .param("ws", workspaceId).param("run", runId).param("kind", kind).query(Integer.class).single();
    }

    protected JsonNode run(UUID workspaceId, UUID runId) throws Exception {
        return api("olga.operator").get("/workspaces/{ws}/runs/{id}", workspaceId, runId).body();
    }
}
