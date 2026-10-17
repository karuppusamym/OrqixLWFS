package com.orqix.compiler.internal.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * Graph view of a definition: node lookup, resolved transitions, reachability, reverse reachability and dominators
 * (a parallel join counts every branch as guaranteed, S12).
 * Nodes whose ID repeats an earlier node's ID are "shadowed": transitions resolve to the first occurrence and the
 * shadowed node is left out of graph rules (it is reported as WF_DUPLICATE_ID).
 */
public final class Graph {

    private final List<Node> nodes;
    private final Map<String, Node> byId = new HashMap<>();
    private final BitSet shadowed = new BitSet();
    private final List<List<Transition>> outgoing = new ArrayList<>();
    private final List<List<Transition>> incoming = new ArrayList<>();
    private final List<Transition> resolved = new ArrayList<>();
    private final List<Node> starts = new ArrayList<>();
    private final List<Node> ends = new ArrayList<>();
    private final BitSet reachable = new BitSet();
    private final BitSet reachesEnd = new BitSet();
    private final BitSet[] dominators;

    public Graph(WorkflowDefinition definition) {
        this.nodes = definition.nodes();
        for (Node n : nodes) {
            outgoing.add(new ArrayList<>());
            incoming.add(new ArrayList<>());
            if (byId.putIfAbsent(n.id(), n) != null) {
                shadowed.set(n.index());
                continue;
            }
            if (n.kind() == NodeKind.START) {
                starts.add(n);
            } else if (n.kind() == NodeKind.END) {
                ends.add(n);
            }
        }
        for (Transition t : definition.transitions()) {
            Node from = byId.get(t.from());
            Node to = byId.get(t.to());
            if (from != null && to != null) {
                resolved.add(t);
                outgoing.get(from.index()).add(t);
                incoming.get(to.index()).add(t);
            }
        }
        for (Node s : starts) {
            mark(s, reachable, true);
        }
        for (Node e : ends) {
            mark(e, reachesEnd, false);
        }
        this.dominators = starts.size() == 1 ? computeDominators(starts.get(0)) : null;
    }

    private void mark(Node root, BitSet seen, boolean forward) {
        Deque<Node> work = new ArrayDeque<>();
        if (!seen.get(root.index())) {
            seen.set(root.index());
            work.add(root);
        }
        while (!work.isEmpty()) {
            Node n = work.poll();
            List<Transition> edges = forward ? outgoing.get(n.index()) : incoming.get(n.index());
            for (Transition t : edges) {
                Node next = byId.get(forward ? t.to() : t.from());
                if (!seen.get(next.index())) {
                    seen.set(next.index());
                    work.add(next);
                }
            }
        }
    }

    /** Iterative data-flow dominators over the nodes reachable from {@code start} (at most 50 nodes). */
    private BitSet[] computeDominators(Node start) {
        int size = nodes.size();
        BitSet[] dom = new BitSet[size];
        BitSet all = (BitSet) reachable.clone();
        for (int i = reachable.nextSetBit(0); i >= 0; i = reachable.nextSetBit(i + 1)) {
            dom[i] = (BitSet) all.clone();
        }
        dom[start.index()] = new BitSet();
        dom[start.index()].set(start.index());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = reachable.nextSetBit(0); i >= 0; i = reachable.nextSetBit(i + 1)) {
                if (i == start.index()) {
                    continue;
                }
                // A parallel join waits for every branch, so whatever is guaranteed in any branch is guaranteed after
                // it (union); every other node with several incoming paths is a simple merge (intersection).
                boolean allBranches = nodes.get(i).kind() == NodeKind.PARALLEL_JOIN && !inclusiveJoin(i);
                BitSet next = null;
                for (Transition t : incoming.get(i)) {
                    int p = byId.get(t.from()).index();
                    if (!reachable.get(p)) {
                        continue;
                    }
                    if (next == null) {
                        next = (BitSet) dom[p].clone();
                    } else if (allBranches) {
                        next.or(dom[p]);
                    } else {
                        next.and(dom[p]);
                    }
                }
                if (next == null) {
                    next = new BitSet();
                }
                next.set(i);
                if (!next.equals(dom[i])) {
                    dom[i] = next;
                    changed = true;
                }
            }
        }
        return dom;
    }

    /** An inclusive join waits only for the paths that started, so a skipped path guarantees nothing (S51). */
    private boolean inclusiveJoin(int joinIndex) {
        String id = nodes.get(joinIndex).id();
        for (Node n : nodes) {
            if (n instanceof Node.ParallelSplit split && split.inclusive() && split.joinRef().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public List<Node> nodes() {
        return nodes;
    }

    /** @return the first node with this ID, or null */
    public Node node(String id) {
        return byId.get(id);
    }

    public boolean isShadowed(Node n) {
        return shadowed.get(n.index());
    }

    /** @return transitions whose from/to name existing nodes, in canonical order */
    public List<Transition> resolvedTransitions() {
        return Collections.unmodifiableList(resolved);
    }

    public List<Transition> outgoing(Node n) {
        return Collections.unmodifiableList(outgoing.get(n.index()));
    }

    public List<Transition> incoming(Node n) {
        return Collections.unmodifiableList(incoming.get(n.index()));
    }

    public List<Node> starts() {
        return Collections.unmodifiableList(starts);
    }

    public List<Node> ends() {
        return Collections.unmodifiableList(ends);
    }

    public boolean isReachable(Node n) {
        return reachable.get(n.index());
    }

    public boolean reachesEnd(Node n) {
        return reachesEnd.get(n.index());
    }

    /** @return true when dominance can be evaluated (exactly one start node) */
    public boolean hasDominators() {
        return dominators != null;
    }

    /**
     * @return true when every path from the start to {@code n} passes through {@code x}; false when {@code n} is
     *         unreachable or dominance is not available
     */
    public boolean dominates(Node x, Node n) {
        if (dominators == null || !reachable.get(n.index())) {
            return false;
        }
        return dominators[n.index()].get(x.index());
    }

    /**
     * @return back edges found by a depth-first search from the start nodes, then from every other node, in canonical
     *         node and transition order (every cycle contains at least one)
     */
    public List<Transition> backEdges() {
        int[] state = new int[nodes.size()];
        List<Transition> back = new ArrayList<>();
        List<Node> roots = new ArrayList<>(starts);
        for (Node n : nodes) {
            if (!shadowed.get(n.index())) {
                roots.add(n);
            }
        }
        for (Node root : roots) {
            if (state[root.index()] == 0) {
                dfs(root, state, back);
            }
        }
        return back;
    }

    private void dfs(Node root, int[] state, List<Transition> back) {
        Deque<Object[]> stack = new ArrayDeque<>();
        state[root.index()] = 1;
        stack.push(new Object[] {root, 0});
        while (!stack.isEmpty()) {
            Object[] frame = stack.peek();
            Node n = (Node) frame[0];
            int i = (Integer) frame[1];
            List<Transition> edges = outgoing.get(n.index());
            if (i >= edges.size()) {
                state[n.index()] = 2;
                stack.pop();
                continue;
            }
            frame[1] = i + 1;
            Transition t = edges.get(i);
            Node next = byId.get(t.to());
            if (state[next.index()] == 1) {
                back.add(t);
            } else if (state[next.index()] == 0) {
                state[next.index()] = 1;
                stack.push(new Object[] {next, 0});
            }
        }
    }
}
