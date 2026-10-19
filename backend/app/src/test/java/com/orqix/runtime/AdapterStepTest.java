package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.integration.AdapterSteps;
import com.orqix.integration.EffectDispatcher;
import com.orqix.integration.adapters.LocalStubTransport;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S73 "Send through an adapter" on PostgreSQL/Flowable: compile-time allowlists, journaled delivery to the local stub
 * only, outputs for later steps, a disabled or narrowed adapter stopping the send, operator retry, and reuse of a recorded
 * result instead of a resend.
 */
class AdapterStepTest extends RuntimeTestBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private LocalStubTransport stub;

    @Autowired
    private AdapterSteps adapters;

    private String mail;
    private String lookup;

    @BeforeEach
    void createAdapters() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        mail = "ops-mail-" + suffix;
        lookup = "lookup-" + suffix;
        Response created = api("wade.admin").post(Map.of("key", mail, "kind", "email", "name", "Operations mail " + suffix,
                "secretRef", "ops-mail", "config", Map.of("from", "noreply@example.com",
                        "allowedRecipientDomains", List.of("example.com"), "maxRecipients", 2)), "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        created = api("wade.admin").post(Map.of("key", lookup, "kind", "jdbc", "name", "Cost centre lookup " + suffix,
                "secretRef", "lookup-db", "config", Map.of("statement", "SELECT id FROM centres WHERE code = :costCenter",
                        "parameters", List.of("costCenter"))), "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
    }

    /** start -> read (jdbc) -> tell (email, uses the row count) -> manager ... */
    private Consumer<ObjectNode> adapterSteps(String recipient) {
        return definition -> {
            try {
                ArrayNode nodes = (ArrayNode) definition.path("nodes");
                nodes.add(MAPPER.readTree("{\"id\":\"read\",\"type\":\"sendAdapter\",\"label\":\"Look up the cost centre\",\"config\":{"
                        + "\"adapter\":\"" + lookup + "\",\"kind\":\"jdbc\",\"inputMapping\":{\"costCenter\":\"/input/costCenter\"}}}"));
                nodes.add(MAPPER.readTree("{\"id\":\"tell\",\"type\":\"sendAdapter\",\"label\":\"Tell operations\",\"config\":{"
                        + "\"adapter\":\"" + mail + "\",\"kind\":\"email\",\"to\":[\"" + recipient + "\"],"
                        + "\"subject\":\"New request {{title}}\",\"body\":\"Amount {{amount}}, {{rows}} matching centres\","
                        + "\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\",\"rows\":\"/steps/read/rowCount\"}}}"));
                ArrayNode transitions = (ArrayNode) definition.path("transitions");
                for (JsonNode t : transitions) {
                    if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                        ((ObjectNode) t).put("to", "read");
                    }
                }
                transitions.add(MAPPER.readTree("{\"id\":\"a1\",\"from\":\"read\",\"to\":\"tell\",\"on\":\"next\"}"));
                transitions.add(MAPPER.readTree("{\"id\":\"a2\",\"from\":\"tell\",\"to\":\"manager\",\"on\":\"next\"}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private UUID startRun(UUID workflow) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("1200")).path("id").asString());
    }

    private void awaitManagerTask(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> openTask(PURCHASING, run, "manager") != null);
    }

    private long sentThrough(String adapterKey) {
        return stub.sent().stream().filter(s -> s.adapterKey().equals(adapterKey)).count();
    }

    private List<Map<String, Object>> journal(UUID run) {
        return jdbc.sql("""
                SELECT node_id, connection_key, connection_version, operation, effect_class, state, attempts,
                       result::text AS result, last_error_code, last_error_detail
                FROM effect_journal WHERE run_id = :run ORDER BY created_at
                """).param("run", run).query().listOfRows();
    }

    @Test
    void stepsSendOnceThroughTheStubAndLaterStepsUseTheirOutputs() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, adapterSteps("ops@example.com"));
        UUID run = startRun(workflow);
        awaitManagerTask(run);

        assertThat(sentThrough(lookup)).isEqualTo(1);
        assertThat(sentThrough(mail)).isEqualTo(1);
        // The stub keeps a digest only; it equals the message built from the fixed recipient and the mapped values,
        // including the database step's row count (0 from the stub).
        ObjectNode expected = json.object();
        expected.putArray("to").add("ops@example.com");
        expected.put("subject", "New request Replacement laptop");
        expected.put("body", "Amount 1200, 0 matching centres");
        String digest = stub.sent().stream().filter(s -> s.adapterKey().equals(mail)).findFirst().orElseThrow().payloadSha256();
        assertThat(digest).isEqualTo(json.canonicalSha256(expected));

        List<Map<String, Object>> entries = journal(run);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("node_id", "read").containsEntry("connection_key", lookup)
                .containsEntry("operation", "jdbc.read").containsEntry("effect_class", "readOnly")
                .containsEntry("state", "succeeded").containsEntry("connection_version", 1);
        assertThat(entries.get(1)).containsEntry("node_id", "tell").containsEntry("connection_key", mail)
                .containsEntry("operation", "email.send").containsEntry("effect_class", "nonIdempotent")
                .containsEntry("state", "succeeded").containsEntry("attempts", 1);
        // The journal keeps the stub outcome, never the message or its recipients.
        assertThat(String.valueOf(entries.get(1).get("result"))).contains("stubbed").doesNotContain("Replacement laptop",
                "ops@example.com", "Amount");

        JsonNode detail = run(PURCHASING, run);
        assertThat(detail.path("effects").findValuesAsString("connectionKey")).contains(mail, lookup);
        JsonNode events = api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body();
        long adapterEvents = 0;
        for (JsonNode e : events.path("items")) {
            if ("EFFECT_SUCCEEDED".equals(e.path("type").asString()) && e.path("data").has("adapterKind")) {
                adapterEvents++;
                assertThat(e.path("data").toString()).doesNotContain("Replacement laptop", "ops@example.com");
            }
        }
        assertThat(adapterEvents).isEqualTo(2);
        JsonNode path = api("rhea.requester").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, run).body();
        assertThat(path.toString()).contains("\"read\"", "\"tell\"");

        // A recorded success is reused: asking again for the same occurrence sends nothing.
        EffectDispatcher.DispatchResult again = adapters.send(new AdapterSteps.Send(PURCHASING, run, "tell", 1, mail, "email",
                expected, "test"));
        assertThat(again).isInstanceOf(EffectDispatcher.Succeeded.class);
        assertThat(((EffectDispatcher.Succeeded) again).reused()).isTrue();
        assertThat(sentThrough(mail)).isEqualTo(1);
    }

    @Test
    void anAttemptWithoutARecordedOutcomeIsNeverResent() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, adapterSteps("ops@example.com"));
        UUID run = startRun(workflow);
        awaitManagerTask(run);
        long before = sentThrough(mail);
        // A crashed attempt: in flight with an expired lease for another occurrence of an email send.
        ObjectNode payload = json.object();
        payload.putArray("to").add("ops@example.com");
        payload.put("subject", "s").put("body", "b");
        jdbc.sql("""
                INSERT INTO effect_journal (id, workspace_id, run_id, node_id, occurrence, effect_key, connection_key,
                       connection_version, operation, effect_class, request_hash, state, attempts, lease_owner, lease_until)
                VALUES (:id, :ws, :run, 'tell', 2, :key, :adapter, 1, 'email.send', 'nonIdempotent', :hash, 'inFlight', 1,
                        'crashed-node', now() - interval '1 minute')
                """).param("id", UUID.randomUUID()).param("ws", PURCHASING).param("run", run)
                .param("key", EffectDispatcher.effectKey(PURCHASING, run, "tell", 2)).param("adapter", mail)
                .param("hash", json.canonicalSha256(payload)).update();
        EffectDispatcher.DispatchResult result = adapters.send(new AdapterSteps.Send(PURCHASING, run, "tell", 2, mail, "email",
                payload, "test"));
        assertThat(result).isInstanceOf(EffectDispatcher.Failed.class);
        assertThat(((EffectDispatcher.Failed) result).classification()).isEqualTo("unknown");
        assertThat(((EffectDispatcher.Failed) result).retryable()).isFalse();
        assertThat(sentThrough(mail)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT state FROM effect_journal WHERE run_id = :run AND occurrence = 2").param("run", run)
                .query(String.class).single()).isEqualTo("unknown");
    }

    @Test
    void aDisabledOrNarrowedAdapterStopsTheSendAndAnOperatorRetrySendsOnceAfterTheFix() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, adapterSteps("ops@example.com"));
        JsonNode adapter = adapterView(mail);
        Response disabled = api("wade.admin").put(Map.of("name", adapter.path("name").asString(), "secretRef", "ops-mail",
                "config", adapter.path("config"), "enabled", false, "expectedVersion", 1), null, "/workspaces/{ws}/adapters/{k}",
                PURCHASING, mail);
        assertThat(disabled.status()).as(disabled.toString()).isEqualTo(200);

        UUID run = startRun(workflow);
        awaitLifecycle(PURCHASING, run, "needsAttention");
        assertThat(sentThrough(mail)).isZero();
        Map<String, Object> refused = journal(run).get(1);
        assertThat(refused).containsEntry("state", "permanentFailure").containsEntry("last_error_code", "CONNECTION_UNAVAILABLE");
        assertThat(String.valueOf(refused.get("last_error_detail"))).contains("disabled");
        assertThat(run(PURCHASING, run).path("attention").toString()).contains("cannot send this message");

        // Re-enabled: an operator retries and the message is sent exactly once, recorded with the new adapter version.
        Response enabled = api("wade.admin").put(Map.of("name", adapter.path("name").asString(), "secretRef", "ops-mail",
                "config", adapter.path("config"), "enabled", true, "expectedVersion", 2), null, "/workspaces/{ws}/adapters/{k}",
                PURCHASING, mail);
        assertThat(enabled.status()).as(enabled.toString()).isEqualTo(200);
        JsonNode detail = run(PURCHASING, run);
        Response retried = api("olga.operator").post(Map.of("reason", "Adapter enabled again", "expectedVersion",
                detail.path("version").asInt()), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/retry", PURCHASING, run);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        awaitManagerTask(run);
        assertThat(sentThrough(mail)).isEqualTo(1);
        // A refused attempt never reached the transport, so the one delivery is attempt 1; the entry keeps the adapter
        // version under which the effect was first registered (2, the disabled version).
        assertThat(journal(run).get(1)).containsEntry("state", "succeeded").containsEntry("attempts", 1)
                .containsEntry("connection_version", 2);

        // Narrowing the allowlist stops later sends; the recipient is fixed in the step, so nothing else is chosen.
        Response narrowed = api("wade.admin").put(Map.of("name", adapter.path("name").asString(), "secretRef", "ops-mail",
                "config", Map.of("from", "noreply@example.com", "allowedRecipientDomains", List.of("example.org")),
                "enabled", true, "expectedVersion", 3), null, "/workspaces/{ws}/adapters/{k}", PURCHASING, mail);
        assertThat(narrowed.status()).as(narrowed.toString()).isEqualTo(200);
        UUID second = startRun(workflow);
        awaitLifecycle(PURCHASING, second, "needsAttention");
        assertThat(String.valueOf(journal(second).get(1).get("last_error_detail"))).contains("/payload/to");
        assertThat(sentThrough(mail)).isEqualTo(1);

        // New publishes are checked against the current adapter: the recipient domain is no longer approved.
        Response validation = validateDraftWith(adapterSteps("ops@example.com"));
        assertThat(validation.body().path("issues").findValuesAsString("code")).contains("WF_ADAPTER_RECIPIENT");
    }

    @Test
    void publishingRefusesUnapprovedRecipientsUnknownAdaptersAndAWrongKind() throws Exception {
        assertThat(validateDraftWith(adapterSteps("ops@evil.example.org")).body().path("issues").findValuesAsString("code"))
                .contains("WF_ADAPTER_RECIPIENT");
        Consumer<ObjectNode> unknown = adapterSteps("ops@example.com").andThen(d -> d.path("nodes").forEach(n -> {
            if ("tell".equals(n.path("id").asString())) {
                ((ObjectNode) n.path("config")).put("adapter", "no-such-adapter");
            }
        }));
        assertThat(validateDraftWith(unknown).body().path("issues").findValuesAsString("code")).contains("WF_ADAPTER_UNKNOWN");
        Consumer<ObjectNode> wrongKind = adapterSteps("ops@example.com").andThen(d -> d.path("nodes").forEach(n -> {
            if ("read".equals(n.path("id").asString())) {
                ((ObjectNode) n.path("config")).put("adapter", mail);
            }
        }));
        assertThat(validateDraftWith(wrongKind).body().path("issues").findValuesAsString("code")).contains("WF_ADAPTER_KIND");
        assertThat(validateDraftWith(adapterSteps("ops@example.com")).body().path("issues").findValuesAsString("code"))
                .noneMatch(code -> code.startsWith("WF_ADAPTER"));
    }

    private JsonNode adapterView(String key) throws Exception {
        for (JsonNode item : api("wade.admin").get("/workspaces/{ws}/adapters", PURCHASING).body().path("items")) {
            if (key.equals(item.path("key").asString())) {
                return item;
            }
        }
        throw new AssertionError("adapter " + key);
    }

    /** Saves a patched purchase draft and validates it (no publish). */
    private Response validateDraftWith(Consumer<ObjectNode> patch) throws Exception {
        String key = "pa-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = api("dana.designer").post(Map.of("key", key, "name", "Purchase " + key, "templateId",
                "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        String id = created.body().path("id").asString();
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id)
                .body().path("definition");
        patch.accept(definition);
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return api("dana.designer").post(Map.of("revision", 2), "/workspaces/{ws}/workflows/{id}/validate", PURCHASING, id);
    }
}
