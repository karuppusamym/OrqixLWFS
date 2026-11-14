package com.orqix.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.integration.alerts.LocalAlertHookStub;
import com.orqix.integration.adapters.LocalStubTransport;
import com.orqix.operations.alerts.AlertEngine;
import com.orqix.operations.alerts.AlertSignals;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * CG-O06 (docs/build/S144): alert rules under alert.manage (grant-only), evaluated on real signal state, deduplicated
 * (one open incident, one notification per destination and kind), with quiet periods, silences, resolve
 * notifications and retried deliveries. Slack and PagerDuty go to the local stub; webhook destinations queue a signed
 * S99 delivery. Messages carry no request values.
 */
class AlertRulesTest extends RuntimeTestBase {

    private static final String OPERATORS = "orqix-purchasing-operators";
    private static final String SLACK_REF = "alerts-slack-hook";
    private static final String PAGER_REF = "alerts-pager-key";

    @Autowired
    private AlertEngine engine;

    @Autowired
    private AlertSignals signals;

    @Autowired
    private LocalAlertHookStub hooks;

    @Autowired
    private LocalStubTransport adapters;

    private final List<String> grants = new ArrayList<>();
    private UUID subscription;

    @BeforeEach
    void prepare() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SLACK_REF), "https://hooks.slack.test.example/services/T/B/x");
        Files.writeString(dir.resolve(PAGER_REF), "pager-routing-key-value");
        hooks.reset();
        adapters.reset();
        Response granted = api("wade.admin").post(Map.of("permission", "alert.manage", "principal",
                Map.of("type", "idpGroup", "value", OPERATORS)), "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(granted.status()).as(granted.toString()).isIn(201, 409);
        if (granted.status() == 201) {
            grants.add(granted.body().path("id").asString());
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String id : grants) {
            api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, id);
        }
        grants.clear();
        jdbc.sql("DELETE FROM alert_delivery WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        jdbc.sql("DELETE FROM alert_rule WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        jdbc.sql("DELETE FROM alert_destination WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        jdbc.sql("DELETE FROM connection_circuit WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        if (subscription != null) {
            jdbc.sql("DELETE FROM webhook_delivery WHERE subscription_id = :s").param("s", subscription).update();
            jdbc.sql("UPDATE webhook_subscription SET enabled = false WHERE id = :s").param("s", subscription).update();
            subscription = null;
        }
    }

    private void openCircuit() {
        jdbc.sql("""
                INSERT INTO connection_circuit (workspace_id, connection_key, state, consecutive_failures, opened_at,
                                                open_until, reason, times_opened, updated_at)
                VALUES (:ws, 'procurement', 'open', 5, now(), now() + interval '5 minutes', 'test', 1, now())
                ON CONFLICT (workspace_id, connection_key) DO UPDATE SET state = 'open'
                """).param("ws", PURCHASING).update();
    }

    private void closeCircuit() {
        jdbc.sql("UPDATE connection_circuit SET state = 'closed' WHERE workspace_id = :ws").param("ws", PURCHASING)
                .update();
    }

    private UUID destination(Map<String, Object> body) throws Exception {
        Response created = api("olga.operator").post(body, "/workspaces/{ws}/alert-destinations", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    private JsonNode rule(List<UUID> destinations, int quietMinutes) throws Exception {
        Response created = api("olga.operator").post(Map.of("name", "Procurement paused", "signal", "circuitOpen",
                "threshold", 1, "connectionKey", "procurement", "severity", "critical", "quietMinutes", quietMinutes,
                "destinationIds", destinations), "/workspaces/{ws}/alert-rules", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body();
    }

    private JsonNode incidents() throws Exception {
        Response list = api("olga.operator").get("/workspaces/{ws}/alert-incidents", PURCHASING);
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        return list.body().path("items");
    }

    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND action = :a")
                .param("ws", PURCHASING).param("a", action).query(Long.class).single();
    }

    @Test
    void alertManageIsRequiredAndGrantOnly() throws Exception {
        Response denied = api("paul.publisher").get("/workspaces/{ws}/alert-rules", PURCHASING);
        assertThat(denied.status()).as(denied.toString()).isEqualTo(403);
        Response deniedWrite = api("paul.publisher").post(Map.of("name", "x", "kind", "slack", "secretRef", SLACK_REF),
                "/workspaces/{ws}/alert-destinations", PURCHASING);
        assertThat(deniedWrite.status()).isEqualTo(403);
        Response workflowScoped = api("wade.admin").post(Map.of("permission", "alert.manage", "workflowId",
                UUID.randomUUID().toString(), "principal", Map.of("type", "idpGroup", "value", OPERATORS)),
                "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(workflowScoped.status()).as(workflowScoped.toString()).isEqualTo(422);
        // Validation: a scope the signal does not support, an unknown destination, a reserved secret namespace.
        UUID slack = destination(Map.of("name", "Ops Slack", "kind", "slack", "secretRef", SLACK_REF));
        Response wrongScope = api("olga.operator").post(Map.of("name", "x", "signal", "aiDriftFallback",
                "threshold", 1, "connectionKey", "procurement", "severity", "info", "destinationIds", List.of(slack)),
                "/workspaces/{ws}/alert-rules", PURCHASING);
        assertThat(wrongScope.status()).isEqualTo(422);
        Response unknown = api("olga.operator").post(Map.of("name", "x", "signal", "circuitOpen", "threshold", 1,
                "severity", "info", "destinationIds", List.of(UUID.randomUUID())), "/workspaces/{ws}/alert-rules",
                PURCHASING);
        assertThat(unknown.status()).isEqualTo(422);
        Response noAdapter = api("olga.operator").post(Map.of("name", "Mail", "kind", "email", "adapterKey",
                "no-such-adapter", "recipients", List.of("ops@purchasing.example")),
                "/workspaces/{ws}/alert-destinations", PURCHASING);
        assertThat(noAdapter.status()).as(noAdapter.toString()).isEqualTo(422);
    }

    @Test
    void firesOnceResolvesAndRespectsQuietPeriodAndSilence() throws Exception {
        UUID slack = destination(Map.of("name", "Ops Slack", "kind", "slack", "secretRef", SLACK_REF));
        UUID pager = destination(Map.of("name", "On call", "kind", "pagerDuty", "secretRef", PAGER_REF));
        JsonNode rule = rule(List.of(slack, pager), 60);
        UUID ruleId = UUID.fromString(rule.path("id").asString());
        Instant t0 = Instant.now().plusSeconds(120);

        // Below the threshold: nothing.
        engine.evaluateDue(t0);
        assertThat(incidents()).isEmpty();

        // Fires once; evaluating again while firing neither opens a second incident nor notifies again.
        openCircuit();
        engine.evaluateDue(t0.plus(Duration.ofMinutes(1)));
        engine.evaluateDue(t0.plus(Duration.ofMinutes(2)));
        engine.evaluateDue(t0.plus(Duration.ofMinutes(3)));
        JsonNode open = incidents();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).path("status").asString()).isEqualTo("firing");
        assertThat(open.get(0).path("openingValue").asInt()).isEqualTo(1);
        assertThat(engine.dispatchQueued(t0.plus(Duration.ofMinutes(3)))).isEqualTo(2);
        assertThat(engine.dispatchQueued(t0.plus(Duration.ofMinutes(3)))).isZero();
        assertThat(hooks.posted()).extracting(LocalAlertHookStub.Posted::kind).containsExactlyInAnyOrder("slack",
                "pagerDuty");
        JsonNode pagerBody = hooks.posted().stream().filter(p -> p.kind().equals("pagerDuty")).findFirst().orElseThrow()
                .body();
        assertThat(pagerBody.path("event_action").asString()).isEqualTo("trigger");
        assertThat(pagerBody.toString()).doesNotContain("pager-routing-key-value");
        JsonNode listed = api("olga.operator").get("/workspaces/{ws}/alert-rules", PURCHASING).body().path("items")
                .get(0);
        assertThat(listed.path("state").asString()).isEqualTo("firing");
        assertThat(listed.path("openIncidentId").asString()).isEqualTo(open.get(0).path("id").asString());

        // Resolves and notifies the same destinations once.
        closeCircuit();
        engine.evaluateDue(t0.plus(Duration.ofMinutes(4)));
        engine.dispatchQueued(t0.plus(Duration.ofMinutes(4)));
        JsonNode resolved = incidents().get(0);
        assertThat(resolved.path("status").asString()).isEqualTo("resolved");
        assertThat(resolved.path("deliveries").findValuesAsString("kind")).containsExactlyInAnyOrder("fired", "fired",
                "resolved", "resolved");
        assertThat(hooks.posted()).hasSize(4);
        assertThat(hooks.posted().get(3).body().toString() + hooks.posted().get(2).body().toString())
                .contains("resolve");

        // Fires again inside the quiet period: recorded, not notified (nor its resolution).
        openCircuit();
        engine.evaluateDue(t0.plus(Duration.ofMinutes(10)));
        engine.dispatchQueued(t0.plus(Duration.ofMinutes(10)));
        JsonNode quiet = incidents().get(0);
        assertThat(quiet.path("status").asString()).isEqualTo("firing");
        assertThat(quiet.path("suppressed").asString()).isEqualTo("quietPeriod");
        assertThat(quiet.path("deliveries")).isEmpty();
        closeCircuit();
        engine.evaluateDue(t0.plus(Duration.ofMinutes(11)));
        engine.dispatchQueued(t0.plus(Duration.ofMinutes(11)));
        assertThat(hooks.posted()).hasSize(4);

        // A silence suppresses a later incident even after the quiet period.
        Response silenced = api("olga.operator").post(Map.of("minutes", 10080), "/workspaces/{ws}/alert-rules/{id}/silence",
                PURCHASING, ruleId);
        assertThat(silenced.status()).as(silenced.toString()).isEqualTo(200);
        openCircuit();
        engine.evaluateDue(t0.plus(Duration.ofHours(3)));
        assertThat(incidents().get(0).path("suppressed").asString()).isEqualTo("silenced");
        assertThat(engine.dispatchQueued(t0.plus(Duration.ofHours(3)))).isZero();

        assertThat(audits("alert.fired")).isGreaterThanOrEqualTo(3);
        assertThat(audits("alert.resolved")).isGreaterThanOrEqualTo(2);
        assertThat(audits("alert.rule-silenced")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void failedDeliveriesAreRetriedThenGiveUpAndWebhooksQueueSignedDeliveries() throws Exception {
        UUID missing = destination(Map.of("name", "Broken Slack", "kind", "slack", "secretRef", "alerts-missing-hook"));
        UUID creator = UUID.fromString(api("olga.operator").get("/me").body().path("subject").path("id").asString());
        subscription = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO webhook_subscription (id, workspace_id, name, url, secret_ref, events, enabled, created_by,
                                                  created_at)
                VALUES (:id, :ws, 'Alerts receiver', 'https://public.test.example/alerts', 'webhook-alerts',
                        ARRAY['callback.waiting'], true, :by, now())
                """).param("id", subscription).param("ws", PURCHASING).param("by", creator).update();
        UUID hook = destination(Map.of("name", "Receiver", "kind", "webhook", "webhookSubscriptionId", subscription));
        rule(List.of(missing, hook), 0);
        Instant t0 = Instant.now().plusSeconds(120);
        openCircuit();
        engine.evaluateDue(t0);
        for (int attempt = 0; attempt < 6; attempt++) {
            engine.dispatchQueued(t0.plus(Duration.ofHours(attempt + 1)));
        }
        JsonNode deliveries = incidents().get(0).path("deliveries");
        JsonNode broken = null;
        JsonNode queued = null;
        for (JsonNode d : deliveries) {
            if (d.path("destinationId").asString().equals(missing.toString())) {
                broken = d;
            } else {
                queued = d;
            }
        }
        assertThat(broken.path("status").asString()).isEqualTo("failed");
        assertThat(broken.path("attempts").asInt()).isEqualTo(5);
        assertThat(broken.path("errorCode").asString()).isEqualTo("secret_missing");
        assertThat(queued.path("status").asString()).isEqualTo("sent");
        assertThat(queued.path("outcome").asString()).isEqualTo("queued");
        String payload = jdbc.sql("SELECT payload::text FROM webhook_delivery WHERE subscription_id = :s AND event_type = 'alert.fired'")
                .param("s", subscription).query(String.class).single();
        JsonNode body = json.parse(payload);
        assertThat(body.path("alert").path("signal").asString()).isEqualTo("circuitOpen");
        assertThat(body.has("request")).isFalse();

        // A test send records its outcome.
        Response test = api("olga.operator").post(Map.of(), "/workspaces/{ws}/alert-destinations/{id}/test",
                PURCHASING, hook);
        assertThat(test.status()).as(test.toString()).isEqualTo(200);
        assertThat(test.body().path("status").asString()).isEqualTo("sent");
        assertThat(audits("alert.destination-tested")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void everySignalCanBeEvaluated() {
        for (String signal : AlertSignals.SCOPES.keySet()) {
            assertThat(signals.value(PURCHASING, signal, 15, null, null, Instant.now())).as(signal)
                    .isGreaterThanOrEqualTo(0);
        }
        openCircuit();
        assertThat(signals.value(PURCHASING, "circuitOpen", 15, null, "procurement", Instant.now())).isEqualTo(1);
        assertThat(signals.value(PURCHASING, "circuitOpen", 15, null, "other", Instant.now())).isZero();
        assertThat(signals.value(SANDBOX, "circuitOpen", 15, null, null, Instant.now())).isZero();
    }

    @Test
    void editingTheThresholdClosesTheOpenIncidentWithoutNotifying() throws Exception {
        UUID slack = destination(Map.of("name", "Ops Slack", "kind", "slack", "secretRef", SLACK_REF));
        JsonNode rule = rule(List.of(slack), 0);
        openCircuit();
        Instant t0 = Instant.now().plusSeconds(120);
        engine.evaluateDue(t0);
        Response stale = api("olga.operator").put(Map.of("name", "Procurement paused", "signal", "circuitOpen",
                "threshold", 2, "connectionKey", "procurement", "severity", "critical", "destinationIds", List.of(slack),
                "expectedVersion", 7), null, "/workspaces/{ws}/alert-rules/{id}", PURCHASING, rule.path("id").asString());
        assertThat(stale.status()).isEqualTo(409);
        Response updated = api("olga.operator").put(Map.of("name", "Procurement paused", "signal", "circuitOpen",
                "threshold", 2, "connectionKey", "procurement", "severity", "critical", "destinationIds", List.of(slack),
                "expectedVersion", 1), null, "/workspaces/{ws}/alert-rules/{id}", PURCHASING, rule.path("id").asString());
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat(updated.body().path("state").asString()).isEqualTo("ok");
        assertThat(updated.body().path("openIncidentId").isNull()).isTrue();
        engine.evaluateDue(t0.plus(Duration.ofMinutes(5)));
        engine.dispatchQueued(t0.plus(Duration.ofMinutes(5)));
        assertThat(incidents().get(0).path("deliveries").findValuesAsString("kind")).containsExactly("fired");
    }
}
