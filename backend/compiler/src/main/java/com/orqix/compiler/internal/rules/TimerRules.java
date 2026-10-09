package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import com.orqix.compiler.internal.model.Durations;
import com.orqix.compiler.internal.model.Node;

/** Wait steps and approval time limits (S12 section 3): duration format and the PT1M to P90D range. */
final class TimerRules {

    private TimerRules() {
    }

    static void check(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (rc.graph().isShadowed(n)) {
                continue;
            }
            if (n instanceof Node.Wait w) {
                duration(rc, n, "duration", w.duration(), "waits");
            } else if (n instanceof Node.Approval a && a.timeoutAfter() != null) {
                duration(rc, n, "timeoutAfter", a.timeoutAfter(), "allows");
            }
        }
    }

    private static void duration(RuleContext rc, Node n, String member, String text, String verb) {
        long minutes = Durations.minutes(text);
        String path = n.configPath(member);
        if (minutes < 0) {
            rc.issues().blocker("WF_DURATION_INVALID", path, n.id(), null, q(n) + " has a time that is not valid ("
                    + text + "). Use days, hours and minutes, for example PT30M, PT48H or P2D.");
        } else if (minutes < Durations.MIN_MINUTES) {
            rc.issues().blocker("WF_DURATION_TOO_SHORT", path, n.id(), null, q(n) + " " + verb
                    + " no time at all. The shortest time is 1 minute (PT1M).");
        } else if (minutes > Durations.MAX_MINUTES) {
            rc.issues().blocker("WF_DURATION_TOO_LONG", path, n.id(), null, q(n) + " " + verb + " "
                    + Durations.describe(minutes) + ". The longest time is 90 days (P90D).");
        }
    }
}
