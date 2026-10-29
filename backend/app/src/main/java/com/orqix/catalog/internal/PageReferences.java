package com.orqix.catalog.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Authoritative reference checks for an application page (S111): every pinned view version exists in the workspace and
 * suits the page context, request components target a startable workflow with a compatible input schema, every chain
 * step targets a component whose pinned view declares that action, and extension components use an existing version
 * (approved when publishing or routing) within its declared capabilities.
 */
@Component
public class PageReferences {
    private static final Map<String, String> STEP_KIND = Map.of("submit", "request", "saveDraft", "request",
            "claim", "approval", "approve", "approval", "reject", "approval", "saveCaseData", "case");
    private static final Map<String, String> STEP_VIEW_ACTION = Map.of("submit", "submit", "saveDraft", "saveDraft",
            "approve", "approve", "reject", "reject", "saveCaseData", "saveData");

    private final JdbcClient jdbc;
    private final Json json;

    public PageReferences(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Pinned view definitions by component id, after all checks pass. */
    public record Checked(PageDefinition.Parsed page, Map<String, JsonNode> views, Map<String, Extension> extensions) { }

    public record Extension(UUID id, int version, String name, String status, String sha256, JsonNode capabilities,
            int height) { }

    public Checked check(UUID ws, JsonNode definition, boolean publishing) {
        PageDefinition.Parsed page = PageDefinition.validate(definition);
        Map<String, JsonNode> views = new HashMap<>();
        for (PageDefinition.Pin pin : page.views()) {
            JsonNode component = page.components().get(pin.componentId());
            JsonNode view = jdbc.sql("""
                    SELECT v.definition::text FROM application_view_version v
                    JOIN application_view a ON a.workspace_id = v.workspace_id AND a.id = v.view_id
                    WHERE v.workspace_id = :ws AND v.view_id = :id AND v.version = :version
                    """).param("ws", ws).param("id", pin.id()).param("version", pin.version()).query(String.class)
                    .optional().map(json::parse)
                    .orElseThrow(() -> fail(pin.componentId(), "pins a view version that does not exist in this workspace"));
            String kind = view.path("kind").asString();
            switch (kind) {
                case "request" -> request(ws, pin.componentId(), component, view);
                case "approval" -> {
                    requireContext(page, "task", pin.componentId(), "an approval view needs a task page");
                    for (JsonNode section : view.path("sections")) for (JsonNode field : section.path("fields")) {
                        if (!PageDefinition.contextBinding(field.path("binding").asString(), "task")) {
                            throw fail(pin.componentId(), "uses an approval binding that a task page cannot read");
                        }
                    }
                }
                case "case" -> requireContext(page, "case", pin.componentId(), "a case view needs a case page");
                case "worklist" -> { }
                default -> throw fail(pin.componentId(), "cannot embed a " + kind + " view");
            }
            for (JsonNode section : view.path("sections")) for (JsonNode field : section.path("fields")) {
                if (Set.of("file", "row").contains(field.path("type").asString())) {
                    throw fail(pin.componentId(), "uses a file or row control, which pages do not render; use the request page");
                }
            }
            if (!kind.equals("request") && component.has("workflowId")) {
                throw fail(pin.componentId(), "may name a workflow only for a request view");
            }
            views.put(pin.componentId(), view);
        }
        for (var chain : page.chains().values()) {
            for (JsonNode step : chain.path("steps")) {
                String action = step.path("action").asString();
                if (action.equals("navigate")) continue;
                String target = step.path("component").asString();
                JsonNode view = views.get(target);
                String kind = view == null ? "" : view.path("kind").asString();
                if (!STEP_KIND.get(action).equals(kind)) {
                    throw PageDefinition.invalid("Chain " + chain.path("id").asString() + " cannot " + action + " component "
                            + target + ": it needs a " + STEP_KIND.get(action) + " view.");
                }
                String declared = STEP_VIEW_ACTION.get(action);
                if (declared != null && !declares(view, declared)) {
                    throw PageDefinition.invalid("Chain " + chain.path("id").asString() + " step " + action
                            + " targets component " + target + ", whose pinned view has no " + declared + " action.");
                }
            }
        }
        Map<String, Extension> extensions = new HashMap<>();
        for (PageDefinition.Pin pin : page.extensions()) {
            Extension extension = extension(ws, pin.id(), pin.version())
                    .orElseThrow(() -> fail(pin.componentId(), "pins an extension version that does not exist in this workspace"));
            if (publishing ? !extension.status().equals("approved") : Set.of("rejected", "revoked").contains(extension.status())) {
                throw fail(pin.componentId(), publishing ? "uses an extension version that is not approved"
                        : "uses an extension version that was " + extension.status());
            }
            JsonNode component = page.components().get(pin.componentId());
            Set<String> declaredBindings = strings(extension.capabilities().path("bindings"));
            for (JsonNode binding : component.path("bindings")) {
                if (!declaredBindings.contains(binding.asString())) {
                    throw fail(pin.componentId(), "grants binding " + binding.asString() + " that the extension does not declare");
                }
            }
            Set<String> declaredActions = strings(extension.capabilities().path("actions"));
            for (JsonNode chainId : component.path("chains")) {
                for (JsonNode step : page.chains().get(chainId.asString()).path("steps")) {
                    if (!declaredActions.contains(step.path("action").asString())) {
                        throw fail(pin.componentId(), "grants chain " + chainId.asString() + " with action "
                                + step.path("action").asString() + " that the extension does not declare");
                    }
                }
            }
            extensions.put(pin.componentId(), extension);
        }
        return new Checked(page, Map.copyOf(views), Map.copyOf(extensions));
    }

    public java.util.Optional<Extension> extension(UUID ws, UUID id, int version) {
        return jdbc.sql("""
                SELECT e.id, v.version, e.name, v.status, v.sha256, v.capabilities::text AS capabilities, v.height
                FROM ui_extension_version v JOIN ui_extension e ON e.workspace_id = v.workspace_id AND e.id = v.extension_id
                WHERE v.workspace_id = :ws AND v.extension_id = :id AND v.version = :version
                """).param("ws", ws).param("id", id).param("version", version)
                .query((rs, n) -> new Extension(rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("name"),
                        rs.getString("status"), rs.getString("sha256"), json.parse(rs.getString("capabilities")),
                        rs.getInt("height"))).optional();
    }

    private void request(UUID ws, String componentId, JsonNode component, JsonNode view) {
        if (!component.has("workflowId")) throw fail(componentId, "needs a workflow to submit a request view to");
        UUID workflow = UUID.fromString(component.path("workflowId").asString());
        JsonNode released = jdbc.sql("""
                SELECT r.definition::text FROM workflow w JOIN workflow_release r ON r.id = w.active_release_id
                WHERE w.workspace_id = :ws AND w.id = :id AND w.archived_at IS NULL
                """).param("ws", ws).param("id", workflow).query(String.class).optional().map(json::parse)
                .orElseThrow(() -> fail(componentId, "names a workflow without an active release in this workspace"));
        JsonNode schema = released.path("inputSchema");
        JsonNode properties = schema.path("properties");
        Set<String> required = strings(schema.path("required"));
        Set<String> bound = new HashSet<>();
        for (JsonNode section : view.path("sections")) {
            for (JsonNode field : section.path("fields")) {
                String binding = field.path("binding").asString();
                String key = binding.startsWith("request.") ? binding.substring(8) : "";
                JsonNode property = properties.path(key);
                if (key.isEmpty() || property.isMissingNode() || !RequestViewCheck.fieldCompatible(field, property)
                        || required.contains(key) != field.path("required").asBoolean(false)) {
                    throw fail(componentId, "binds " + binding + ", which does not match the workflow input schema");
                }
                bound.add(key);
            }
        }
        if (!bound.containsAll(required)) throw fail(componentId, "leaves required workflow input fields unbound");
    }

    private static boolean declares(JsonNode view, String type) {
        for (JsonNode action : view.path("actions")) if (type.equals(action.path("type").asString())) return true;
        return false;
    }

    private static void requireContext(PageDefinition.Parsed page, String context, String component, String reason) {
        if (!page.context().equals(context)) throw fail(component, "cannot be used here: " + reason);
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static ApiException fail(String component, String reason) {
        return PageDefinition.invalid("Component " + component + " " + reason + ".");
    }
}
