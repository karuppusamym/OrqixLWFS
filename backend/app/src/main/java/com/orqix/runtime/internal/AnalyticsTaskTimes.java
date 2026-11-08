package com.orqix.runtime.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Waiting versus working time of one human task (CG-N05, docs/build/S145). Waiting is time with nobody assigned,
 * working is time with someone assigned, until the task ended. The claims, returns and reassignments of the task's step
 * while the task was open say when the assignment changed.
 *
 * <p>When another task of the same step was open at the same time (several approvers, list items), the step's events
 * cannot be told apart; the task then falls back to its own last claim time and is marked {@code approximate}, and its
 * reassignments are not counted.
 */
final class AnalyticsTaskTimes {

    record Times(Double waitingSeconds, Double workingSeconds, int claims, int releases, int reassignments,
            boolean approximate) {
    }

    private AnalyticsTaskTimes() {
    }

    /**
     * @param nodeEvents the run's events of the task's step, in timeline order
     * @param overlapping another task of the same step was open at some point while this one was
     */
    static Times compute(Instant created, Instant ended, Instant claimedAt, List<RunEvents.EventRow> nodeEvents,
            boolean overlapping) {
        if (overlapping) {
            return ended == null ? new Times(null, null, 0, 0, 0, true) : fallback(created, ended, claimedAt, true);
        }
        List<RunEvents.EventRow> mine = nodeEvents.stream()
                .filter(e -> isAssignment(e.type()) && !e.occurredAt().isBefore(created)
                        && (ended == null || !e.occurredAt().isAfter(ended)))
                .toList();
        int claims = 0;
        int releases = 0;
        int reassignments = 0;
        for (RunEvents.EventRow e : mine) {
            switch (e.type()) {
                case "TASK_CLAIMED" -> claims++;
                case "TASK_RELEASED" -> releases++;
                default -> reassignments++;
            }
        }
        if (ended == null) {
            return new Times(null, null, claims, releases, reassignments, false);
        }
        if (mine.isEmpty()) {
            Times t = fallback(created, ended, claimedAt, false);
            return t;
        }
        boolean assigned = initiallyAssigned(mine.getFirst());
        Instant mark = created;
        double waiting = 0;
        double working = 0;
        for (RunEvents.EventRow e : mine) {
            double span = seconds(mark, e.occurredAt());
            if (assigned) {
                working += span;
            } else {
                waiting += span;
            }
            mark = e.occurredAt();
            assigned = switch (e.type()) {
                case "TASK_CLAIMED" -> true;
                case "TASK_RELEASED" -> false;
                default -> !e.data().path("to").isNull() && !e.data().path("to").isMissingNode();
            };
        }
        double rest = seconds(mark, ended);
        if (assigned) {
            working += rest;
        } else {
            waiting += rest;
        }
        return new Times(waiting, working, claims, releases, reassignments, false);
    }

    /** A task that was assigned when it was created: its first assignment change takes someone away. */
    private static boolean initiallyAssigned(RunEvents.EventRow first) {
        return switch (first.type()) {
            case "TASK_RELEASED" -> true;
            case "TASK_REASSIGNED" -> !first.data().path("from").isNull() && !first.data().path("from").isMissingNode();
            default -> false;
        };
    }

    private static Times fallback(Instant created, Instant ended, Instant claimedAt, boolean approximate) {
        if (claimedAt == null) {
            return new Times(seconds(created, ended), 0.0, 0, 0, 0, approximate);
        }
        Instant claim = claimedAt.isBefore(created) ? created : claimedAt.isAfter(ended) ? ended : claimedAt;
        return new Times(seconds(created, claim), seconds(claim, ended), 0, 0, 0, approximate);
    }

    static boolean isAssignment(String type) {
        return "TASK_CLAIMED".equals(type) || "TASK_RELEASED".equals(type) || "TASK_REASSIGNED".equals(type);
    }

    static double seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis() / 1000.0);
    }
}
