package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.identity.PolicyService;
import com.orqix.support.AgentLlmStub;
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
    void naturalLanguageFindsAnAllowedWorkflowButStillCollectsAndReviewsBeforeStarting() throws Exception {
        AgentLlmStub.reset();
        UUID workflow = publishPurchase(PURCHASING, null);
        AgentLlmStub.answer("{\"workflowIds\":[\"" + workflow + "\"]}", 0.0001);
        AgentLlmStub.answer("{\"title\":\"Replacement laptop\",\"purpose\":\"Current device failed\"}", 0.0001);
        String key = UUID.randomUUID().toString();

        Response response = send("rhea.requester", PURCHASING, null,
                "I need a replacement laptop because my current device failed", key);

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.body().path("stage").asString()).isEqualTo("collecting");
        assertThat(response.body().path("field").path("name").asString()).isEqualTo("costCenter");
        assertThat(response.body().path("message").asString()).contains("picked up What are you buying?, Business purpose");
        assertThat(runCount(workflow)).isZero();
        assertThat(AgentLlmStub.requests()).hasSize(2);
        assertThat(AgentLlmStub.requests().getFirst().path("messages").toString())
                .contains("I need a replacement laptop");
        assertThat(AgentLlmStub.requests().getFirst().path("messages").toString()).contains("Purchase");
        assertThat(AgentLlmStub.requests().getLast().path("messages").toString()).contains("What are you buying?", "Business purpose");
        assertThat(AgentLlmStub.requests().getLast().path("response_format").toString()).contains("title", "purpose");

        Response replay = send("rhea.requester", PURCHASING, null,
                "I need a replacement laptop because my current device failed", key);
        assertThat(replay.status()).as(replay.toString()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(AgentLlmStub.requests()).as("a replay never calls the provider again").hasSize(2);
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
        assertThat(after.path("message").asString()).contains("nothing to confirm");
        assertThat(runCount(workflow)).isEqualTo(1);
    }

    @Test
    void collectionAnswersValidateItemsAndBoundsBeforeSubmission() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, definition -> {
            var properties = (tools.jackson.databind.node.ObjectNode) definition.path("inputSchema").path("properties");
            var collection = properties.putObject("quantities").put("type", "array").put("title", "Quantities")
                    .put("minItems", 1).put("maxItems", 2);
            collection.putObject("items").put("type", "integer").put("title", "Quantity").put("minimum", 1).put("maximum", 5);
        });
        JsonNode conversation = begin(workflow);
        for (String answer : new String[] {"Laptop", "Replacement", "OPS", "10.00"})
            conversation = next("rhea.requester", conversation, answer);
        assertThat(conversation.path("field").path("name").asString()).isEqualTo("quantities");
        for (String invalid : new String[] {"invalid JSON", "[0]", "[1,2,3]", "[1,\"two\"]"}) {
            conversation = next("rhea.requester", conversation, invalid);
            assertThat(conversation.path("stage").asString()).isEqualTo("collecting");
        }
        conversation = next("rhea.requester", conversation, "[1,2]");
        assertThat(conversation.path("review").path("input").path("quantities")).isEqualTo(json.parse("[1,2]"));
        JsonNode completed = next("rhea.requester", conversation, "confirm");
        assertThat(completed.path("stage").asString()).isEqualTo("completed");
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

    @Test
    void promptsAndReviewUseBusinessLanguageAndAcceptPlainLists() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, definition -> {
            var properties = (tools.jackson.databind.node.ObjectNode) definition.path("inputSchema").path("properties");
            properties.putObject("items").put("type", "array").put("title", "Items needed").put("minItems", 1).put("maxItems", 5)
                    .putObject("items").put("type", "string").put("title", "Item").put("maxLength", 40);
        });
        JsonNode conversation = begin(workflow);
        assertThat(conversation.path("message").asString()).doesNotContain("??").doesNotContain("What is What");
        conversation = next("rhea.requester", conversation, "Desk kit");
        conversation = next("rhea.requester", conversation, "New starter");
        conversation = next("rhea.requester", conversation, "ops");
        assertThat(conversation.path("field").path("name").asString()).as("choices are matched ignoring case").isEqualTo("amount");
        conversation = next("rhea.requester", conversation, "20");
        assertThat(conversation.path("field").path("name").asString()).isEqualTo("items");
        conversation = next("rhea.requester", conversation, "Laptop, Monitor ,, Dock");
        assertThat(conversation.path("stage").asString()).isEqualTo("review");
        assertThat(conversation.path("review").path("input").path("items")).isEqualTo(json.parse("[\"Laptop\",\"Monitor\",\"Dock\"]"));
        assertThat(conversation.path("review").path("input").path("costCenter").asString()).isEqualTo("OPS");
        var labels = new java.util.ArrayList<String>();
        conversation.path("review").path("fields").forEach(f -> labels.add(f.path("label").asString()));
        assertThat(labels).contains("What are you buying?", "Cost center", "Items needed");
        assertThat(conversation.path("message").asString()).doesNotContain("fieldName");
        assertThat(runCount(workflow)).isZero();
    }

    @Test
    void taskReviewShowsTheRequestAndNeedsAnExplicitChoiceThenConfirmation() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        JsonNode tasks = next("mani.manager", null, "my tasks");
        assertThat(tasks.path("message").asString()).doesNotContain("approve");
        boolean offered = false;
        for (JsonNode choice : tasks.path("choices")) {
            assertThat(choice.path("command").asString()).startsWith("review ");
            offered |= choice.path("command").asString().equals("review " + taskId);
        }
        // "my tasks" lists at most 25 per tab, oldest first. Other tests share this workspace, so when older manager tasks
        // fill the list the new task is not among them; the review below opens it by id either way.
        assertThat(offered || tasks.path("choices").size() >= 25).as("the new task is offered, or the list is full").isTrue();

        JsonNode review = next("mani.manager", tasks, "review " + taskId);
        assertThat(review.path("stage").asString()).isEqualTo("confirmingTask");
        assertThat(review.has("taskAction")).as("nothing is prepared until the person chooses").isFalse();
        assertThat(review.path("review").path("fields").size()).isPositive();
        assertThat(review.path("review").path("fields").toString()).contains("\"label\"");
        JsonNode early = next("mani.manager", review, "confirm");
        assertThat(early.path("stage").asString()).isEqualTo("confirmingTask");
        assertThat(early.path("message").asString()).contains("Approve or Reject");
        assertThat(openTask(PURCHASING, runId, "manager")).as("an early confirm decides nothing").isNotNull();

        JsonNode reject = next("mani.manager", early, "reject " + taskId);
        assertThat(reject.path("taskAction").asString()).isEqualTo("reject");
        JsonNode noReason = next("mani.manager", reject, "confirm");
        assertThat(noReason.path("stage").asString()).isEqualTo("confirmingTask");
        assertThat(noReason.path("message").asString()).contains("reason");
        assertThat(openTask(PURCHASING, runId, "manager")).isNotNull();

        JsonNode withReason = next("mani.manager", noReason, "reject " + taskId + ": Please attach a quote");
        JsonNode done = next("mani.manager", withReason, "confirm");
        assertThat(done.path("stage").asString()).isEqualTo("completed");
        assertThat(done.path("message").asString()).contains("rejected");
        assertThat(openTask(PURCHASING, runId, "manager")).isNull();
    }

    @Test
    void taskReviewIsLimitedToPeopleWhoCanActOnTheTask() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String taskId = openTask(PURCHASING, runId, "manager").path("id").asString();
        Response outsider = send("rhea.requester", PURCHASING, null, "review " + taskId);
        assertThat(outsider.status()).isIn(403, 404);
        assertThat(openTask(PURCHASING, runId, "manager")).isNotNull();
    }

    @Test
    void datesAreAskedForPlainlyValidatedAndShownReadably() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, definition -> {
            var properties = (tools.jackson.databind.node.ObjectNode) definition.path("inputSchema").path("properties");
            properties.putObject("needBy").put("type", "string").put("format", "date").put("title", "Needed by")
                    .put("formatMinimum", "2026-01-01");
        });
        JsonNode conversation = begin(workflow);
        for (String answer : new String[] {"Desk", "New starter", "OPS", "20"})
            conversation = next("rhea.requester", conversation, answer);
        assertThat(conversation.path("field").path("name").asString()).isEqualTo("needBy");
        assertThat(conversation.path("message").asString()).contains("year-month-day", "2026-10-14");
        conversation = next("rhea.requester", conversation, "next Tuesday");
        assertThat(conversation.path("stage").asString()).isEqualTo("collecting");
        assertThat(conversation.path("message").asString()).contains("year-month-day");
        conversation = next("rhea.requester", conversation, "2025-12-31");
        assertThat(conversation.path("stage").asString()).isEqualTo("collecting");
        conversation = next("rhea.requester", conversation, "2026-10-14");
        assertThat(conversation.path("stage").asString()).isEqualTo("review");
        String shown = null;
        for (JsonNode row : conversation.path("review").path("fields"))
            if (row.path("name").asString().equals("needBy")) shown = row.path("display").asString();
        assertThat(shown).isEqualTo("14 October 2026");
        assertThat(conversation.path("review").path("input").path("needBy").asString()).isEqualTo("2026-10-14");
    }
}
