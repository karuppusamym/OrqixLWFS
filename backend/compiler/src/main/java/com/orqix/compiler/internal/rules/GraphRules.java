package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/** Structure rules (S4 section 5.1). */
final class GraphRules {

    private GraphRules() {
    }

    static void check(RuleContext rc) {
        duplicateIds(rc);
        unknownNodes(rc);
        startAndEnd(rc);
        outgoing(rc);
        cycles(rc);
        reachability(rc);
    }

    private static void duplicateIds(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        Map<String, Boolean> seen = new HashMap<>();
        for (Node n : d.nodes()) {
            if (seen.putIfAbsent(n.id(), true) != null) {
                rc.issues().blocker("WF_DUPLICATE_ID", n.path() + "/id", n.id(), null, "The ID " + q(n.id())
                        + " is used more than once. Every step and path needs its own ID.");
            } else if (n.id().equals(d.workflowKey())) {
                rc.issues().blocker("WF_DUPLICATE_ID", n.path() + "/id", n.id(), null, "The ID " + q(n.id())
                        + " is the same as the workflow key. Choose a different step ID.");
            }
        }
        for (Transition t : d.transitions()) {
            if (seen.putIfAbsent(t.id(), true) != null) {
                rc.issues().blocker("WF_DUPLICATE_ID", t.path() + "/id", null, t.id(), "The ID " + q(t.id())
                        + " is used more than once. Every step and path needs its own ID.");
            } else if (t.id().equals(d.workflowKey())) {
                rc.issues().blocker("WF_DUPLICATE_ID", t.path() + "/id", null, t.id(), "The ID " + q(t.id())
                        + " is the same as the workflow key. Choose a different path ID.");
            }
        }
    }

    private static void unknownNodes(RuleContext rc) {
        for (Transition t : rc.definition().transitions()) {
            if (rc.graph().node(t.from()) == null) {
                rc.issues().blocker("WF_TRANSITION_UNKNOWN_NODE", t.path() + "/from", null, t.id(),
                        "This path starts at " + q(t.from()) + ", which is not a step of this workflow.");
            }
            if (rc.graph().node(t.to()) == null) {
                rc.issues().blocker("WF_TRANSITION_UNKNOWN_NODE", t.path() + "/to", null, t.id(),
                        "This path leads to " + q(t.to()) + ", which is not a step of this workflow.");
            }
        }
    }

    private static void startAndEnd(RuleContext rc) {
        List<Node> starts = rc.definition().nodes().stream().filter(n -> n.kind() == NodeKind.START).toList();
        if (starts.isEmpty()) {
            rc.issues().blocker("WF_START_COUNT", "/nodes", null, null,
                    "The workflow needs exactly one request form (start step).");
        }
        for (Node extra : starts.stream().skip(1).toList()) {
            rc.issues().blocker("WF_START_COUNT", extra.path(), extra.id(), null, "The workflow can have only one"
                    + " request form (start step). Remove " + q(extra) + " or turn it into another kind of step.");
        }
        if (rc.definition().nodes().stream().noneMatch(n -> n.kind() == NodeKind.END)) {
            rc.issues().blocker("WF_NO_END", "/nodes", null, null, "The workflow needs at least one Finish step.");
        }
        for (Transition t : rc.definition().transitions()) {
            Node to = rc.graph().node(t.to());
            if (to != null && to.kind() == NodeKind.START) {
                rc.issues().blocker("WF_START_INCOMING", t.path() + "/to", to.id(), t.id(),
                        "No path can lead back to the request form " + q(to) + ".");
            }
            Node from = rc.graph().node(t.from());
            if (from != null && from.kind() == NodeKind.END) {
                rc.issues().blocker("WF_END_OUTGOING", t.path() + "/from", from.id(), t.id(),
                        "The Finish step " + q(from) + " cannot continue to another step.");
            }
        }
    }

    private static void outgoing(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (rc.graph().isShadowed(n) || n.kind() == NodeKind.END) {
                continue;
            }
            boolean timeout = n instanceof Node.Approval a && a.timeoutAfter() != null;
            Set<Route> allowed = switch (n.kind()) {
                case APPROVAL -> timeout ? Set.of(Route.APPROVED, Route.REJECTED, Route.TIMED_OUT)
                        : Set.of(Route.APPROVED, Route.REJECTED);
                case BRANCH -> Set.of(Route.CONDITION, Route.DEFAULT);
                case WAIT_FOR_MESSAGE -> ((Node.WaitForMessage) n).timeoutAfter() != null
                        ? Set.of(Route.NEXT, Route.TIMED_OUT) : Set.of(Route.NEXT);
                case HTTP, START_WORKFLOW -> Set.of(Route.NEXT, Route.FAILED);
                default -> Set.of(Route.NEXT);
            };
            Map<Route, Integer> counts = new HashMap<>();
            for (Transition t : rc.leaving(n)) {
                if (t.on() == Route.FAILED && !allowed.contains(Route.FAILED)) {
                    rc.issues().blocker("WF_FAILURE_ROUTE_KIND", t.path() + "/on", n.id(), t.id(), n.label() + " ("
                            + n.kind().businessLabel() + ") cannot have a path for when it fails. Only a call to a"
                            + " system, an AI agent or another workflow can.");
                } else if (!allowed.contains(t.on())) {
                    String hint = t.on() == Route.TIMED_OUT ? (n.kind() == NodeKind.WAIT_FOR_MESSAGE
                            ? " Set a time limit on this step to add a path for when the message does not arrive in"
                            + " time, or remove this path."
                            : " Set a time limit on this step to add a path for when"
                            + " nobody decides in time, or remove this path.") : "";
                    rc.issues().blocker("WF_OUTGOING_INVALID", t.path() + "/on", n.id(), t.id(), n.label() + " ("
                            + n.kind().businessLabel() + ") can only continue on " + routes(allowed) + ", not on "
                            + q(t.on().json()) + "." + hint);
                } else {
                    counts.merge(t.on(), 1, Integer::sum);
                }
            }
            switch (n.kind()) {
                case APPROVAL -> {
                    approvalRoute(rc, n, counts.getOrDefault(Route.APPROVED, 0), "approved");
                    approvalRoute(rc, n, counts.getOrDefault(Route.REJECTED, 0), "rejected");
                    if (timeout) {
                        int timedOut = counts.getOrDefault(Route.TIMED_OUT, 0);
                        if (timedOut == 0) {
                            rc.issues().blocker("WF_TIMEOUT_ROUTE", n.path(), n.id(), null, n.label()
                                    + " has a time limit, so it needs a path for when nobody decides in time.");
                        } else if (timedOut > 1) {
                            rc.issues().blocker("WF_TIMEOUT_ROUTE", n.path(), n.id(), null, n.label()
                                    + " can have only one path for when nobody decides in time.");
                        }
                    }
                }
                case PARALLEL_SPLIT -> {
                    // The number of branches is checked with the other parallel rules (ParallelRules).
                }
                case WAIT_FOR_MESSAGE -> {
                    int next = counts.getOrDefault(Route.NEXT, 0);
                    if (next == 0) {
                        rc.issues().blocker("WF_OUTGOING_INVALID", n.path(), n.id(), null, n.label()
                                + " needs a next step for when the message arrives.");
                    } else if (next > 1) {
                        rc.issues().blocker("WF_OUTGOING_INVALID", n.path(), n.id(), null, n.label()
                                + " can continue to only one next step.");
                    }
                    if (((Node.WaitForMessage) n).timeoutAfter() != null) {
                        int timedOut = counts.getOrDefault(Route.TIMED_OUT, 0);
                        if (timedOut == 0) {
                            rc.issues().blocker("WF_MESSAGE_TIMEOUT_ROUTE", n.path(), n.id(), null, n.label()
                                    + " has a time limit, so it needs a path for when the message does not arrive"
                                    + " in time.");
                        } else if (timedOut > 1) {
                            rc.issues().blocker("WF_MESSAGE_TIMEOUT_ROUTE", n.path(), n.id(), null, n.label()
                                    + " can have only one path for when the message does not arrive in time.");
                        }
                    }
                }
                case BRANCH -> {
                    int defaults = counts.getOrDefault(Route.DEFAULT, 0);
                    if (defaults == 0) {
                        rc.issues().blocker("WF_BRANCH_DEFAULT", n.path(), n.id(), null, n.label()
                                + " needs a default path for when no condition applies.");
                    } else if (defaults > 1) {
                        rc.issues().blocker("WF_BRANCH_DEFAULT", n.path(), n.id(), null, n.label()
                                + " can have only one default path.");
                    }
                    if (counts.getOrDefault(Route.CONDITION, 0) == 0) {
                        rc.issues().blocker("WF_BRANCH_NO_CONDITION", n.path(), n.id(), null, n.label()
                                + " needs at least one path with a condition.");
                    }
                    branchSingleTarget(rc, n);
                }
                default -> {
                    int next = counts.getOrDefault(Route.NEXT, 0);
                    if (next == 0) {
                        rc.issues().blocker("WF_OUTGOING_INVALID", n.path(), n.id(), null, n.label()
                                + " needs a next step.");
                    } else if (next > 1) {
                        rc.issues().blocker("WF_OUTGOING_INVALID", n.path(), n.id(), null, n.label()
                                + " can continue to only one next step.");
                    }
                    if (counts.getOrDefault(Route.FAILED, 0) > 1) {
                        rc.issues().blocker("WF_FAILURE_ROUTE_COUNT", n.path(), n.id(), null, n.label()
                                + " can have only one path for when it fails.");
                    }
                }
            }
        }
    }

    private static void approvalRoute(RuleContext rc, Node n, int count, String outcome) {
        if (count == 0) {
            rc.issues().blocker("WF_APPROVAL_ROUTES", n.path(), n.id(), null, n.label()
                    + " needs a path for when the request is " + outcome + ".");
        } else if (count > 1) {
            rc.issues().blocker("WF_APPROVAL_ROUTES", n.path(), n.id(), null, n.label()
                    + " can have only one path for when the request is " + outcome + ".");
        }
    }

    private static void branchSingleTarget(RuleContext rc, Node n) {
        List<Transition> routes = rc.graph().outgoing(n);
        Set<String> targets = new HashSet<>();
        routes.forEach(t -> targets.add(t.to()));
        if (routes.size() >= 2 && targets.size() == 1) {
            Node target = rc.graph().node(routes.get(0).to());
            rc.issues().warning("WF_BRANCH_SINGLE_TARGET", n.path(), n.id(), null, "All paths of " + q(n)
                    + " lead to " + q(target) + ", so the choice has no effect.");
        }
    }

    private static void cycles(RuleContext rc) {
        for (Transition t : rc.graph().backEdges()) {
            Node from = rc.graph().node(t.from());
            Node to = rc.graph().node(t.to());
            String message = from == to ? q(from) + " leads back to itself. Loops are not supported yet."
                    : "The path from " + q(from) + " to " + q(to) + " creates a loop. Loops are not supported yet.";
            rc.issues().blocker("WF_CYCLE", t.path(), null, t.id(), message);
        }
    }

    private static void reachability(RuleContext rc) {
        Graph g = rc.graph();
        for (Node n : rc.definition().nodes()) {
            if (g.isShadowed(n)) {
                continue;
            }
            if (!g.starts().isEmpty() && !g.isReachable(n)) {
                rc.issues().blocker("WF_UNREACHABLE_NODE", n.path(), n.id(), null,
                        q(n) + " can never be reached from the request form.");
            }
            if (!g.ends().isEmpty() && !g.reachesEnd(n)) {
                rc.issues().blocker("WF_DEAD_END", n.path(), n.id(), null,
                        q(n) + " never leads to a Finish step.");
            }
        }
    }

    private static String routes(Set<Route> allowed) {
        return allowed.stream().map(Route::json).sorted().map(RuleContext::q)
                .reduce((a, b) -> a + " or " + b).orElse("");
    }
}
