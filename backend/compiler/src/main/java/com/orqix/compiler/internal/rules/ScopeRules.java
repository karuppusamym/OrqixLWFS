package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.orqix.compiler.internal.model.Durations;
import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Subprocess;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * Subprocesses, business errors, signals and automatic undo (docs/build/S55).
 * <ul>
 * <li>An embedded subprocess (a "section") groups steps that are entered through exactly one entry step and left by
 * the ordinary paths of its members; it may have a time limit and an error route to a step outside it. Sections do not
 * nest and hold only steps whose open work the runtime can cancel cleanly.</li>
 * <li>An event subprocess (a "handler") is started by an error, a signal of this request or a timer from the start of
 * the request. An interrupting handler stops the rest of the request and ends at a Finish step; one that runs alongside
 * ends where its last step has no path.</li>
 * <li>Every raised error must be caught by its section or by an error handler; error codes starting with ORQIX_ are
 * reserved for the platform.</li>
 * <li>An automatic undo call names the call it undoes, has no paths of its own and runs only from an "Undo completed
 * calls" step.</li>
 * </ul>
 */
final class ScopeRules {

    static final String RESERVED_ERROR_PREFIX = "ORQIX_";

    private static final Set<NodeKind> SECTION_KINDS = Set.of(NodeKind.APPROVAL, NodeKind.DECISION, NodeKind.BRANCH,
            NodeKind.HTTP, NodeKind.GENERATE_DOCUMENT, NodeKind.NOTIFICATION, NodeKind.WAIT, NodeKind.WAIT_FOR_SIGNAL,
            NodeKind.SEND_SIGNAL, NodeKind.RAISE_ERROR, NodeKind.COMPENSATE);
    private static final Set<NodeKind> HANDLER_KINDS = Set.of(NodeKind.APPROVAL, NodeKind.DECISION, NodeKind.BRANCH,
            NodeKind.HTTP, NodeKind.GENERATE_DOCUMENT, NodeKind.NOTIFICATION, NodeKind.WAIT, NodeKind.SEND_SIGNAL,
            NodeKind.COMPENSATE, NodeKind.END);
    /** Work an interrupting handler could not cancel cleanly (child requests, worker jobs, message waits). */
    private static final Set<NodeKind> NOT_INTERRUPTIBLE = Set.of(NodeKind.START_WORKFLOW, NodeKind.EXTERNAL_TASK,
            NodeKind.WAIT_FOR_MESSAGE);

    private ScopeRules() {
    }

    static void check(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        if (!d.subprocesses().isEmpty()) {
            structure(rc);
        }
        errors(rc);
        callFailures(rc);
        signals(rc);
        undo(rc);
    }

    // ---------------------------------------------------------------- subprocess structure

    private static void structure(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        Graph g = rc.graph();
        Set<String> taken = new HashSet<>();
        d.nodes().forEach(n -> taken.add(n.id()));
        d.transitions().forEach(t -> taken.add(t.id()));
        taken.add(d.workflowKey());
        Set<String> scopeIds = new HashSet<>();
        Map<String, Subprocess> owner = new HashMap<>();
        boolean parallel = d.nodes().stream().anyMatch(n -> n.kind() == NodeKind.PARALLEL_SPLIT);
        for (Subprocess s : d.subprocesses()) {
            if (taken.contains(s.id()) || !scopeIds.add(s.id())) {
                rc.issues().blocker("WF_SUBPROCESS_DUPLICATE_ID", s.path() + "/id", null, null, "The ID "
                        + q(s.id()) + " of the subprocess " + q(s.label()) + " is already used by a step, a path,"
                        + " the workflow or another subprocess. Give it its own ID.");
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < s.nodes().size(); i++) {
                String id = s.nodes().get(i);
                String path = s.path() + "/nodes/" + i;
                Node n = g.node(id);
                if (n == null) {
                    rc.issues().blocker("WF_SUBPROCESS_NODE_UNKNOWN", path, null, null, "The subprocess "
                            + q(s.label()) + " lists " + q(id) + ", which is not a step.");
                    continue;
                }
                Subprocess other = owner.putIfAbsent(id, s);
                if (!seen.add(id) || other != null) {
                    rc.issues().blocker("WF_SUBPROCESS_NODE_DUPLICATE", path, id, null, q(n) + " is listed "
                            + (other != null && other != s ? "in the subprocess " + q(other.label()) + " and " : "twice ")
                            + "in the subprocess " + q(s.label()) + ". A step can belong to one subprocess only, and"
                            + " subprocesses do not nest.");
                    continue;
                }
                kind(rc, s, n, path);
            }
            settings(rc, s);
            if (s.embedded()) {
                section(rc, s, parallel);
            } else {
                handler(rc, s);
            }
        }
        triggers(rc);
        if (d.subprocesses().stream().anyMatch(s -> s.event() && s.interrupting())) {
            for (Node n : d.nodes()) {
                if (NOT_INTERRUPTIBLE.contains(n.kind()) && !g.isShadowed(n)) {
                    rc.issues().blocker("WF_SUBPROCESS_INTERRUPT_UNSUPPORTED", n.path(), n.id(), null, q(n) + " ("
                            + n.kind().businessLabel() + ") cannot be stopped by a handler that interrupts the request."
                            + " Make the handlers run alongside the request, or remove this step.");
                }
            }
        }
    }

    private static void kind(RuleContext rc, Subprocess s, Node n, String path) {
        boolean allowed = s.embedded() ? SECTION_KINDS.contains(n.kind()) : HANDLER_KINDS.contains(n.kind());
        if (n.kind() == NodeKind.END && s.event() && !s.interrupting()) {
            allowed = false;
        }
        if (n instanceof Node.Http h && h.undoHandler()) {
            rc.issues().blocker("WF_UNDO_HANDLER_SCOPE", path, n.id(), null, q(n) + " is an automatic undo call. It"
                    + " runs from an Undo step and cannot be part of a subprocess.");
            return;
        }
        if (!allowed) {
            String why = n.kind() == NodeKind.END
                    ? (s.embedded() ? " A section is left by its paths; put the Finish step after it."
                    : " A handler that runs alongside the request does not finish it; let its last step end without a path.")
                    : "";
            rc.issues().blocker("WF_SUBPROCESS_STEP_KIND", path, n.id(), null, q(n) + " ("
                    + n.kind().businessLabel() + ") cannot be part of " + (s.embedded() ? "a section" : "a handler")
                    + "." + why);
        }
    }

    private static void settings(RuleContext rc, Subprocess s) {
        String path = s.path();
        if (s.embedded()) {
            if (s.triggerType() != null || s.interrupting()) {
                rc.issues().blocker("WF_SUBPROCESS_SETTING", path + "/trigger", null, null, "The section "
                        + q(s.label()) + " is entered by its paths, so it has no trigger. Make it an event subprocess"
                        + " or remove the trigger.");
            }
            if ((s.timeoutAfter() == null) != (s.onTimeout() == null)) {
                rc.issues().blocker("WF_SUBPROCESS_TIMEOUT", path + (s.timeoutAfter() == null ? "/onTimeout" : "/timeoutAfter"),
                        null, null, "The section " + q(s.label()) + " needs both a time limit and the step to continue"
                        + " at when it passes.");
            }
            if (s.timeoutAfter() != null) {
                duration(rc, path + "/timeoutAfter", s.label(), s.timeoutAfter());
            }
            if (s.errorCode() != null && s.errorCode().startsWith(RESERVED_ERROR_PREFIX)) {
                reserved(rc, path + "/onError/errorCode", s.errorCode());
            }
            return;
        }
        if (s.timeoutAfter() != null || s.onTimeout() != null || s.errorTo() != null) {
            rc.issues().blocker("WF_SUBPROCESS_SETTING", path, null, null, "The handler " + q(s.label())
                    + " cannot have a time limit or an error route; those belong to sections.");
        }
        if (s.triggerType() == null) {
            rc.issues().blocker("WF_SUBPROCESS_SETTING", path + "/trigger", null, null, "The handler "
                    + q(s.label()) + " needs a trigger: an error, a signal or a time from the start of the request.");
            return;
        }
        switch (s.triggerType()) {
            case "error" -> {
                if (!s.interrupting()) {
                    rc.issues().blocker("WF_SUBPROCESS_SETTING", path + "/interrupting", null, null, "The handler "
                            + q(s.label()) + " starts on an error, so it always interrupts the request.");
                }
                if (s.signal() != null || s.after() != null) {
                    mixed(rc, s);
                }
                if (s.triggerErrorCode() != null && s.triggerErrorCode().startsWith(RESERVED_ERROR_PREFIX)) {
                    reserved(rc, path + "/trigger/errorCode", s.triggerErrorCode());
                }
            }
            case "signal" -> {
                if (s.signal() == null) {
                    rc.issues().blocker("WF_SUBPROCESS_SETTING", path + "/trigger/signal", null, null, "The handler "
                            + q(s.label()) + " starts on a signal; name the signal.");
                }
                if (s.triggerErrorCode() != null || s.after() != null) {
                    mixed(rc, s);
                }
            }
            default -> {
                if (s.after() == null) {
                    rc.issues().blocker("WF_SUBPROCESS_SETTING", path + "/trigger/after", null, null, "The handler "
                            + q(s.label()) + " starts after a time; set how long after the start of the request.");
                } else {
                    duration(rc, path + "/trigger/after", s.label(), s.after());
                }
                if (s.triggerErrorCode() != null || s.signal() != null) {
                    mixed(rc, s);
                }
            }
        }
    }

    private static void mixed(RuleContext rc, Subprocess s) {
        rc.issues().blocker("WF_SUBPROCESS_SETTING", s.path() + "/trigger", null, null, "The trigger of the handler "
                + q(s.label()) + " mixes settings of different kinds of trigger. Keep only those of "
                + q(s.triggerType()) + ".");
    }

    private static void section(RuleContext rc, Subprocess s, boolean parallel) {
        Graph g = rc.graph();
        Node entry = g.entry(s);
        List<Node> members = members(rc, s);
        if (members.isEmpty()) {
            return;
        }
        if (entry == null) {
            rc.issues().blocker("WF_SUBPROCESS_ENTRY", s.path() + "/nodes", null, null, "Paths from outside the section "
                    + q(s.label()) + " must all lead to the same first step of it, and at least one must.");
            return;
        }
        boolean exits = false;
        for (Node m : members) {
            for (Transition t : g.outgoing(m)) {
                if (g.scopeOf(g.node(t.to())) != s) {
                    exits = true;
                    Subprocess target = g.scopeOf(g.node(t.to()));
                    if (target != null && (target.event() || g.entry(target) != g.node(t.to()))) {
                        rc.issues().blocker("WF_SUBPROCESS_ROUTE_TARGET", t.path() + "/to", m.id(), t.id(),
                                "A path can leave the section " + q(s.label()) + " only to a step outside every"
                                        + " subprocess or to the first step of another section.");
                    }
                }
            }
        }
        if (!exits) {
            rc.issues().blocker("WF_SUBPROCESS_EXIT", s.path() + "/nodes", null, null, "No path leaves the section "
                    + q(s.label()) + ". Add a path from one of its steps to the step that follows it.");
        }
        route(rc, s, s.onTimeout(), s.path() + "/onTimeout", members);
        route(rc, s, s.errorTo(), s.path() + "/onError/to", members);
        if (parallel && (s.onTimeout() != null || s.errorTo() != null)) {
            rc.issues().blocker("WF_SUBPROCESS_PARALLEL", s.path(), null, null, "The section " + q(s.label())
                    + " has a time limit or an error route, which is not supported yet in a workflow that runs steps in"
                    + " parallel.");
        }
        if (s.errorTo() != null && members.stream().noneMatch(m -> raises(m, s.errorCode()))) {
            rc.issues().warning("WF_SUBPROCESS_ERROR_UNUSED", s.path() + "/onError", null, null, "No step in the"
                    + " section " + q(s.label()) + " raises " + (s.errorCode() == null ? "an error"
                    : "the error " + q(s.errorCode())) + ", so its error route is never taken. A failing system call"
                    + " uses its own failure path unless it is set to raise an error when it fails.");
        }
    }

    /** A step that can raise {@code code} (null: any code): a raised error or a call set to raise one on failure. */
    private static boolean raises(Node m, String code) {
        String raised = m instanceof Node.RaiseError r ? r.errorCode()
                : m instanceof Node.Http h ? h.raiseOnFailure() : null;
        return raised != null && (code == null || code.equals(raised));
    }

    private static void route(RuleContext rc, Subprocess s, String targetId, String path, List<Node> members) {
        if (targetId == null) {
            return;
        }
        Graph g = rc.graph();
        Node target = g.node(targetId);
        if (target == null) {
            rc.issues().blocker("WF_SUBPROCESS_ROUTE_TARGET", path, null, null, "The section " + q(s.label())
                    + " continues at " + q(targetId) + ", which is not a step.");
            return;
        }
        Subprocess other = g.scopeOf(target);
        if (other == s || target.kind() == NodeKind.START || target.kind() == NodeKind.PARALLEL_JOIN
                || (other != null && (other.event() || g.entry(other) != target))) {
            rc.issues().blocker("WF_SUBPROCESS_ROUTE_TARGET", path, target.id(), null, "The section " + q(s.label())
                    + " cannot continue at " + q(target) + ". Choose a step after the section that is not inside a"
                    + " subprocess (or the first step of another section), and not a \"Wait for all\" step.");
            return;
        }
        // The target must not lead back into the section, or leaving it would start it again.
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        work.add(target);
        seen.set(target.index());
        while (!work.isEmpty()) {
            Node n = work.poll();
            if (members.contains(n)) {
                rc.issues().blocker("WF_SUBPROCESS_ROUTE_LOOP", path, target.id(), null, "From " + q(target)
                        + " the request can come back into the section " + q(s.label()) + ". Loops are not supported.");
                return;
            }
            for (Transition t : g.outgoing(n)) {
                Node next = g.node(t.to());
                if (!seen.get(next.index())) {
                    seen.set(next.index());
                    work.add(next);
                }
            }
        }
    }

    private static void handler(RuleContext rc, Subprocess s) {
        Graph g = rc.graph();
        List<Node> members = members(rc, s);
        if (members.isEmpty()) {
            return;
        }
        for (Transition t : rc.definition().transitions()) {
            Node from = g.node(t.from());
            Node to = g.node(t.to());
            if (from == null || to == null) {
                continue;
            }
            boolean fromIn = g.scopeOf(from) == s;
            boolean toIn = g.scopeOf(to) == s;
            if (fromIn != toIn) {
                rc.issues().blocker("WF_SUBPROCESS_HANDLER_PATH", t.path(), null, t.id(), "The handler "
                        + q(s.label()) + " is started by its trigger only, and its paths stay inside it. This path"
                        + (fromIn ? " leaves it." : " enters it."));
            }
        }
        if (g.entry(s) == null) {
            rc.issues().blocker("WF_SUBPROCESS_ENTRY", s.path() + "/nodes", null, null, "The handler " + q(s.label())
                    + " needs exactly one first step: the only one of its steps that no path leads to.");
        }
    }

    private static void triggers(RuleContext rc) {
        Map<String, String> seen = new HashMap<>();
        for (Subprocess s : rc.definition().subprocesses()) {
            if (!s.event() || s.triggerType() == null) {
                continue;
            }
            String key = switch (s.triggerType()) {
                case "error" -> "error:" + Objects.requireNonNullElse(s.triggerErrorCode(), "*");
                case "signal" -> "signal:" + s.signal();
                default -> null;
            };
            if (key != null && seen.putIfAbsent(key, s.label()) != null) {
                rc.issues().blocker("WF_SUBPROCESS_TRIGGER_DUPLICATE", s.path() + "/trigger", null, null, "The handlers "
                        + q(seen.get(key)) + " and " + q(s.label()) + " start on the same "
                        + (key.startsWith("error") ? "error" : "signal") + ". Keep one of them.");
            }
        }
    }

    private static List<Node> members(RuleContext rc, Subprocess s) {
        return s.nodes().stream().map(rc.graph()::node).filter(n -> n != null && rc.graph().scopeOf(n) == s)
                .distinct().toList();
    }

    private static void duration(RuleContext rc, String path, String label, String text) {
        long minutes = Durations.minutes(text);
        if (minutes < 0) {
            rc.issues().blocker("WF_DURATION_INVALID", path, null, null, q(label) + " has a time that is not valid ("
                    + text + "). Use days, hours and minutes, for example PT30M, PT48H or P2D.");
        } else if (minutes < Durations.MIN_MINUTES) {
            rc.issues().blocker("WF_DURATION_TOO_SHORT", path, null, null, q(label)
                    + " has no time at all. The shortest time is 1 minute (PT1M).");
        } else if (minutes > Durations.MAX_MINUTES) {
            rc.issues().blocker("WF_DURATION_TOO_LONG", path, null, null, q(label) + " allows "
                    + Durations.describe(minutes) + ". The longest time is 90 days (P90D).");
        }
    }

    private static void reserved(RuleContext rc, String path, String code) {
        rc.issues().blocker("WF_ERROR_CODE_RESERVED", path, null, null, "The error code " + q(code)
                + " starts with ORQIX_, which is reserved for the platform. Choose another code.");
    }

    // ---------------------------------------------------------------- errors

    private static void errors(RuleContext rc) {
        Graph g = rc.graph();
        for (Node n : rc.definition().nodes()) {
            if (!(n instanceof Node.RaiseError r) || g.isShadowed(n)) {
                continue;
            }
            String path = n.configPath("errorCode");
            if (r.errorCode().startsWith(RESERVED_ERROR_PREFIX)) {
                rc.issues().blocker("WF_ERROR_CODE_RESERVED", path, n.id(), null, "The error code "
                        + q(r.errorCode()) + " starts with ORQIX_, which is reserved for the platform. Choose another"
                        + " code.");
                continue;
            }
            Subprocess scope = g.scopeOf(n);
            if (scope != null && scope.event()) {
                continue; // reported as a step kind a handler cannot hold
            }
            boolean caught = scope != null && scope.errorTo() != null
                    && (scope.errorCode() == null || scope.errorCode().equals(r.errorCode()));
            caught |= rc.definition().subprocesses().stream().anyMatch(s -> s.event() && "error".equals(s.triggerType())
                    && (s.triggerErrorCode() == null || s.triggerErrorCode().equals(r.errorCode())));
            if (!caught) {
                rc.issues().blocker("WF_ERROR_NOT_CAUGHT", path, n.id(), null, q(n) + " raises "
                        + q(r.errorCode()) + ", but nothing catches it. Put the step in a section with an error route"
                        + " for this code, or add a handler that starts on this error.");
            }
        }
    }

    // ---------------------------------------------------------------- call failures as errors (S66)

    /**
     * A call with {@code raiseErrorOnFailure} turns its known failures into a business error for the error route of
     * its section. The opt-in is per call and explicit: the call must sit in a section whose error route catches the
     * code (error handlers and outer sections are not searched), and it cannot also have its own failed path (S32) or
     * be an automatic undo call. Platform codes stay reserved.
     */
    private static void callFailures(RuleContext rc) {
        Graph g = rc.graph();
        for (Node n : rc.definition().nodes()) {
            if (!(n instanceof Node.Http h) || h.raiseOnFailure() == null || g.isShadowed(n)) {
                continue;
            }
            String path = n.configPath("raiseErrorOnFailure");
            String code = h.raiseOnFailure();
            if (code.startsWith(RESERVED_ERROR_PREFIX)) {
                reserved(rc, path, code);
                continue;
            }
            if (h.automaticUndo()) {
                rc.issues().blocker("WF_CALL_FAILURE_ERROR_CONFLICT", path, n.id(), null, q(n) + " is an automatic"
                        + " undo call. It runs from an Undo step and cannot raise an error when it fails.");
                continue;
            }
            for (Transition t : g.outgoing(n)) {
                if (t.on() == Route.FAILED) {
                    rc.issues().blocker("WF_CALL_FAILURE_ERROR_CONFLICT", path, n.id(), t.id(), q(n) + " has its own"
                            + " failure path and is also set to raise " + q(code) + " when it fails. Keep one of"
                            + " them.");
                }
            }
            Subprocess scope = g.scopeOf(n);
            if (scope == null || !scope.embedded() || scope.errorTo() == null
                    || (scope.errorCode() != null && !scope.errorCode().equals(code))) {
                rc.issues().blocker("WF_CALL_FAILURE_ERROR_NOT_CAUGHT", path, n.id(), null, q(n) + " raises "
                        + q(code) + " when it fails, but " + (scope == null || !scope.embedded()
                        ? "it is not in a section." : "the error route of the section " + q(scope.label())
                        + (scope.errorTo() == null ? " is not set." : " catches only " + q(scope.errorCode()) + "."))
                        + " Put the call in a section whose error route catches this code, or remove the setting.");
            }
        }
    }

    // ---------------------------------------------------------------- signals

    private static void signals(RuleContext rc) {
        Set<String> waited = new HashSet<>();
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.WaitForSignal w) {
                waited.add(w.signal());
            }
        }
        rc.definition().subprocesses().stream().filter(s -> s.event() && "signal".equals(s.triggerType())
                && s.signal() != null).forEach(s -> waited.add(s.signal()));
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.SendSignal send && !waited.contains(send.signal()) && !rc.graph().isShadowed(n)) {
                rc.issues().warning("WF_SIGNAL_NOT_WAITED", n.configPath("signal"), n.id(), null, q(n) + " sends "
                        + q(send.signal()) + ", but no step or handler of this workflow waits for it. A signal never"
                        + " reaches other requests.");
            }
        }
    }

    // ---------------------------------------------------------------- automatic undo

    private static void undo(RuleContext rc) {
        Graph g = rc.graph();
        Map<String, Node> handlerOf = new HashMap<>();
        boolean anyHandler = false;
        for (Node n : rc.definition().nodes()) {
            if (!(n instanceof Node.Http h) || g.isShadowed(n) || !h.automaticUndo()) {
                continue;
            }
            if (h.compensates() == null) {
                rc.issues().blocker("WF_UNDO_TARGET_REQUIRED", h.configPath("compensation"), h.id(), null, q(h)
                        + " is an automatic undo call, so it must name the call it undoes.");
                continue;
            }
            anyHandler = true;
            for (Transition t : rc.definition().transitions()) {
                if (t.from().equals(h.id()) || t.to().equals(h.id())) {
                    rc.issues().blocker("WF_UNDO_HANDLER_ROUTES", t.path(), h.id(), t.id(), q(h) + " is an"
                            + " automatic undo call. It runs only from an \"Undo completed calls\" step, so no path can"
                            + " lead to it or leave it.");
                }
            }
            Node other = handlerOf.putIfAbsent(h.compensates(), h);
            if (other != null) {
                rc.issues().blocker("WF_UNDO_DUPLICATE", h.configPath("compensates"), h.id(), null, q(other) + " and "
                        + q(h) + " both undo " + q(h.compensates()) + " automatically. Keep one of them.");
            }
            Node target = g.node(h.compensates());
            if (target instanceof Node.Http t && t.undoHandler()) {
                rc.issues().blocker("WF_COMPENSATES_TARGET", h.configPath("compensates"), h.id(), null, q(h)
                        + " cannot undo another undo call.");
            }
        }
        boolean anyStep = false;
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.Compensate && !g.isShadowed(n)) {
                anyStep = true;
                if (!anyHandler) {
                    rc.issues().blocker("WF_UNDO_NOTHING", n.path(), n.id(), null, q(n) + " has nothing to undo. Mark"
                            + " a call to a system as the automatic undo of an earlier call first.");
                }
            }
        }
        if (anyHandler && !anyStep) {
            rc.issues().warning("WF_UNDO_NEVER_RUN", "/nodes", null, null, "The workflow has automatic undo calls but"
                    + " no \"Undo completed calls\" step, so they never run.");
        }
    }
}
