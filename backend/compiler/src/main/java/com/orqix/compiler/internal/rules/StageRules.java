package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.internal.model.Durations;
import com.orqix.compiler.internal.model.Enums.NodeKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Stage;

/**
 * Case stages (S17): a presentation and monitoring layer over the steps. Stage ids are unique, every listed step
 * exists and belongs to at most one stage, every stage has steps, and an optional time limit (SLA) is a duration of
 * PT1M to P90D. Stages add nothing to the generated process, so these rules never touch the graph.
 */
final class StageRules {

    /** Steps that only route the request: they take no time of their own, so a stage does not need to list them. */
    private static final Set<NodeKind> ROUTING = Set.of(NodeKind.START, NodeKind.END, NodeKind.BRANCH, NodeKind.DECISION,
            NodeKind.PARALLEL_SPLIT, NodeKind.PARALLEL_JOIN);
    /** Steps that never show as the current step of a request (they finish within the same moment). */
    private static final Set<NodeKind> INSTANT = Set.of(NodeKind.START, NodeKind.END, NodeKind.BRANCH, NodeKind.DECISION,
            NodeKind.PARALLEL_SPLIT, NodeKind.PARALLEL_JOIN, NodeKind.NOTIFICATION);

    private StageRules() {
    }

    static void check(RuleContext rc) {
        var stages = rc.definition().stages();
        if (stages.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        Map<String, String> stageOf = new HashMap<>();
        for (Stage s : stages) {
            if (!ids.add(s.id())) {
                rc.issues().blocker("WF_STAGE_DUPLICATE_ID", s.path() + "/id", null, null, "Two stages have the ID "
                        + q(s.id()) + ". Give each stage its own ID.");
            }
            if (s.nodes().isEmpty()) {
                rc.issues().blocker("WF_STAGE_EMPTY", s.path() + "/nodes", null, null, "The stage " + q(s.label())
                        + " has no steps. Add steps to it or remove it.");
            }
            boolean waits = false;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < s.nodes().size(); i++) {
                String id = s.nodes().get(i);
                String path = s.path() + "/nodes/" + i;
                Node node = rc.graph().node(id);
                if (node == null) {
                    rc.issues().blocker("WF_STAGE_NODE_UNKNOWN", path, null, null, "The stage " + q(s.label())
                            + " lists " + q(id) + ", which is not a step.");
                    continue;
                }
                if (!seen.add(id)) {
                    rc.issues().blocker("WF_STAGE_NODE_DUPLICATE", path, id, null, q(node) + " is listed twice in the"
                            + " stage " + q(s.label()) + ".");
                    continue;
                }
                String other = stageOf.putIfAbsent(id, s.label());
                if (other != null) {
                    rc.issues().blocker("WF_STAGE_NODE_DUPLICATE", path, id, null, q(node) + " is in the stage "
                            + q(other) + " and in the stage " + q(s.label()) + ". A step can belong to one stage only.");
                }
                waits |= !INSTANT.contains(node.kind());
            }
            if (!s.nodes().isEmpty() && !waits && seen.stream().allMatch(id -> rc.graph().node(id) != null)) {
                rc.issues().warning("WF_STAGE_NO_WAITING_STEP", s.path() + "/nodes", null, null, "The stage "
                        + q(s.label()) + " has only steps that finish at once, so it will never show as the current"
                        + " stage of a request. Add an approval, wait, system call or workflow step.");
            }
            if (s.sla() != null) {
                sla(rc, s);
            }
        }
        for (Node n : rc.definition().nodes()) {
            if (!ROUTING.contains(n.kind()) && !stageOf.containsKey(n.id())) {
                rc.issues().warning("WF_STAGE_NOT_COVERED", "/stages", n.id(), null, q(n) + " is not in any stage."
                        + " Requests waiting at it will not show a stage.");
            }
        }
    }

    private static void sla(RuleContext rc, Stage s) {
        long minutes = Durations.minutes(s.sla());
        String path = s.path() + "/sla";
        if (minutes < 0) {
            rc.issues().blocker("WF_STAGE_SLA_INVALID", path, null, null, "The stage " + q(s.label())
                    + " has a time limit that is not valid (" + s.sla() + "). Use days, hours and minutes, for"
                    + " example PT8H, PT48H or P5D.");
        } else if (minutes < Durations.MIN_MINUTES) {
            rc.issues().blocker("WF_STAGE_SLA_TOO_SHORT", path, null, null, "The stage " + q(s.label())
                    + " has no time limit at all. The shortest is 1 minute (PT1M).");
        } else if (minutes > Durations.MAX_MINUTES) {
            rc.issues().blocker("WF_STAGE_SLA_TOO_LONG", path, null, null, "The stage " + q(s.label()) + " allows "
                    + Durations.describe(minutes) + ". The longest time limit is 90 days (P90D).");
        }
    }
}
