package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.PollSchedule;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.Disjointness.Typed;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

import tools.jackson.databind.JsonNode;

/**
 * "Keep checking until..." on a "Call a system" step and the safety-net check of a "Wait for a message" step
 * (docs/build/S129, CG-W01 and CG-W03). A check may run many times, so the operation must be read-only; the timing is
 * bounded so the worst-case number of checks is known before publishing; the done and failed conditions must not
 * overlap; a timedOut path is required, and a failed path when there is a failed condition.
 */
final class PollRules {

    private PollRules() {
    }

    static void check(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (rc.graph().isShadowed(n)) {
                continue;
            }
            if (n instanceof Node.Http h && h.polls()) {
                poll(rc, h);
            } else if (n instanceof Node.WaitForMessage m && m.fallbackCheck() != null) {
                fallback(rc, m, m.fallbackCheck(), m.payload(), m.timeoutAfter(), m.configPath("fallbackCheck"),
                        "The safety-net check of " + q(m));
            } else if (n instanceof Node.WaitForFirst w) {
                // S139: a check branch is checked like a safety net; its mapped results fill the branch's values.
                for (Node.FirstBranch b : w.branches()) {
                    if ("check".equals(b.kind()) && b.check() != null) {
                        fallback(rc, w, b.check(), b.payload(), null, b.path(w) + "/check",
                                "The check of the branch " + q(b.label()) + " of " + q(w));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ CG-W01

    private static void poll(RuleContext rc, Node.Http h) {
        Node.Poll p = h.poll();
        String base = h.configPath("poll");
        if (h.compensates() != null || h.raiseOnFailure() != null) {
            rc.issues().blocker("WF_POLL_COMBINATION", base, h.id(), null, q(h) + " keeps checking until done, so it"
                    + " cannot also be an undo call or raise an error when it fails. Use its failed and timed-out"
                    + " paths instead.");
        }
        readOnly(rc, h, h.connectionRef(), h.operation(), base);
        long every = PollSchedule.seconds(p.every());
        long cap = PollSchedule.seconds(p.maxInterval());
        long deadline = PollSchedule.seconds(p.giveUpAfter());
        boolean timing = true;
        if (every < PollSchedule.MIN_EVERY || every > PollSchedule.MAX_INTERVAL) {
            rc.issues().blocker("WF_POLL_INTERVAL", base + "/every", h.id(), null, q(h) + " checks every "
                    + PollSchedule.describe(Math.max(0, every)) + ". Choose from 10 seconds to 1 day.");
            timing = false;
        }
        if (cap < every || cap > PollSchedule.MAX_INTERVAL) {
            rc.issues().blocker("WF_POLL_BACKOFF", base + "/backoff/maxInterval", h.id(), null, "The longest"
                    + " interval between checks must be at least the first interval (" + PollSchedule.describe(Math.max(0, every))
                    + ") and at most 1 day.");
            timing = false;
        }
        if (deadline < PollSchedule.MIN_DEADLINE || deadline > PollSchedule.MAX_DEADLINE || deadline < every) {
            rc.issues().blocker("WF_POLL_DEADLINE", base + "/giveUpAfter", h.id(), null, q(h) + " gives up after "
                    + PollSchedule.describe(Math.max(0, deadline)) + ". Choose from 1 minute to 90 days, and at least"
                    + " the interval between checks.");
            timing = false;
        }
        if (timing && p.maxChecks() == null) {
            int worst = PollSchedule.worstCase(every, p.multiplier(), cap, deadline, p.firstCheckImmediate(),
                    p.finalCheckAtDeadline());
            if (worst > PollSchedule.CHECK_LIMIT) {
                rc.issues().blocker("WF_POLL_TOO_MANY_CHECKS", base, h.id(), null, "With these settings " + q(h)
                        + " could check more than " + PollSchedule.CHECK_LIMIT + " times. Check less often, give up"
                        + " sooner, or set the most checks to make.");
            }
        }
        routes(rc, h, p);
        List<Typed> until = condition(rc, h, p.until(), base + "/until", h.resultMapping());
        if (p.failWhen() != null) {
            List<Typed> failWhen = condition(rc, h, p.failWhen(), base + "/failWhen", h.resultMapping());
            if (until != null && failWhen != null && LogicalConditions.overlap(p.until(), until, p.failWhen(), failWhen)) {
                rc.issues().blocker("WF_POLL_OVERLAP", base + "/failWhen", h.id(), null, "The answer can be both done"
                        + " and failed at the same time. Change the conditions so that they never both hold.");
            }
        }
        for (int i = 0; i < p.optionalResults().size(); i++) {
            String key = p.optionalResults().get(i);
            if (!h.resultMapping().containsKey(key)) {
                rc.issues().blocker("WF_POLL_OPTIONAL", base + "/optionalResults/" + i, h.id(), null, q(key)
                        + " is not a result of " + q(h) + ". Only kept results can be marked as possibly missing.");
            }
        }
        for (int i = 0; i < p.showWhileWaiting().size(); i++) {
            String key = p.showWhileWaiting().get(i);
            if (!h.resultMapping().containsKey(key)) {
                rc.issues().blocker("WF_POLL_SHOW", base + "/showWhileWaiting/" + i, h.id(), null, q(key)
                        + " is not a result of " + q(h) + ". Show only values it returns: "
                        + String.join(", ", h.resultMapping().keySet()) + ".");
            }
        }
    }

    private static void routes(RuleContext rc, Node.Http h, Node.Poll p) {
        int timedOut = 0;
        int failed = 0;
        for (Transition t : rc.leaving(h)) {
            if (t.on() == Route.TIMED_OUT) {
                timedOut++;
            } else if (t.on() == Route.FAILED) {
                failed++;
            }
        }
        if (timedOut != 1) {
            rc.issues().blocker("WF_POLL_ROUTES", h.path(), h.id(), null, q(h) + (timedOut == 0
                    ? " keeps checking until done, so it needs a path for when it gives up."
                    : " can have only one path for when it gives up."));
        }
        if (p.failWhen() != null && failed == 0) {
            rc.issues().blocker("WF_POLL_ROUTES", h.path(), h.id(), null, q(h) + " has a condition for when the work"
                    + " failed, so it needs a path for when it fails.");
        }
    }

    // ------------------------------------------------------------------ CG-W03

    private static void fallback(RuleContext rc, Node m, Node.FallbackCheck f, List<Node.PayloadField> payload,
            String timeoutAfter, String base, String what) {
        java.util.function.Function<String, Node.PayloadField> fieldOf = name -> payload.stream()
                .filter(p -> p.name().equals(name)).findFirst().orElse(null);
        long every = PollSchedule.seconds(f.every());
        if (every < PollSchedule.MIN_FALLBACK_EVERY || every > PollSchedule.MAX_INTERVAL) {
            rc.issues().blocker("WF_POLL_INTERVAL", base + "/every", m.id(), null, what
                    + " runs every " + PollSchedule.describe(Math.max(0, every)) + ". Choose from 1 minute to 1 day.");
        } else if (f.maxChecks() == null) {
            long deadline = timeoutAfter == null ? PollSchedule.MAX_DEADLINE
                    : com.orqix.compiler.internal.model.Durations.minutes(timeoutAfter) * 60;
            if (PollSchedule.worstCase(every, java.math.BigDecimal.ONE, every, deadline, false, false)
                    > PollSchedule.CHECK_LIMIT) {
                rc.issues().blocker("WF_POLL_TOO_MANY_CHECKS", base + "/every", m.id(), null, what
                        + " could run more than " + PollSchedule.CHECK_LIMIT + " times. Check less"
                        + " often, set a time limit on the step, or set the most checks to make.");
            }
        }
        // Inputs and the connection, as for a call to a system.
        for (Map.Entry<String, String> e : f.inputMapping().entrySet()) {
            String path = JsonPointers.append(base + "/inputMapping", e.getKey());
            if (rc.types().resolve(e.getValue()) instanceof Unknown u) {
                rc.issues().blocker("WF_FIELDREF_UNKNOWN", path, m.id(), null, u.message());
            } else {
                Conditions.availability(rc, e.getValue(), m, path, null);
            }
        }
        JsonNode operation = readOnly(rc, m, f.connectionRef(), f.operation(), base);
        for (Map.Entry<String, String> e : f.resultMapping().entrySet()) {
            String path = JsonPointers.append(base + "/resultMapping", e.getKey());
            Node.PayloadField field = fieldOf.apply(e.getKey());
            if (field == null) {
                rc.issues().blocker("WF_POLL_FALLBACK_MAPPING", path, m.id(), null, q(e.getKey()) + " is not a value"
                        + " the message carries. Map only declared message values.");
                continue;
            }
            if (operation != null) {
                Connections.ResponseScalar scalar = Connections.responseScalar(operation, e.getValue());
                if (scalar == null) {
                    rc.issues().blocker("WF_RESULT_POINTER_UNKNOWN", path, m.id(), null, "The response of "
                            + q(f.operation()) + " has no single value at " + e.getValue() + ".");
                } else if (scalar.kind() != field.type() && !(scalar.kind() == com.orqix.compiler.internal.model.Enums
                        .FieldKind.INTEGER && field.type() == com.orqix.compiler.internal.model.Enums.FieldKind.NUMBER)) {
                    rc.issues().blocker("WF_POLL_FALLBACK_MAPPING", path, m.id(), null, "The message value "
                            + q(e.getKey()) + " is " + field.type().description() + ", but the answer at "
                            + e.getValue() + " is " + scalar.kind().description() + ".");
                }
            }
        }
        for (Node.PayloadField field : payload) {
            if (field.required() && !f.resultMapping().containsKey(field.name())) {
                rc.issues().blocker("WF_POLL_FALLBACK_MAPPING", base + "/resultMapping", m.id(), null, "The message"
                        + " value " + q(field.name()) + " is required, so the safety-net check must provide it.");
            }
        }
        if (operation != null) {
            Map<String, Connections.RequestField> request = Connections.requestFields(operation);
            for (String name : f.inputMapping().keySet()) {
                if (!request.containsKey(name)) {
                    rc.issues().blocker("WF_MAPPING_UNKNOWN_TARGET", JsonPointers.append(base + "/inputMapping", name),
                            m.id(), null, "The operation " + q(f.operation()) + " has no request field " + q(name) + ".");
                }
            }
            for (String required : Connections.requiredRequestFields(operation)) {
                if (!f.inputMapping().containsKey(required)) {
                    rc.issues().blocker("WF_MAPPING_MISSING_REQUIRED", base + "/inputMapping", m.id(), null,
                            "The operation " + q(f.operation()) + " requires " + q(required) + ".");
                }
            }
        }
        condition(rc, m, f.until(), base + "/until", f.resultMapping());
    }

    // ------------------------------------------------------------------ shared

    /** @return the operation when it resolves (also when it is not read-only, which is reported) */
    private static JsonNode readOnly(RuleContext rc, Node n, String connection, String operationName, String base) {
        Connections.Pinned pinned = rc.connections().pinned(connection);
        if (pinned == null) {
            if (!(n instanceof Node.Http)) {
                rc.issues().blocker("WF_CONNECTION_UNDECLARED", base + "/connectionRef", n.id(), null, q(n)
                        + " checks the connection " + q(connection) + ", which is not listed in the workflow's"
                        + " dependencies.");
            }
            return null;
        }
        JsonNode operation = rc.connections().operation(connection, operationName);
        if (operation == null) {
            if (!(n instanceof Node.Http) && pinned.status() == Connections.Status.OK) {
                rc.issues().blocker("WF_OPERATION_UNKNOWN", base + "/operation", n.id(), null, "The connection "
                        + q(connection) + " has no operation " + q(operationName) + ".");
            }
            return null;
        }
        if (!"readOnly".equals(operation.path("effectClass").asString(""))) {
            rc.issues().blocker("WF_POLL_READONLY", n instanceof Node.Http ? n.configPath("operation")
                    : base + "/operation", n.id(), null, "A check may run many times, so " + q(operationName)
                    + " must only read. It is marked as changing something in the other system.");
        }
        return operation;
    }

    /**
     * Checks the terms of a done or failed condition. Terms may use the step's own mapped results (evaluated on each
     * answer) and values guaranteed before the step.
     *
     * @return the typed terms, or null when a term cannot be typed
     */
    private static List<Typed> condition(RuleContext rc, Node n, Condition c, String base, Map<String, String> mapped) {
        List<Condition> terms = c.compound() ? c.terms() : List.of(c);
        List<Typed> typed = new ArrayList<>();
        boolean valid = true;
        String own = "/steps/" + n.id() + "/";
        for (int i = 0; i < terms.size(); i++) {
            Condition term = terms.get(i);
            String path = c.compound() ? base + "/" + c.group() + "/" + i : base;
            if (term.fieldRef().startsWith(own)) {
                String key = term.fieldRef().substring(own.length());
                if (!mapped.containsKey(key)) {
                    rc.issues().blocker("WF_POLL_CONDITION", path + "/fieldRef", n.id(), null, q(key) + " is not a"
                            + " value that the check returns. Use one of: " + String.join(", ", mapped.keySet()) + ".");
                    valid = false;
                    continue;
                }
            } else if (rc.types().resolve(term.fieldRef()) instanceof Resolved) {
                Conditions.availability(rc, term.fieldRef(), n, path + "/fieldRef", null);
            }
            Typed value = Conditions.check(rc, term, path, n.id(), null);
            if (value == null) {
                valid = false;
            } else {
                typed.add(value);
            }
        }
        return valid ? List.copyOf(typed) : null;
    }
}
