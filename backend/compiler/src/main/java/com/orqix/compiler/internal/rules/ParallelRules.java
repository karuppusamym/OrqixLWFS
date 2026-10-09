package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * "Run in parallel" structure (S12 section 1). A split and its join enclose a region of branches. Rules:
 * <ul>
 * <li>the join named by the split exists, is a join step and belongs to this split only;</li>
 * <li>at least two branches, each with its own first step and at least one step;</li>
 * <li>branches share no step and no path jumps into or out of a branch, except that a rejection (or a timed-out
 * approval) may continue to a rejection tail (notifications leading only to rejected Finish steps), which ends the
 * whole request;</li>
 * <li>every step of a branch can reach the join and each branch enters the join through exactly one path (a parallel
 * join waits for one token per incoming path, so two paths from one branch could never both arrive);</li>
 * <li>parallel steps nest at most {@value #MAX_NESTING} levels deep.</li>
 * </ul>
 */
final class ParallelRules {

    /** Deepest allowed nesting of parallel regions (a region inside a branch of another region). */
    static final int MAX_NESTING = 2;

    private ParallelRules() {
    }

    static void check(RuleContext rc) {
        Graph g = rc.graph();
        Map<String, List<Node.ParallelSplit>> splitsByJoin = new HashMap<>();
        List<Node.ParallelSplit> valid = new ArrayList<>();
        for (Node n : rc.definition().nodes()) {
            if (g.isShadowed(n) || !(n instanceof Node.ParallelSplit split)) {
                continue;
            }
            Node target = g.node(split.joinRef());
            if (!(target instanceof Node.ParallelJoin)) {
                String why = target == null ? "which is not a step of this workflow"
                        : q(target) + " is not a \"Wait for all\" step";
                rc.issues().blocker("WF_PARALLEL_JOIN_UNKNOWN", split.configPath("joinRef"), split.id(), null,
                        q(split) + " needs a matching \"Wait for all\" step, but it points to " + q(split.joinRef())
                                + ", " + why + ".");
                continue;
            }
            splitsByJoin.computeIfAbsent(target.id(), k -> new ArrayList<>()).add(split);
        }
        for (Node n : rc.definition().nodes()) {
            if (g.isShadowed(n) || !(n instanceof Node.ParallelJoin join)) {
                continue;
            }
            List<Node.ParallelSplit> owners = splitsByJoin.getOrDefault(join.id(), List.of());
            if (owners.isEmpty()) {
                rc.issues().blocker("WF_PARALLEL_JOIN_ORPHAN", join.path(), join.id(), null, q(join)
                        + " waits for parallel branches, but no \"Run in parallel\" step points to it.");
            }
            for (Node.ParallelSplit extra : owners.stream().skip(1).toList()) {
                rc.issues().blocker("WF_PARALLEL_JOIN_SHARED", extra.configPath("joinRef"), extra.id(), null,
                        q(join) + " can wait for only one \"Run in parallel\" step. Give " + q(extra)
                                + " its own \"Wait for all\" step.");
            }
        }
        for (List<Node.ParallelSplit> owners : splitsByJoin.values()) {
            valid.add(owners.get(0));
        }
        valid.sort((a, b) -> Integer.compare(a.index(), b.index()));

        Map<String, BitSet> regions = new LinkedHashMap<>();
        for (Node.ParallelSplit split : valid) {
            Node.ParallelJoin join = (Node.ParallelJoin) g.node(split.joinRef());
            BitSet region = analyze(rc, split, join);
            if (region != null) {
                regions.put(split.id(), region);
            }
        }
        nesting(rc, valid, regions);
    }

    // ---------------------------------------------------------------- one split

    /** @return the nodes of all branches, or null when the region could not be analysed (an issue was reported) */
    private static BitSet analyze(RuleContext rc, Node.ParallelSplit split, Node.ParallelJoin join) {
        Graph g = rc.graph();
        List<Transition> branches = g.outgoing(split);
        if (branches.size() < 2) {
            rc.issues().blocker("WF_PARALLEL_BRANCHES", split.path(), split.id(), null, q(split)
                    + " needs at least two paths to run in parallel.");
            return null;
        }
        List<Node> heads = new ArrayList<>();
        boolean ok = true;
        for (Transition t : branches) {
            Node head = g.node(t.to());
            if (head == join) {
                rc.issues().blocker("WF_PARALLEL_EMPTY_BRANCH", t.path() + "/to", split.id(), t.id(),
                        "A path of " + q(split) + " goes straight to " + q(join) + ". Every path needs at least one"
                                + " step.");
                ok = false;
            } else if (heads.contains(head)) {
                rc.issues().blocker("WF_PARALLEL_BRANCHES", t.path() + "/to", split.id(), t.id(), "Two paths of "
                        + q(split) + " lead to the same step " + q(head) + ". Every path needs its own first step.");
                ok = false;
            } else {
                heads.add(head);
            }
        }
        if (!ok) {
            return null;
        }

        Map<Integer, Boolean> tailCache = new HashMap<>();
        // Steps that come after the join: a path from a branch into them skips the join.
        BitSet after = forward(g, join);
        List<BitSet> reach = new ArrayList<>();
        BitSet all = new BitSet();
        for (Node head : heads) {
            BitSet r = reach(rc, head, join, after, tailCache);
            reach.add(r);
            all.or(r);
        }

        // Branches share no step.
        BitSet seen = new BitSet();
        for (BitSet r : reach) {
            BitSet shared = (BitSet) r.clone();
            shared.and(seen);
            for (int i = shared.nextSetBit(0); i >= 0; i = shared.nextSetBit(i + 1)) {
                Node n = g.nodes().get(i);
                rc.issues().blocker("WF_PARALLEL_CROSS_BRANCH", n.path(), n.id(), null, q(n) + " is reached from more"
                        + " than one path of " + q(split) + ". Each path needs its own steps; the paths meet only at "
                        + q(join) + ".");
            }
            seen.or(r);
        }

        for (int i = all.nextSetBit(0); i >= 0; i = all.nextSetBit(i + 1)) {
            Node n = g.nodes().get(i);
            // Nothing leaves a branch, except a rejection that ends the whole request.
            for (Transition t : g.outgoing(n)) {
                Node to = g.node(t.to());
                if (to == join || all.get(to.index()) || escape(rc, t, n, to, tailCache)) {
                    continue;
                }
                rc.issues().blocker("WF_PARALLEL_JUMP_OUT", t.path() + "/to", n.id(), t.id(), "The path from "
                        + q(n) + " to " + q(to) + " leaves the parallel steps of " + q(split) + ". A path can leave"
                        + " only when the request is rejected and ends. Continue to " + q(join) + " instead.");
            }
            // Nothing enters a branch from outside.
            for (Transition t : g.incoming(n)) {
                Node from = g.node(t.from());
                boolean fromSplit = from == split && heads.contains(n);
                if (!fromSplit && !all.get(from.index())) {
                    rc.issues().blocker("WF_PARALLEL_JUMP_IN", t.path() + "/to", n.id(), t.id(), "The path from "
                            + q(from) + " leads into " + q(n) + ", which is inside the parallel steps of "
                            + q(split) + ". Paths can enter only through " + q(split) + ".");
                }
            }
        }
        for (Transition t : g.incoming(join)) {
            Node from = g.node(t.from());
            if (!all.get(from.index())) {
                rc.issues().blocker("WF_PARALLEL_JUMP_IN", t.path() + "/to", join.id(), t.id(), "The path from "
                        + q(from) + " leads into " + q(join) + " without going through " + q(split) + ".");
            }
        }

        // Every step of a branch reaches the join; each branch enters the join exactly once.
        BitSet reachesJoin = reachesJoin(rc, join, all, tailCache);
        for (int i = all.nextSetBit(0); i >= 0; i = all.nextSetBit(i + 1)) {
            if (!reachesJoin.get(i)) {
                Node n = g.nodes().get(i);
                rc.issues().blocker("WF_PARALLEL_BRANCH_OPEN", n.path(), n.id(), null, q(n) + " is inside the"
                        + " parallel steps of " + q(split) + " but never leads to " + q(join)
                        + ". Every path must end there (or be rejected).");
            }
        }
        for (int b = 0; b < reach.size(); b++) {
            int entering = 0;
            for (Transition t : g.incoming(join)) {
                if (reach.get(b).get(g.node(t.from()).index())) {
                    entering++;
                }
            }
            if (entering > 1) {
                rc.issues().blocker("WF_PARALLEL_JOIN_MISMATCH", join.path(), join.id(), null, "The path starting"
                        + " with " + q(heads.get(b)) + " reaches " + q(join) + " in " + entering + " ways. Let the"
                        + " path come together in one shared step first, so each path of " + q(split)
                        + " enters " + q(join) + " exactly once.");
            }
        }
        return all;
    }

    /** Nodes reachable from {@code start} (not including it, unless it lies on a cycle). */
    private static BitSet forward(Graph g, Node start) {
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        work.add(start);
        while (!work.isEmpty()) {
            Node n = work.poll();
            for (Transition t : g.outgoing(n)) {
                Node to = g.node(t.to());
                if (!seen.get(to.index())) {
                    seen.set(to.index());
                    work.add(to);
                }
            }
        }
        return seen;
    }

    /** Nodes reachable from {@code head} without passing the join, a step after the join or an escape path. */
    private static BitSet reach(RuleContext rc, Node head, Node join, BitSet after,
            Map<Integer, Boolean> tailCache) {
        Graph g = rc.graph();
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        seen.set(head.index());
        work.add(head);
        while (!work.isEmpty()) {
            Node n = work.poll();
            for (Transition t : g.outgoing(n)) {
                Node to = g.node(t.to());
                if (to == join || escape(rc, t, n, to, tailCache) || after.get(to.index())
                        || seen.get(to.index())) {
                    continue;
                }
                seen.set(to.index());
                work.add(to);
            }
        }
        return seen;
    }

    /** Nodes of the region that can reach the join through ordinary (non-escape) paths. */
    private static BitSet reachesJoin(RuleContext rc, Node join, BitSet region, Map<Integer, Boolean> tailCache) {
        Graph g = rc.graph();
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        for (Transition t : g.incoming(join)) {
            Node from = g.node(t.from());
            if (region.get(from.index()) && !seen.get(from.index())) {
                seen.set(from.index());
                work.add(from);
            }
        }
        while (!work.isEmpty()) {
            Node n = work.poll();
            for (Transition t : g.incoming(n)) {
                Node from = g.node(t.from());
                if (region.get(from.index()) && !seen.get(from.index())
                        && !escape(rc, t, from, n, tailCache)) {
                    seen.set(from.index());
                    work.add(from);
                }
            }
        }
        return seen;
    }

    /**
     * A rejection or a timed-out approval that continues to a rejection tail: it ends the whole request, so it is
     * allowed to leave a parallel branch.
     */
    static boolean escape(RuleContext rc, Transition t, Node from, Node to, Map<Integer, Boolean> tailCache) {
        if (t.on() != Route.REJECTED && t.on() != Route.TIMED_OUT) {
            return false;
        }
        if (from.kind() != NodeKind.APPROVAL) {
            return false;
        }
        return tailCache.computeIfAbsent(to.index(), k -> rejectionTail(rc.graph(), to));
    }

    /** Only notifications lead from here, and every Finish step reached is a rejection. */
    private static boolean rejectionTail(Graph g, Node start) {
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        seen.set(start.index());
        work.add(start);
        boolean end = false;
        while (!work.isEmpty()) {
            Node n = work.poll();
            switch (n) {
                case Node.Notification notification -> {
                }
                case Node.End e -> {
                    if (!"rejected".equals(e.outcomeKind())) {
                        return false;
                    }
                    end = true;
                }
                default -> {
                    return false;
                }
            }
            for (Transition t : g.outgoing(n)) {
                Node next = g.node(t.to());
                if (!seen.get(next.index())) {
                    seen.set(next.index());
                    work.add(next);
                }
            }
        }
        return end;
    }

    // ---------------------------------------------------------------- nesting

    private static void nesting(RuleContext rc, List<Node.ParallelSplit> splits, Map<String, BitSet> regions) {
        for (Node.ParallelSplit split : splits) {
            int depth = 1;
            for (Map.Entry<String, BitSet> e : regions.entrySet()) {
                if (!e.getKey().equals(split.id()) && e.getValue().get(split.index())) {
                    depth++;
                }
            }
            if (depth > MAX_NESTING) {
                rc.issues().blocker("WF_PARALLEL_DEPTH", split.path(), split.id(), null, q(split) + " is nested "
                        + depth + " levels deep. Parallel steps can be nested at most " + MAX_NESTING
                        + " levels deep.");
            }
        }
    }
}
