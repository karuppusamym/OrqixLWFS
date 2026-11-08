package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

/**
 * Guarded move of a request between steps of its own release (CG-O03, docs/build/S143): move it to a step whose
 * inputs exist, skip the step it waits at, or run a step again. A move never repeats an external effect that already
 * happened: calls recorded in the effect journal are answered from the journal when the step runs again, and any other
 * step with an effect (a notice, a document, a child request, a worker or AI job) refuses the re-run.
 */
public final class MoveRules {

    public static final Set<String> KINDS = Set.of("move", "skip", "rerun");

    /** Steps a request can be taken away from: their open work is closed by Orqix when the token leaves. */
    static final Set<String> FROM_KINDS = Set.of("approval", "wait", "http", "sendAdapter");

    /** Steps a request can be moved to: each starts from its own element, with nothing generated in front of it. */
    static final Set<String> TO_KINDS = Set.of("approval", "wait", "http", "sendAdapter", "decision", "calculate",
            "branch", "notification");

    /** Steps whose external effect goes through the effect journal; a re-run reuses the recorded result. */
    static final Set<String> JOURNALED_KINDS = Set.of("http", "sendAdapter");

    /** Steps with an effect outside the journal: running one again would repeat it. */
    static final Set<String> UNJOURNALED_EFFECT_KINDS = Set.of("notification", "generateDocument", "startWorkflow",
            "externalTask", "aiTask", "sendSignal", "compensate");

    /**
     * What the request holds right now.
     *
     * @param activeSteps   canonical ids of the steps it is at (a step needing attention included)
     * @param producedSteps steps whose data the request has
     * @param visitedSteps  steps the request has been at (its timeline)
     * @param effectsInDoubt steps with a call in flight or an unknown outcome
     * @param attentionStep the step that needs attention, or null
     */
    public record Facts(List<String> activeSteps, Set<String> producedSteps, Set<String> visitedSteps,
            Set<String> effectsInDoubt, String attentionStep) {
    }

    /** A step the move passes over ({@code notRun}) or runs again ({@code runsAgain}, {@code reuseRecorded}). */
    public record Affected(String nodeId, String label, String kind, String handling) {
    }

    public record Plan(String kind, String from, String fromLabel, String to, String toLabel, List<Affected> skipped,
            List<Affected> rerun, List<ReleaseCompatibility.Finding> findings) {
        public boolean allowed() {
            return findings.isEmpty();
        }
    }

    private MoveRules() {
    }

    public static Plan plan(JsonNode definition, Facts facts, String kind, String fromId, String toId) {
        StepGraph g = new StepGraph(definition);
        List<ReleaseCompatibility.Finding> findings = new ArrayList<>();
        Set<String> active = new LinkedHashSet<>();
        for (String id : facts.activeSteps()) {
            active.add(MigrationRules.canonical(g, id));
        }
        String from = fromId == null ? "" : fromId;
        String to = toId == null ? "" : toId;
        List<Affected> skipped = new ArrayList<>();
        List<Affected> rerun = new ArrayList<>();
        if (!KINDS.contains(kind)) {
            findings.add(f("MOVE_KIND_MISMATCH", "Choose move, skip or rerun.", null));
        }
        if (active.size() != 1) {
            findings.add(f("STEP_COUNT", active.isEmpty() ? "The request is not at a step, so there is nothing to move."
                    : "The request is at several steps at once (parallel branches); a move needs a request at exactly"
                            + " one step.", null));
        } else if (!active.contains(from)) {
            findings.add(f("FROM_NOT_CURRENT", "The request is not at the step '" + g.label(from) + "' any more.", from));
        }
        JsonNode fromNode = g.node(from);
        JsonNode toNode = g.node(to);
        if (toNode == null) {
            findings.add(f("STEP_MISSING", "The release has no step '" + to + "'.", to));
        }
        if (fromNode == null || toNode == null || !findings.isEmpty()) {
            return new Plan(kind, from, g.label(from), to, g.label(to), skipped, rerun, findings);
        }
        String fromKind = g.type(from);
        String toKind = g.type(to);
        boolean fromPolls = "http".equals(fromKind) && fromNode.path("config").path("poll").isObject();
        boolean failedCall = JOURNALED_KINDS.contains(fromKind) && from.equals(facts.attentionStep());
        if (!FROM_KINDS.contains(fromKind) || fromPolls || (JOURNALED_KINDS.contains(fromKind) && !failedCall)) {
            findings.add(f("MOVE_FROM_KIND", "A request at a '" + fromKind + "' step cannot be moved away from it here;"
                    + " approvals, waits and calls that failed can.", from));
        }
        boolean toPolls = "http".equals(toKind) && toNode.path("config").path("poll").isObject();
        if (!TO_KINDS.contains(toKind) || toPolls) {
            findings.add(f("MOVE_TARGET_KIND", "A request cannot be moved to a '" + toKind + "' step; choose an"
                    + " approval, a wait, a call, a decision, a calculation, a branch or a notice.", to));
        }
        for (String id : List.of(from, to)) {
            if (g.repetition(id) != null) {
                findings.add(f("MULTI_INSTANCE", "The step '" + g.label(id) + "' is repeated (" + g.repetition(id)
                        + "); requests are not moved into or out of repeated steps.", id));
            }
        }
        if (facts.effectsInDoubt().contains(from)) {
            findings.add(f("EFFECT_IN_FLIGHT", "The call of '" + g.label(from) + "' may have reached the other system."
                    + " Reconcile it before moving the request.", from));
        }
        Set<String> loopSteps = ReleaseCompatibility.loopSteps(definition);
        if (loopSteps.contains(from) || loopSteps.contains(to)) {
            findings.add(f("LOOP_IN_PROGRESS", "Requests are not moved into or out of a loop (\"send back to\" with a"
                    + " limit); its count would no longer match the path taken.", loopSteps.contains(from) ? from : to));
        }
        for (StepGraph.Region region : g.regions()) {
            if (region.inside().contains(from) || region.inside().contains(to)) {
                findings.add(f("PARALLEL_REGION", "Requests are not moved into or out of a parallel section.",
                        region.split()));
                break;
            }
        }
        if (!definition.path("subprocesses").isMissingNode()) {
            findings.add(f("SUBPROCESSES", "This workflow has sections or handlers; its requests are not moved between"
                    + " steps.", null));
        }

        // Classify the move from the graph, so the operator's intent and the effect on the request agree.
        Set<String> fromAhead = g.reach(from);
        Set<String> toAhead = g.reach(to);
        String actual = to.equals(from) || toAhead.contains(from) ? "rerun" : g.hasRoute(from, to) ? "skip" : "move";
        if (KINDS.contains(kind) && !actual.equals(kind)) {
            findings.add(f("MOVE_KIND_MISMATCH", switch (actual) {
                case "rerun" -> "The step '" + g.label(to) + "' comes before (or is) the current step: this is a re-run.";
                case "skip" -> "The step '" + g.label(to) + "' directly follows the current step: this is a skip.";
                default -> "The step '" + g.label(to) + "' neither follows the current step directly nor comes before"
                        + " it: this is a move.";
            }, to));
        }

        if ("rerun".equals(actual)) {
            // The steps from the target back to the current one run again.
            Set<String> again = new TreeSet<>(toAhead);
            again.retainAll(g.before(from));
            for (String id : g.nodeIds()) {
                if (!again.contains(id)) {
                    continue;
                }
                String k = g.type(id);
                String handling = "runsAgain";
                boolean ran = facts.visitedSteps().contains(id) || facts.producedSteps().contains(id);
                if (JOURNALED_KINDS.contains(k) && ran) {
                    handling = "reuseRecorded";
                    if (facts.effectsInDoubt().contains(id)) {
                        findings.add(f("EFFECT_IN_FLIGHT", "The call of '" + g.label(id) + "' has an unconfirmed"
                                + " outcome. Reconcile it before running it again.", id));
                    }
                } else if (UNJOURNALED_EFFECT_KINDS.contains(k) && ran) {
                    handling = "wouldRepeat";
                    findings.add(f("EFFECT_WOULD_REPEAT", "Running '" + g.label(id) + "' again would repeat what it"
                            + " already did (" + k + "). Choose a step after it.", id));
                }
                rerun.add(new Affected(id, g.label(id), k, handling));
            }
        } else {
            skipped.add(new Affected(from, g.label(from), fromKind, "notRun"));
            if ("move".equals(actual)) {
                Set<String> between = new TreeSet<>(fromAhead);
                between.retainAll(g.before(to));
                between.remove(from);
                between.remove(to);
                for (String id : g.nodeIds()) {
                    if (between.contains(id)) {
                        skipped.add(new Affected(id, g.label(id), g.type(id), "notRun"));
                    }
                }
            }
        }

        // Inputs: the target and everything after it must find the data they read, already produced or produced by a
        // step that runs before the reader. Skipped steps produce nothing.
        Set<String> seen = new LinkedHashSet<>();
        for (String n : toAhead) {
            Set<String> reads = new TreeSet<>(g.reads(n));
            reads.addAll(g.routeReads(n));
            if ("end".equals(g.type(n))) {
                reads.addAll(g.requiredOutputReads());
            }
            Set<String> earlier = g.before(n);
            for (String x : reads) {
                boolean producedAhead = toAhead.contains(x) && earlier.contains(x) && !x.equals(n);
                if (!facts.producedSteps().contains(x) && !producedAhead && seen.add(n + ">" + x)) {
                    findings.add(f("DATA_MISSING", "The step '" + g.label(n) + "' reads data of '" + g.label(x)
                            + "', which this request does not have. Choose a step whose inputs exist.", n));
                }
            }
        }
        return new Plan(actual, from, g.label(from), to, g.label(to), skipped, rerun, findings);
    }

    private static ReleaseCompatibility.Finding f(String code, String message, String nodeId) {
        return new ReleaseCompatibility.Finding(code, message, nodeId);
    }
}
