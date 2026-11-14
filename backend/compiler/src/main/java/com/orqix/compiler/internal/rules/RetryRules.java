package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.Map;

import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.StepRetry;

/**
 * Step-level retry policies (CG-W04, docs/build/S131). The schema bounds the attempts (1-10) and the multiplier
 * (1-4) and the duration format; this rule bounds the durations: {@code delay} from 5 seconds to 1 hour and
 * {@code maxDelay} from {@code delay} to 6 hours.
 */
final class RetryRules {

    private RetryRules() {
    }

    static void check(RuleContext rc) {
        for (Map.Entry<String, StepRetry> e : rc.definition().execution().stepRetry().entrySet()) {
            Node n = rc.graph().node(e.getKey());
            if (n == null || rc.graph().isShadowed(n)) {
                continue;
            }
            StepRetry r = e.getValue();
            long delay = r.delaySeconds();
            if (delay < 0) {
                rc.issues().blocker("WF_RETRY_DELAY_INVALID", r.path() + "/delay", n.id(), null, q(n)
                        + " has a retry delay that is not valid (" + r.delay() + "). Use hours, minutes and seconds,"
                        + " for example PT10S, PT5M or PT1H.");
                continue;
            }
            if (delay < StepRetry.MIN_DELAY_SECONDS) {
                rc.issues().blocker("WF_RETRY_DELAY_TOO_SHORT", r.path() + "/delay", n.id(), null, q(n)
                        + " retries after " + r.delay() + ". The shortest delay between attempts is 5 seconds (PT5S).");
            } else if (delay > StepRetry.MAX_DELAY_SECONDS) {
                rc.issues().blocker("WF_RETRY_DELAY_TOO_LONG", r.path() + "/delay", n.id(), null, q(n)
                        + " retries after " + r.delay() + ". The longest first delay is 1 hour (PT1H); use the"
                        + " multiplier and the longest delay to wait longer between later attempts.");
            }
            if (r.maxDelay() == null) {
                continue;
            }
            long max = StepRetry.seconds(r.maxDelay());
            if (max < 0) {
                rc.issues().blocker("WF_RETRY_DELAY_INVALID", r.path() + "/maxDelay", n.id(), null, q(n)
                        + " has a longest retry delay that is not valid (" + r.maxDelay() + "). Use hours, minutes"
                        + " and seconds, for example PT30M or PT6H.");
            } else if (max > StepRetry.MAX_MAX_DELAY_SECONDS) {
                rc.issues().blocker("WF_RETRY_MAX_DELAY_TOO_LONG", r.path() + "/maxDelay", n.id(), null, q(n)
                        + " may wait " + r.maxDelay() + " between attempts. The longest delay is 6 hours (PT6H).");
            } else if (max < delay) {
                rc.issues().blocker("WF_RETRY_MAX_DELAY_BELOW_DELAY", r.path() + "/maxDelay", n.id(), null, q(n)
                        + " has a longest retry delay (" + r.maxDelay() + ") shorter than its first delay ("
                        + r.delay() + "). Make the longest delay at least the first delay.");
            }
        }
    }
}
