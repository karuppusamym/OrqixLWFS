package com.orqix.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** Waiting versus working time of a task (CG-N05, docs/build/S145), from the assignment events of its step. */
class AnalyticsTaskTimesTest {

    private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static RunEvents.EventRow event(long seq, String type, int minute, Map<String, ?> data) {
        return new RunEvents.EventRow(seq, type, "manager", null, T0.plusSeconds(minute * 60L), JSON.valueToTree(data));
    }

    @Test
    void claimsReturnsAndReassignmentsSplitTheTime() {
        java.util.Map<String, Object> toGroup = new java.util.HashMap<>();
        toGroup.put("from", "Mani");
        toGroup.put("to", null);
        List<RunEvents.EventRow> events = List.of(
                event(1, "TASK_CREATED", 0, Map.of()),
                event(2, "TASK_CLAIMED", 10, Map.of()),   // waited 10
                event(3, "TASK_RELEASED", 15, Map.of()),  // worked 5
                event(4, "TASK_CLAIMED", 30, Map.of()),   // waited 15
                event(5, "TASK_REASSIGNED", 40, toGroup), // worked 10, back to the group
                event(6, "TASK_CLAIMED", 45, Map.of()),   // waited 5
                event(7, "TASK_DECIDED", 60, Map.of()));  // worked 15
        AnalyticsTaskTimes.Times t = AnalyticsTaskTimes.compute(T0, T0.plusSeconds(3600), T0.plusSeconds(2700), events,
                false);
        assertThat(t.waitingSeconds()).isEqualTo(30 * 60.0);
        assertThat(t.workingSeconds()).isEqualTo(30 * 60.0);
        assertThat(t.claims()).isEqualTo(3);
        assertThat(t.releases()).isEqualTo(1);
        assertThat(t.reassignments()).isEqualTo(1);
        assertThat(t.approximate()).isFalse();
    }

    @Test
    void aTaskAssignedFromTheStartWorksUntilItIsReassignedAway() {
        List<RunEvents.EventRow> events = List.of(event(1, "TASK_REASSIGNED", 20, Map.of("from", "Mani", "to", "Max")));
        AnalyticsTaskTimes.Times t = AnalyticsTaskTimes.compute(T0, T0.plusSeconds(1800), T0.plusSeconds(1200), events,
                false);
        assertThat(t.waitingSeconds()).isZero();
        assertThat(t.workingSeconds()).isEqualTo(1800.0);
    }

    @Test
    void withoutEventsOrWithOverlappingTasksTheLastClaimDecides() {
        AnalyticsTaskTimes.Times none = AnalyticsTaskTimes.compute(T0, T0.plusSeconds(600), null, List.of(), false);
        assertThat(none.waitingSeconds()).isEqualTo(600.0);
        assertThat(none.workingSeconds()).isZero();
        AnalyticsTaskTimes.Times shared = AnalyticsTaskTimes.compute(T0, T0.plusSeconds(600), T0.plusSeconds(200),
                List.of(event(1, "TASK_CLAIMED", 1, Map.of())), true);
        assertThat(shared.waitingSeconds()).isEqualTo(200.0);
        assertThat(shared.workingSeconds()).isEqualTo(400.0);
        assertThat(shared.approximate()).isTrue();
        assertThat(shared.reassignments()).isZero();
        AnalyticsTaskTimes.Times open = AnalyticsTaskTimes.compute(T0, null, null, List.of(), false);
        assertThat(open.waitingSeconds()).isNull();
    }
}
