package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * EX06/EX08 slice on PostgreSQL and Flowable (docs/build/S59): sections with an error route and a time limit, handlers
 * started by a signal or a timer, signals that stay inside one request, the operator signal API and its permission
 * boundary, and the automatic undo lifecycle on top of the HTTP failure route.
 */
class ScopesAndEventsRuntimeTest extends RuntimeTestBase {

    // ---------------------------------------------------------------- definition helpers

    private void add(ObjectNode d, String json) {
        ((ArrayNode) d.path("nodes")).add(this.json.parse(json));
    }

    private void path(ObjectNode d, String json) {
        ((ArrayNode) d.path("transitions")).add(this.json.parse(json));
    }

    private static void retarget(ObjectNode d, String id, String to) {
        for (JsonNode t : d.path("transitions")) {
            if (id.equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", to);
            }
        }
    }

    private void scope(ObjectNode d, String json) {
        if (!d.has("subprocesses")) {
            d.putArray("subprocesses");
        }
        ((ArrayNode) d.path("subprocesses")).add(this.json.parse(json));
    }

    private static String note(String id, String message) {
        return "{\"id\":\"" + id + "\",\"type\":\"notification\",\"label\":\"" + id + "\",\"config\":{\"channel\":\"inApp\","
                + "\"recipient\":\"initiator\",\"message\":\"" + message + "\"}}";
    }

    private static String call(String id, String label, String title, String extra) {
        return "{\"id\":\"" + id + "\",\"type\":\"http\",\"label\":\"" + label + "\",\"config\":{\"connectionRef\":\"procurement\","
                + "\"operation\":\"createOrder\",\"inputMapping\":{\"title\":\"" + title + "\",\"amount\":\"/input/amount\","
                + "\"costCenter\":\"/input/costCenter\"},\"resultMapping\":{\"procurementId\":\"/id\"},\"timeoutSeconds\":3" + extra + "}}";
    }

    /** The review (limit check, manager, policy, routing, finance) is a section with an error route and, optionally, a time limit. */
    private Consumer<ObjectNode> section(boolean timeLimit) {
        return d -> {
            add(d, "{\"id\":\"limit\",\"type\":\"branch\",\"label\":\"Within the limit?\",\"config\":{}}");
            add(d, "{\"id\":\"tooBig\",\"type\":\"raiseError\",\"label\":\"Over the limit\",\"config\":{\"errorCode\":\"TOO_EXPENSIVE\"}}");
            add(d, note("limitNotice", "Over the limit"));
            retarget(d, "t1", "limit");
            path(d, "{\"id\":\"l1\",\"from\":\"limit\",\"to\":\"tooBig\",\"on\":\"condition\",\"label\":\"100,000 or more\","
                    + "\"condition\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":100000}}");
            path(d, "{\"id\":\"l2\",\"from\":\"limit\",\"to\":\"manager\",\"on\":\"default\"}");
            path(d, "{\"id\":\"l3\",\"from\":\"limitNotice\",\"to\":\"rejected\",\"on\":\"next\"}");
            scope(d, "{\"id\":\"review\",\"label\":\"Review\",\"type\":\"embedded\",\"nodes\":[\"limit\",\"tooBig\",\"manager\","
                    + "\"amountPolicy\",\"route\",\"finance\"]," + (timeLimit ? "\"timeoutAfter\":\"P5D\",\"onTimeout\":\"notifyRejected\"," : "")
                    + "\"onError\":{\"to\":\"limitNotice\",\"errorCode\":\"TOO_EXPENSIVE\"}}");
            d.remove("uiLayout");
        };
    }

    private UUID startRun(UUID workflow, String amount) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal(amount)).path("id").asString());
    }

    private List<String> events(UUID run) {
        return jdbc.sql("SELECT type || ':' || coalesce(node_id, '') FROM run_event WHERE run_id = :run ORDER BY seq")
                .param("run", run).query(String.class).list();
    }

    private String eventData(UUID run, String type) {
        return jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = :type ORDER BY seq LIMIT 1")
                .param("run", run).param("type", type).query(String.class).optional().orElse(null);
    }

    private String taskStatus(UUID run, String node) {
        return jdbc.sql("SELECT status FROM task_context WHERE run_id = :run AND node_id = :node ORDER BY created_at DESC LIMIT 1")
                .param("run", run).param("node", node).query(String.class).single();
    }

    private void awaitTask(UUID run, String node) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, run, node) != null);
    }

    private void awaitEvent(UUID run, String type) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> events(run).stream().anyMatch(e -> e.startsWith(type + ":")));
    }

    private JsonNode path(UUID run) throws Exception {
        return api("olga.operator").get("/workspaces/{ws}/runs/{id}/path", PURCHASING, run).body();
    }

    // ---------------------------------------------------------------- sections and errors

    @Test
    void aRaisedErrorLeavesTheSectionByItsErrorRouteAndANormalRequestLeavesByItsPaths() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, section(false));
        UUID big = startRun(workflow, "150000");
        awaitLifecycle(PURCHASING, big, "completed", "needsAttention");
        JsonNode done = run(PURCHASING, big);
        assertThat(done.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(done.path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(events(big)).containsSubsequence("SECTION_ENTERED:review", "ERROR_RAISED:tooBig", "ERROR_CAUGHT:review",
                "SECTION_LEFT:review", "NOTIFICATION_CREATED:limitNotice", "RUN_COMPLETED:rejected");
        assertThat(eventData(big, "SECTION_LEFT")).contains("\"how\": \"error\"");
        JsonNode bigPath = path(big);
        assertThat(bigPath.path("taken").toString()).contains("review__onError");
        assertThat(bigPath.path("diagram").path("scopes").get(0).path("routes").get(0).path("id").asString()).isEqualTo("review__onError");
        assertThat(bigPath.path("scopeVisits").get(0).path("how").asString()).isEqualTo("error");
        assertThat(bigPath.path("visited").toString()).contains("\"nodeId\":\"tooBig\"", "\"outcome\":\"raised\"");
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :run").param("run", big).query(Long.class).single()).isZero();

        // Below the limit the section is left by its own paths: the gateway after it continues at the purchase order.
        UUID small = startRun(workflow, "10");
        awaitTask(small, "manager");
        approve("mani.manager", PURCHASING, small, "manager");
        awaitLifecycle(PURCHASING, small, "completed", "needsAttention");
        assertThat(run(PURCHASING, small).path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(eventData(small, "SECTION_LEFT")).contains("\"how\": \"completed\"", "\"exit\": \"procure\"");
        assertThat(Stub.postCount()).isEqualTo(1);
    }

    @Test
    void aSectionTimeLimitCancelsTheOpenApprovalAndContinuesAtItsTarget() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, section(true));
        UUID run = startRun(workflow, "10");
        awaitTask(run, "manager");
        assertThat(run(PURCHASING, run).path("currentSteps").size()).isEqualTo(1);
        fastForward(PURCHASING, run); // the 5-day time limit of the section passes
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(run(PURCHASING, run).path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(taskStatus(run, "manager")).isEqualTo("cancelled");
        assertThat(events(run)).containsSubsequence("SECTION_ENTERED:review", "TASK_CREATED:manager",
                "STEP_INTERRUPTED:manager", "NOTIFICATION_CREATED:notifyRejected", "RUN_COMPLETED:rejected");
        assertThat(eventData(run, "STEP_INTERRUPTED")).contains("\"reason\": \"timedOut\"", "\"by\": \"review\"");
        JsonNode path = path(run);
        assertThat(path.path("taken").toString()).contains("review__onTimeout");
        assertThat(path.path("visited").toString()).contains("\"outcome\":\"interrupted\"");
    }

    // ---------------------------------------------------------------- call failures as section errors (S71)

    /** The purchase order and the confirmation are a section whose error route continues at the rejection notice. */
    private Consumer<ObjectNode> orderSection(boolean optIn) {
        return d -> {
            scope(d, "{\"id\":\"order\",\"label\":\"Order\",\"type\":\"embedded\",\"nodes\":[\"procure\",\"notify\"],"
                    + "\"onError\":{\"to\":\"notifyRejected\",\"errorCode\":\"ORDER_REJECTED\"}}");
            if (optIn) {
                for (JsonNode n : d.path("nodes")) {
                    if ("procure".equals(n.path("id").asString())) {
                        ((ObjectNode) n.path("config")).put("raiseErrorOnFailure", "ORDER_REJECTED");
                    }
                }
            }
            d.remove("uiLayout");
        };
    }

    @Test
    void aRejectedCallThatOptsInRaisesItsErrorAndLeavesTheSectionByItsErrorRoute() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, orderSection(true));
        UUID run = startRun(workflow, "10");
        awaitTask(run, "manager");
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("lifecycle").asString()).as(events(run).toString()).isEqualTo("completed");
        assertThat(done.path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(done.path("currentSteps").size()).isZero();
        // A known rejection is sent once, never retried, and raised as the section's business error.
        assertThat(Stub.postCount()).isEqualTo(1);
        assertThat(events(run)).containsSubsequence("SECTION_ENTERED:order", "EFFECT_FAILED:procure",
                "ERROR_RAISED:procure", "ERROR_CAUGHT:order", "SECTION_LEFT:order",
                "NOTIFICATION_CREATED:notifyRejected", "RUN_COMPLETED:rejected");
        assertThat(events(run)).doesNotContain("STEP_FAILED:procure", "STEP_INTERRUPTED:procure", "NOTIFICATION_CREATED:notify");
        assertThat(eventData(run, "ERROR_RAISED")).contains("\"errorCode\": \"ORDER_REJECTED\"", "\"source\": \"callFailure\"",
                "\"failureKind\": \"rejected\"");
        assertThat(eventData(run, "EFFECT_FAILED")).contains("\"raisedError\": \"ORDER_REJECTED\"", "\"routed\": true");
        assertThat(eventData(run, "SECTION_LEFT")).contains("\"how\": \"error\"");
        assertThat(jdbc.sql("SELECT state FROM effect_journal WHERE run_id = :run AND node_id = 'procure'")
                .param("run", run).query(String.class).single()).isEqualTo("permanentFailure");
        JsonNode path = path(run);
        assertThat(path.path("taken").toString()).contains("order__onError");
        assertThat(path.path("visited").toString()).contains("\"nodeId\":\"procure\"", "\"outcome\":\"raised\"");
        assertThat(path.path("scopeVisits").get(0).path("how").asString()).isEqualTo("error");
    }

    @Test
    void anUnavailableSystemStillNeedsAttentionAndACallWithoutTheOptInKeepsItsOldBehavior() throws Exception {
        // With the opt-in, a failure that may be temporary is retried and then needs attention; no error is raised.
        UUID workflow = publishPurchase(PURCHASING, orderSection(true));
        UUID run = startRun(workflow, "10");
        awaitTask(run, "manager");
        Stub.fault(Stub.Mode.STATUS500, 20, 0);
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "needsAttention", "completed");
        JsonNode stuck = run(PURCHASING, run);
        assertThat(stuck.path("lifecycle").asString()).isEqualTo("needsAttention");
        assertThat(stuck.path("attention").path("nodeId").asString()).isEqualTo("procure");
        assertThat(events(run)).noneMatch(e -> e.startsWith("ERROR_RAISED") || e.startsWith("SECTION_LEFT"));

        // Without the opt-in, a rejected call in the same section needs attention exactly as before (S32 default).
        Stub.reset();
        bindStub(PURCHASING);
        UUID plain = publishPurchase(PURCHASING, orderSection(false));
        UUID other = startRun(plain, "10");
        awaitTask(other, "manager");
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        approve("mani.manager", PURCHASING, other, "manager");
        awaitLifecycle(PURCHASING, other, "needsAttention", "completed");
        JsonNode held = run(PURCHASING, other);
        assertThat(held.path("lifecycle").asString()).isEqualTo("needsAttention");
        assertThat(held.path("attention").path("nodeId").asString()).isEqualTo("procure");
        assertThat(events(other)).noneMatch(e -> e.startsWith("ERROR_RAISED") || e.startsWith("ERROR_CAUGHT"));
    }

    // ---------------------------------------------------------------- handlers and signals

    private Consumer<ObjectNode> handlers() {
        return d -> {
            add(d, "{\"id\":\"withdrawn\",\"type\":\"end\",\"label\":\"Withdrawn\",\"config\":{\"businessOutcome\":\"Withdrawn\","
                    + "\"outcomeKind\":\"rejected\"}}");
            add(d, note("sayWithdrawn", "Your request was withdrawn"));
            add(d, note("remind", "Your request is still open"));
            path(d, "{\"id\":\"w1\",\"from\":\"sayWithdrawn\",\"to\":\"withdrawn\",\"on\":\"next\"}");
            scope(d, "{\"id\":\"withdraw\",\"label\":\"Withdraw\",\"type\":\"event\",\"nodes\":[\"sayWithdrawn\",\"withdrawn\"],"
                    + "\"trigger\":{\"type\":\"signal\",\"signal\":\"withdraw\"},\"interrupting\":true}");
            scope(d, "{\"id\":\"reminder\",\"label\":\"Reminder\",\"type\":\"event\",\"nodes\":[\"remind\"],"
                    + "\"trigger\":{\"type\":\"timer\",\"after\":\"P3D\"},\"interrupting\":false}");
            d.remove("uiLayout");
        };
    }

    private Map<String, Object> signal(String name, UUID run) throws Exception {
        return Map.of("signal", name, "reason", "Requester asked to withdraw",
                "expectedVersion", run(PURCHASING, run).path("version").asInt());
    }

    @Test
    void anOperatorSignalStartsAnInterruptingHandlerOfThatRequestOnly() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, handlers());
        UUID run = startRun(workflow, "10");
        UUID other = startRun(workflow, "10");
        awaitTask(run, "manager");
        awaitTask(other, "manager");

        // Permission boundary: a requester may not, an outsider does not even see the request.
        var requester = api("rhea.requester").post(signal("withdraw", run), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/signals", PURCHASING, run);
        assertThat(requester.status()).isEqualTo(403);
        var outsider = api("otto.outsider").post(signal("withdraw", run), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/signals", PURCHASING, run);
        assertThat(outsider.status()).isEqualTo(404);
        var unknown = api("olga.operator").post(signal("nobody-waits", run), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/signals", PURCHASING, run);
        assertThat(unknown.status()).isEqualTo(409);
        assertThat(unknown.code()).isEqualTo("SIGNAL_NOT_AWAITED");

        String key = UUID.randomUUID().toString();
        Map<String, Object> body = signal("withdraw", run);
        var sent = api("olga.operator").post(body, key, "/workspaces/{ws}/runs/{id}/signals", PURCHASING, run);
        assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
        assertThat(api("olga.operator").post(body, key, "/workspaces/{ws}/runs/{id}/signals", PURCHASING, run).status())
                .isEqualTo(200);
        try {
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "completed".equals(lifecycle(PURCHASING, run)));
        } catch (RuntimeException e) {
            throw new AssertionError("Request did not finish: " + lifecycle(PURCHASING, run) + " " + events(run) + " "
                    + run(PURCHASING, run).path("currentSteps") + " " + run(PURCHASING, run).path("attention"), e);
        }
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("outcome").asString()).isEqualTo("Withdrawn");
        assertThat(taskStatus(run, "manager")).isEqualTo("cancelled");
        assertThat(events(run)).containsSubsequence("SIGNAL_SENT:", "STEP_INTERRUPTED:manager", "HANDLER_STARTED:withdraw",
                "NOTIFICATION_CREATED:sayWithdrawn", "RUN_COMPLETED:withdrawn");
        assertThat(events(run).stream().filter(e -> e.startsWith("SIGNAL_SENT")).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'run.signal-sent' AND resource_id = :id")
                .param("id", run.toString()).query(Long.class).single()).isEqualTo(1);

        // The other request of the same workflow is untouched.
        assertThat(lifecycle(PURCHASING, other)).isNotEqualTo("completed");
        assertThat(openTask(PURCHASING, other, "manager")).isNotNull();
        assertThat(events(other)).noneMatch(e -> e.startsWith("HANDLER_STARTED"));
    }

    @Test
    void aTimerHandlerRunsAlongsideTheRequestWhichStillFinishesNormally() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, handlers());
        UUID run = startRun(workflow, "10");
        awaitTask(run, "manager");
        fastForward(PURCHASING, run); // three days pass
        awaitEvent(run, "NOTIFICATION_CREATED");
        assertThat(events(run)).containsSubsequence("HANDLER_STARTED:reminder", "NOTIFICATION_CREATED:remind");
        assertThat(openTask(PURCHASING, run, "manager")).isNotNull();
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Fulfilled");
    }

    @Test
    void aSentSignalReachesTheWaitingStepsAndHandlersOfItsOwnRequestOnly() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, d -> {
            // procure -> announce (send) -> await (wait for the operator's "goods-received") -> notify
            add(d, "{\"id\":\"announce\",\"type\":\"sendSignal\",\"label\":\"Announce the order\",\"config\":{\"signal\":\"order-placed\"}}");
            add(d, "{\"id\":\"await\",\"type\":\"waitForSignal\",\"label\":\"Wait for the goods\",\"config\":{\"signal\":\"goods-received\"}}");
            add(d, note("placedNote", "Your order was placed"));
            retarget(d, "t9", "announce");
            path(d, "{\"id\":\"s1\",\"from\":\"announce\",\"to\":\"await\",\"on\":\"next\"}");
            path(d, "{\"id\":\"s2\",\"from\":\"await\",\"to\":\"notify\",\"on\":\"next\"}");
            scope(d, "{\"id\":\"onPlaced\",\"label\":\"Order placed\",\"type\":\"event\",\"nodes\":[\"placedNote\"],"
                    + "\"trigger\":{\"type\":\"signal\",\"signal\":\"order-placed\"},\"interrupting\":false}");
            d.remove("uiLayout");
        });
        UUID waiting = startRun(workflow, "10");
        awaitTask(waiting, "manager");
        UUID run = startRun(workflow, "10");
        awaitTask(run, "manager");
        approve("mani.manager", PURCHASING, run, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> run(PURCHASING, run).path("currentSteps").toString()
                .contains("waitForSignal"));
        assertThat(events(run)).containsSubsequence("EFFECT_SUCCEEDED:procure", "SIGNAL_SENT:announce",
                "HANDLER_STARTED:onPlaced", "NOTIFICATION_CREATED:placedNote");
        assertThat(events(run)).containsSubsequence("SIGNAL_SENT:announce", "SIGNAL_WAIT_STARTED:await");
        // The other request, also subscribed to "order-placed" through its handler, did not receive it.
        assertThat(events(waiting)).noneMatch(e -> e.startsWith("HANDLER_STARTED"));

        var sent = api("olga.operator").post(Map.of("signal", "goods-received", "reason", "Goods arrived",
                "expectedVersion", run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/signals", PURCHASING, run);
        assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Fulfilled");
        assertThat(events(run)).contains("SIGNAL_RECEIVED:await");
        assertThat(path(run).path("visited").toString()).contains("\"nodeId\":\"await\"");
    }

    // ---------------------------------------------------------------- automatic undo

    /** procure, reserve, then confirm; when confirm is rejected, undo what completed (most recent first) and finish failed. */
    private Consumer<ObjectNode> undo() {
        return d -> {
            add(d, call("reserve", "Reserve stock", "/input/title", ""));
            add(d, call("confirm", "Confirm the order", "/input/title", ""));
            add(d, call("cancelOrder", "Cancel the order", "/steps/procure/procurementId",
                    ",\"compensates\":\"procure\",\"compensation\":\"automatic\""));
            add(d, call("releaseStock", "Release the stock", "/steps/reserve/procurementId",
                    ",\"compensates\":\"reserve\",\"compensation\":\"automatic\""));
            add(d, "{\"id\":\"undoAll\",\"type\":\"compensate\",\"label\":\"Undo what was done\",\"config\":{}}");
            add(d, "{\"id\":\"notOrdered\",\"type\":\"end\",\"label\":\"Not ordered\",\"config\":{\"businessOutcome\":\"Not ordered\","
                    + "\"outcomeKind\":\"failed\"}}");
            retarget(d, "t9", "reserve");
            path(d, "{\"id\":\"u1\",\"from\":\"reserve\",\"to\":\"confirm\",\"on\":\"next\"}");
            path(d, "{\"id\":\"u2\",\"from\":\"confirm\",\"to\":\"notify\",\"on\":\"next\"}");
            path(d, "{\"id\":\"u3\",\"from\":\"confirm\",\"to\":\"undoAll\",\"on\":\"failed\"}");
            path(d, "{\"id\":\"u4\",\"from\":\"undoAll\",\"to\":\"notOrdered\",\"on\":\"next\"}");
            d.remove("uiLayout");
        };
    }

    private List<String> plan(UUID run) {
        return jdbc.sql("SELECT position || ':' || target_node || ':' || handler_node || ':' || state FROM run_compensation"
                + " WHERE run_id = :run ORDER BY position").param("run", run).query(String.class).list();
    }

    @Test
    void aRejectedCallUndoesTheCompletedCallsMostRecentFirstAndFinishesFailed() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, undo());
        UUID run = startRun(workflow, "10");
        awaitTask(run, "manager");
        Stub.fault(Stub.Mode.NONE, 2, 0);
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "completed", "needsAttention");
        JsonNode done = run(PURCHASING, run);
        assertThat(done.path("lifecycle").asString()).isEqualTo("completed");
        assertThat(done.path("outcome").asString()).isEqualTo("Not ordered");
        assertThat(done.path("outcomeKind").asString()).isEqualTo("failed");
        // procure, reserve, confirm (rejected), then the two undo calls.
        assertThat(Stub.postCount()).isEqualTo(5);
        assertThat(plan(run)).containsExactly("0:reserve:releaseStock:undone", "1:procure:cancelOrder:undone");
        assertThat(events(run)).containsSubsequence("STEP_FAILED:confirm", "UNDO_SUCCEEDED:releaseStock",
                "UNDO_SUCCEEDED:cancelOrder", "COMPENSATION_COMPLETED:undoAll", "RUN_COMPLETED:notOrdered");
        assertThat(path(run).path("visited").toString()).contains("\"nodeId\":\"cancelOrder\"", "\"nodeId\":\"undoAll\"");
    }

    @Test
    void aFailingUndoCallNeedsAttentionAndARetryContinuesThePlanWithoutResendingWhatWasUndone() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, undo());
        UUID run = startRun(workflow, "10");
        awaitTask(run, "manager");
        Stub.fault(Stub.Mode.NONE, 2, 0);
        Stub.fault(Stub.Mode.STATUS400, 1, 0);
        Stub.fault(Stub.Mode.NONE, 1, 0);
        Stub.fault(Stub.Mode.STATUS500, 20, 0);
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "needsAttention", "completed");
        JsonNode stuck = run(PURCHASING, run);
        assertThat(stuck.path("lifecycle").asString()).isEqualTo("needsAttention");
        assertThat(stuck.path("attention").path("nodeId").asString()).isEqualTo("undoAll");
        assertThat(plan(run)).containsExactly("0:reserve:releaseStock:undone", "1:procure:cancelOrder:pending");

        Stub.reset();
        bindStub(PURCHASING);
        var retried = api("olga.operator").post(Map.of("reason", "Procurement is back",
                "expectedVersion", run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/retry", PURCHASING, run);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Not ordered");
        // Only the remaining undo call was sent; the stock release was not sent again.
        assertThat(Stub.postCount()).isEqualTo(1);
        assertThat(plan(run)).containsExactly("0:reserve:releaseStock:undone", "1:procure:cancelOrder:undone");
    }
}
