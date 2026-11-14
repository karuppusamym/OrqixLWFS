package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.integration.packages.PackagePolls;
import com.orqix.integration.packages.PackageTriggerService;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.PackageTestBase;
import com.orqix.support.ServiceNowStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Package triggers (CG-C05, docs/build/S133 section 5) on PostgreSQL through the public API: webhook verification by
 * HMAC (hex, a start trigger), JWT (issuer, audience, expiry, lifetime, algorithm bound to the key) and basic
 * credentials (weaker, HTTPS only), dedupe of repeated events, dead records without content; polling with a
 * watermark, overlap and dedupe through the binding, and one active poller across concurrent schedulers.
 */
class PackageTriggersTest extends PackageTestBase {

    @Autowired
    OrqixProperties properties;

    @Autowired
    PackagePolls polls;

    private String prefix;

    @BeforeEach
    void installVariant() throws Exception {
        prefix = prefix();
        Map<String, String> files = variant(prefix, "1.0.0");
        files.put("triggers/request-start.webhook.json", """
                {"kind":"webhook","key":"request-start","label":"Start a purchase from ServiceNow",
                 "verification":{"scheme":"hmacSha256","signatureHeader":"X-Signature","timestampHeader":"X-Timestamp",
                   "encoding":"hex","prefix":"sha256="},
                 "dedupeKey":["/sys_id"],"action":{"type":"start"},
                 "mapping":{"title":"/short_description","purpose":"/description","costCenter":"/cost_center","amount":"/amount"}}
                """);
        files.put("triggers/incident-jwt.webhook.json", """
                {"kind":"webhook","key":"incident-jwt","label":"Incident outcome (JWT)",
                 "verification":{"scheme":"jwt","algorithms":["HS256","RS256"],"maxLifetimeSeconds":300},
                 "dedupeKey":["/sys_id","/sys_mod_count"],
                 "action":{"type":"message","message":"servicenow-incident-updated","correlationFrom":"/correlation_id"},
                 "mapping":{"state":"/state"}}
                """);
        files.put("triggers/incident-basic.webhook.json", """
                {"kind":"webhook","key":"incident-basic","label":"Incident outcome (basic)","verification":{"scheme":"basic"},
                 "dedupeKey":["/sys_id","/sys_mod_count"],
                 "action":{"type":"message","message":"servicenow-incident-updated","correlationFrom":"/correlation_id"},
                 "mapping":{"state":"/state"}}
                """);
        editJson(files, "manifest.json", m -> {
            ArrayNode triggers = (ArrayNode) m.path("triggers");
            triggers.removeAll();
            for (String key : List.of("incident-basic", "incident-changes", "incident-jwt", "incident-updated", "request-start")) {
                triggers.add(key);
            }
            ArrayNode paths = (ArrayNode) m.path("contents").path("triggers");
            paths.add("triggers/request-start.webhook.json").add("triggers/incident-jwt.webhook.json")
                    .add("triggers/incident-basic.webhook.json");
        });
        Response imported = importPackage("wade.admin", signed(files), null);
        assertThat(imported.status()).as(imported.toString()).isEqualTo(201);
        assertThat(imported.body().path("review").path("triggers").toString()).contains("\"weaker\":true");
        assertThat(decide("paul.publisher", imported.body(), "approve").status()).isEqualTo(200);
        assertThat(install("wade.admin", imported.body().path("summary").path("id").asString()).status()).isEqualTo(201);
        bindToStub(prefix);
        Files.createDirectories(properties.secrets().dir());
    }

    private String secret(String name, String value) throws Exception {
        Files.writeString(properties.secrets().dir().resolve(name), value);
        return name;
    }

    private JsonNode configure(Map<String, Object> body) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(body);
        request.put("packageId", "com.orqix." + prefix);
        Response created = api("wade.admin").post(request, "/workspaces/{ws}/package-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body();
    }

    private int post(JsonNode trigger, byte[] body, Map<String, String> headers, boolean secure) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/v1" + trigger.path("receivePath")
                .asString().substring("/api/v1".length())).contentType(MediaType.APPLICATION_JSON).content(body)
                .secure(secure);
        headers.forEach(request::header);
        var result = mvc.perform(request).andReturn();
        com.orqix.support.OpenApiContract.validate(result);
        return result.getResponse().getStatus();
    }

    private static String hex(String secret, String timestamp, byte[] body) {
        return "sha256=" + HexFormat.of().formatHex(PackageTriggerService.hmacSha256(secret.getBytes(StandardCharsets.UTF_8),
                (timestamp + ".").getBytes(StandardCharsets.UTF_8), body));
    }

    private static String jwt(String secret, Map<String, Object> claims) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(new tools.jackson.databind.json.JsonMapper().writeValueAsBytes(claims));
        String signature = b64.encodeToString(PackageTriggerService.hmacSha256(secret.getBytes(StandardCharsets.UTF_8),
                (header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return "Bearer " + header + "." + payload + "." + signature;
    }

    // ------------------------------------------------------------------ webhooks

    @Test
    void anHmacStartTriggerStartsOneRequestAsItsRunAsPerson() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        // The run-as person needs workflow.start; a designer without connector.manage cannot set it up at all.
        assertThat(api("dana.designer").post(Map.of("packageId", "com.orqix." + prefix, "triggerKey", "request-start",
                "workflowId", workflow, "secretRef", "x-hook"), "/workspaces/{ws}/package-triggers", PURCHASING).status())
                .isEqualTo(403);
        JsonNode trigger = configure(Map.of("triggerKey", "request-start", "workflowId", workflow,
                "secretRef", secret("pt-start-" + prefix, "start-secret-value")));
        assertThat(trigger.path("action").asString()).isEqualTo("start");
        byte[] body = json.write(Map.of("sys_id", "REQ-" + prefix, "short_description", "Laptop from ServiceNow",
                "description", "Replacement", "cost_center", "OPS", "amount", 120)).getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        assertThat(post(trigger, body, Map.of("X-Timestamp", ts, "X-Signature", hex("wrong", ts, body)), false))
                .isEqualTo(401);
        String old = Long.toString(Instant.now().minusSeconds(900).getEpochSecond());
        assertThat(post(trigger, body, Map.of("X-Timestamp", old, "X-Signature", hex("start-secret-value", old, body)), false))
                .isEqualTo(401);
        assertThat(post(trigger, body, Map.of("X-Timestamp", ts, "X-Signature", hex("start-secret-value", ts, body)), false))
                .isEqualTo(202);
        // A repeat (same dedupe key) never starts a second request.
        String ts2 = Long.toString(Instant.now().getEpochSecond());
        assertThat(post(trigger, body, Map.of("X-Timestamp", ts2, "X-Signature", hex("start-secret-value", ts2, body)), false))
                .isEqualTo(202);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :wf").param("wf", workflow)
                .query(Integer.class).single()).isEqualTo(1);
        // An event without the mapped values becomes a dead record (code only, no content).
        byte[] bad = json.write(Map.of("sys_id", "REQ2-" + prefix)).getBytes(StandardCharsets.UTF_8);
        String ts3 = Long.toString(Instant.now().getEpochSecond());
        assertThat(post(trigger, bad, Map.of("X-Timestamp", ts3, "X-Signature", hex("start-secret-value", ts3, bad)), false))
                .isEqualTo(422);
        JsonNode dead = api("olga.operator").get("/workspaces/{ws}/package-triggers/{id}/dead-records", PURCHASING,
                trigger.path("id").asString()).body();
        assertThat(dead.path("items").get(0).path("errorCode").asString()).isEqualTo("unmapped");
        assertThat(dead.toString()).doesNotContain("REQ2-");
        JsonNode listed = api("wade.admin").get("/workspaces/{ws}/package-triggers", PURCHASING).body();
        JsonNode status = null;
        for (JsonNode t : listed.path("items")) {
            if (t.path("id").asString().equals(trigger.path("id").asString())) {
                status = t.path("status");
            }
        }
        assertThat(status.path("recordsHandled").asInt()).isEqualTo(1);
        assertThat(status.path("duplicates").asInt()).isEqualTo(1);
        assertThat(status.path("deadRecords").asInt()).isEqualTo(1);
        // Disabled triggers answer like unknown ones.
        Response disabled = api("wade.admin").put(Map.of("workflowId", workflow, "secretRef", "pt-start-" + prefix,
                "enabled", false, "expectedVersion", trigger.path("version").asInt()), null,
                "/workspaces/{ws}/package-triggers/{id}", PURCHASING, trigger.path("id").asString());
        assertThat(disabled.status()).as(disabled.toString()).isEqualTo(200);
        assertThat(post(trigger, body, Map.of("X-Timestamp", ts, "X-Signature", hex("start-secret-value", ts, body)), false))
                .isEqualTo(404);
    }

    @Test
    void jwtTriggersCheckIssuerAudienceExpiryAndLifetime() throws Exception {
        JsonNode missing = api("wade.admin").post(Map.of("packageId", "com.orqix." + prefix, "triggerKey", "incident-jwt",
                "secretRef", secret("pt-jwt-" + prefix, "jwt-shared-secret-value-0123456789")),
                "/workspaces/{ws}/package-triggers", PURCHASING).body();
        assertThat(missing.path("fieldErrors").toString()).contains("/jwtIssuer");
        JsonNode trigger = configure(Map.of("triggerKey", "incident-jwt", "secretRef", "pt-jwt-" + prefix,
                "jwtIssuer", "https://dev.service-now.com", "jwtAudience", "orqix"));
        byte[] body = json.write(Map.of("sys_id", "J1-" + prefix, "sys_mod_count", "1", "state", "6",
                "correlation_id", "ORQ-9999-raise-1")).getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String secret = "jwt-shared-secret-value-0123456789";
        Map<String, Object> good = Map.of("iss", "https://dev.service-now.com", "aud", "orqix", "iat", now, "exp", now + 120);
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(Map.of("iss", "https://evil.example", "aud", "orqix", "iat", now, "exp", now + 120));
        bad.add(Map.of("iss", "https://dev.service-now.com", "aud", "someone-else", "iat", now, "exp", now + 120));
        bad.add(Map.of("iss", "https://dev.service-now.com", "aud", "orqix", "iat", now - 600, "exp", now - 300));
        bad.add(Map.of("iss", "https://dev.service-now.com", "aud", "orqix", "iat", now, "exp", now + 86_400));
        bad.add(Map.of("iss", "https://dev.service-now.com", "aud", "orqix"));
        for (Map<String, Object> claims : bad) {
            assertThat(post(trigger, body, Map.of("Authorization", jwt(secret, claims)), false)).as(claims.toString())
                    .isEqualTo(401);
        }
        assertThat(post(trigger, body, Map.of("Authorization", jwt("another-secret", good)), false)).isEqualTo(401);
        assertThat(post(trigger, body, Map.of(), false)).isEqualTo(401);
        assertThat(post(trigger, body, Map.of("Authorization", jwt(secret, good)), false)).isEqualTo(202);
    }

    @Test
    void basicCredentialsAreWeakerAndOnlyAcceptedOverHttps() throws Exception {
        JsonNode trigger = configure(Map.of("triggerKey", "incident-basic",
                "secretRef", secret("pt-basic-" + prefix, "servicenow:basic-password-value")));
        assertThat(trigger.path("weaker").asBoolean()).isTrue();
        byte[] body = json.write(Map.of("sys_id", "B1-" + prefix, "sys_mod_count", "1", "state", "6",
                "correlation_id", "ORQ-9999-raise-1")).getBytes(StandardCharsets.UTF_8);
        String credentials = "Basic " + Base64.getEncoder().encodeToString("servicenow:basic-password-value"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(post(trigger, body, Map.of("Authorization", credentials), false)).isEqualTo(401);
        assertThat(post(trigger, body, Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                "servicenow:wrong".getBytes(StandardCharsets.UTF_8))), true)).isEqualTo(401);
        assertThat(post(trigger, body, Map.of("Authorization", credentials), true)).isEqualTo(202);
    }

    // ------------------------------------------------------------------ polling

    @Test
    void pollingReadsSinceTheWatermarkWithOverlapDedupesAndHasOnePoller() throws Exception {
        // Set up disabled, so no scheduler polls before the records exist.
        JsonNode trigger = configure(Map.of("triggerKey", "incident-changes", "enabled", false));
        assertThat(trigger.path("kind").asString()).isEqualTo("poll");
        String id = trigger.path("id").asString();
        String a = ServiceNowStub.seed("INC" + prefix.substring(2, 8) + "1", "2", "0".repeat(32), "A");
        String b = ServiceNowStub.seed("INC" + prefix.substring(2, 8) + "2", "2", "0".repeat(32), "B");
        String ca = "ORQ-7001-" + prefix + "-1";
        String cb = "ORQ-7002-" + prefix + "-1";
        ServiceNowStub.setField(a, "correlation_id", ca);
        ServiceNowStub.setField(b, "correlation_id", cb);
        ServiceNowStub.update(a, "6", "Solved");
        ServiceNowStub.update(b, "8", "Canceled");
        jdbc.sql("UPDATE package_trigger SET enabled = true, next_run_at = now() - interval '1 second'"
                + " WHERE id = CAST(:id AS uuid)").param("id", id).update();
        // Two schedulers at once (plus the application's own): exactly one claims the trigger and polls.
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> await(go, polls));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> await(go, polls));
        go.countDown();
        assertThat(first.get() + second.get()).isLessThanOrEqualTo(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                .until(() -> status(id).path("polls").asInt() >= 1 && !status(id).path("leased").asBoolean());
        JsonNode status = status(id);
        assertThat(status.path("polls").asInt()).isEqualTo(1);
        assertThat(status.path("recordsHandled").asInt()).isEqualTo(2);
        assertThat(status.path("lastErrorCode").isNull()).as(status.toString()).isTrue();
        assertThat(status.path("watermark").asString()).matches("^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$");
        assertThat(reads()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_message WHERE workspace_id = :ws AND correlation_key IN (:a, :b)")
                .param("ws", PURCHASING).param("a", ca).param("b", cb).query(Integer.class).single()).isEqualTo(2);
        // The next poll re-reads the overlap minute: the same records are dropped as duplicates.
        jdbc.sql("UPDATE package_trigger SET next_run_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
                .param("id", id).update();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() -> {
            polls.runDue();
            return status(id).path("polls").asInt() >= 2;
        });
        status = status(id);
        assertThat(status.path("recordsHandled").asInt()).isEqualTo(2);
        assertThat(status.path("duplicates").asInt()).isEqualTo(2);
        assertThat(reads()).isEqualTo(2);
        // A trigger leased by another pod is not polled.
        jdbc.sql("UPDATE package_trigger SET next_run_at = now() - interval '1 second', lease_owner = 'other-pod',"
                + " lease_until = now() + interval '1 minute' WHERE id = CAST(:id AS uuid)").param("id", id).update();
        polls.runDue();
        assertThat(status(id).path("polls").asInt()).isEqualTo(2);
        assertThat(status(id).path("leased").asBoolean()).isTrue();
        jdbc.sql("UPDATE package_trigger SET lease_owner = NULL, lease_until = NULL, enabled = false WHERE id = CAST(:id AS uuid)")
                .param("id", id).update();
        assertThat(jdbc.sql("SELECT count(*) FROM package_trigger_record WHERE trigger_id = CAST(:id AS uuid) AND status = 'dead'")
                .param("id", id).query(Integer.class).single()).isZero();
    }

    private long reads() {
        return ServiceNowStub.calls().stream().filter(c -> "GET".equals(c.method())
                && c.path().equals("/api/now/table/incident") && c.query().getOrDefault("sysparm_query", "")
                        .startsWith("sys_updated_on>")).count();
    }

    private static int await(CountDownLatch go, PackagePolls polls) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return polls.runDue();
    }

    private JsonNode status(String id) throws Exception {
        for (JsonNode t : api("wade.admin").get("/workspaces/{ws}/package-triggers", PURCHASING).body().path("items")) {
            if (t.path("id").asString().equals(id)) {
                return t.path("status");
            }
        }
        throw new AssertionError("trigger " + id);
    }
}
