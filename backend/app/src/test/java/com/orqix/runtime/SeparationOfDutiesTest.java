package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** RL03 separation of duties across approval steps (docs/build/S41) through the public API. */
class SeparationOfDutiesTest extends RuntimeTestBase {

    /** Both approvals go to the managers group; the second optionally keeps apart from the first. */
    private static Consumer<ObjectNode> sameGroup(boolean separate) {
        return definition -> {
            for (JsonNode node : definition.path("nodes")) {
                if ("finance".equals(node.path("id").asString())) {
                    ObjectNode config = (ObjectNode) node.path("config");
                    config.put("candidateGroupRef", "managers");
                    if (separate) {
                        config.putArray("separateFrom").add("manager");
                    }
                }
            }
        };
    }

    private UUID runAtFinance(UUID workflow) throws Exception {
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal("6000")).path("id").asString());
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, "finance") != null);
        return run;
    }

    /** Walks every page of the claimable queue: the shared test database can hold more than one page of open tasks. */
    private boolean claimableQueueContains(String user, String taskId) throws Exception {
        String cursor = null;
        do {
            String path = "/workspaces/{ws}/tasks?view=claimable&limit=100" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = api(user).get(path, PURCHASING).body();
            for (JsonNode item : page.path("items")) {
                if (taskId.equals(item.path("id").asString())) {
                    return true;
                }
            }
            cursor = page.path("nextCursor").isMissingNode() || page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null && !cursor.isEmpty());
        return false;
    }

    @Test
    void thePersonWhoDecidedTheFirstStepCannotClaimDecideOrSeeTheSecond() throws Exception {
        UUID run = runAtFinance(publishPurchase(PURCHASING, sameGroup(true)));
        String taskId = openTask(PURCHASING, run, "finance").path("id").asString();

        Response refused = claim("mani.manager", PURCHASING, taskId);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().path("code").asString()).isEqualTo("SEPARATION_OF_DUTIES");
        JsonNode detail = api("mani.manager").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
        assertThat(detail.path("allowedActions").path("claim").path("allowed").asBoolean()).isFalse();
        assertThat(detail.path("allowedActions").path("claim").path("reasonCode").asString()).isEqualTo("SEPARATION_OF_DUTIES");

        // The task is not offered to them in the queue, but it is offered to the other manager.
        assertThat(claimableQueueContains("mani.manager", taskId)).isFalse();
        assertThat(claimableQueueContains("max.manager", taskId)).isTrue();

        assertThat(claim("max.manager", PURCHASING, taskId).status()).isEqualTo(200);
        assertThat(decide("max.manager", PURCHASING, taskId, "approve", null).status()).isEqualTo(201);
        awaitLifecycle(PURCHASING, run, "completed");
    }

    @Test
    void withoutTheSettingTheSamePersonMayDecideBothSteps() throws Exception {
        UUID run = runAtFinance(publishPurchase(PURCHASING, sameGroup(false)));
        String taskId = openTask(PURCHASING, run, "finance").path("id").asString();
        assertThat(claim("mani.manager", PURCHASING, taskId).status()).isEqualTo(200);
        assertThat(decide("mani.manager", PURCHASING, taskId, "approve", null).status()).isEqualTo(201);
    }

    @Test
    void publishingIsBlockedWhenTheReferencedStepIsNotAnEarlierApproval() throws Exception {
        Response created = api("dana.designer").post(java.util.Map.of("key", "sod-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Bad separation", "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        UUID id = UUID.fromString(created.body().path("id").asString());
        JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id).body();
        ObjectNode definition = (ObjectNode) draft.path("definition");
        for (JsonNode node : definition.path("nodes")) {
            if ("manager".equals(node.path("id").asString())) {
                // The first approval cannot be kept apart from a later one: it is decided first.
                ((ObjectNode) node.path("config")).putArray("separateFrom").add("finance");
            }
        }
        Response saved = api("dana.designer").put(java.util.Map.of("definition", definition),
                "\"r" + draft.path("revision").asInt() + "\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, id);
        Response validation = api("dana.designer").post(java.util.Map.of("revision", saved.body().path("revision").asInt()),
                "/workspaces/{ws}/workflows/{id}/validate", PURCHASING, id);
        assertThat(validation.body().toString()).contains("WF_SEPARATION_INVALID");
    }
}
