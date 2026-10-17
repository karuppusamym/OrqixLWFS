package com.orqix.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/**
 * T-SEC-08, hardened. {@code SecretIsolationTest} covers the success and rejected-call paths, the export bundle,
 * audit metadata and logs with a well-formed credential. This class covers the paths where a secret is most likely
 * to leak by accident:
 * <ul>
 *   <li>a credential that cannot be sent (an embedded line break makes the header invalid, so the JDK would raise an
 *       exception that quotes it) and a credential file that does not exist;</li>
 *   <li>the connection test endpoint with both of those and with a good credential;</li>
 *   <li>problem bodies of denied and invalid requests made while such bindings exist;</li>
 *   <li>inbound bearer tokens that are rejected (the {@code Authorization} header of a caller, not of a connection);</li>
 *   <li>every text, JSON and binary column of the {@code orqix} and {@code flowable} schemas, so that nothing is
 *       persisted anywhere (job exception messages and stack traces, variables, journal, events, audit).</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretLeakScenariosTest extends RuntimeTestBase {

    @Autowired
    OrqixProperties properties;

    @Test
    void tSec08_unsendableOrMissingCredentialNeverLeaksIntoResponsesProblemsDatabaseOrLogs(CapturedOutput logs)
            throws Exception {
        String badCredential = "test-secret-canary-" + UUID.randomUUID();
        String injectedHeader = "injected-" + UUID.randomUUID().toString().substring(0, 12);
        String goodCredential = "test-secret-canary-" + UUID.randomUUID();
        String inboundToken = "inbound-canary-" + UUID.randomUUID();
        String badRef = "test-" + UUID.randomUUID();
        String goodRef = "test-" + UUID.randomUUID();
        String missingRef = "test-missing-" + UUID.randomUUID();
        Path directory = properties.secrets().dir();
        Files.createDirectories(directory);
        Path badFile = directory.resolve(badRef);
        Path goodFile = directory.resolve(goodRef);
        Files.writeString(badFile, badCredential + "\nX-Injected: " + injectedHeader, StandardCharsets.UTF_8);
        Files.writeString(goodFile, goodCredential, StandardCharsets.UTF_8);
        List<String> secrets = List.of(badCredential, injectedHeader, goodCredential, inboundToken);
        List<String> corpus = new ArrayList<>();
        try {
            UUID workflow = publishPurchase(PURCHASING, null);

            // 1. A credential that cannot be sent as a header: the call fails before the wire.
            bind(badRef);
            UUID unsendable = startAndApprove(workflow);
            awaitLifecycle(PURCHASING, unsendable, "needsAttention", "completed");
            JsonNode attention = run(PURCHASING, unsendable);
            assertThat(attention.path("lifecycle").asString()).as(attention.toString()).isEqualTo("needsAttention");
            assertThat(attention.path("attention").path("reason").asString()).isEqualTo("EFFECT_REJECTED");
            assertThat(Stub.postCount()).as("nothing reached the stub").isZero();
            corpus.addAll(views(unsendable));

            // 2. A binding whose credential file does not exist.
            bind(missingRef);
            UUID missing = startAndApprove(workflow);
            awaitLifecycle(PURCHASING, missing, "needsAttention", "completed");
            assertThat(run(PURCHASING, missing).path("attention").path("reason").asString())
                    .isEqualTo("CONNECTION_UNAVAILABLE");
            corpus.addAll(views(missing));

            // 3. The connection test with each of the three credentials.
            bind(badRef);
            Response testBad = connectionTest();
            assertThat(testBad.status()).as(testBad.toString()).isEqualTo(200);
            assertThat(testBad.body().path("outcome").asString()).isEqualTo("failed");
            bind(missingRef);
            Response testMissing = connectionTest();
            assertThat(testMissing.body().path("errorCode").asString()).isEqualTo("CONNECTION_UNAVAILABLE");
            bind(goodRef);
            Response testGood = connectionTest();
            assertThat(testGood.body().path("outcome").asString()).as(testGood.toString()).isEqualTo("succeeded");
            corpus.addAll(List.of(testBad.toString(), testMissing.toString(), testGood.toString()));

            // 4. Problem bodies of denied, invalid and conflicting requests while these bindings exist.
            corpus.add(api("wade.admin").post(Map.of("version", 1, "operation", "createOrder"),
                    "/workspaces/{ws}/connections/{key}/test", PURCHASING, "procurement").toString());
            corpus.add(api("wade.admin").post(Map.of("version", 1, "operation", "nope"),
                    "/workspaces/{ws}/connections/{key}/test", PURCHASING, "procurement").toString());
            corpus.add(api("dana.designer").post(Map.of("version", 1, "operation", "health"),
                    "/workspaces/{ws}/connections/{key}/test", PURCHASING, "procurement").toString());
            corpus.add(api("wade.admin").get("/workspaces/{ws}/connections/{key}", PURCHASING, "unknown").toString());
            corpus.add(api("rhea.requester").get("/workspaces/{ws}/connections/{key}", PURCHASING, "procurement")
                    .toString());
            Response badBinding = api("wade.admin").put(Map.of("baseUrl", "https://127.0.0.1", "enabled", true,
                    "auth", Map.of("type", "bearerToken", "secretRef", goodRef)), "\"r1\"",
                    "/workspaces/{ws}/connections/{key}/binding", PURCHASING, "procurement");
            assertThat(badBinding.status()).as(badBinding.toString()).isGreaterThanOrEqualTo(400);
            corpus.add(badBinding.toString());
            String effectId = attention.path("attention").path("effectId").asString();
            Response wrongResult = api("olga.operator").post(Map.of("resolution", "confirmedApplied",
                    "evidence", Map.of("note", "Checked with the supplier by phone"), "result", Map.of("x", "y")),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{run}/effects/{effect}/reconcile",
                    PURCHASING, unsendable, effectId);
            assertThat(wrongResult.status()).as(wrongResult.toString()).isGreaterThanOrEqualTo(400);
            corpus.add(wrongResult.toString());
            Response retried = api("olga.operator").post(Map.of("reason", "Credential was rotated", "expectedVersion", 1),
                    UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/retry", PURCHASING, unsendable);
            corpus.add(retried.toString());

            // 5. Inbound Authorization headers that are rejected must not be echoed back.
            for (String path : List.of("/api/v1/me", "/api/v1/workspaces/" + PURCHASING + "/runs")) {
                var result = mvc.perform(MockMvcRequestBuilders.get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + inboundToken)).andReturn();
                assertThat(result.getResponse().getStatus()).isIn(401, 403);
                StringBuilder headers = new StringBuilder();
                result.getResponse().getHeaderNames().forEach(name -> headers.append(name).append(": ")
                        .append(result.getResponse().getHeaders(name)).append('\n'));
                corpus.add(result.getResponse().getContentAsString() + "\n" + headers);
            }

            // --- nothing contains a secret, the secrets directory, or an Authorization header line
            String directoryPath = directory.toAbsolutePath().toString();
            for (String text : corpus) {
                for (String secret : secrets) {
                    assertThat(text).doesNotContain(secret);
                }
                assertThat(text).doesNotContain(directoryPath).doesNotContain("Authorization:")
                        .doesNotContain("authorization:").doesNotContain("Bearer " + goodCredential);
            }
            String captured = logs.getAll();
            for (String secret : secrets) {
                assertThat(captured).as("captured log output").doesNotContain(secret);
            }
            assertThat(captured).doesNotContain("Authorization:").doesNotContain(directoryPath);

            // --- and nothing is persisted anywhere in the database
            for (int i = 0; i < secrets.size(); i++) {
                assertThat(columnsContaining(secrets.get(i))).as("columns holding canary #" + i).isEmpty();
            }
            System.out.printf("T-SEC-08 (hardened): %d responses and problem bodies, %d log characters and %d database "
                    + "columns scanned for 4 canaries: no leak%n", corpus.size(), captured.length(), columnCount());
        } finally {
            bindStub(PURCHASING);
            Files.deleteIfExists(badFile);
            Files.deleteIfExists(goodFile);
        }
    }

    private void bind(String secretRef) {
        jdbc.sql("UPDATE connection_binding SET auth_type = 'bearerToken', secret_ref = :ref, enabled = true "
                + "WHERE workspace_id = :ws AND connection_key = 'procurement'")
                .param("ref", secretRef).param("ws", PURCHASING).update();
    }

    private Response connectionTest() throws Exception {
        return api("wade.admin").post(Map.of("version", 1, "operation", "health"),
                "/workspaces/{ws}/connections/{key}/test", PURCHASING, "procurement");
    }

    private UUID startAndApprove(UUID workflow) throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("1800")).path("id")
                .asString());
        approve("mani.manager", PURCHASING, runId, "manager");
        return runId;
    }

    /** Every view of a run that any role can read, plus the lists and notifications around it. */
    private List<String> views(UUID runId) throws Exception {
        List<String> all = new ArrayList<>();
        for (String user : List.of("rhea.requester", "olga.operator", "wade.admin", "mani.manager", "vic.viewer")) {
            all.add(api(user).get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).toString());
            all.add(api(user).get("/workspaces/{ws}/runs/{id}/events", PURCHASING, runId).toString());
        }
        all.add(api("olga.operator").get("/workspaces/{ws}/runs?limit=100", PURCHASING).toString());
        all.add(api("rhea.requester").get("/workspaces/{ws}/me/notifications", PURCHASING).toString());
        all.add(api("wade.admin").get("/workspaces/{ws}/connections", PURCHASING).toString());
        all.add(api("wade.admin").get("/workspaces/{ws}/connections/{key}", PURCHASING, "procurement").toString());
        all.add(api("wade.admin").get("/workspaces/{ws}/access", PURCHASING).toString());
        return all;
    }

    private record Column(String schema, String table, String column, String type) {
    }

    private List<Column> textColumns() {
        return jdbc.sql("""
                SELECT table_schema, table_name, column_name, data_type FROM information_schema.columns
                WHERE table_schema IN ('orqix', 'flowable')
                  AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json', 'bytea')
                ORDER BY 1, 2, 3
                """).query((rs, n) -> new Column(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    private int columnCount() {
        return textColumns().size();
    }

    /** "schema.table.column" of every text, JSON or binary column in which {@code needle} occurs. */
    private List<String> columnsContaining(String needle) {
        List<String> found = new ArrayList<>();
        for (Column c : textColumns()) {
            String qualified = "\"" + c.schema() + "\".\"" + c.table() + "\"";
            String column = "\"" + c.column() + "\"";
            String predicate = "bytea".equals(c.type()) ? "position(convert_to(:needle, 'UTF8') in " + column + ") > 0"
                    : "position(:needle in " + column + "::text) > 0";
            int hits = jdbc.sql("SELECT count(*) FROM " + qualified + " WHERE " + predicate).param("needle", needle)
                    .query(Integer.class).single();
            if (hits > 0) {
                found.add(c.schema() + "." + c.table() + "." + c.column());
            }
        }
        return found;
    }
}
