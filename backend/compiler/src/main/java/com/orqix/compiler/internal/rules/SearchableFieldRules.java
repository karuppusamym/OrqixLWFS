package com.orqix.compiler.internal.rules;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * CG-O04 (docs/build/S142): request fields an operator can search by. Only scalar request fields (text without file or
 * time formats, numbers, whole numbers, yes/no and dates) can be searchable, at most {@value #MAX} per workflow. The
 * schema allows {@code searchable} on every scalar field shape (also result forms and row columns); these rules keep
 * it on the request form. Searchability never changes execution, so it does not change the compiled BPMN.
 */
public final class SearchableFieldRules {

    /** Most searchable fields one workflow may declare (the projection stores one row per field and request). */
    public static final int MAX = 10;

    /** Longest text value the projection indexes (run_search_value.value_text). */
    public static final int MAX_INDEXED_TEXT = 200;

    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean");

    private SearchableFieldRules() {
    }

    public static void validate(JsonNode root, Issues issues) {
        JsonNode properties = root.path("inputSchema").path("properties");
        int count = 0;
        for (Map.Entry<String, JsonNode> e : properties.properties()) {
            JsonNode field = e.getValue();
            String base = "/inputSchema/properties/" + e.getKey();
            if (field.path("searchable").asBoolean(false)) {
                count++;
                String type = field.path("type").asString("");
                String format = field.path("format").asString(null);
                String title = "\"" + field.path("title").asString(e.getKey()) + "\"";
                if (!TYPES.contains(type) || "attachment".equals(format) || "time".equals(format)
                        || "date-time".equals(format)) {
                    issues.blocker("WF_SEARCHABLE_FIELD", base + "/searchable", null, null, title
                            + " cannot be searchable. Choose a text, number, whole number, yes/no or date question;"
                            + " files, times of day, date-times and lists are not indexed.");
                } else if ("string".equals(type) && format == null && !field.has("enum")
                        && (!field.has("maxLength") || field.path("maxLength").asInt() > MAX_INDEXED_TEXT)) {
                    issues.warning("WF_SEARCHABLE_LONG_TEXT", base + "/maxLength", null, null, title
                            + " can hold more than " + MAX_INDEXED_TEXT + " characters; longer answers are not"
                            + " searchable. Set a maximum length of " + MAX_INDEXED_TEXT + " or less.");
                }
            }
            nested(field.path("items"), base + "/items", issues);
        }
        if (count > MAX) {
            issues.blocker("WF_SEARCHABLE_LIMIT", "/inputSchema", null, null, "This workflow marks " + count
                    + " request questions as searchable; at most " + MAX + " are allowed.");
        }
        // Anywhere else (step forms, result forms, row columns) a searchable flag would be silently ignored.
        outside(root, "", issues);
    }

    private static void nested(JsonNode node, String path, Issues issues) {
        if (node.isMissingNode() || !node.isObject()) {
            return;
        }
        if (node.path("searchable").asBoolean(false)) {
            issues.blocker("WF_SEARCHABLE_FIELD", path + "/searchable", null, null,
                    "Items of a list and columns of rows cannot be searchable; mark a single request question instead.");
        }
        for (Map.Entry<String, JsonNode> column : node.path("properties").properties()) {
            nested(column.getValue(), path + "/properties/" + column.getKey(), issues);
        }
    }

    private static void outside(JsonNode node, String path, Issues issues) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String child = path + "/" + e.getKey();
                if ("/inputSchema".equals(child)) {
                    continue;
                }
                if ("searchable".equals(e.getKey()) && e.getValue().isBoolean() && node.has("type")
                        && node.has("title")) {
                    issues.blocker("WF_SEARCHABLE_FIELD", child, null, null, "Only questions of the request form"
                            + " can be searchable.");
                    continue;
                }
                outside(e.getValue(), child, issues);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                outside(node.get(i), path + "/" + i, issues);
            }
        }
    }
}
