package com.orqix.runtime.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Read-only questions about the step graph of a canonical workflow definition (docs/build/S143): what lies ahead of a
 * step, which steps a step reads data from, and where the parallel sections are. Bounded "send back" paths (S138) are
 * left out of reachability so the graph is acyclic.
 */
final class StepGraph {

    /** {@code /steps/<id>/...} field references and {@code steps.<id>.} expression references. */
    private static final Pattern STEP_REF = Pattern.compile("(?:/steps/|\\bsteps\\.)([A-Za-z0-9_-]+)");

    private final JsonNode definition;

    StepGraph(JsonNode definition) {
        this.definition = definition;
    }

    JsonNode definition() {
        return definition;
    }

    JsonNode node(String id) {
        if (id == null) {
            return null;
        }
        for (JsonNode n : definition.path("nodes")) {
            if (id.equals(n.path("id").asString(null))) {
                return n;
            }
        }
        return null;
    }

    String type(String id) {
        JsonNode n = node(id);
        return n == null ? "" : n.path("type").asString("");
    }

    String label(String id) {
        JsonNode n = node(id);
        return n == null ? id : n.path("label").asString(id);
    }

    List<String> nodeIds() {
        List<String> ids = new ArrayList<>();
        for (JsonNode n : definition.path("nodes")) {
            ids.add(n.path("id").asString(""));
        }
        return ids;
    }

    /** Steps reachable from {@code root} along forward routes, {@code root} included. */
    Set<String> reach(String root) {
        return walk(Set.of(root), true);
    }

    /** Steps reachable from any of {@code roots} along forward routes, the roots included. */
    Set<String> reachAll(Set<String> roots) {
        return walk(roots, true);
    }

    /** Steps from which {@code target} can be reached along forward routes, {@code target} included. */
    Set<String> before(String target) {
        return walk(Set.of(target), false);
    }

    private Set<String> walk(Set<String> roots, boolean forward) {
        Set<String> seen = new LinkedHashSet<>(roots);
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (JsonNode t : definition.path("transitions")) {
                if (t.path("maxTraversals").isNumber()) {
                    continue;
                }
                String a = t.path(forward ? "from" : "to").asString("");
                String b = t.path(forward ? "to" : "from").asString("");
                if (current.equals(a) && seen.add(b)) {
                    queue.add(b);
                }
            }
        }
        return seen;
    }

    boolean hasRoute(String from, String to) {
        for (JsonNode t : definition.path("transitions")) {
            if (from.equals(t.path("from").asString("")) && to.equals(t.path("to").asString(""))
                    && !t.path("maxTraversals").isNumber()) {
                return true;
            }
        }
        return false;
    }

    /** Step ids whose data {@code nodeId} reads in its settings (its own outputs excluded). */
    Set<String> reads(String nodeId) {
        JsonNode n = node(nodeId);
        Set<String> refs = n == null ? new TreeSet<>() : refs(n.path("config"));
        refs.remove(nodeId);
        return refs;
    }

    /** Step ids read by the conditions of the routes leaving {@code nodeId}. */
    Set<String> routeReads(String nodeId) {
        Set<String> refs = new TreeSet<>();
        for (JsonNode t : definition.path("transitions")) {
            if (nodeId.equals(t.path("from").asString(""))) {
                refs.addAll(refs(t.path("condition")));
            }
        }
        return refs;
    }

    /** Step ids read by required request outputs (the end of the request fails without them). */
    Set<String> requiredOutputReads() {
        Set<String> refs = new TreeSet<>();
        for (var entry : definition.path("outputMapping").properties()) {
            if (entry.getValue().path("required").asBoolean(false)) {
                refs.addAll(refs(entry.getValue()));
            }
        }
        return refs;
    }

    static Set<String> refs(JsonNode value) {
        Set<String> refs = new TreeSet<>();
        if (value == null || value.isMissingNode() || value.isNull()) {
            return refs;
        }
        Matcher m = STEP_REF.matcher(value.toString());
        while (m.find()) {
            refs.add(m.group(1));
        }
        return refs;
    }

    /** A parallel section: its split, its join, whether it is inclusive, and the steps strictly inside it. */
    record Region(String split, String join, boolean inclusive, Set<String> inside) {
    }

    List<Region> regions() {
        List<Region> regions = new ArrayList<>();
        for (JsonNode n : definition.path("nodes")) {
            if (!"parallelSplit".equals(n.path("type").asString())) {
                continue;
            }
            String split = n.path("id").asString("");
            String join = n.path("config").path("joinRef").asString("");
            Set<String> inside = new TreeSet<>(reach(split));
            inside.retainAll(before(join));
            inside.remove(split);
            inside.remove(join);
            regions.add(new Region(split, join, n.path("config").path("inclusive").asBoolean(false), inside));
        }
        return regions;
    }

    /** Comparable form of a region: the split and join settings, the steps inside and the routes between them. */
    String regionSignature(Region region) {
        StringBuilder b = new StringBuilder();
        Set<String> scope = new TreeSet<>(region.inside());
        scope.add(region.split());
        scope.add(region.join());
        for (String id : scope) {
            JsonNode n = node(id);
            b.append(id).append(':').append(n == null ? "" : n.path("type").toString() + n.path("config")).append('|');
        }
        List<String> flows = new ArrayList<>();
        for (JsonNode t : definition.path("transitions")) {
            String from = t.path("from").asString("");
            String to = t.path("to").asString("");
            if (scope.contains(from) && scope.contains(to)) {
                flows.add(from + ">" + to + ":" + t.path("on").asString("") + t.path("condition"));
            }
        }
        // Routes entering the join from outside count too: the join waits for every incoming route.
        for (JsonNode t : definition.path("transitions")) {
            if (region.join().equals(t.path("to").asString("")) && !scope.contains(t.path("from").asString(""))) {
                flows.add("in:" + t.path("from").asString(""));
            }
        }
        flows.sort(null);
        return b.append(flows).toString();
    }

    /** Whether the definition has any multi-instance, per-item or for-each setting on {@code nodeId}. */
    String repetition(String nodeId) {
        JsonNode config = node(nodeId) == null ? null : node(nodeId).path("config");
        if (config == null) {
            return null;
        }
        for (String key : List.of("multiInstance", "perItem", "forEach")) {
            if (!config.path(key).isMissingNode() && !config.path(key).isNull()) {
                return key;
            }
        }
        return null;
    }
}
