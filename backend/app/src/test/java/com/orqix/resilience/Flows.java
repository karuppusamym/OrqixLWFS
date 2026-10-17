package com.orqix.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Purchase-approval flow helpers for the resilience suite, written against {@link Node} so that every step can be sent
 * to a chosen instance. Same API paths and payloads as {@code com.orqix.support.RuntimeTestBase}.
 */
public final class Flows {

    public static final UUID PURCHASING = Node.PURCHASING;
    public static final BigDecimal SMALL = new BigDecimal("1800");

    private Flows() {
    }

    /** Points the seeded procurement binding at the WireMock stub (enabled, no auth). */
    public static void bindStub(Node node, UUID workspaceId, String stubBaseUrl) {
        node.jdbc().sql("""
                UPDATE connection_binding SET base_url = :url, enabled = true, auth_type = 'none', secret_ref = NULL,
                       auth_header_name = NULL
                WHERE workspace_id = :ws AND connection_key = 'procurement'
                """)
                .param("url", stubBaseUrl).param("ws", workspaceId).update();
    }

    /** Creates a workflow from the purchase template (patched) and publishes it; returns the workflow id. */
    public static UUID publishPurchase(Node node, UUID workspaceId, int httpTimeoutSeconds,
            Consumer<ObjectNode> patch) throws Exception {
        Client designer = node.as("dana.designer");
        Client publisher = node.as("paul.publisher");
        String key = "res-" + UUID.randomUUID().toString().substring(0, 8);
        Resp created = designer.post(Map.of("key", key, "name", "Purchase " + key, "templateId", "purchase-approval"),
                "/workspaces/{ws}/workflows", workspaceId);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        ObjectNode definition = (ObjectNode) designer.get("/workspaces/{ws}/workflows/{id}/draft", workspaceId, id)
                .body().path("definition");
        ((ObjectNode) definition.path("execution")).put("retryDelaySeconds", 5);
        for (JsonNode n : definition.path("nodes")) {
            if ("http".equals(n.path("type").asString())) {
                ((ObjectNode) n.path("config")).put("timeoutSeconds", httpTimeoutSeconds);
            }
        }
        if (patch != null) {
            patch.accept(definition);
        }
        Resp saved = designer.put(Map.of("definition", definition), "\"r1\"", "/workspaces/{ws}/workflows/{id}/draft",
                workspaceId, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Resp published = publisher.post(Map.of("draftRevision", 2, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", workspaceId, id);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return id;
    }

    public static Map<String, Object> input(BigDecimal amount) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", "Replacement laptop");
        input.put("purpose", "Device failed");
        input.put("costCenter", "OPS");
        input.put("amount", amount);
        return input;
    }

    public static UUID start(Node node, String user, UUID workspaceId, UUID workflowId, BigDecimal amount)
            throws Exception {
        Resp started = node.as(user).post(Map.of("workflowId", workflowId, "input", input(amount)),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", workspaceId);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    public static JsonNode run(Node node, UUID workspaceId, UUID runId) throws Exception {
        return node.as("olga.operator").get("/workspaces/{ws}/runs/{id}", workspaceId, runId).body();
    }

    /** Open task of {@code nodeId} in the run (read by the operator), or null. */
    public static JsonNode openTask(Node node, UUID workspaceId, UUID runId, String nodeId) throws Exception {
        for (JsonNode task : run(node, workspaceId, runId).path("tasks")) {
            if (nodeId.equals(task.path("nodeId").asString()) && "open".equals(task.path("status").asString())) {
                return task;
            }
        }
        return null;
    }

    public static Resp claim(Node node, String user, UUID workspaceId, String taskId) throws Exception {
        JsonNode task = node.as("olga.operator").get("/workspaces/{ws}/tasks/{id}", workspaceId, taskId).body();
        return node.as(user).post(Map.of("expectedVersion", task.path("version").asInt()),
                "/workspaces/{ws}/tasks/{id}/claim", workspaceId, taskId);
    }

    public static Resp decide(Node node, String user, UUID workspaceId, String taskId, String action) throws Exception {
        JsonNode task = node.as("olga.operator").get("/workspaces/{ws}/tasks/{id}", workspaceId, taskId).body();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", task.path("version").asInt());
        body.put("action", action);
        return node.as(user).post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/tasks/{id}/decision",
                workspaceId, taskId);
    }

    /** Claims then decides (approve) on {@code node}; asserts both succeed. */
    public static void approve(Node node, String user, UUID workspaceId, UUID runId, String nodeId) throws Exception {
        JsonNode task = openTask(node, workspaceId, runId, nodeId);
        assertThat(task).as("open task " + nodeId + " of " + runId).isNotNull();
        String taskId = task.path("id").asString();
        Resp claimed = claim(node, user, workspaceId, taskId);
        assertThat(claimed.status()).as(claimed.toString()).isEqualTo(200);
        Resp decided = decide(node, user, workspaceId, taskId, "approve");
        assertThat(decided.status()).as(decided.toString()).isEqualTo(201);
    }

    public static String lifecycle(Node node, UUID workspaceId, UUID runId) {
        return node.jdbc().sql("SELECT lifecycle FROM workflow_run WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", runId).query(String.class).single();
    }

    /** Waits until the run reaches one of the lifecycles (no timer fast-forwarding: real retry delays apply). */
    public static void awaitLifecycle(Node node, UUID workspaceId, UUID runId, Duration atMost, String... lifecycles) {
        Set<String> wanted = Set.of(lifecycles);
        Awaitility.await().atMost(atMost).pollInterval(Duration.ofMillis(250))
                .until(() -> wanted.contains(lifecycle(node, workspaceId, runId)));
    }
}
