package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.identity.PolicyService;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/** Guided commands must preserve form validation, explicit review, authorship, and runtime permissions. */
class ConversationApiTest extends RuntimeTestBase {
    @Autowired private PolicyService policy;

    private Response send(String user, UUID ws, JsonNode conversation, String message) throws Exception {
        return send(user, ws, conversation, message, UUID.randomUUID().toString());
    }

    private Response send(String user, UUID ws, JsonNode conversation, String message, String key) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (conversation != null) {
            body.put("conversationId", conversation.path("id").asString());
            body.put("expectedVersion", conversation.path("version").asInt());
        }
        return api(user).post(body, key, "/workspaces/{ws}/conversations/messages", ws);
    }

    private JsonNode next(String user, JsonNode conversation, String message) throws Exception {
        Response response = send(user, PURCHASING, conversation, message);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.body();
    }

    private JsonNode begin(UUID workflow) throws Exception {
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflow)
                .body().path("key").asString();
        return next("rhea.requester", null, "start " + key);
    }

    private JsonNode reviewed(UUID workflow) throws Exception {
        JsonNode conversation = begin(workflow);
        for (String answer : new String[] {"Replacement laptop", "Existing device failed", "OPS", "100.00"}) {
            conversation = next("rhea.requester", conversation, answer);
        }
        assertThat(conversation.path("stage").asString()).isEqualTo("review");
        return conversation;
    }

    private long runCount(UUID workflow) {
        return jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :id")
                .param("id", workflow).query(Long.class).single();
    }

    @Test
    void collectsTypedFieldsRejectsInvalidValuesAndPermitsReviewedEdits() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        JsonNode conversation = begin(workflow);
        assertThat(conversation.path("field").path("name").asString()).isEqualTo("title");
        conversation = next("rhea.requester", conversation, "Replacement laptop");
        conversation = next("rhea.requester", conversation, "Existing device failed");
        assertThat(conversation.path("field").path("name").asString()).isEqualTo("costCenter");
        conversation = next("rhea.requester", conversation, "INVALID");
        assertThat(conversation.path("stage").asString()).isEqualTo("collecting");
        assertThat(conversation.path("field").path("name").asString()).isEqualTo("costCenter");
        conversation = next("rhea.requester", conversation, "OPS");
        conversation = next("rhea.requester", conversation, "not a number");
        assertThat(conversation.path("field").path("name").asString()).isEqualTo("amount");
        conversation = next("rhea.requester", conversation, "-5");
        assertThat(conversation.path("stage").asString()).isEqualTo("collecting");
        conversation = next("rhea.requester", conversation, "1,250.25");
        assertThat(conversation.path("review").path("input").path("amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1250.25"));
        conversation = next("rhea.requester", conversation, "amount=150.50");
        assertThat(conversation.path("review").path("input").path("amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("150.50"));
        assertThat(runCount(workflow)).isZero();
    }

    @Test
    void confirmationCreatesExactlyOneRunAndReplaysTheSameResponse() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        JsonNode conversation = reviewed(workflow);
        assertThat(runCount(workflow)).isZero();
        String key = UUID.randomUUID().toString();
        Response submitted = send("rhea.requester", PURCHASING, conversation, "confirm", key);
        assertThat(submitted.status()).as(submitted.toString()).isEqualTo(200);
        assertThat(submitted.body().path("stage").asString()).isEqualTo("completed");
        assertThat(submitted.body().path("runId").asString()).isNotBlank();
        Response replay = send("rhea.requester", PURCHASING, conversation, "confirm", key);
        assertThat(replay.status()).as(replay.toString()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(submitted.body());
        assertThat(runCount(workflow)).isEqualTo(1);
        JsonNode after = next("rhea.requester", submitted.body(), "confirm");
        assertThat(after.path("message").asString()).contains("no reviewed action");
        assertThat(runCount(workflow)).isEqualTo(1);
    }

    @Test
    void draftCanBeResumedButStaleMessagesCannotOverwriteIt() throws Exception {
        JsonNode original = begin(publishPurchase(PURCHASING, null));
        JsonNode updated = next("rhea.requester", original, "Laptop");
        Response resumed = api("rhea.requester").get("/workspaces/{ws}/conversations/{id}",
                PURCHASING, updated.path("id").asString());
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.body()).isEqualTo(updated);
        Response stale = send("rhea.requester", PURCHASING, original, "Stale title");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("TASK_VERSION_CONFLICT");
        JsonNode current = api("rhea.requester").get("/workspaces/{ws}/conversations/{id}",
                PURCHASING, updated.path("id").asString()).body();
        assertThat(current).isEqualTo(updated);
    }

    @Test
    void conversationIsPrivateToItsAuthorAndWorkspaceAndExpires() throws Exception {
        JsonNode conversation = begin(publishPurchase(PURCHASING, null));
        String id = conversation.path("id").asString();
        assertThat(api("dana.designer").get("/workspaces/{ws}/conversations/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(send("dana.designer", PURCHASING, conversation, "Laptop").status()).isEqualTo(404);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/conversations/{id}", SANDBOX, id).status()).isEqualTo(404);
        assertThat(send("rhea.requester", SANDBOX, conversation, "Laptop").status()).isEqualTo(404);
        jdbc.sql("UPDATE conversation SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        assertThat(api("rhea.requester").get("/workspaces/{ws}/conversations/{id}", PURCHASING, id).status()).isEqualTo(404);
    }

    @Test
    void taskCommandRequiresConfirmationAndReplaysWithoutASecondDecision() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        JsonNode pending = next("mani.manager", null, "approve " + taskId);
        assertThat(pending.path("stage").asString()).isEqualTo("confirmingTask");
        assertThat(openTask(PURCHASING, runId, "manager")).isNotNull();
        String key = UUID.randomUUID().toString();
        Response decision = send("mani.manager", PURCHASING, pending, "confirm", key);
        assertThat(decision.status()).as(decision.toString()).isEqualTo(200);
        assertThat(decision.body().path("stage").asString()).isEqualTo("completed");
        assertThat(openTask(PURCHASING, runId, "manager")).isNull();
        Response replay = send("mani.manager", PURCHASING, pending, "confirm", key);
        assertThat(replay.status()).as(replay.toString()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(decision.body());
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :id AND type = 'TASK_DECIDED'")
                .param("id", runId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void taskConfirmationRechecksPermissionAndConcurrentOwnership() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        JsonNode pending = next("mani.manager", null, "approve " + taskId);
        UUID roleBinding = UUID.fromString("00000000-0000-7000-8000-000000000306");
        try {
            jdbc.sql("UPDATE role_binding SET role = 'VIEWER' WHERE id = :id").param("id", roleBinding).update();
            policy.evictAll();
            Response rejected = send("mani.manager", PURCHASING, pending, "confirm");
            assertThat(rejected.status()).isIn(403, 404);
            assertThat(openTask(PURCHASING, runId, "manager")).isNotNull();
        } finally {
            jdbc.sql("UPDATE role_binding SET role = 'APPROVER' WHERE id = :id").param("id", roleBinding).update();
            policy.evictAll();
        }
        assertThat(claim("max.manager", PURCHASING, taskId).status()).isEqualTo(200);
        Response conflict = send("mani.manager", PURCHASING, pending, "confirm");
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("TASK_VERSION_CONFLICT");
        assertThat(openTask(PURCHASING, runId, "manager")).isNotNull();
    }
}
