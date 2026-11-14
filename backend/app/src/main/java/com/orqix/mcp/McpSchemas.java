package com.orqix.mcp;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a workflow request form ({@code inputSchema}, the approved JSON Schema subset of workflow-v0) into the input
 * schema of a start tool (JSON Schema 2020-12, as MCP expects). Standard keywords are kept; Orqix-only keywords become
 * description text; file fields ({@code format: attachment}) are left out because a file cannot be uploaded through
 * MCP, and a form with a required file field gets no tool at all.
 */
final class McpSchemas {

    private static final Set<String> COPIED = Set.of("type", "title", "description", "enum", "minLength", "maxLength",
            "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems",
            "uniqueItems", "default");

    private McpSchemas() {
    }

    /** The tool input schema, or empty when the form needs a file. */
    static Optional<ObjectNode> toolInput(Json json, JsonNode formSchema) {
        if (formSchema == null || !formSchema.isObject()) {
            return Optional.of(emptyObject(json));
        }
        return object(json, formSchema);
    }

    private static Optional<ObjectNode> object(Json json, JsonNode schema) {
        ObjectNode out = emptyObject(json);
        if (schema.hasNonNull("title")) {
            out.put("title", schema.get("title").asString());
        }
        if (schema.hasNonNull("description")) {
            out.put("description", schema.get("description").asString());
        }
        ObjectNode props = (ObjectNode) out.get("properties");
        Set<String> required = new java.util.LinkedHashSet<>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        for (Map.Entry<String, JsonNode> e : schema.path("properties").properties()) {
            JsonNode field = e.getValue();
            if (isFile(field)) {
                if (required.contains(e.getKey())) {
                    return Optional.empty();
                }
                required.remove(e.getKey());
                continue;
            }
            Optional<ObjectNode> converted = field(json, field);
            if (converted.isEmpty()) {
                if (required.contains(e.getKey())) {
                    return Optional.empty();
                }
                required.remove(e.getKey());
                continue;
            }
            props.set(e.getKey(), converted.get());
        }
        if (!required.isEmpty()) {
            ArrayNode list = out.putArray("required");
            required.forEach(list::add);
        }
        return Optional.of(out);
    }

    private static Optional<ObjectNode> field(Json json, JsonNode field) {
        String type = field.path("type").asString("");
        if ("object".equals(type)) {
            return object(json, field);
        }
        ObjectNode out = json.object();
        for (Map.Entry<String, JsonNode> e : field.properties()) {
            if (COPIED.contains(e.getKey())) {
                out.set(e.getKey(), e.getValue().deepCopy());
            }
        }
        StringBuilder notes = new StringBuilder(out.path("description").asString(""));
        if ("date".equals(field.path("format").asString(""))) {
            out.put("format", "date");
            note(notes, "A date written YYYY-MM-DD.");
            if (field.hasNonNull("formatMinimum")) {
                note(notes, "Not before " + field.get("formatMinimum").asString() + ".");
            }
            if (field.hasNonNull("formatMaximum")) {
                note(notes, "Not after " + field.get("formatMaximum").asString() + ".");
            }
        }
        if ("array".equals(type)) {
            JsonNode items = field.path("items");
            if (isFile(items)) {
                return Optional.empty();
            }
            Optional<ObjectNode> converted = field(json, items);
            if (converted.isEmpty()) {
                return Optional.empty();
            }
            out.set("items", converted.get());
        }
        if (!notes.isEmpty()) {
            out.put("description", notes.toString());
        }
        return Optional.of(out);
    }

    private static boolean isFile(JsonNode field) {
        return "attachment".equals(field.path("format").asString(""));
    }

    private static void note(StringBuilder notes, String text) {
        if (!notes.isEmpty()) {
            notes.append(' ');
        }
        notes.append(text);
    }

    private static ObjectNode emptyObject(Json json) {
        ObjectNode out = json.object();
        out.put("type", "object");
        out.putObject("properties");
        out.put("additionalProperties", false);
        return out;
    }
}
