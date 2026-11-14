package com.orqix.compiler.internal.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * Bounded loops of a definition (CG-E01, docs/build/S138). A loop is a declared path back to an earlier step with
 * {@code maxTraversals}; its body is every step on a forward path (bounded paths left out) from the step it leads to
 * back to the step it leaves. Every other path is "forward", and the compiler requires the forward graph to be
 * acyclic, so each cycle passes through at least one bounded path and the number of visits of any step is at most
 * {@code 1 + sum(maxTraversals of the loops whose body holds it)}. Pure: used by the rules, the BPMN generator and the
 * simulator.
 */
public final class Loops {

    /** Most bounded loops in one workflow; with maxTraversals at most 10 a step runs at most 51 times. */
    public static final int MAX_LOOPS = 5;

    /** Process variable prefix holding how many times a bounded path was taken. */
    public static final String TRAVERSAL_VARIABLE = "orqix_loop_";
    /** Process variable prefix holding the visit number (occurrence) of a step inside a loop body. */
    public static final String OCCURRENCE_VARIABLE = "orqix_occ_";
    /** Suffix of the exclusive gateway generated at the end of a bounded path. */
    public static final String GUARD = "__loop";
    /** Suffix of the flow from the guard back to the earlier step. */
    public static final String AGAIN = "__again";

    /** One bounded loop: the path back, its body (canonical node order) and the path taken once it is used up. */
    public record Loop(Transition back, List<String> body, Transition limit) {
        public int maxTraversals() {
            return back.maxTraversals();
        }
    }

    private final Map<String, Loop> bySource = new LinkedHashMap<>();
    private final Map<String, Loop> byTransition = new LinkedHashMap<>();
    private final Map<String, Integer> maxVisits = new HashMap<>();

    public Loops(WorkflowDefinition d) {
        Map<String, Integer> order = new HashMap<>();
        d.nodes().forEach(n -> order.putIfAbsent(n.id(), n.index()));
        Map<String, List<String>> forward = new HashMap<>();
        Map<String, List<String>> backward = new HashMap<>();
        for (Transition t : d.transitions()) {
            if (!t.bounded()) {
                forward.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t.to());
                backward.computeIfAbsent(t.to(), k -> new ArrayList<>()).add(t.from());
            }
        }
        for (Transition t : d.transitions()) {
            if (!t.bounded() || bySource.containsKey(t.from())) {
                continue;
            }
            Set<String> fromTarget = reach(t.to(), forward);
            Set<String> toSource = reach(t.from(), backward);
            Set<String> body = new TreeSet<>((a, b) -> Integer.compare(order.getOrDefault(a, Integer.MAX_VALUE),
                    order.getOrDefault(b, Integer.MAX_VALUE)));
            for (String id : fromTarget) {
                if (toSource.contains(id)) {
                    body.add(id);
                }
            }
            Transition limit = d.transitions().stream()
                    .filter(o -> o.from().equals(t.from()) && o.on() == Route.LIMIT_REACHED).findFirst().orElse(null);
            Loop loop = new Loop(t, List.copyOf(body), limit);
            bySource.put(t.from(), loop);
            byTransition.put(t.id(), loop);
        }
        for (Loop loop : bySource.values()) {
            loop.body().forEach(id -> maxVisits.merge(id, loop.maxTraversals(), Integer::sum));
        }
    }

    private static Set<String> reach(String root, Map<String, List<String>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>();
        seen.add(root);
        work.add(root);
        while (!work.isEmpty()) {
            for (String next : edges.getOrDefault(work.poll(), List.of())) {
                if (seen.add(next)) {
                    work.add(next);
                }
            }
        }
        return seen;
    }

    /** @return every loop, one per step that has a bounded path, in transition order */
    public List<Loop> all() {
        return List.copyOf(byTransition.values());
    }

    public boolean isEmpty() {
        return byTransition.isEmpty();
    }

    /** @return the loop whose bounded path leaves this step, or null */
    public Loop leaving(String stepId) {
        return bySource.get(stepId);
    }

    /** @return the loop of this bounded path, or null when the transition is not one */
    public Loop of(Transition t) {
        return byTransition.get(t.id());
    }

    /** @return true when the step is inside at least one loop body, so it can run more than once */
    public boolean inLoop(String stepId) {
        return maxVisits.containsKey(stepId);
    }

    /** @return the most times the step can run in one request (1 outside loops) */
    public int maxVisits(String stepId) {
        return 1 + maxVisits.getOrDefault(stepId, 0);
    }

    /** @return the steps inside loop bodies, sorted by id */
    public Set<String> loopSteps() {
        return Collections.unmodifiableSet(new TreeSet<>(maxVisits.keySet()));
    }
}
