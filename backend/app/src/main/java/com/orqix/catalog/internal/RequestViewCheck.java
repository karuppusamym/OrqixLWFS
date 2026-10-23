package com.orqix.catalog.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;

/** Publication checks for an exact, immutable request-view layout pin. Runtime uses the release's copied layout. */
@Component
public class RequestViewCheck {
    private final JdbcClient jdbc;
    private final Json json;
    public RequestViewCheck(JdbcClient jdbc, Json json) { this.jdbc = jdbc; this.json = json; }

    public List<ValidationIssue> issues(UUID workspace, JsonNode workflow) {
        var pin = workflow.path("inputUi").path("viewRef");
        if (pin.isMissingNode()) return workflow.path("inputUi").has("viewDefinition")
                ? List.of(issue(AssetReferenceCheck.INVALID,"Copied request presentation requires an exact published view pin.")) : List.of();
        if (!pin.isObject() || !pin.path("id").isString() || !pin.path("version").isIntegralNumber()
                || !pin.path("version").canConvertToInt() || pin.path("version").asInt() < 1)
            return List.of(issue(AssetReferenceCheck.INVALID, "The request view pin is invalid."));
        try {
            UUID id = UUID.fromString(pin.path("id").asString());
            int version = pin.path("version").asInt();
            var found = jdbc.sql("""
                    SELECT v.definition::text,a.published_version FROM application_view_version v
                    JOIN application_view a ON a.workspace_id=v.workspace_id AND a.id=v.view_id
                    WHERE v.workspace_id=:ws AND v.view_id=:id AND v.version=:version
                    """).param("ws",workspace).param("id",id).param("version",version)
                    .query((rs,n) -> java.util.Map.entry(rs.getString(1),rs.getInt(2))).optional();
            if (found.isEmpty()) return List.of(issue(AssetReferenceCheck.MISSING, "The pinned request view version does not exist in this workspace."));
            var definition = json.parse(found.get().getKey());
            var problems = matches(workflow, definition);
            if (!problems.isEmpty()) return List.of(issue(AssetReferenceCheck.DIVERGED, problems.getFirst()));
            if (found.get().getValue() > version) return List.of(new ValidationIssue(AssetReferenceCheck.STALE, Severity.WARNING,
                    "A newer request view version is available. Publishing retains version " + version + ".",
                    "/inputUi/viewRef",null,null));
            return List.of();
        } catch (IllegalArgumentException ex) {
            return List.of(issue(AssetReferenceCheck.INVALID, "The request view pin is invalid."));
        }
    }

    /** Returns incompatibilities without changing input validation, conditions, routing or action permissions. */
    List<String> matches(JsonNode workflow, JsonNode view) {
        if (!"request".equals(view.path("kind").asString())) return List.of("Only request views may bind to a request form.");
        if (view.path("sections").size() > 10) return List.of("A request form supports at most ten view sections.");
        var actions = view.path("actions");
        if (actions.size() > 1 || (actions.size() == 1 && !"submit".equals(actions.get(0).path("type").asString())))
            return List.of("This request view uses actions that are not supported by the request form.");
        var schema = workflow.path("inputSchema");
        var properties = schema.path("properties");
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(value -> required.add(value.asString()));
        Set<String> bound = new HashSet<>();
        var order = json.mapper().createArrayNode();
        var sections = json.mapper().createArrayNode();
        var widgets = json.object();
        for (var section : view.path("sections")) {
            var projected = sections.addObject().put("id",section.path("id").asString())
                    .put("title",section.path("label").asString()).put("columns",section.path("columns").asInt());
            var fields = projected.putArray("fields");
            for (var field : section.path("fields")) {
                String binding = field.path("binding").asString();
                if (!binding.startsWith("request.")) return List.of("A request view contains an incompatible binding.");
                String key = binding.substring(8);
                var property = properties.path(key);
                String type = field.path("type").asString();
                boolean compatible = fieldCompatible(field, property);
                if (!compatible || !bound.add(key) || required.contains(key) != field.path("required").asBoolean(false))
                    return List.of("The request view's field types, required fields or bindings do not match the workflow input schema.");
                if (!field.path("label").equals(property.path("title"))) return List.of("Request field labels differ from the pinned view.");
                order.add(key); fields.add(key);
                if (Set.of("text","number","choice").contains(type)) widgets.put(key, type.equals("choice") ? "select" : type);
            }
        }
        if (!bound.equals(new HashSet<>(properties.propertyNames()))) return List.of("Every workflow input field must have exactly one view binding.");
        var ui = workflow.path("inputUi");
        if((view.has("presentation") || ui.has("viewDefinition")) && !view.equals(ui.path("viewDefinition")))
            return List.of("The copied request presentation differs from its pinned view. Reapply that version.");
        String submit = actions.isEmpty() ? "" : actions.get(0).path("label").asString();
        if (!order.equals(ui.path("order")) || !sections.equals(ui.path("layout").path("sections"))
                || !widgets.equals(ui.path("widgets")) || !submit.equals(ui.path("submitLabel").asString("")))
            return List.of("The request form layout or submit action differs from its pinned view. Reapply the view or detach it before editing.");
        return List.of();
    }
    static boolean fieldCompatible(JsonNode field, JsonNode property) {
        return switch (field.path("type").asString()) {
            case "text" -> "string".equals(property.path("type").asString()) && !property.has("format") && !property.has("enum");
            case "number" -> Set.of("number", "integer").contains(property.path("type").asString());
            case "date" -> "string".equals(property.path("type").asString()) && "date".equals(property.path("format").asString());
            case "file" -> "string".equals(property.path("type").asString()) && "attachment".equals(property.path("format").asString());
            case "row" -> "array".equals(property.path("type").asString()) && "object".equals(property.path("items").path("type").asString());
            case "choice" -> choiceMatches(field, property);
            default -> false;
        };
    }
    private static boolean choiceMatches(JsonNode field, JsonNode property) {
        if (!"string".equals(property.path("type").asString()) || !property.path("enum").isArray()) return false;
        List<String> values = new ArrayList<>();
        for (var option : field.path("options")) {
            if (!option.path("label").equals(option.path("value"))) return false;
            values.add(option.path("value").asString());
        }
        List<String> expected = new ArrayList<>();
        property.path("enum").forEach(value -> expected.add(value.asString()));
        return expected.equals(values);
    }
    private static ValidationIssue issue(String code, String message) {
        return new ValidationIssue(code,Severity.BLOCKER,message,"/inputUi/viewRef",null,null);
    }
}
