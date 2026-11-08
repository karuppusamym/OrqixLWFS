package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Loops;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * Bounded loops (CG-E01, docs/build/S138). GraphRules refuses every cycle that no bounded path closes; these rules
 * check the bounded paths themselves, so that the compiler can prove every step runs a known, small number of times:
 * <ul>
 * <li>a bounded path leads back to an earlier step (its target reaches its source on forward paths);</li>
 * <li>the step it leaves has exactly one bounded path and exactly one {@code limitReached} path;</li>
 * <li>a workflow has at most {@link Loops#MAX_LOOPS} loops;</li>
 * <li>a loop stays inside one section or handler and takes whole parallel blocks or none;</li>
 * <li>steps whose runtime still keeps one row per step (message waits, polling calls, child workflows, document
 * generation, signals and undo) cannot be inside a loop in this slice.</li>
 * </ul>
 */
final class LoopRules {

    private LoopRules() {
    }

    static void check(RuleContext rc) {
        WorkflowDefinition d = rc.definition();
        if (d.transitions().stream().noneMatch(Transition::bounded)) {
            return;
        }
        Graph g = rc.graph();
        Loops loops = new Loops(d);
        int count = 0;
        Set<String> sourcesSeen = new HashSet<>();
        for (Transition t : d.transitions()) {
            if (!t.bounded()) {
                continue;
            }
            Node from = g.node(t.from());
            Node to = g.node(t.to());
            if (from == null || to == null) {
                continue; // reported as WF_TRANSITION_UNKNOWN_NODE
            }
            if (t.on() == Route.LIMIT_REACHED) {
                rc.issues().blocker("WF_LOOP_LIMIT_ROUTE", t.path() + "/maxTraversals", from.id(), t.id(),
                        "The path for when the limit is reached cannot have a limit of its own.");
                continue;
            }
            if (!sourcesSeen.add(from.id())) {
                rc.issues().blocker("WF_LOOP_ONE_PER_STEP", t.path() + "/maxTraversals", from.id(), t.id(), q(from)
                        + " already has a path back with a limit. A step can send the request back on one path only.");
                continue;
            }
            count++;
            Loops.Loop loop = loops.of(t);
            if (loop == null || !loop.body().contains(from.id())) {
                rc.issues().blocker("WF_LOOP_NOT_BACK", t.path() + "/maxTraversals", from.id(), t.id(), "Only a path"
                        + " back to an earlier step can have a limit. " + q(to) + " does not lead on to " + q(from)
                        + ", so this path does not go back; remove its limit.");
                continue;
            }
            long limits = rc.leaving(from).stream().filter(o -> o.on() == Route.LIMIT_REACHED).count();
            if (limits == 0) {
                rc.issues().blocker("WF_LOOP_LIMIT_ROUTE", from.path(), from.id(), t.id(), q(from) + " sends the"
                        + " request back at most " + t.maxTraversals() + " time" + (t.maxTraversals() == 1 ? "" : "s")
                        + ", so it needs a path for when the limit is reached.");
            } else if (limits > 1) {
                rc.issues().blocker("WF_LOOP_LIMIT_ROUTE", from.path(), from.id(), t.id(), q(from)
                        + " can have only one path for when the limit is reached.");
            }
            scope(rc, g, loop, from, to, t);
            parallel(rc, g, d, loop, t);
            kinds(rc, g, d, loop, t);
        }
        if (count > Loops.MAX_LOOPS) {
            rc.issues().blocker("WF_LOOP_COUNT", "/transitions", null, null, "A workflow can have at most "
                    + Loops.MAX_LOOPS + " paths back with a limit; this one has " + count + ".");
        }
    }

    private static void scope(RuleContext rc, Graph g, Loops.Loop loop, Node from, Node to, Transition t) {
        WorkflowDefinition.Subprocess scope = g.scopeOf(from);
        for (String id : loop.body()) {
            if (g.scopeOf(g.node(id)) != scope) {
                rc.issues().blocker("WF_LOOP_SCOPE", t.path(), from.id(), t.id(), "The path from " + q(from)
                        + " back to " + q(to) + " would go into or out of a section or handler. A loop must stay"
                        + " inside one section, or outside every section.");
                return;
            }
        }
    }

    /** A loop takes a whole parallel block (split and join) or none of it, and never jumps into a branch. */
    private static void parallel(RuleContext rc, Graph g, WorkflowDefinition d, Loops.Loop loop, Transition t) {
        Set<String> body = new HashSet<>(loop.body());
        for (Node n : d.nodes()) {
            if (!(n instanceof Node.ParallelSplit split) || g.isShadowed(n)) {
                continue;
            }
            boolean splitIn = body.contains(split.id());
            boolean joinIn = body.contains(split.joinRef());
            Set<String> branch = between(g, split.id(), split.joinRef());
            boolean branchTouched = branch.stream().anyMatch(body::contains);
            if (splitIn != joinIn || (branchTouched && !splitIn)) {
                rc.issues().blocker("WF_LOOP_PARALLEL", t.path(), t.from(), t.id(), "The path back from "
                        + q(g.node(t.from())) + " to " + q(g.node(t.to())) + " would cut through the parallel block "
                        + q(split) + ". A loop must contain the whole block, from " + q(split) + " to its join, or"
                        + " stay out of it.");
                return;
            }
        }
    }

    /** Steps strictly between a split and its join on forward paths. */
    private static Set<String> between(Graph g, String splitId, String joinId) {
        Set<String> after = new HashSet<>();
        Deque<String> work = new ArrayDeque<>(List.of(splitId));
        while (!work.isEmpty()) {
            String id = work.poll();
            Node n = g.node(id);
            if (n == null || id.equals(joinId)) {
                continue;
            }
            for (Transition t : g.outgoing(n)) {
                if (!t.bounded() && after.add(t.to())) {
                    work.add(t.to());
                }
            }
        }
        after.remove(joinId);
        after.remove(splitId);
        return after;
    }

    private static void kinds(RuleContext rc, Graph g, WorkflowDefinition d, Loops.Loop loop, Transition t) {
        for (String id : loop.body()) {
            Node n = g.node(id);
            String why = switch (n.kind()) {
                case WAIT_FOR_MESSAGE -> "a message wait keeps one subscription per step";
                case START_WORKFLOW -> "a child workflow already repeats with its own \"Repeat until\" setting";
                case GENERATE_DOCUMENT -> "a generated document is kept once per step";
                case WAIT_FOR_SIGNAL, SEND_SIGNAL -> "signals are matched once per step";
                case COMPENSATE -> "undo runs once for the whole request";
                case HTTP -> ((Node.Http) n).polls() ? "a call that keeps checking keeps one check row per step"
                        : ((Node.Http) n).undoHandler() ? "an automatic undo call runs once"
                        : d.nodes().stream().anyMatch(o -> o instanceof Node.Http u && n.id().equals(u.compensates()))
                        ? "its automatic undo call runs once for the request" : null;
                default -> null;
            };
            if (why != null) {
                rc.issues().blocker("WF_LOOP_STEP_KIND", n.path(), n.id(), t.id(), q(n) + " ("
                        + n.kind().businessLabel() + ") cannot be inside the loop from " + q(g.node(t.from()))
                        + " back to " + q(g.node(t.to())) + ": " + why + ". Move it before or after the loop.");
            }
            if (n.kind() == NodeKind.START) {
                rc.issues().blocker("WF_LOOP_NOT_BACK", t.path(), n.id(), t.id(),
                        "No path can lead back to the request form " + q(n) + ".");
            }
        }
    }
}
