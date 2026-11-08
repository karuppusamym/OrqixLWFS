package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.orqix.support.AiTaskStub;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/**
 * S146 part C, CG-A07 "Explain this request" on real PostgreSQL and Flowable: a request whose procurement call is
 * refused with 403 while a parallel branch waits for a one-day timer. AI answers come from {@link AiTaskStub}.
 */
class RunExplainTest extends RuntimeTestBase {

    private static final String START = "{\"id\":\"start\",\"type\":\"start\",\"label\":\"Request submitted\",\"config\":{}}";

    @AfterEach
    void cleanUp() {
        com.orqix.support.TestDatabase.legacyFixtureJdbc().sql("DELETE FROM orqix.workspace_feature").update();
        jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND permission = 'connection.manage'"
                + " AND principal_value = 'orqix-purchasing-operators'").param("ws", PURCHASING).update();
    }

    private UUID publishSplit() throws Exception {
        return publishPurchase(PURCHASING, d -> {
            JsonNode procure = null;
            for (JsonNode n : d.path("nodes")) {
                if ("procure".equals(n.path("id").asString())) {
                    procure = n;
                }
            }
            d.set("nodes", json.parse("[" + START + ","
                    + "{\"id\":\"split\",\"type\":\"parallelSplit\",\"label\":\"Together\",\"config\":{\"joinRef\":\"join\"}},"
                    + json.write(procure) + ","
                    + "{\"id\":\"pause\",\"type\":\"wait\",\"label\":\"Cool-off period\",\"config\":{\"duration\":\"P1D\"}},"
                    + "{\"id\":\"join\",\"type\":\"parallelJoin\",\"label\":\"Both done\",\"config\":{}},"
                    + "{\"id\":\"done\",\"type\":\"end\",\"label\":\"Fulfilled\",\"config\":{\"businessOutcome\":\"Fulfilled\",\"outcomeKind\":\"success\"}}]"));
            d.set("transitions", json.parse("["
                    + "{\"id\":\"t1\",\"from\":\"start\",\"to\":\"split\",\"on\":\"next\"},"
                    + "{\"id\":\"t2\",\"from\":\"split\",\"to\":\"procure\",\"on\":\"next\"},"
                    + "{\"id\":\"t3\",\"from\":\"split\",\"to\":\"pause\",\"on\":\"next\"},"
                    + "{\"id\":\"t4\",\"from\":\"procure\",\"to\":\"join\",\"on\":\"next\"},"
                    + "{\"id\":\"t5\",\"from\":\"pause\",\"to\":\"join\",\"on\":\"next\"},"
                    + "{\"id\":\"t6\",\"from\":\"join\",\"to\":\"done\",\"on\":\"next\"}]"));
            d.set("decisions", json.parse("[]"));
            d.set("outputMapping", json.object());
            d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
            d.remove("uiLayout");
        });
    }

    /** Lifecycle, version, steps, attention, timeline length, effects and engine jobs: everything explain must not touch. */
    private List<Object> state(UUID runId) {
        List<Object> s = new ArrayList<>(jdbc.sql("""
                SELECT lifecycle, version, current_steps::text, attention::text, event_seq FROM workflow_run WHERE id = :id
                """).param("id", runId).query((rs, n) -> List.<Object>of(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5))).single());
        s.add(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :id").param("id", runId).query(Long.class).single());
        s.add(jdbc.sql("SELECT string_agg(state || ':' || attempts, ',') FROM effect_journal WHERE run_id = :id")
                .param("id", runId).query(String.class).single());
        String pid = processInstance(PURCHASING, runId);
        s.add(managementService.createTimerJobQuery().processInstanceId(pid).count());
        s.add(managementService.createDeadLetterJobQuery().processInstanceId(pid).count());
        s.add(Stub.postCount());
        return s;
    }

    private Response explain(String user, UUID runId, Object body) throws Exception {
        return api(user).post(body, "/workspaces/{ws}/runs/{id}/explain", PURCHASING, runId);
    }

    private long generations(UUID runId) {
        return jdbc.sql("""
                SELECT count(*) FROM ai_generation g JOIN workflow_run r ON r.workflow_id = g.workflow_id
                WHERE r.id = :id AND g.kind = 'aiExplain'
                """).param("id", runId).query(Long.class).single();
    }

    private static List<String> ids(JsonNode list, String field) {
        List<String> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.path(field).asString()));
        return ids;
    }

    @Test
    void explainsAStuckRequestWithoutChangingIt() throws Exception {
        Stub.fault(Stub.Mode.STATUS403, 1, 0);
        UUID workflowId = publishSplit();
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflowId, new BigDecimal("1800"))
                .path("id").asString());
        // No fast-forward: the timer branch must keep waiting; a 403 is definitive and stops the call at once.
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> "needsAttention".equals(lifecycle(PURCHASING, runId)));
        List<Object> before = state(runId);
        assertThat((long) before.get(7)).as("timer still waiting").isEqualTo(1L);
        AiTaskStub.reset();

        // ---- AI explanation (operator)
        Response first = explain("olga.operator", runId, Map.of());
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        JsonNode e = first.body();
        assertThat(e.path("source").asString()).isEqualTo("ai");
        assertThat(e.path("readOnly").asBoolean()).isTrue();
        assertThat(e.path("summary").asString()).startsWith("Stub explanation").contains("needsAttention");
        List<String> permitted = ids(e.path("permittedActions"), "id");
        String effectId = run(PURCHASING, runId).path("effects").get(0).path("id").asString();
        assertThat(permitted).containsExactly("askAdministrator:connection:procurement", "reconcileEffect:" + effectId,
                "retryRun", "cancelRun");
        assertThat(ids(e.path("suggestedActions"), "actionId")).containsExactly("askAdministrator:connection:procurement");
        assertThat(e.path("droppedActions").size()).isEqualTo(1);
        assertThat(e.path("droppedActions").get(0).asString()).isEqualTo("deleteRequest");
        // The stub cites F1, F2 and the unknown F999; only known fact ids survive.
        List<String> evidence = new ArrayList<>();
        e.path("evidence").forEach(n -> evidence.add(n.asString()));
        assertThat(evidence).containsExactly("F1", "F2");
        String facts = e.path("facts").toString();
        assertThat(facts).contains("HTTP_403").contains("Cool-off period").contains("timer").contains("procurement");
        assertThat(facts).doesNotContain("Replacement laptop", "Device failed", "\"OPS\"", "injected forbidden");
        assertThat(e.path("ai").path("cached").asBoolean()).isFalse();
        assertThat(e.path("ai").path("profile").asString()).isEqualTo("task-openai");
        // What reached the model: facts as delimited data, temperature 0, strict schema, no request values.
        List<AiTaskStub.Call> calls = AiTaskStub.calls();
        assertThat(calls).hasSize(1);
        String sent = calls.get(0).body().toString();
        assertThat(sent).contains("<run_facts>").contains("askAdministrator:connection:procurement")
                .doesNotContain("Replacement laptop").doesNotContain("Device failed");
        assertThat(calls.get(0).body().path("temperature").asDouble(-1)).isEqualTo(0.0);
        assertThat(generations(runId)).isEqualTo(1);

        // ---- same state, same caller: the answer is reused, nothing metered again
        JsonNode again = explain("olga.operator", runId, null).body();
        assertThat(again.path("ai").path("cached").asBoolean()).isTrue();
        assertThat(again.path("stateDigest").asString()).isEqualTo(e.path("stateDigest").asString());
        assertThat(generations(runId)).isEqualTo(1);
        assertThat(AiTaskStub.calls()).hasSize(1);

        // ---- rules on request
        JsonNode rules = explain("olga.operator", runId, Map.of("preferRules", true)).body();
        assertThat(rules.path("source").asString()).isEqualTo("rules");
        assertThat(rules.path("fallbackReason").isNull()).isTrue();
        assertThat(rules.path("ai").isNull()).isTrue();
        assertThat(rules.path("summary").asString()).contains("procurement has refused the call with HTTP 403 since")
                .contains("waits for a timer until");
        assertThat(ids(rules.path("suggestedActions"), "actionId")).containsExactly(
                "askAdministrator:connection:procurement", "retryRun", "cancelRun");

        // ---- AI switched off in the workspace: the rules answer, no model call
        assertThat(api("wade.admin").put(Map.of("enabled", false), null, "/workspaces/{ws}/features/{feature}",
                PURCHASING, "ai").status()).isEqualTo(200);
        JsonNode off = explain("olga.operator", runId, Map.of()).body();
        assertThat(off.path("source").asString()).isEqualTo("rules");
        assertThat(off.path("fallbackReason").asString()).isEqualTo("featureOff");
        assertThat(off.path("summary").asString()).contains("HTTP 403");
        assertThat(AiTaskStub.calls()).hasSize(1);
        assertThat(api("wade.admin").put(Map.of("enabled", true), null, "/workspaces/{ws}/features/{feature}",
                PURCHASING, "ai").status()).isEqualTo(200);

        // ---- a caller who may manage connections gets the binding action instead of "ask an administrator"
        Response grant = api("wade.admin").post(Map.of("permission", "connection.manage", "principal",
                Map.of("type", "idpGroup", "value", "orqix-purchasing-operators")), "/workspaces/{ws}/access/grants",
                PURCHASING);
        assertThat(grant.status()).as(grant.toString()).isEqualTo(201);
        JsonNode managed = explain("olga.operator", runId, Map.of("preferRules", true)).body();
        List<String> managedIds = ids(managed.path("permittedActions"), "id");
        assertThat(managedIds).contains("updateConnectionBinding:procurement")
                .doesNotContain("askAdministrator:connection:procurement");
        assertThat(ids(managed.path("suggestedActions"), "actionId")).startsWith("updateConnectionBinding:procurement");
        assertThat(managed.path("stateDigest").asString()).isNotEqualTo(rules.path("stateDigest").asString());
        api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, grant.body().path("id").asString());

        // ---- who may ask
        assertThat(explain("vic.viewer", runId, Map.of()).status()).isEqualTo(403);
        assertThat(explain("rhea.requester", runId, Map.of()).status()).isEqualTo(403);
        assertThat(explain("otto.outsider", runId, Map.of()).status()).isEqualTo(404);
        assertThat(explain("fred.facilities", runId, Map.of()).status()).isEqualTo(404);
        assertThat(explain("olga.operator", UUID.randomUUID(), Map.of()).status()).isEqualTo(404);

        // ---- audit holds counts and codes only; the request did not change at all
        List<String> audits = jdbc.sql("""
                SELECT metadata::text FROM audit_event WHERE action = 'ai.run.explained' AND resource_id = :run
                ORDER BY occurred_at
                """).param("run", runId.toString()).query(String.class).list();
        assertThat(audits).hasSize(5);
        assertThat(audits.get(0)).contains("\"source\": \"ai\"").contains("\"droppedCount\": 1")
                .doesNotContain("Stub explanation").doesNotContain("HTTP 403");
        assertThat(audits.get(3)).contains("featureOff");
        assertThat(state(runId)).isEqualTo(before);
        assertThat(lifecycle(PURCHASING, runId)).isEqualTo("needsAttention");
    }
}
