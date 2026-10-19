package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.orqix.identity.SubjectService.SubjectRef;
import com.orqix.runtime.api.RuntimeDtos;

import tools.jackson.databind.JsonNode;

/**
 * Derives the "Where is my request" path of a run from what is already stored: the pinned release graph, the run events
 * and the current steps (docs/build/S5-runtime.md section 8, S7 section 4). Pure and cheap: no queries of its own.
 *
 * <p>Events name the step they belong to (an approval is created and decided, a wait starts and ends, a decision,
 * system call or notification completes, a child run starts and ends, the run finishes at an end step). Steps without
 * events of their own (choices and parallel splits and joins) are inferred: they were passed when a step before them
 * was visited and a step after them was visited or is current. The start step is visited with {@code RUN_STARTED}.
 * Routes are "taken" when both ends were visited in order and, for approval routes, the approval ended with that
 * outcome.
 */
public final class RunPathBuilder {

    public record Position(double x, double y) {
    }

    public record Node(String id, String kind, String label, String outcomeKind, String outcome, boolean hasTimeout,
            Position position) {
    }

    public record Transition(String id, String from, String to, String on, String label) {
    }

    public record Diagram(List<Node> nodes, List<Transition> transitions, List<Scope> scopes) {
        public Diagram(List<Node> nodes, List<Transition> transitions) {
            this(nodes, transitions, List.of());
        }
    }

    /**
     * A section or handler of the release (S55). {@code routes} are a section's time-limit and error routes, with ids
     * {@code <scope>__onTimeout} and {@code <scope>__onError} as used in {@code taken}.
     */
    public record Scope(String id, String label, String type, List<String> nodes, String trigger, Boolean interrupting,
            List<Transition> routes) {
    }

    /** One time a section was entered or a handler started (S55); {@code how} is null while it is still open. */
    public record ScopeVisit(String scopeId, Instant startedAt, Instant endedAt, String how) {
    }

    public record Visit(String nodeId, int occurrence, Instant enteredAt, Instant leftAt, String outcome) {
    }

    public record Current(String nodeId, String label, String kind, String status, Instant since, String candidateGroup,
            SubjectRef assignee, Instant dueAt) {
    }

    public record Result(UUID runId, String lifecycle, Diagram diagram, List<Visit> visited, List<Current> current,
            List<String> taken, boolean historyTruncated, List<ScopeVisit> scopeVisits) {
        public Result(UUID runId, String lifecycle, Diagram diagram, List<Visit> visited, List<Current> current,
                List<String> taken) {
            this(runId, lifecycle, diagram, visited, current, taken, false, List.of());
        }

        public Result(UUID runId, String lifecycle, Diagram diagram, List<Visit> visited, List<Current> current,
                List<String> taken, boolean historyTruncated) {
            this(runId, lifecycle, diagram, visited, current, taken, historyTruncated, List.of());
        }
    }

    /** Steps that leave no event of their own. */
    private static final Set<String> SILENT = Set.of("branch", "parallelSplit", "parallelJoin");

    private static final class MutableVisit {
        final String nodeId;
        final int occurrence;
        final Instant enteredAt;
        Instant leftAt;
        String outcome;

        MutableVisit(String nodeId, int occurrence, Instant enteredAt) {
            this.nodeId = nodeId;
            this.occurrence = occurrence;
            this.enteredAt = enteredAt;
        }

        boolean isOpen() {
            return outcome == null;
        }

        Visit freeze() {
            return new Visit(nodeId, occurrence, enteredAt, leftAt, outcome);
        }
    }

    private RunPathBuilder() {
    }

    public static Result build(UUID runId, String lifecycle, JsonNode definition, JsonNode sourceMap,
            List<RunEvents.EventRow> events, List<RuntimeDtos.CurrentStep> currentSteps, String attentionNodeId) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        String startId = null;
        for (JsonNode n : definition.path("nodes")) {
            String id = n.path("id").asString(null);
            if (id == null) {
                continue;
            }
            nodes.put(id, n);
            if (startId == null && "start".equals(n.path("type").asString(null))) {
                startId = id;
            }
        }
        List<Transition> transitions = new ArrayList<>();
        for (JsonNode t : definition.path("transitions")) {
            String from = t.path("from").asString(null);
            String to = t.path("to").asString(null);
            if (from != null && to != null && nodes.containsKey(from) && nodes.containsKey(to)) {
                transitions.add(new Transition(t.path("id").asString(from + "-" + to), from, to,
                        t.path("on").asString("next"), t.path("label").asString(null)));
            }
        }

        Map<String, List<MutableVisit>> visits = new LinkedHashMap<>();
        List<ScopeVisit> scopeVisits = new ArrayList<>();
        Map<String, Integer> openScopes = new HashMap<>();
        Map<String, Instant> childEnded = new HashMap<>();
        Map<String, String> childEndedKind = new HashMap<>();
        for (RunEvents.EventRow e : events) {
            String node = canonical(sourceMap, e.nodeId());
            if (node != null && !nodes.containsKey(node)) {
                node = null;
            }
            Instant at = e.occurredAt();
            switch (e.type()) {
                case "RUN_STARTED" -> {
                    if (startId != null && !visits.containsKey(startId)) {
                        instant(visits, startId, at, "completed");
                    }
                }
                case "TASK_CREATED", "WAIT_STARTED", "SIGNAL_WAIT_STARTED" -> {
                    if (node != null) {
                        open(visits, node, at);
                    }
                }
                case "SIGNAL_RECEIVED" -> {
                    if (node != null) {
                        close(visits, node, at, "completed");
                    }
                }
                case "SIGNAL_SENT", "UNDO_SUCCEEDED", "COMPENSATION_COMPLETED" -> {
                    if (node != null) {
                        instant(visits, node, at, "completed");
                    }
                }
                case "ERROR_RAISED" -> {
                    if (node != null) {
                        instant(visits, node, at, "raised");
                    }
                }
                case "STEP_INTERRUPTED" -> {
                    if (node != null && latestOpen(visits, node) != null) {
                        close(visits, node, at, "interrupted");
                    } else if (node != null) {
                        // An HTTP or undo step that was still running shows as interrupted.
                        instant(visits, node, at, "interrupted");
                    }
                }
                case "SECTION_ENTERED", "HANDLER_STARTED" -> {
                    String scope = e.nodeId();
                    if (scope != null) {
                        openScopes.put(scope, scopeVisits.size());
                        scopeVisits.add(new ScopeVisit(scope, at, null, null));
                    }
                }
                case "SECTION_LEFT" -> {
                    Integer index = e.nodeId() == null ? null : openScopes.remove(e.nodeId());
                    if (index != null) {
                        ScopeVisit v = scopeVisits.get(index);
                        scopeVisits.set(index, new ScopeVisit(v.scopeId(), v.startedAt(), at,
                                e.data().path("how").asString("completed")));
                    }
                }
                case "TASK_DECIDED" -> {
                    if (node != null) {
                        close(visits, node, at, "reject".equals(e.data().path("action").asString(null)) ? "rejected"
                                : "approved");
                    }
                }
                case "TASK_TIMED_OUT" -> {
                    if (node != null) {
                        close(visits, node, at, "timedOut");
                    }
                }
                case "STEP_FAILED" -> {
                    if (node != null) {
                        close(visits, node, at, "failed");
                    }
                }
                case "WAIT_ENDED" -> {
                    if (node != null) {
                        close(visits, node, at, "timedOut".equals(e.data().path("outcome").asString(null)) ? "timedOut" : "completed");
                    }
                }
                case "DECISION_EVALUATED", "EFFECT_SUCCEEDED", "NOTIFICATION_CREATED", "DOCUMENT_GENERATED" -> {
                    if (node != null) {
                        instant(visits, node, at, "completed");
                    }
                }
                case "CHILD_STARTED" -> {
                    if (node != null && latestOpen(visits, node) == null) {
                        open(visits, node, at);
                    }
                }
                case "CHILD_ENDED" -> {
                    if (node != null) {
                        childEnded.put(node, at);
                        childEndedKind.put(node, e.data().path("outcomeKind").asString("success"));
                    }
                }
                case "RUN_COMPLETED" -> {
                    if (node != null) {
                        instant(visits, node, at, "completed");
                    }
                    closeAll(visits, at, "cancelled");
                    closeScopes(scopeVisits, openScopes, at);
                }
                case "RUN_CANCELLED" -> {
                    closeAll(visits, at, "cancelled");
                    closeScopes(scopeVisits, openScopes, at);
                }
                default -> {
                    // not part of the path
                }
            }
        }

        List<Current> current = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();
        String attention = canonical(sourceMap, attentionNodeId);
        for (RuntimeDtos.CurrentStep step : currentSteps) {
            String id = canonical(sourceMap, step.nodeId());
            if (id == null || !nodes.containsKey(id)) {
                continue;
            }
            currentIds.add(id);
            boolean attentionHere = "needsAttention".equals(lifecycle) && id.equals(attention);
            String status = attentionHere ? "needsAttention" : ("http".equals(step.kind()) || "generateDocument".equals(step.kind())
                    || "compensate".equals(step.kind()) || "adapter".equals(step.kind())) ? "running" : "waiting";
            current.add(new Current(id, step.label(), step.kind(), status, step.since(), step.candidateGroup(),
                    step.assignee(), step.dueAt()));
        }

        // A child step ends when its last child run ended and the parent moved on.
        for (Map.Entry<String, Instant> ended : childEnded.entrySet()) {
            MutableVisit open = latestOpen(visits, ended.getKey());
            if (open != null && !currentIds.contains(ended.getKey())) {
                open.leftAt = ended.getValue();
                open.outcome = "cancelled".equals(childEndedKind.get(ended.getKey())) ? "cancelled" : "completed";
            }
        }

        // Steps without events of their own: passed when something before and something after them was reached.
        Map<String, List<String>> before = new HashMap<>();
        Map<String, List<String>> after = new HashMap<>();
        for (Transition t : transitions) {
            before.computeIfAbsent(t.to(), k -> new ArrayList<>()).add(t.from());
            after.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t.to());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
                String id = entry.getKey();
                if (visits.containsKey(id) || !SILENT.contains(entry.getValue().path("type").asString(""))) {
                    continue;
                }
                Instant passedAt = null;
                for (String p : before.getOrDefault(id, List.of())) {
                    List<MutableVisit> pv = visits.get(p);
                    if (pv == null) {
                        continue;
                    }
                    for (MutableVisit v : pv) {
                        Instant when = v.leftAt != null ? v.leftAt : v.enteredAt;
                        if (passedAt == null || when.isAfter(passedAt)) {
                            passedAt = when;
                        }
                    }
                }
                if (passedAt == null) {
                    continue;
                }
                boolean reached = false;
                for (String s : after.getOrDefault(id, List.of())) {
                    if (visits.containsKey(s) || currentIds.contains(s)) {
                        reached = true;
                        break;
                    }
                }
                if (reached) {
                    instant(visits, id, passedAt, "completed");
                    changed = true;
                }
            }
        }

        List<String> taken = new ArrayList<>();
        for (Transition t : transitions) {
            if (isTaken(t, visits, currentIds)) {
                taken.add(t.id());
            }
        }
        List<Scope> scopes = scopes(definition, nodes);
        for (Scope scope : scopes) {
            for (Transition route : scope.routes()) {
                String how = "timedOut".equals(route.on()) ? "timedOut" : "error";
                if (scopeVisits.stream().anyMatch(v -> v.scopeId().equals(scope.id()) && how.equals(v.how()))
                        && (visits.containsKey(route.to()) || currentIds.contains(route.to()))) {
                    taken.add(route.id());
                }
            }
        }

        List<Visit> visited = new ArrayList<>();
        visits.values().forEach(list -> list.forEach(v -> visited.add(v.freeze())));
        visited.sort(Comparator.comparing(Visit::enteredAt));

        List<Node> diagramNodes = new ArrayList<>();
        JsonNode positions = definition.path("uiLayout").path("positions");
        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            JsonNode n = entry.getValue();
            JsonNode config = n.path("config");
            JsonNode p = positions.path(entry.getKey());
            Position position = p.path("x").isNumber() && p.path("y").isNumber()
                    ? new Position(p.path("x").asDouble(), p.path("y").asDouble()) : null;
            boolean end = "end".equals(n.path("type").asString(""));
            diagramNodes.add(new Node(entry.getKey(), n.path("type").asString("step"), n.path("label").asString(entry.getKey()),
                    end ? config.path("outcomeKind").asString(null) : null,
                    end ? config.path("businessOutcome").asString(null) : null,
                    config.has("timeoutAfter") && !config.path("timeoutAfter").isNull(), position));
        }
        return new Result(runId, lifecycle, new Diagram(diagramNodes, transitions, scopes), visited, current, taken,
                false, scopeVisits);
    }

    private static List<Scope> scopes(JsonNode definition, Map<String, JsonNode> nodes) {
        List<Scope> scopes = new ArrayList<>();
        for (JsonNode s : definition.path("subprocesses")) {
            String id = s.path("id").asString(null);
            if (id == null) {
                continue;
            }
            List<String> members = new ArrayList<>();
            s.path("nodes").forEach(n -> {
                if (nodes.containsKey(n.asString(""))) {
                    members.add(n.asString(""));
                }
            });
            boolean event = "event".equals(s.path("type").asString(""));
            List<Transition> routes = new ArrayList<>();
            String onTimeout = s.path("onTimeout").asString(null);
            if (!event && onTimeout != null && nodes.containsKey(onTimeout)) {
                routes.add(new Transition(id + "__onTimeout", id, onTimeout, "timedOut", null));
            }
            String onError = s.path("onError").path("to").asString(null);
            if (!event && onError != null && nodes.containsKey(onError)) {
                routes.add(new Transition(id + "__onError", id, onError, "error", null));
            }
            scopes.add(new Scope(id, s.path("label").asString(id), event ? "event" : "embedded", members,
                    event ? s.path("trigger").path("type").asString(null) : null,
                    event ? s.path("interrupting").asBoolean(false) : null, routes));
        }
        return scopes;
    }

    private static void closeScopes(List<ScopeVisit> scopeVisits, Map<String, Integer> openScopes, Instant at) {
        // Handlers have no event of their own when they end; everything open ends with the request.
        for (int i = 0; i < scopeVisits.size(); i++) {
            ScopeVisit v = scopeVisits.get(i);
            if (v.endedAt() == null) {
                scopeVisits.set(i, new ScopeVisit(v.scopeId(), v.startedAt(), at, "completed"));
            }
        }
        openScopes.clear();
    }

    private static boolean isTaken(Transition t, Map<String, List<MutableVisit>> visits, Set<String> currentIds) {
        List<MutableVisit> from = visits.get(t.from());
        if (from == null) {
            return false;
        }
        boolean outcomeRoute = "approved".equals(t.on()) || "rejected".equals(t.on()) || "timedOut".equals(t.on()) || "failed".equals(t.on());
        for (MutableVisit f : from) {
            if (f.leftAt == null || (outcomeRoute && !t.on().equals(f.outcome))
                    || ("next".equals(t.on()) && ("timedOut".equals(f.outcome) || "cancelled".equals(f.outcome) || "failed".equals(f.outcome)))) {
                continue;
            }
            if (currentIds.contains(t.to())) {
                return true;
            }
            for (MutableVisit v : visits.getOrDefault(t.to(), List.of())) {
                if (!v.enteredAt.isBefore(f.leftAt)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String canonical(JsonNode sourceMap, String id) {
        if (id == null) {
            return null;
        }
        if (sourceMap == null || sourceMap.isMissingNode() || sourceMap.isNull()) {
            return id;
        }
        return sourceMap.path("bpmnToCanonical").path(id).asString(id);
    }

    private static MutableVisit latestOpen(Map<String, List<MutableVisit>> visits, String node) {
        List<MutableVisit> list = visits.get(node);
        if (list == null) {
            return null;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).isOpen()) {
                return list.get(i);
            }
        }
        return null;
    }

    private static void open(Map<String, List<MutableVisit>> visits, String node, Instant at) {
        List<MutableVisit> list = visits.computeIfAbsent(node, k -> new ArrayList<>());
        list.add(new MutableVisit(node, list.size() + 1, at));
    }

    private static void close(Map<String, List<MutableVisit>> visits, String node, Instant at, String outcome) {
        MutableVisit v = latestOpen(visits, node);
        if (v == null) {
            // The opening event was never seen (older run): the step still happened.
            instant(visits, node, at, outcome);
            return;
        }
        v.leftAt = at;
        v.outcome = outcome;
    }

    private static void instant(Map<String, List<MutableVisit>> visits, String node, Instant at, String outcome) {
        List<MutableVisit> list = visits.computeIfAbsent(node, k -> new ArrayList<>());
        MutableVisit v = new MutableVisit(node, list.size() + 1, at);
        v.leftAt = at;
        v.outcome = outcome;
        list.add(v);
    }

    private static void closeAll(Map<String, List<MutableVisit>> visits, Instant at, String outcome) {
        for (List<MutableVisit> list : visits.values()) {
            for (MutableVisit v : list) {
                if (v.isOpen()) {
                    v.leftAt = at;
                    v.outcome = outcome;
                }
            }
        }
    }
}
