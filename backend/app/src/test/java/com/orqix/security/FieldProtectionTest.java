package com.orqix.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.FieldClassification;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-I05 (docs/build/S151): personal and sensitive answers are masked for everyone, sensitive answers are encrypted
 * at rest (request store and engine variable) while routing still sees them, and data.reveal holders reveal them
 * through an audited operation. The data.reveal grant made here is removed again.
 */
class FieldProtectionTest extends RuntimeTestBase {

    private UUID publishClassified() throws Exception {
        return publishPurchase(PURCHASING, definition -> {
            ObjectNode properties = (ObjectNode) definition.path("inputSchema").path("properties");
            ((ObjectNode) properties.path("purpose")).put("classification", "personal");
            ((ObjectNode) properties.path("amount")).put("classification", "sensitive");
            ((ObjectNode) properties.path("costCenter")).put("classification", "internal");
        });
    }

    @Test
    void sensitiveAnswersAreEncryptedAtRestMaskedEverywhereAndStillRouteTheRequest() throws Exception {
        UUID workflow = publishClassified();
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("6000")).path("id")
                .asString());

        // At rest: the sensitive answer is ciphertext in the request store and in the engine variable.
        String stored = jdbc.sql("SELECT input->>'amount' FROM workflow_run WHERE id = :id").param("id", runId)
                .query(String.class).single();
        assertThat(stored).startsWith("orqix-enc:v1:dev:");
        assertThat(jdbc.sql("SELECT input->>'purpose' FROM workflow_run WHERE id = :id").param("id", runId)
                .query(String.class).single()).isEqualTo("Device failed");
        String variable = jdbc.sql("""
                SELECT v.text_ FROM act_ru_variable v JOIN act_ru_execution e ON e.id_ = v.proc_inst_id_
                WHERE e.business_key_ = :bk AND v.name_ = 'orqix_input' AND e.parent_id_ IS NULL
                """).param("bk", runId.toString()).query(String.class).list().stream().findFirst().orElse(null);
        if (variable != null) {
            assertThat(variable).contains("orqix-enc:v1:").doesNotContain("6000");
        }

        // Masked for the operator (run.read-data), the requester and the approver alike.
        for (String user : List.of("olga.operator", "rhea.requester")) {
            JsonNode run = api(user).get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body();
            assertThat(run.path("input").path("amount").asString()).as(user).isEqualTo(FieldClassification.MASK);
            assertThat(run.path("input").path("purpose").asString()).as(user).isEqualTo(FieldClassification.MASK);
            assertThat(run.path("input").path("costCenter").asString()).as(user).isEqualTo("OPS");
            assertThat(run.path("input").path("title").asString()).as(user).isEqualTo("Replacement laptop");
            assertThat(run.path("protectedFields").toString()).as(user).contains("amount", "purpose");
        }
        JsonNode task = openTask(PURCHASING, runId, "manager");
        JsonNode taskDetail = api("mani.manager").get("/workspaces/{ws}/tasks/{id}", PURCHASING,
                task.path("id").asString()).body();
        assertThat(taskDetail.path("request").path("input").path("amount").asString()).isEqualTo(FieldClassification.MASK);

        // The engine decrypts: 6000 is routed to finance after the manager approves.
        approve("mani.manager", PURCHASING, runId, "manager");
        JsonNode finance = openTask(PURCHASING, runId, "finance");
        assertThat(finance).as("finance review for an amount of 6000").isNotNull();
    }

    @Test
    void revealNeedsDataRevealAndDataVisibilityAndIsAudited() throws Exception {
        UUID workflow = publishClassified();
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("75.5")).path("id")
                .asString());
        Response refused = api("olga.operator").post(Map.of(), "/workspaces/{ws}/runs/{id}/reveal", PURCHASING, runId);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(403);
        assertThat(api("fred.facilities").post(Map.of(), "/workspaces/{ws}/runs/{id}/reveal", PURCHASING, runId)
                .status()).isEqualTo(404);
        // vic.viewer sees the request (run.read) but not its data: no reveal even with data.reveal.
        String olga = api("olga.operator").get("/me").body().path("subject").path("id").asString();
        String vic = api("vic.viewer").get("/me").body().path("subject").path("id").asString();
        JsonNode olgaGrant = api("wade.admin").post(Map.of("permission", "data.reveal", "principal",
                Map.of("type", "subject", "value", olga)), "/workspaces/{ws}/access/grants", PURCHASING).body();
        JsonNode vicGrant = api("wade.admin").post(Map.of("permission", "data.reveal", "principal",
                Map.of("type", "subject", "value", vic)), "/workspaces/{ws}/access/grants", PURCHASING).body();
        try {
            assertThat(api("vic.viewer").post(Map.of(), "/workspaces/{ws}/runs/{id}/reveal", PURCHASING, runId).status())
                    .isEqualTo(403);
            Response revealed = api("olga.operator").post(Map.of(), "/workspaces/{ws}/runs/{id}/reveal", PURCHASING,
                    runId);
            assertThat(revealed.status()).as(revealed.toString()).isEqualTo(200);
            assertThat(revealed.body().path("values").path("amount").decimalValue()).isEqualByComparingTo("75.5");
            assertThat(revealed.body().path("values").path("purpose").asString()).isEqualTo("Device failed");
            assertThat(revealed.body().path("values").has("title")).isFalse();
            Response one = api("olga.operator").post(Map.of("fields", List.of("purpose")),
                    "/workspaces/{ws}/runs/{id}/reveal", PURCHASING, runId);
            assertThat(one.body().path("values").size()).isEqualTo(1);
            assertThat(api("olga.operator").post(Map.of("fields", List.of("title")), "/workspaces/{ws}/runs/{id}/reveal",
                    PURCHASING, runId).status()).isEqualTo(422);
            // The detail stays masked after a reveal.
            assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}", PURCHASING, runId).body().path("input")
                    .path("amount").asString()).isEqualTo(FieldClassification.MASK);
            String metadata = jdbc.sql("""
                    SELECT metadata::text FROM audit_event WHERE action = 'data.revealed' AND resource_id = :run
                    ORDER BY chain_seq LIMIT 1
                    """).param("run", runId.toString()).query(String.class).single();
            assertThat(metadata).contains("amount", "purpose", "sensitive", "personal").doesNotContain("75.5",
                    "Device failed");
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'auth.denied'"
                    + " AND metadata->>'operationId' = 'revealRunData' AND actor_subject_id = CAST(:s AS uuid)")
                    .param("s", vic).query(Long.class).single()).isPositive();
        } finally {
            api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, olgaGrant.path("id").asString());
            api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, vicGrant.path("id").asString());
        }
    }

    @Test
    void exportsMaskProtectedAnswersInTheDatabaseFunction() {
        String masked = jdbc.sql("""
                SELECT orqix_mask_input(CAST(:input AS jsonb), CAST(:definition AS jsonb))::text
                """)
                .param("input", "{\"a\":\"x\",\"b\":5,\"c\":null,\"d\":\"y\"}")
                .param("definition", "{\"inputSchema\":{\"properties\":{\"a\":{\"classification\":\"personal\"},"
                        + "\"b\":{\"classification\":\"sensitive\"},\"c\":{\"classification\":\"personal\"},"
                        + "\"d\":{\"classification\":\"internal\"}}}}")
                .query(String.class).single();
        assertThat(masked).contains("\"a\": \"" + FieldClassification.MASK + "\"", "\"b\": \""
                + FieldClassification.MASK + "\"", "\"c\": null", "\"d\": \"y\"");
    }
}
