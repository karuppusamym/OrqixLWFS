package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.internal.RunEvents.EventRow;
import com.orqix.runtime.internal.RunPathBuilder;
import com.orqix.support.Fixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The "Where is my request" path derived from events and the release graph (no database). */
class RunPathBuilderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");
    private static final UUID RUN = UUID.randomUUID();

    private static EventRow ev(long seq, String type, String node, int minutes, Map<String, Object> data) {
        return new EventRow(seq, type, node, null, T0.plusSeconds(minutes * 60L), JSON.valueToTree(data));
    }

    private static RuntimeDtos.CurrentStep step(String node, String label, String kind, int minutes) {
        return new RuntimeDtos.CurrentStep(node, label, kind, T0.plusSeconds(minutes * 60L), null, null, null);
    }

    private static List<String> visitedIds(RunPathBuilder.Result r) {
        return r.visited().stream().map(RunPathBuilder.Visit::nodeId).toList();
    }

    @Test
    void handledFailureMarksOnlyTheFailedRouteEvenWhenSuccessHasTheSameTarget() {
        JsonNode definition = JSON.readTree("""
            {"nodes":[{"id":"start","type":"start"},{"id":"call","type":"http"},{"id":"end","type":"end"}],
             "transitions":[{"id":"begin","from":"start","to":"call","on":"next"},
                {"id":"success","from":"call","to":"end","on":"next"},
                {"id":"failure","from":"call","to":"end","on":"failed"}]}
            """);
        var result = RunPathBuilder.build(RUN,"completed",definition,null,List.of(
                ev(1,"RUN_STARTED",null,0,Map.of()), ev(2,"STEP_FAILED","call",1,Map.of("outcome","failed")),
                ev(3,"RUN_COMPLETED","end",2,Map.of())),List.of(),null);
        assertThat(result.taken()).containsExactly("begin","failure");
        assertThat(result.visited()).filteredOn(v -> "call".equals(v.nodeId()))
                .extracting(RunPathBuilder.Visit::outcome).containsExactly("failed");
    }

    @Test
    void waitingAtTheFirstApprovalShowsStartPassedAndTheApprovalCurrent() {
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "waiting", Fixtures.purchaseTemplate(), null,
                List.of(ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "manager", 0, Map.of())),
                List.of(step("manager", "Review request", "approval", 0)), null);
        assertThat(visitedIds(r)).containsExactly("start", "manager");
        assertThat(r.visited().get(1).outcome()).isNull();
        assertThat(r.current()).hasSize(1);
        assertThat(r.current().get(0).status()).isEqualTo("waiting");
        assertThat(r.taken()).containsExactly("t1");
        assertThat(r.diagram().nodes()).hasSize(10);
        assertThat(r.diagram().transitions()).extracting(RunPathBuilder.Transition::id).contains("t5", "t6");
    }

    @Test
    void aFulfilledRunInfersTheChoiceAndTakesTheDefaultRoute() {
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "completed", Fixtures.purchaseTemplate(), null, List.of(
                ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "manager", 0, Map.of()),
                ev(3, "TASK_DECIDED", "manager", 5, Map.of("action", "approve")),
                ev(4, "DECISION_EVALUATED", "amountPolicy", 5, Map.of()), ev(5, "EFFECT_SUCCEEDED", "procure", 6, Map.of()),
                ev(6, "NOTIFICATION_CREATED", "notify", 6, Map.of()), ev(7, "RUN_COMPLETED", "fulfilled", 6, Map.of())),
                List.of(), null);
        assertThat(visitedIds(r)).containsExactlyInAnyOrder("start", "manager", "amountPolicy", "route", "procure", "notify",
                "fulfilled");
        assertThat(r.visited()).filteredOn(v -> v.nodeId().equals("manager")).singleElement()
                .satisfies(v -> assertThat(v.outcome()).isEqualTo("approved"));
        assertThat(r.visited()).filteredOn(v -> v.nodeId().equals("route")).singleElement()
                .satisfies(v -> assertThat(v.outcome()).isEqualTo("completed"));
        assertThat(r.taken()).containsExactlyInAnyOrder("t1", "t2", "t4", "t6", "t9", "t10");
        assertThat(r.current()).isEmpty();
    }

    @Test
    void aRejectionTakesTheRejectedRouteOnly() {
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "completed", Fixtures.purchaseTemplate(), null, List.of(
                ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "manager", 0, Map.of()),
                ev(3, "TASK_DECIDED", "manager", 5, Map.of("action", "reject")),
                ev(4, "NOTIFICATION_CREATED", "notifyRejected", 5, Map.of()), ev(5, "RUN_COMPLETED", "rejected", 5, Map.of())),
                List.of(), null);
        assertThat(r.taken()).containsExactlyInAnyOrder("t1", "t3", "t11");
        assertThat(visitedIds(r)).doesNotContain("amountPolicy", "route", "finance", "procure");
    }

    @Test
    void aFailedSystemCallIsCurrentAndNeedsAttention() {
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "needsAttention", Fixtures.purchaseTemplate(), null, List.of(
                ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "manager", 0, Map.of()),
                ev(3, "TASK_DECIDED", "manager", 5, Map.of("action", "approve")),
                ev(4, "DECISION_EVALUATED", "amountPolicy", 5, Map.of()), ev(5, "EFFECT_FAILED", "procure", 6, Map.of())),
                List.of(step("procure", "Create purchase order", "http", 5)), "procure");
        assertThat(r.current()).singleElement().satisfies(c -> {
            assertThat(c.nodeId()).isEqualTo("procure");
            assertThat(c.status()).isEqualTo("needsAttention");
        });
        assertThat(visitedIds(r)).contains("route").doesNotContain("procure");
        assertThat(r.taken()).contains("t6");
    }

    @Test
    void helperElementsMapToTheirCanonicalStep() {
        JsonNode map = JSON.valueToTree(Map.of("bpmnToCanonical", Map.of("manager_task", "manager")));
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "waiting", Fixtures.purchaseTemplate(), map,
                List.of(ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "manager_task", 0, Map.of())),
                List.of(step("manager_task", "Review request", "approval", 0)), null);
        assertThat(visitedIds(r)).containsExactly("start", "manager");
        assertThat(r.current().get(0).nodeId()).isEqualTo("manager");
    }

    @Test
    void parallelBranchesShowTheSplitPassedAndTheJoinNotYetReached() {
        JsonNode definition = JSON.valueToTree(Map.of("nodes", List.of(
                node("start", "start"), node("split", "parallelSplit"), node("a", "approval"), node("b", "approval"),
                node("join", "parallelJoin"), node("end", "end")),
                "transitions", List.of(t("t1", "start", "split", "next"), t("t2", "split", "a", "next"),
                        t("t3", "split", "b", "next"), t("t4", "a", "join", "approved"), t("t5", "b", "join", "approved"),
                        t("t6", "join", "end", "next"))));
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "waiting", definition, null, List.of(
                ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "a", 0, Map.of()),
                ev(3, "TASK_CREATED", "b", 0, Map.of()), ev(4, "TASK_DECIDED", "a", 4, Map.of("action", "approve"))),
                List.of(step("b", "b", "approval", 0)), null);
        assertThat(visitedIds(r)).contains("split").doesNotContain("join", "end");
        assertThat(r.taken()).containsExactlyInAnyOrder("t1", "t2", "t3");
    }

    @Test
    void aCancelledRunClosesTheOpenStepAsCancelled() {
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "cancelled", Fixtures.purchaseTemplate(), null, List.of(
                ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "manager", 0, Map.of()),
                ev(3, "RUN_CANCELLED", null, 3, Map.of("reason", "x"))), List.of(), null);
        assertThat(r.visited()).filteredOn(v -> v.nodeId().equals("manager")).singleElement()
                .satisfies(v -> {
                    assertThat(v.outcome()).isEqualTo("cancelled");
                    assertThat(v.leftAt()).isEqualTo(T0.plusSeconds(180));
                });
    }

    @Test
    void aRepeatedStepCountsOccurrences() {
        RunPathBuilder.Result r = RunPathBuilder.build(RUN, "waiting", Fixtures.purchaseTemplate(), null, List.of(
                ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "TASK_CREATED", "manager", 0, Map.of()),
                ev(3, "TASK_DECIDED", "manager", 1, Map.of("action", "approve")),
                ev(4, "TASK_CREATED", "manager", 2, Map.of())), List.of(step("manager", "Review request", "approval", 2)), null);
        assertThat(r.visited()).filteredOn(v -> v.nodeId().equals("manager")).extracting(RunPathBuilder.Visit::occurrence)
                .containsExactly(1, 2);
    }

    @Test
    void messageTimeoutIsShownAsTimedOutAndUsesOnlyItsTimeoutRoute() {
        JsonNode definition = JSON.valueToTree(Map.of("nodes", List.of(node("start", "start"),
                node("payment", "wait"), node("paid", "end"), node("expired", "end")),
                "transitions", List.of(t("begin", "start", "payment", "next"),
                        t("received", "payment", "expired", "next"), t("timeout", "payment", "expired", "timedOut"))));
        var path = RunPathBuilder.build(RUN, "completed", definition, null,
                List.of(ev(1, "RUN_STARTED", null, 0, Map.of()), ev(2, "WAIT_STARTED", "payment", 0, Map.of()),
                        ev(3, "WAIT_ENDED", "payment", 5, Map.of("outcome", "timedOut")),
                        ev(4, "RUN_COMPLETED", "expired", 5, Map.of())), List.of(), null);
        assertThat(path.visited()).filteredOn(v -> v.nodeId().equals("payment")).singleElement()
                .satisfies(v -> assertThat(v.outcome()).isEqualTo("timedOut"));
        assertThat(path.taken()).containsExactly("begin", "timeout");
    }

    private static Map<String, Object> node(String id, String type) {
        return Map.of("id", id, "type", type, "label", id, "config", Map.of());
    }

    private static Map<String, Object> t(String id, String from, String to, String on) {
        return Map.of("id", id, "from", from, "to", to, "on", on);
    }
}
