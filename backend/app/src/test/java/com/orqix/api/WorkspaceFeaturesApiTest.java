package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.orqix.identity.WorkspaceFeatures;
import com.orqix.integration.kafka.KafkaTriggerService;
import com.orqix.support.Fixtures;
import com.orqix.support.OpenApiContract;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Workspace feature switches (docs/build/S127): only workspace administrators switch modules, each change is audited
 * and on /me, a switched-off module refuses its gated operations with 409 FEATURE_DISABLED after the 404 visibility
 * check, reads and running work continue, kind-dependent gates (agent connections, Kafka adapters) apply only to that
 * kind, Kafka triggers pause, and the server's gate table matches x-orqix-feature in openapi.yaml. Same Kafka stub
 * properties as KafkaTriggerApiTest so the Spring context is shared.
 */
@TestPropertySource(properties = {
        "orqix.kafka.mode=stub",
        "orqix.kafka.clusters.main.bootstrap-servers=localhost:1",
        "orqix.kafka.clusters.main.security-protocol=PLAINTEXT",
        "orqix.kafka.clusters.main.allowed-topics=orders,payments"})
class WorkspaceFeaturesApiTest extends RuntimeTestBase {

    @Autowired
    private KafkaTriggerService kafkaTriggers;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    @AfterEach
    void everythingOn() {
        // Other test classes share the database: never leave a module off (owner connection, no audit).
        com.orqix.support.TestDatabase.legacyFixtureJdbc().sql("DELETE FROM orqix.workspace_feature").update();
    }

    private Response set(String user, UUID ws, String feature, boolean enabled) throws Exception {
        return api(user).put(Map.of("enabled", enabled), null, "/workspaces/{ws}/features/{feature}", ws, feature);
    }

    private JsonNode state(JsonNode list, String feature) {
        for (JsonNode item : list.path("items")) {
            if (feature.equals(item.path("feature").asString())) {
                return item;
            }
        }
        throw new AssertionError("no " + feature);
    }

    private List<String> disabledOnMe(String user, UUID ws) throws Exception {
        for (JsonNode w : api(user).get("/me").body().path("workspaces")) {
            if (ws.toString().equals(w.path("id").asString())) {
                return w.path("disabledFeatures").valueStream().map(JsonNode::asString).toList();
            }
        }
        throw new AssertionError("not a member");
    }

    private long audits(UUID ws, String feature, boolean enabled) {
        return jdbc.sql("""
                SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND action = 'workspace.feature.updated'
                  AND metadata ->> 'feature' = :feature AND metadata ->> 'enabled' = :enabled
                """).param("ws", ws).param("feature", feature).param("enabled", String.valueOf(enabled))
                .query(Long.class).single();
    }

    private Response createCaseDefinition(String key) throws Exception {
        String source;
        try (var input = getClass().getResourceAsStream("/cases/nested-stages.cmmn")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("nestedCase", key);
        }
        return api("dana.designer").post(Map.of("key", key, "name", "Switch check", "sourceXml", source),
                "/workspaces/" + SANDBOX + "/cases/definitions");
    }

    @Test
    void everythingIsOnByDefaultAndOnlyAdministratorsSwitch() throws Exception {
        Response list = api("rhea.requester").get("/workspaces/{ws}/features", PURCHASING);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().path("items").valueStream().map(i -> i.path("feature").asString()).toList())
                .containsExactly("ai", "cases", "migration", "agents", "apps", "kafka");
        assertThat(list.body().path("items").valueStream().allMatch(i -> i.path("enabled").asBoolean())).isTrue();
        assertThat(disabledOnMe("rhea.requester", PURCHASING)).isEmpty();

        assertThat(api("otto.outsider").get("/workspaces/{ws}/features", PURCHASING).status()).isEqualTo(404);
        assertThat(set("otto.outsider", PURCHASING, "cases", false).status()).isEqualTo(404);
        Response designer = set("dana.designer", PURCHASING, "cases", false);
        assertThat(designer.status()).isEqualTo(403);
        assertThat(designer.code()).isEqualTo("FORBIDDEN");
        Response unknown = set("wade.admin", PURCHASING, "everything", false);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(disabledOnMe("wade.admin", PURCHASING)).isEmpty();
    }

    @Test
    void switchingCasesOffBlocksNewCasesUntilItIsSwitchedOnAgainAndIsAudited() throws Exception {
        long offBefore = audits(SANDBOX, "cases", false);
        long onBefore = audits(SANDBOX, "cases", true);
        String key = "switch" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // A case started before the switch keeps running afterwards.
        Response running = createCaseDefinition(key + "a");
        assertThat(running.status()).as(running.toString()).isEqualTo(201);
        Response started = api("rhea.requester").post(Map.of(), "start-" + key,
                "/workspaces/" + SANDBOX + "/cases/definitions/" + running.body().path("id").asString() + "/instances");
        assertThat(started.status()).isEqualTo(201);
        String instance = started.body().path("id").asString();

        Response off = set("wade.admin", SANDBOX, "cases", false);
        assertThat(off.status()).as(off.toString()).isEqualTo(200);
        JsonNode cases = state(off.body(), "cases");
        assertThat(cases.path("enabled").asBoolean()).isFalse();
        assertThat(cases.path("updatedBy").path("displayName").asString()).isNotBlank();
        assertThat(disabledOnMe("dana.designer", SANDBOX)).containsExactly("cases");
        assertThat(audits(SANDBOX, "cases", false)).isEqualTo(offBefore + 1);
        // Switching off again changes nothing and writes no second record.
        assertThat(set("wade.admin", SANDBOX, "cases", false).status()).isEqualTo(200);
        assertThat(audits(SANDBOX, "cases", false)).isEqualTo(offBefore + 1);

        Response refused = createCaseDefinition(key + "b");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("FEATURE_DISABLED");
        assertThat(refused.body().path("feature").asString()).isEqualTo("cases");
        Response startRefused = api("rhea.requester").post(Map.of(), "again-" + key,
                "/workspaces/" + SANDBOX + "/cases/definitions/" + running.body().path("id").asString() + "/instances");
        assertThat(startRefused.code()).isEqualTo("FEATURE_DISABLED");
        // Outsiders still learn nothing: 404 before 409.
        assertThat(api("otto.outsider").post(Map.of("key", key, "name", "x", "sourceXml", "<x/>"),
                "/workspaces/" + SANDBOX + "/cases/definitions").status()).isEqualTo(404);
        // Reads and the running case continue.
        assertThat(api("rhea.requester").get("/workspaces/" + SANDBOX + "/cases/instances/" + instance + "/plan").status())
                .isEqualTo(200);
        assertThat(api("dana.designer").get("/workspaces/" + SANDBOX + "/cases/definitions").status()).isEqualTo(200);
        // Other modules and other workspaces are not affected.
        assertThat(disabledOnMe("wade.admin", PURCHASING)).isEmpty();

        Response on = set("wade.admin", SANDBOX, "cases", true);
        assertThat(state(on.body(), "cases").path("enabled").asBoolean()).isTrue();
        assertThat(audits(SANDBOX, "cases", true)).isEqualTo(onBefore + 1);
        assertThat(disabledOnMe("dana.designer", SANDBOX)).isEmpty();
        Response again = createCaseDefinition(key + "c");
        assertThat(again.status()).as(again.toString()).isEqualTo(201);
    }

    @Test
    void aiOffShowsInTheStatusAndRefusesAiCalls() throws Exception {
        assertThat(set("wade.admin", PURCHASING, "ai", false).status()).isEqualTo(200);
        Response status = api("dana.designer").get("/workspaces/{ws}/ai/status", PURCHASING);
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().path("enabled").asBoolean()).isFalse();
        assertThat(status.body().path("reason").asString()).contains("switched off");
        assertThat(status.body().path("profiles")).isEmpty();
        Response draft = api("dana.designer").post(Map.of("description", "A purchase approval for amounts over 1000"),
                UUID.randomUUID().toString(), "/workspaces/{ws}/ai/drafts", PURCHASING);
        assertThat(draft.status()).isEqualTo(409);
        assertThat(draft.body().path("feature").asString()).isEqualTo("ai");
    }

    @Test
    void agentsOffRefusesAgentConnectionsButNotHttpOnes() throws Exception {
        assertThat(set("wade.admin", SANDBOX, "agents", false).status()).isEqualTo(200);
        ObjectNode agent = (ObjectNode) Fixtures.load("examples/connections/vendor-risk-assistant.connection.json");
        String agentKey = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.put("key", agentKey);
        Response refused = api("wade.admin").post(Map.of("definition", agent),
                "/workspaces/{ws}/connections/{key}/versions", SANDBOX, agentKey);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(409);
        assertThat(refused.body().path("feature").asString()).isEqualTo("agents");

        ObjectNode http = (ObjectNode) Fixtures.procurementConnection();
        String httpKey = "http-" + UUID.randomUUID().toString().substring(0, 8);
        http.put("key", httpKey);
        Response allowed = api("wade.admin").post(Map.of("definition", http),
                "/workspaces/{ws}/connections/{key}/versions", SANDBOX, httpKey);
        assertThat(allowed.status()).as(allowed.toString()).isEqualTo(201);

        assertThat(set("wade.admin", SANDBOX, "agents", true).status()).isEqualTo(200);
        Response registered = api("wade.admin").post(Map.of("definition", agent),
                "/workspaces/{ws}/connections/{key}/versions", SANDBOX, agentKey);
        assertThat(registered.status()).as(registered.toString()).isEqualTo(201);
    }

    @Test
    void kafkaOffPausesTriggersAndRefusesKafkaAdaptersOnly() throws Exception {
        var owner = com.orqix.support.TestDatabase.legacyFixtureJdbc();
        owner.sql("DELETE FROM orqix.kafka_inbound_record WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger_status WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID wade = UUID.fromString(api("wade.admin").get("/me").body().path("subject").path("id").asString());
        Response grant = api("wade.admin").post(Map.of("permission", "workflow.start", "workflowId", workflowId,
                "principal", Map.of("type", "subject", "value", wade.toString())), "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(grant.status()).isIn(201, 409);
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("key", "feed-" + UUID.randomUUID().toString().substring(0, 8));
        trigger.put("name", "Orders feed");
        trigger.put("cluster", "main");
        trigger.put("topic", "orders");
        trigger.put("action", "start");
        trigger.put("workflowId", workflowId);
        trigger.put("mapping", Map.of("title", "/title", "purpose", "/purpose", "costCenter", "/costCenter", "amount", "/amount"));
        trigger.put("startFrom", "earliest");
        Response created = api("wade.admin").post(trigger, "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID triggerId = UUID.fromString(created.body().path("id").asString());
        assertThat(kafkaTriggers.enabled()).anyMatch(t -> t.id().equals(triggerId));

        assertThat(set("wade.admin", PURCHASING, "kafka", false).status()).isEqualTo(200);
        assertThat(kafkaTriggers.enabled()).as("the consumer runner stops this trigger").noneMatch(t -> t.id().equals(triggerId));
        trigger.put("key", "feed-" + UUID.randomUUID().toString().substring(0, 8));
        Response refused = api("wade.admin").post(trigger, "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(refused.code()).isEqualTo("FEATURE_DISABLED");
        Response test = api("wade.admin").post(Map.of("value", Map.of("title", "x")),
                "/workspaces/{ws}/kafka-triggers/{id}/test-message", PURCHASING, triggerId);
        assertThat(test.code()).isEqualTo("FEATURE_DISABLED");
        assertThat(api("wade.admin").get("/workspaces/{ws}/kafka-triggers", PURCHASING).status()).isEqualTo(200);
        String adapterKey = "kafka-" + UUID.randomUUID().toString().substring(0, 8);
        Response adapter = api("wade.admin").post(Map.of("key", adapterKey, "kind", "kafka", "name", "Orders out",
                "secretRef", "orders-ref", "config", Map.of("allowedTopics", List.of("orders"))), "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(adapter.status()).isEqualTo(409);
        assertThat(adapter.body().path("feature").asString()).isEqualTo("kafka");
        // A non-Kafka adapter is not part of this module.
        Response teams = api("wade.admin").post(Map.of("key", "teams-" + UUID.randomUUID().toString().substring(0, 8),
                "kind", "teams", "name", "Team channel", "secretRef", "teams-ref", "config", Map.of()),
                "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(teams.code()).isNotEqualTo("FEATURE_DISABLED");

        assertThat(set("wade.admin", PURCHASING, "kafka", true).status()).isEqualTo(200);
        assertThat(kafkaTriggers.enabled()).anyMatch(t -> t.id().equals(triggerId));
        Response createdAdapter = api("wade.admin").post(Map.of("key", adapterKey, "kind", "kafka", "name", "Orders out",
                "secretRef", "orders-ref", "config", Map.of("allowedTopics", List.of("orders"))), "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(createdAdapter.status()).as(createdAdapter.toString()).isEqualTo(201);
    }

    @Test
    void serverGateMatchesTheContract() {
        Map<String, String> contract = new java.util.TreeMap<>();
        Set<String> conditional = new TreeSet<>();
        JsonNode paths = OpenApiContract.get().document().path("paths");
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                JsonNode feature = op.getValue().path("x-orqix-feature");
                if (feature.isMissingNode()) {
                    continue;
                }
                String id = op.getValue().path("operationId").asString();
                Set<String> features = new TreeSet<>();
                if (feature.isArray()) {
                    feature.forEach(f -> features.add(f.asString()));
                } else {
                    features.add(feature.asString());
                }
                boolean when = op.getValue().has("x-orqix-feature-when");
                if (when) {
                    conditional.add(id);
                }
                contract.put(id, (when ? "" : op.getKey().toUpperCase() + " " + path.getKey() + " ") + features);
                assertThat(op.getValue().path("responses").has("409")).as(id + " declares 409").isTrue();
            }
        }
        Map<String, String> server = new java.util.TreeMap<>();
        WorkspaceFeatures.GATED_OPERATIONS.forEach((id, gate) -> server.put(id, gate.method() + " " + gate.path() + " "
                + new TreeSet<>(gate.features().stream().map(WorkspaceFeatures.Feature::value).toList())));
        WorkspaceFeatures.CONDITIONAL_OPERATIONS.forEach((id, feature) -> server.put(id, "[" + feature.value() + "]"));
        assertThat(server).isEqualTo(contract);
        assertThat(conditional).isEqualTo(new TreeSet<>(WorkspaceFeatures.CONDITIONAL_OPERATIONS.keySet()));

        // Every gated route is served by a controller under the same method and route, so the interceptor sees it.
        Set<String> routes = new HashSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().forEach(pattern -> {
            if (pattern.startsWith("/api/v1")) {
                info.getMethodsCondition().getMethods().forEach(m -> routes.add(
                        new WorkspaceFeatures.Gate(m.name(), pattern.substring("/api/v1".length()), Set.of()).route()));
            }
        }));
        assertThat(routes).containsAll(WorkspaceFeatures.GATED_ROUTES.keySet());
    }
}
