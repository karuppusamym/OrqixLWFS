package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import com.orqix.compiler.internal.model.Durations;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/**
 * Wait steps and approval time limits (S12 sections 3 and 10): duration format and the PT1M to P90D range, and the
 * "wait until a date" form: exactly one of duration and date field, the field must be a date that is always present.
 */
final class TimerRules {

    private TimerRules() {
    }

    static void check(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (rc.graph().isShadowed(n)) {
                continue;
            }
            if (n instanceof Node.Wait w) {
                wait(rc, w);
            } else if (n instanceof Node.Approval a && a.timeoutAfter() != null) {
                duration(rc, n, "timeoutAfter", a.timeoutAfter(), "allows");
            } else if (n instanceof Node.WaitForMessage m && m.timeoutAfter() != null) {
                duration(rc, n, "timeoutAfter", m.timeoutAfter(), "waits");
            }
        }
    }

    private static void wait(RuleContext rc, Node.Wait w) {
        boolean hasDuration = w.duration() != null;
        boolean hasDate = w.untilField() != null;
        if (hasDuration == hasDate) {
            rc.issues().blocker("WF_WAIT_CHOICE", w.configPath(hasDuration ? "untilField" : "duration"), w.id(), null,
                    q(w) + (hasDuration ? " has both a length of time and a date. Choose one."
                            : " needs either a length of time or a date to wait until."));
            return;
        }
        if (hasDuration) {
            duration(rc, w, "duration", w.duration(), "waits");
            return;
        }
        String path = w.configPath("untilField");
        Resolution resolution = rc.types().resolve(w.untilField());
        if (resolution instanceof Unknown u) {
            rc.issues().blocker("WF_FIELDREF_UNKNOWN", path, w.id(), null, u.message());
            return;
        }
        if (!(resolution instanceof Resolved r)) {
            return;
        }
        if (r.type().kind() != FieldKind.DATE) {
            rc.issues().blocker("WF_WAIT_FIELD_TYPE", path, w.id(), null, q(w) + " waits until the date in "
                    + w.untilField() + ", but that is " + r.type().kind().description() + ", not a date. Choose a"
                    + " date field.");
            return;
        }
        Conditions.availability(rc, w.untilField(), w, path, null);
        if (r.producer() == null && !rc.definition().inputSchema().required().contains(r.key())) {
            rc.issues().blocker("WF_WAIT_FIELD_OPTIONAL", path, w.id(), null, q(w) + " waits until the date in "
                    + q(rc.definition().inputSchema().properties().get(r.key()).title()) + ", but that field is"
                    + " optional on the request form, so there may be no date to wait for. Make the field required.");
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
