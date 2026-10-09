package com.orqix.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-RUN-07, hardened: {@code RunsAndTasksTest.tRun06_07} proves that a rollback makes new starts use the older
 * release and that a waiting run of release 1 is unchanged. This class adds the other direction and the in-flight
 * case: at the moment of the rollback there are (a) a run of the newer release 2 waiting for its manager, (b) a run
 * of release 2 whose HTTP job is executing, and (c) a run of release 1 waiting. None of them may change release,
 * route or outcome; the audit trail records both activations; rolling forward again works.
 *
 * <p>Release 1 sends amounts from 5,000 to finance, release 2 only from 10,000, so the route a run takes shows which
 * release really executes it.
 */
class RollbackPinningTest extends RuntimeTestBase {

    private static final BigDecimal AMOUNT = new BigDecimal("6000");

    @Test
    void tRun07_rollbackNeverMovesWaitingOrRunningInstancesAndRollForwardWorks() throws Exception {
        UUID workflowId = publishPurchase(PURCHASING, null);
        UUID waitingOnR1 = startRun(workflowId); // pinned to release 1
        publishRelease2(workflowId);
        UUID waitingOnR2 = startRun(workflowId);
        UUID runningOnR2 = startRun(workflowId);
        assertThat(releaseNumber(waitingOnR1)).isEqualTo(1);
        assertThat(releaseNumber(waitingOnR2)).isEqualTo(2);
        assertThat(releaseNumber(runningOnR2)).isEqualTo(2);

        // The HTTP job of runningOnR2 is in flight when the rollback happens.
        Stub.fault(Stub.Mode.DELAY, 1, 2500); // shorter than the 3 s HTTP timeout of the test workflows
        approve("mani.manager", PURCHASING, runningOnR2, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> Stub.postCount() >= 1);
        assertThat(lifecycle(PURCHASING, runningOnR2)).isEqualTo("running");
        int activationsBefore = activationAudits(workflowId);

        activate(workflowId, 1);

        assertThat(activeReleaseNumber(workflowId)).isEqualTo(1);
        assertThat(activationAudits(workflowId)).as("rollback audited").isEqualTo(activationsBefore + 1);
        UUID startedAfterRollback = startRun(workflowId);
        assertThat(releaseNumber(startedAfterRollback)).as("new starts use the rolled-back release").isEqualTo(1);
        // Nothing that existed before the rollback changed its release.
        assertThat(releaseNumber(waitingOnR1)).isEqualTo(1);
        assertThat(releaseNumber(waitingOnR2)).isEqualTo(2);
        assertThat(releaseNumber(runningOnR2)).isEqualTo(2);

        // The job that was running finishes on release 2: Fulfilled, no finance task, one order.
        awaitLifecycle(PURCHASING, runningOnR2, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, runningOnR2)).isEqualTo("completed");
        assertThat(run(PURCHASING, runningOnR2).path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(visitedFinance(runningOnR2)).as("release 2 does not route 6,000 to finance").isFalse();
        assertThat(releaseNumber(runningOnR2)).isEqualTo(2);

        // The waiting release 2 run keeps release 2 semantics although release 1 is active: no finance step.
        approve("max.manager", PURCHASING, waitingOnR2, "manager");
        awaitLifecycle(PURCHASING, waitingOnR2, "completed", "needsAttention");
        assertThat(lifecycle(PURCHASING, waitingOnR2)).isEqualTo("completed");
        assertThat(visitedFinance(waitingOnR2)).isFalse();
        assertThat(releaseNumber(waitingOnR2)).isEqualTo(2);

        // The release 1 runs (waiting before, started after the rollback) both go to finance.
        for (UUID runOnR1 : new UUID[] {waitingOnR1, startedAfterRollback}) {
            approve("mani.manager", PURCHASING, runOnR1, "manager");
            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> openTask(PURCHASING, runOnR1, "finance") != null);
            assertThat(releaseNumber(runOnR1)).isEqualTo(1);
            approve("fin.finance", PURCHASING, runOnR1, "finance");
            awaitLifecycle(PURCHASING, runOnR1, "completed", "needsAttention");
            assertThat(run(PURCHASING, runOnR1).path("outcome").asString()).isEqualTo("Fulfilled");
        }
        assertThat(Stub.orderCount()).as("one order per completed run, none duplicated").isEqualTo(4);

        // Roll forward again: release 2 is selected for new starts, the audit trail has both changes.
        activate(workflowId, 2);
        UUID afterRollForward = startRun(workflowId);
        assertThat(releaseNumber(afterRollForward)).isEqualTo(2);
        assertThat(activationAudits(workflowId)).isEqualTo(activationsBefore + 2);
    }

    private UUID startRun(UUID workflowId) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, workflowId, AMOUNT).path("id").asString());
    }

    private int releaseNumber(UUID runId) throws Exception {
        return run(PURCHASING, runId).path("release").path("releaseNumber").asInt();
    }

    private boolean visitedFinance(UUID runId) throws Exception {
        for (JsonNode task : run(PURCHASING, runId).path("tasks")) {
            if ("finance".equals(task.path("nodeId").asString())) {
                return true;
            }
        }
        return false;
    }

    private int activeReleaseNumber(UUID workflowId) throws Exception {
        return api("paul.publisher").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).body()
                .path("activeRelease").path("releaseNumber").asInt();
    }

    private int activationAudits(UUID workflowId) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.active-release.changed'")
                .param("ws", PURCHASING).param("wf", workflowId.toString()).query(Integer.class).single();
    }

    /** Selects release {@code releaseNumber} for new starts (publisher). */
    private void activate(UUID workflowId, int releaseNumber) throws Exception {
        JsonNode workflow = api("paul.publisher").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflowId).body();
        String releaseId = null;
        for (JsonNode release : api("paul.publisher").get("/workspaces/{ws}/workflows/{id}/releases", PURCHASING,
                workflowId).body().path("items")) {
            if (release.path("releaseNumber").asInt() == releaseNumber) {
                releaseId = release.path("id").asString();
            }
        }
        assertThat(releaseId).as("release " + releaseNumber).isNotNull();
        Response response = api("paul.publisher").post(Map.of("releaseId", releaseId, "expectedVersion",
                workflow.path("version").asInt()), "/workspaces/{ws}/workflows/{id}/active-release", PURCHASING,
                workflowId);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
    }

    /** Publishes release 2 with the finance threshold raised to 10,000. */
    private void publishRelease2(UUID workflowId) throws Exception {
        ObjectNode definition = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft",
                PURCHASING, workflowId).body().path("definition");
        ((ObjectNode) definition.path("decisions").get(0).path("rows").get(0).path("when")).put("value", 10000);
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r2\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflowId);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response published = api("paul.publisher").post(Map.of("draftRevision", 3, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflowId);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        assertThat(published.body().path("releaseNumber").asInt()).isEqualTo(2);
    }
}
