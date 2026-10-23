package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;

/** The bounded, data-only UI02 view format. No browser code, expressions or external actions are accepted. */
final class ViewDefinition {
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern BINDING = Pattern.compile("(?:request|task|case)\\.[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern ROUTE = Pattern.compile("/(?!/)[A-Za-z0-9/_-]{1,199}");
    private static final Set<String> FIELD_TYPES = Set.of("text", "number", "date", "choice", "file", "row", "boolean");
    private static final Set<String> REQUEST_ACTIONS = Set.of("submit", "saveDraft", "navigate");
    private static final Set<String> APPROVAL_ACTIONS = Set.of("approve", "reject", "requestCorrection", "navigate");

    private ViewDefinition() { }

    static String validate(Json json, JsonNode node) {
        if (node == null || !node.isObject() || json.write(node).getBytes(StandardCharsets.UTF_8).length > 32_768) {
            fail("definition", "must be a JSON object of at most 32 KiB");
        }
        keys(node, "definition", Set.of("schemaVersion", "kind", "sections", "actions"));
        if (!node.path("schemaVersion").isIntegralNumber() || node.path("schemaVersion").asInt() != 1) {
            fail("schemaVersion", "must be 1");
        }
        String kind = string(node, "kind", 20, "definition");
        if (!kind.equals("request") && !kind.equals("approval") && !kind.equals("case")) fail("kind", "must be request, approval or case");
        JsonNode sections = node.path("sections");
        if (!sections.isArray() || sections.isEmpty() || sections.size() > 20) fail("sections", "must contain 1 to 20 sections");
        Set<String> ids = new HashSet<>();
        Set<String> bindings = new HashSet<>();
        int fieldCount = 0;
        for (int i = 0; i < sections.size(); i++) {
            JsonNode section = sections.get(i);
            String path = "sections/" + i;
            object(section, path);
            keys(section, path, Set.of("id", "label", "columns", "fields"));
            uniqueId(section, path, ids);
            string(section, "label", 120, path);
            if (!section.path("columns").isIntegralNumber() ||
                    (section.path("columns").asInt() != 1 && section.path("columns").asInt() != 2)) {
                fail(path + "/columns", "must be 1 or 2");
            }
            JsonNode fields = section.path("fields");
            if (!fields.isArray() || fields.isEmpty() || fields.size() > 50) fail(path + "/fields", "must contain 1 to 50 fields");
            fieldCount += fields.size();
            if (fieldCount > 200) fail("sections", "may contain at most 200 fields");
            for (int j = 0; j < fields.size(); j++) {
                JsonNode field = fields.get(j);
                String fp = path + "/fields/" + j;
                object(field, fp);
                keys(field, fp, Set.of("id", "label", "type", "binding", "required", "options"));
                uniqueId(field, fp, ids);
                string(field, "label", 120, fp);
                String type = string(field, "type", 20, fp);
                if (!FIELD_TYPES.contains(type) || type.equals("boolean") && !kind.equals("case")) fail(fp + "/type", "is not a supported control");
                String binding = string(field, "binding", 72, fp);
                if (!BINDING.matcher(binding).matches() || (kind.equals("request") && !binding.startsWith("request.")) || kind.equals("approval") && binding.startsWith("case.") || kind.equals("case") && !binding.startsWith("case.")) {
                    fail(fp + "/binding", "must address a supported data field");
                }
                if (!bindings.add(binding)) fail(fp + "/binding", "is duplicated");
                JsonNode required = field.get("required");
                if (required != null && !required.isBoolean()) fail(fp + "/required", "must be boolean");
                JsonNode options = field.get("options");
                if (type.equals("choice")) {
                    if (options == null || !options.isArray() || options.isEmpty() || options.size() > 100) {
                        fail(fp + "/options", "choice needs 1 to 100 options");
                    }
                    Set<String> values = new HashSet<>();
                    for (int k = 0; k < options.size(); k++) {
                        JsonNode option = options.get(k);
                        String op = fp + "/options/" + k;
                        object(option, op);
                        keys(option, op, Set.of("value", "label"));
                        String value = string(option, "value", 80, op);
                        string(option, "label", 120, op);
                        if (!values.add(value)) fail(op + "/value", "is duplicated");
                    }
                } else if (options != null) {
                    fail(fp + "/options", "is only available for choice controls");
                }
            }
        }
        JsonNode actions = node.path("actions");
        if (!actions.isArray() || actions.size() > 10) fail("actions", "must contain at most 10 actions");
        for (int i = 0; i < actions.size(); i++) {
            JsonNode action = actions.get(i);
            String path = "actions/" + i;
            object(action, path);
            keys(action, path, Set.of("id", "label", "type", "target"));
            uniqueId(action, path, ids);
            string(action, "label", 80, path);
            String type = string(action, "type", 30, path);
            if (!(kind.equals("request") ? REQUEST_ACTIONS : kind.equals("case") ? Set.of("saveData", "navigate") : APPROVAL_ACTIONS).contains(type)) {
                fail(path + "/type", "is not allowed for this view kind");
            }
            if (type.equals("navigate")) {
                String target = string(action, "target", 200, path);
                if (!ROUTE.matcher(target).matches() || target.contains("..")) fail(path + "/target", "must be a local route");
            } else if (action.has("target")) {
                fail(path + "/target", "is only allowed for navigation");
            }
        }
        return kind;
    }

    private static void object(JsonNode node, String path) {
        if (node == null || !node.isObject()) fail(path, "must be an object");
    }

    private static void keys(JsonNode node, String path, Set<String> allowed) {
        for (String name : node.propertyNames()) if (!allowed.contains(name)) fail(path + "/" + name, "is not supported");
    }

    private static void uniqueId(JsonNode node, String path, Set<String> ids) {
        String id = string(node, "id", 64, path);
        if (!ID.matcher(id).matches()) fail(path + "/id", "must be an identifier");
        if (!ids.add(id)) fail(path + "/id", "is duplicated");
    }

    private static String string(JsonNode node, String key, int max, String path) {
        JsonNode value = node.path(key);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > max ||
                !value.asString().equals(value.asString().trim())) fail(path + "/" + key, "must be nonblank trimmed text");
        return value.asString();
    }

    private static void fail(String path, String reason) {
        throw new ApiException(ProblemCode.VALIDATION_FAILED, path + " " + reason + ".");
    }
}
