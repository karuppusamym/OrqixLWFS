package com.orqix.compiler.internal.rules;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * CG-I05 (docs/build/S151): field classification. Only questions of the request form carry a classification (the
 * schema allows it on every scalar field shape). personal and sensitive answers are masked and sensitive ones encrypted
 * at rest, so they cannot be searchable (the search index stores plain values) or files (a file has its own access
 * rules). A classification never changes execution, so it does not change the compiled BPMN.
 */
public final class FieldClassificationRules {

    /** Classifications whose answers are masked for everyone and revealed only through the audited reveal. */
    public static final Set<String> PROTECTED = Set.of("personal", "sensitive");

    private FieldClassificationRules() {
    }

    public static void validate(JsonNode root, Issues issues) {
        for (Map.Entry<String, JsonNode> e : root.path("inputSchema").path("properties").properties()) {
            JsonNode field = e.getValue();
            String base = "/inputSchema/properties/" + e.getKey();
            String classification = field.path("classification").asString(null);
            String title = "\"" + field.path("title").asString(e.getKey()) + "\"";
            if (classification != null && PROTECTED.contains(classification)) {
                if (field.path("searchable").asBoolean(false)) {
                    issues.blocker("WF_FIELD_CLASSIFICATION", base + "/searchable", null, null, title + " is "
                            + classification + " and cannot be searchable: the search index keeps plain values.");
                }
                if ("attachment".equals(field.path("format").asString(null))) {
                    issues.blocker("WF_FIELD_CLASSIFICATION", base + "/classification", null, null, title
                            + " is a file; files follow their own access rules and cannot be " + classification + ".");
                }
            }
            nested(field.path("items"), base + "/items", issues);
        }
        outside(root, "", issues);
    }

    private static void nested(JsonNode node, String path, Issues issues) {
        if (node.isMissingNode() || !node.isObject()) {
            return;
        }
        if (node.has("classification")) {
            issues.blocker("WF_FIELD_CLASSIFICATION", path + "/classification", null, null,
                    "Items of a list and columns of rows cannot be classified; classify a single request question.");
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
                if ("classification".equals(e.getKey()) && e.getValue().isString() && node.has("type")
                        && node.has("title")) {
                    issues.blocker("WF_FIELD_CLASSIFICATION", child, null, null, "Only questions of the request"
                            + " form can be classified.");
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
