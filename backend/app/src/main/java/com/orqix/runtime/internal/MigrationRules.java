package com.orqix.runtime.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

/**
 * General release migration with explicit step mapping (CG-O02, docs/build/S143). Extends the S38 rules
 * ({@link ReleaseCompatibility}) instead of loosening them: the steps a request waits at must still exist with the same
 * kind and settings (an approval may be renamed through a mapping), and the history the request travelled may differ
 * only when every step ahead still finds the data it reads. Everything that cannot be carried over safely is refused
 * with a reason: effects in flight, loops and repeated steps in progress, changed parallel sections, joins that could
 * never complete.
 */
public final class MigrationRules {

    /** One instruction "the request at (or the data of) step {@code from} continues as step {@code to}". */
    public record Mapping(String from, String to) {
    }

    /**
     * What the request holds right now, read under the run lock.
     *
     * @param activeSteps    canonical ids of the steps it waits at (one per parallel branch)
     * @param producedSteps  steps whose data the request has ({@code orqix_step_<id>} variables)
     * @param loopTraversals bounded loop paths already taken at least once (transition ids)
     * @param effectsInFlight steps with an external call started and not finished, or with an unknown outcome
     * @param busySteps      worker jobs held by a worker and checks in progress
     */
    public record Facts(List<String> activeSteps, Set<String> producedSteps, Set<String> loopTraversals,
            Set<String> effectsInFlight, Set<String> busySteps) {
    }

    /** How one waiting step is carried over: {@code keep} (same id) or {@code rename} (mapped to another id). */
    public record Step(String from, String to, String kind, String action, String label) {
    }

    /** Data of a finished step copied under the id the target release reads it by. */
    public record DataCopy(String from, String to) {
    }

    public record Plan(List<Step> steps, List<DataCopy> dataCopies, List<ReleaseCompatibility.Finding> findings,
            List<Mapping> suggestedMappings) {
        public boolean allowed() {
            return findings.isEmpty();
        }

        /** Engine activity mappings: only the steps whose id changes. */
        public Map<String, String> activityMappings() {
            Map<String, String> result = new LinkedHashMap<>();
            for (Step s : steps) {
                if (!s.from().equals(s.to())) {
                    result.put(s.from(), s.to());
                }
            }
            return result;
        }
    }

    /** Waiting step kinds that can be carried over to another release (with identical settings). */
    static final Set<String> MOVABLE_KINDS = Set.of("approval", "wait", "waitForMessage", "waitForSignal",
            "waitForFirst", "startWorkflow", "externalTask");

    /** Only an approval keeps its open task when its id changes; other waits are bound to their engine element id. */
    static final Set<String> RENAMABLE_KINDS = Set.of("approval");

    private MigrationRules() {
    }

    public static Plan plan(JsonNode source, JsonNode target, Facts facts, List<Mapping> mappings) {
        StepGraph from = new StepGraph(source);
        StepGraph to = new StepGraph(target);
        List<ReleaseCompatibility.Finding> findings = new ArrayList<>();
        Map<String, String> map = new LinkedHashMap<>();
        Set<String> active = new LinkedHashSet<>();
        for (String id : facts.activeSteps()) {
            active.add(canonical(from, id));
        }

        for (Mapping m : mappings) {
            if (m.from() == null || m.to() == null || m.from().isBlank() || m.to().isBlank()) {
                findings.add(finding("MAPPING_INVALID", "Each mapping needs a step of the current release and a step"
                        + " of the target release.", null));
            } else if (map.containsKey(m.from())) {
                findings.add(finding("MAPPING_INVALID", "The step '" + from.label(m.from()) + "' is mapped twice.",
                        m.from()));
            } else if (from.node(m.from()) == null) {
                findings.add(finding("MAPPING_INVALID", "The current release has no step '" + m.from() + "'.", m.from()));
            } else if (to.node(m.to()) == null) {
                findings.add(finding("MAPPING_INVALID", "The target release has no step '" + m.to() + "'.", m.from()));
            } else if (!active.contains(m.from()) && !facts.producedSteps().contains(m.from())) {
                findings.add(finding("MAPPING_INVALID", "The request has not reached the step '"
                        + from.label(m.from()) + "', so there is nothing of it to map.", m.from()));
            } else {
                map.put(m.from(), m.to());
            }
        }

        if (active.isEmpty()) {
            findings.add(finding("STEP_COUNT", "The request is not waiting at a step, so there is nothing to move.", null));
            return new Plan(List.of(), List.of(), findings, List.of());
        }

        List<Step> steps = new ArrayList<>();
        Map<String, String> positions = new LinkedHashMap<>();
        for (String a : active) {
            JsonNode sourceNode = from.node(a);
            if (sourceNode == null) {
                findings.add(finding("STEP_UNKNOWN", "The current step is not part of the current release.", a));
                continue;
            }
            String kind = sourceNode.path("type").asString("");
            boolean polling = "http".equals(kind) && sourceNode.path("config").path("poll").isObject();
            String t = map.getOrDefault(a, a);
            if (positions.containsValue(t)) {
                findings.add(finding("MAPPING_MERGE", "Two waiting steps would continue as the same step '" + to.label(t)
                        + "'; each waiting step needs its own step in the target release.", a));
            }
            positions.put(a, t);
            if (!MOVABLE_KINDS.contains(kind) && !polling) {
                findings.add(finding("STEP_KIND", "A request waiting at a '" + kind + "' step cannot be moved; approvals,"
                        + " waits, worker steps, checks and child requests can.", a));
                continue;
            }
            String repeated = from.repetition(a) != null ? from.repetition(a) : to.repetition(t);
            if (repeated != null) {
                findings.add(finding("MULTI_INSTANCE", "The step '" + from.label(a) + "' is repeated (" + repeated
                        + "); a request inside a repeated step cannot be moved.", a));
            }
            if (facts.effectsInFlight().contains(a) || facts.busySteps().contains(a)) {
                findings.add(finding("EFFECT_IN_FLIGHT", "The step '" + from.label(a) + "' has work in flight (a call,"
                        + " a check or a worker job). Wait until it settles or reconcile it.", a));
            }
            JsonNode targetNode = to.node(t);
            if (targetNode == null) {
                findings.add(finding("STEP_MISSING", "The target release has no step '" + from.label(a)
                        + "'. Map it to a step of the target release.", a));
                continue;
            }
            if (!t.equals(a) && !RENAMABLE_KINDS.contains(kind)) {
                findings.add(finding("RENAME_UNSUPPORTED", "Only an approval can continue under another step id; the"
                        + " step '" + from.label(a) + "' must keep its id in the target release.", a));
            }
            if (!kind.equals(targetNode.path("type").asString(""))) {
                findings.add(finding("STEP_CHANGED", "The step '" + from.label(a) + "' would continue as a different"
                        + " kind of step.", a));
            } else if (!sourceNode.path("config").equals(targetNode.path("config"))
                    || !references(source, sourceNode).equals(references(target, targetNode))) {
                findings.add(finding(polling ? "POLL_CHANGED" : "STEP_CHANGED", "The settings of the step '"
                        + from.label(a) + "' differ in the target release (for example its group, deadline, checks or"
                        + " connection version); its open work cannot be carried over.", a));
            }
            steps.add(new Step(a, t, kind, t.equals(a) ? "keep" : "rename", to.label(t)));
        }

        // Loops (S138): traversal counts and visit numbers belong to the loop paths of the current release.
        boolean loopsEqual = ReleaseCompatibility.loops(source).equals(ReleaseCompatibility.loops(target));
        Set<String> sourceLoops = ReleaseCompatibility.loopSteps(source);
        Set<String> targetLoops = ReleaseCompatibility.loopSteps(target);
        for (var e : positions.entrySet()) {
            boolean inLoop = sourceLoops.contains(e.getKey()) || targetLoops.contains(e.getValue());
            if (inLoop && (!loopsEqual || !e.getKey().equals(e.getValue()))) {
                findings.add(finding("LOOP_IN_PROGRESS", "The request is inside a loop (\"send back to\" with a limit);"
                        + " it can move only when the loop is identical in the target release and the step keeps its"
                        + " id.", e.getKey()));
            }
        }
        if (!facts.loopTraversals().isEmpty() && !loopsEqual) {
            findings.add(finding("LOOP_IN_PROGRESS", "The request has already gone round a loop whose path or limit"
                    + " differs in the target release; its count could not be carried over.", null));
        }

        // Parallel sections the request is inside must be identical, with the waiting steps keeping their ids.
        Set<String> openJoins = new TreeSet<>();
        for (StepGraph.Region region : from.regions()) {
            List<String> inside = active.stream().filter(region.inside()::contains).toList();
            if (inside.isEmpty()) {
                continue;
            }
            openJoins.add(region.join());
            if (region.inclusive()) {
                findings.add(finding("INCLUSIVE_REGION", "The request is inside an inclusive parallel section (its join"
                        + " waits only for the branches that started); it cannot be moved there.", region.split()));
                continue;
            }
            StepGraph.Region other = to.regions().stream().filter(r -> r.split().equals(region.split())).findFirst()
                    .orElse(null);
            if (other == null || !from.regionSignature(region).equals(to.regionSignature(other))) {
                findings.add(finding("PARALLEL_REGION", "The request is inside the parallel section after '"
                        + from.label(region.split()) + "', which differs in the target release; its branches could not"
                        + " be carried over.", region.split()));
            } else if (inside.stream().anyMatch(id -> !id.equals(positions.getOrDefault(id, id)))) {
                findings.add(finding("PARALLEL_REGION", "Steps inside a parallel section keep their ids when the"
                        + " request moves.", region.split()));
            }
        }

        Set<String> ahead = to.reachAll(new LinkedHashSet<>(positions.values()));
        for (StepGraph.Region region : to.regions()) {
            if (!region.inclusive() && ahead.contains(region.join()) && !openJoins.contains(region.join())
                    && !ahead.contains(region.split())) {
                findings.add(finding("JOIN_UNREACHABLE", "In the target release the request would reach the join of"
                        + " the parallel section after '" + to.label(region.split()) + "' without having passed its"
                        + " split, and would wait there forever.", region.join()));
            }
        }

        // Data: every step ahead must find the data it reads, either already produced (or copied through a mapping)
        // or produced by a step that runs before it.
        List<DataCopy> copies = new ArrayList<>();
        Set<String> produced = new TreeSet<>(facts.producedSteps());
        for (var e : map.entrySet()) {
            if (!active.contains(e.getKey()) && facts.producedSteps().contains(e.getKey())
                    && !e.getKey().equals(e.getValue())) {
                copies.add(new DataCopy(e.getKey(), e.getValue()));
                produced.add(e.getValue());
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String n : ahead) {
            Set<String> reads = new TreeSet<>(to.routeReads(n));
            if (!positions.containsValue(n)) {
                reads.addAll(to.reads(n));
            }
            if ("end".equals(to.type(n))) {
                reads.addAll(to.requiredOutputReads());
            }
            Set<String> earlier = to.before(n);
            for (String x : reads) {
                boolean producedAhead = ahead.contains(x) && earlier.contains(x);
                if (!produced.contains(x) && !producedAhead && missing.add(n + ">" + x)) {
                    findings.add(finding("DATA_MISSING", "The step '" + to.label(n) + "' of the target release reads"
                            + " data of '" + to.label(x) + "', which this request does not have"
                            + (from.node(x) == null ? "" : " under that id") + ". Map the step that produced it, or"
                            + " choose another release.", n));
                }
            }
        }

        if (!source.path("subprocesses").isMissingNode() || !target.path("subprocesses").isMissingNode()) {
            findings.add(finding("SUBPROCESSES", "One of the releases has sections or handlers; requests of such"
                    + " workflows cannot be moved.", null));
        }
        if (!source.path("stages").equals(target.path("stages"))) {
            findings.add(finding("STAGES_CHANGED", "The case stages differ between the releases, so stage progress"
                    + " could not be carried over.", null));
        }
        return new Plan(steps, copies, findings, suggest(from, to, active, facts.producedSteps(), map));
    }

    /**
     * Mappings the operator probably wants: a waiting step or a finished step whose id the target release no longer
     * has, paired with the one step of the same kind and label that only the target release has.
     */
    static List<Mapping> suggest(StepGraph from, StepGraph to, Set<String> active, Set<String> produced,
            Map<String, String> given) {
        List<Mapping> result = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>(active);
        candidates.addAll(produced);
        for (String id : candidates) {
            if (given.containsKey(id) || to.node(id) != null || from.node(id) == null) {
                continue;
            }
            List<String> matches = new ArrayList<>();
            for (String other : to.nodeIds()) {
                if (from.node(other) == null && to.type(other).equals(from.type(id))
                        && to.label(other).equalsIgnoreCase(from.label(id))) {
                    matches.add(other);
                }
            }
            if (matches.isEmpty()) {
                for (String other : to.nodeIds()) {
                    if (from.node(other) == null && to.type(other).equals(from.type(id))
                            && to.node(other).path("config").equals(from.node(id).path("config"))) {
                        matches.add(other);
                    }
                }
            }
            if (matches.size() == 1) {
                result.add(new Mapping(id, matches.getFirst()));
            }
        }
        return result;
    }

    /** The canonical step of an active id: generated helpers ({@code <step>__<branch>}) belong to their step. */
    static String canonical(StepGraph graph, String id) {
        if (graph.node(id) != null || !id.contains("__")) {
            return id;
        }
        String prefix = id.substring(0, id.indexOf("__"));
        return graph.node(prefix) != null ? prefix : id;
    }

    private static List<JsonNode> references(JsonNode definition, JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        String decisionRef = node.path("config").path("decisionRef").asString("");
        for (JsonNode table : definition.path("decisions")) {
            if (!decisionRef.isEmpty() && decisionRef.equals(table.path("key").asString(""))) {
                result.add(table);
            }
        }
        Set<String> connections = new TreeSet<>();
        connections.add(node.path("config").path("connectionRef").asString(""));
        connections.add(node.path("config").path("fallbackCheck").path("connectionRef").asString(""));
        connections.remove("");
        for (JsonNode dependency : definition.path("dependencies").path("connections")) {
            if (connections.contains(dependency.path("key").asString(""))) {
                result.add(dependency);
            }
        }
        for (JsonNode dependency : definition.path("dependencies")) {
            if (dependency.isObject() && connections.contains(dependency.path("key").asString(""))) {
                result.add(dependency);
            }
        }
        return result;
    }

    static ReleaseCompatibility.Finding finding(String code, String message, String nodeId) {
        return new ReleaseCompatibility.Finding(code, message, nodeId);
    }
}
