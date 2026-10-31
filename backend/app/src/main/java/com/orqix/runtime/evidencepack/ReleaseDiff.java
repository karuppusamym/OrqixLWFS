package com.orqix.runtime.evidencepack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

/**
 * Server-side change summary between two canonical workflow definitions, for the evidence pack. It mirrors the areas
 * of the studio's version comparison (steps, routes, rule tables, forms, settings) at summary level: what was added,
 * removed or changed, never field values. {@code uiLayout} and {@code metadata} are ignored (no behavior).
 */
public final class ReleaseDiff {

    /** One change line. */
    public record Change(String kind, String area, String title) {
    }

    private static final Set<String> IGNORED = Set.of("uiLayout", "metadata", "nodes", "transitions", "decisions",
            "inputSchema", "inputUi", "outputSchema", "outputMapping");

    private ReleaseDiff() {
    }

    public static List<Change> compare(JsonNode before, JsonNode after) {
        List<Change> changes = new ArrayList<>();
        Map<String, JsonNode> oldNodes = byKey(before.path("nodes"), "id");
        Map<String, JsonNode> newNodes = byKey(after.path("nodes"), "id");
        for (Map.Entry<String, JsonNode> entry : newNodes.entrySet()) {
            JsonNode node = entry.getValue();
            JsonNode old = oldNodes.get(entry.getKey());
            String name = label(node, entry.getKey());
            if (old == null) {
                changes.add(new Change("added", "step", "Step added: " + name + " (" + node.path("type").asString() + ")"));
            } else if (!old.equals(node)) {
                List<String> what = new ArrayList<>();
                if (!Objects.equals(old.path("label").asString(""), node.path("label").asString(""))) {
                    what.add("renamed from \"" + label(old, entry.getKey()) + "\"");
                }
                if (!old.path("type").equals(node.path("type"))) {
                    what.add("type " + old.path("type").asString() + " -> " + node.path("type").asString());
                }
                Set<String> keys = new TreeSet<>();
                old.path("config").propertyNames().forEach(keys::add);
                node.path("config").propertyNames().forEach(keys::add);
                List<String> settings = new ArrayList<>();
                for (String key : keys) {
                    if (!old.path("config").path(key).equals(node.path("config").path(key))) {
                        settings.add(key);
                    }
                }
                if (!settings.isEmpty()) {
                    what.add("settings changed: " + String.join(", ", settings));
                }
                if (what.isEmpty()) {
                    what.add("details changed");
                }
                changes.add(new Change("changed", "step", "Step changed: " + name + " (" + String.join("; ", what) + ")"));
            }
        }
        for (Map.Entry<String, JsonNode> entry : oldNodes.entrySet()) {
            if (!newNodes.containsKey(entry.getKey())) {
                changes.add(new Change("removed", "step", "Step removed: " + label(entry.getValue(), entry.getKey())));
            }
        }

        Map<String, JsonNode> oldRoutes = byKey(before.path("transitions"), "id");
        Map<String, JsonNode> newRoutes = byKey(after.path("transitions"), "id");
        for (Map.Entry<String, JsonNode> entry : newRoutes.entrySet()) {
            JsonNode old = oldRoutes.get(entry.getKey());
            if (old == null) {
                changes.add(new Change("added", "route", "Route added: " + route(after, entry.getValue())));
            } else if (!old.equals(entry.getValue())) {
                changes.add(new Change("changed", "route", "Route changed: " + route(after, entry.getValue())));
            }
        }
        for (Map.Entry<String, JsonNode> entry : oldRoutes.entrySet()) {
            if (!newRoutes.containsKey(entry.getKey())) {
                changes.add(new Change("removed", "route", "Route removed: " + route(before, entry.getValue())));
            }
        }

        Map<String, JsonNode> oldRules = byKey(before.path("decisions"), "key");
        Map<String, JsonNode> newRules = byKey(after.path("decisions"), "key");
        for (Map.Entry<String, JsonNode> entry : newRules.entrySet()) {
            JsonNode old = oldRules.get(entry.getKey());
            int rows = entry.getValue().path("rows").size();
            if (old == null) {
                changes.add(new Change("added", "rule", "Rule table added: " + entry.getKey() + " (" + rows + " rows)"));
            } else if (!old.equals(entry.getValue())) {
                changes.add(new Change("changed", "rule", "Rule table changed: " + entry.getKey() + " ("
                        + old.path("rows").size() + " -> " + rows + " rows)"));
            }
        }
        for (String key : oldRules.keySet()) {
            if (!newRules.containsKey(key)) {
                changes.add(new Change("removed", "rule", "Rule table removed: " + key));
            }
        }

        if (!before.path("inputSchema").equals(after.path("inputSchema"))
                || !before.path("inputUi").equals(after.path("inputUi"))) {
            Set<String> oldFields = fieldNames(before.path("inputSchema"));
            Set<String> newFields = fieldNames(after.path("inputSchema"));
            List<String> what = new ArrayList<>();
            newFields.stream().filter(f -> !oldFields.contains(f)).forEach(f -> what.add("+" + f));
            oldFields.stream().filter(f -> !newFields.contains(f)).forEach(f -> what.add("-" + f));
            changes.add(new Change("changed", "form", "Request form changed"
                    + (what.isEmpty() ? " (field rules or layout)" : " (fields " + String.join(", ", what) + ")")));
        }
        if (!before.path("outputSchema").equals(after.path("outputSchema"))
                || !before.path("outputMapping").equals(after.path("outputMapping"))) {
            changes.add(new Change("changed", "form", "Result fields changed"));
        }

        Set<String> keys = new TreeSet<>();
        before.propertyNames().forEach(keys::add);
        after.propertyNames().forEach(keys::add);
        for (String key : keys) {
            if (!IGNORED.contains(key) && !before.path(key).equals(after.path(key))) {
                String kind = before.path(key).isMissingNode() ? "added" : after.path(key).isMissingNode() ? "removed" : "changed";
                changes.add(new Change(kind, "settings", "Workflow setting " + kind + ": " + key));
            }
        }
        return changes;
    }

    private static Map<String, JsonNode> byKey(JsonNode array, String key) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode item : array) {
            result.put(item.path(key).asString(""), item);
        }
        return result;
    }

    private static String label(JsonNode node, String fallback) {
        String label = node.path("label").asString("");
        return label.isBlank() ? fallback : label;
    }

    private static String route(JsonNode definition, JsonNode transition) {
        Map<String, JsonNode> nodes = byKey(definition.path("nodes"), "id");
        String from = transition.path("from").asString("");
        String to = transition.path("to").asString("");
        String on = transition.path("on").asString("next");
        return (nodes.containsKey(from) ? label(nodes.get(from), from) : from) + " -> "
                + (nodes.containsKey(to) ? label(nodes.get(to), to) : to) + ("next".equals(on) ? "" : " (" + on + ")");
    }

    private static Set<String> fieldNames(JsonNode schema) {
        Set<String> names = new TreeSet<>();
        schema.path("properties").propertyNames().forEach(names::add);
        return names;
    }
}
