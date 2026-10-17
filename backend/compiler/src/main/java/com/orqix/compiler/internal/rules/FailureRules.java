package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * Failure routes (S32). A "Call a system" step may have one optional {@code failed} path:
 * a business reaction to a known permanent HTTP rejection or invalid read-only response. Child workflow failure
 * paths are blocked; exhausted retries retain operator incidents. The failure facts {@code failureKind}, {@code failureHttpStatus} and {@code failureMessage} are
 * outputs of that step that exist only on the failed path. An outcome that may have happened (unknown) never takes
 * the path. Also checks the {@code compensates} annotation of a call step.
 */
final class FailureRules {

    /** Reserved output names of a step with a failed path, in documentation order. */
    static final List<String> FAILURE_OUTPUTS = List.of("failureKind", "failureHttpStatus", "failureMessage");

    /** Values of {@code failureKind}. */
    static final Set<String> FAILURE_KINDS = Set.of("rejected", "invalidResponse");

    private FailureRules() {
    }

    /** @return true for the step kinds that can have a failed path */
    static boolean canFail(Node n) {
        return n.kind() == NodeKind.HTTP || n.kind() == NodeKind.START_WORKFLOW;
    }

    /** @return the {@code failed} transition leaving the step, or null */
    static Transition failedRoute(Graph g, Node n) {
        for (Transition t : g.outgoing(n)) {
            if (t.on() == Route.FAILED) {
                return t;
            }
        }
        return null;
    }

    static boolean hasFailedRoute(Graph g, Node n) {
        return canFail(n) && failedRoute(g, n) != null;
    }

    static boolean isFailureOutput(String key) {
        return FAILURE_OUTPUTS.contains(key);
    }

    /**
     * @return true when {@code target} can be reached from the start without taking the {@code excluded} route of
     *         {@code producer} (so a value that exists only on the other route is not guaranteed there)
     */
    static boolean reachableWithout(RuleContext rc, Node producer, Route excluded, Node target) {
        Graph g = rc.graph();
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        for (Node s : g.starts()) {
            seen.set(s.index());
            work.add(s);
        }
        while (!work.isEmpty()) {
            Node n = work.poll();
            if (n == target) {
                return true;
            }
            for (Transition t : g.outgoing(n)) {
                if (n == producer && t.on() == excluded) {
                    continue;
                }
                Node to = g.node(t.to());
                if (!seen.get(to.index())) {
                    seen.set(to.index());
                    work.add(to);
                }
            }
        }
        return false;
    }

    static void check(RuleContext rc) {
        Graph g = rc.graph();
        for (Node n : rc.definition().nodes()) {
            if (g.isShadowed(n)) {
                continue;
            }
            Transition failed = failedRoute(g, n);
            if (failed != null && canFail(n)) {
                route(rc, n, failed);
            }
            if (n instanceof Node.Http h && h.compensates() != null) {
                compensates(rc, h);
            }
        }
    }

    private static void route(RuleContext rc, Node n, Transition failed) {
        Graph g = rc.graph();
        if (n instanceof Node.StartWorkflow) {
            rc.issues().blocker("WF_FAILURE_ROUTE_CHILD_UNSUPPORTED", failed.path() + "/on", n.id(), failed.id(),
                    "Child workflow failures require operator recovery. A child cannot use a failed route yet; use its normal business outcome instead.");
        }
        if (n instanceof Node.StartWorkflow w && (w.repeat() != null || w.forEach() != null)) {
            rc.issues().blocker("WF_FAILURE_ROUTE_ITERATION", failed.path() + "/on", n.id(), failed.id(), q(n)
                    + " starts another workflow several times, so it cannot have a path for when it fails. Remove"
                    + " that path, or start the other workflow once.");
        }
        if (n instanceof Node.Http h) {
            for (String reserved : FAILURE_OUTPUTS) {
                if (h.resultMapping().containsKey(reserved)) {
                    rc.issues().blocker("WF_FAILURE_ROUTE_OUTPUT_CLASH", h.configPath("resultMapping") + "/" + reserved,
                            n.id(), failed.id(), q(n) + " has a path for when it fails, so the result name "
                                    + q(reserved) + " is reserved for the failure details. Use another name.");
                }
            }
        }
        for (Transition next : g.outgoing(n)) {
            if (next.on() == Route.NEXT && next.to().equals(failed.to())) {
                rc.issues().warning("WF_FAILURE_ROUTE_SAME_TARGET", failed.path() + "/to", n.id(), failed.id(),
                        "The path for when " + q(n) + " fails leads to the same step as the path for when it works,"
                                + " so a failure would not be handled differently.");
            }
        }
    }

    /** The undo annotation: the target must be an earlier call that can change something, on every path. */
    private static void compensates(RuleContext rc, Node.Http h) {
        Graph g = rc.graph();
        String path = h.configPath("compensates");
        Node target = g.node(h.compensates());
        if (target == null) {
            rc.issues().blocker("WF_COMPENSATES_UNKNOWN", path, h.id(), null, q(h) + " is marked as the undo of "
                    + q(h.compensates()) + ", which is not a step of this workflow.");
            return;
        }
        if (target == h) {
            rc.issues().blocker("WF_COMPENSATES_SELF", path, h.id(), null, q(h) + " cannot undo itself.");
            return;
        }
        if (!(target instanceof Node.Http earlier)) {
            rc.issues().blocker("WF_COMPENSATES_TARGET", path, h.id(), null, q(h) + " is marked as the undo of "
                    + q(target) + ", but that is not a call to a system. Only a call that can change something in"
                    + " another system can be undone.");
            return;
        }
        String effect = effectClass(rc, earlier);
        if ("readOnly".equals(effect)) {
            rc.issues().blocker("WF_COMPENSATES_TARGET", path, h.id(), null, q(h) + " is marked as the undo of "
                    + q(earlier) + ", but that call only reads data, so there is nothing to undo.");
            return;
        }
        if (g.hasDominators() && g.isReachable(h) && !g.dominates(earlier, h)) {
            rc.issues().blocker("WF_COMPENSATES_ORDER", path, h.id(), null, q(earlier)
                    + " does not always happen before " + q(h) + ", so " + q(h) + " cannot be its undo. Move the"
                    + " undo to a path that always passes " + q(earlier) + ".");
        }
    }

    /** @return the effect class of the call's pinned operation, or null when it cannot be resolved */
    private static String effectClass(RuleContext rc, Node.Http n) {
        var operation = rc.connections().operation(n.connectionRef(), n.operation());
        if (operation == null || operation.get("effectClass") == null) {
            return null;
        }
        return operation.get("effectClass").stringValue();
    }
}
