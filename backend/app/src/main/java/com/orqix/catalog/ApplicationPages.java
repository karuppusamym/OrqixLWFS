package com.orqix.catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.internal.PageDefinition;
import com.orqix.catalog.internal.PageReferences;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;

/**
 * Immutable published application pages (S111) for the page runtime. Loading does not grant access: callers check
 * membership first, and every data read and action is authorized by its own operation.
 */
@Service
public class ApplicationPages {
    public record Extension(UUID id, int version, String name, String status, String sha256, JsonNode capabilities,
            int height) { }

    /** Components and chains by id; pinned views and extension uses by component id. */
    public record Page(UUID id, int version, String title, String context, JsonNode definition,
            Map<String, JsonNode> components, Map<String, JsonNode> chains, Map<String, ApplicationViewQueries.Pinned> views,
            Map<String, Extension> extensions) { }

    private final JdbcClient jdbc;
    private final Json json;
    private final PageReferences references;

    public ApplicationPages(JdbcClient jdbc, Json json, PageReferences references) {
        this.jdbc = jdbc;
        this.json = json;
        this.references = references;
    }

    /** For route assignment: the full publication checks run again, so a revoked extension blocks a new route pin. */
    @Transactional(readOnly = true)
    public Page requireRoutable(UUID ws, UUID id, int version) {
        JsonNode definition = definition(ws, id, version);
        PageReferences.Checked checked = references.check(ws, definition, true);
        Map<String, Extension> extensions = new HashMap<>();
        checked.extensions().forEach((component, e) -> extensions.put(component, extension(e)));
        Map<String, ApplicationViewQueries.Pinned> views = new HashMap<>();
        for (PageDefinition.Pin pin : checked.page().views()) {
            views.put(pin.componentId(), new ApplicationViewQueries.Pinned(pin.id(), pin.version(), checked.views().get(pin.componentId())));
        }
        return new Page(id, version, definition.path("title").asString(), checked.page().context(), definition,
                checked.page().components(), checked.page().chains(), Map.copyOf(views), Map.copyOf(extensions));
    }

    /** For rendering and running a routed page: views are immutable; extension status is read as it is now. */
    @Transactional(readOnly = true)
    public Page load(UUID ws, UUID id, int version) {
        JsonNode definition = definition(ws, id, version);
        PageDefinition.Parsed parsed = PageDefinition.validate(definition);
        Map<String, ApplicationViewQueries.Pinned> views = new HashMap<>();
        for (PageDefinition.Pin pin : parsed.views()) {
            String view = jdbc.sql("SELECT definition::text FROM application_view_version WHERE workspace_id = :ws AND view_id = :id AND version = :version")
                    .param("ws", ws).param("id", pin.id()).param("version", pin.version()).query(String.class).single();
            views.put(pin.componentId(), new ApplicationViewQueries.Pinned(pin.id(), pin.version(), json.parse(view)));
        }
        Map<String, Extension> extensions = new HashMap<>();
        for (PageDefinition.Pin pin : parsed.extensions()) {
            references.extension(ws, pin.id(), pin.version()).ifPresent(e -> extensions.put(pin.componentId(), extension(e)));
        }
        return new Page(id, version, definition.path("title").asString(), parsed.context(), definition,
                parsed.components(), parsed.chains(), Map.copyOf(views), Map.copyOf(extensions));
    }

    /** {@code task.<summary>} and {@code request.<field>} on task pages; {@code case.<field>} on case pages. */
    public static boolean contextBinding(String binding, String context) {
        return PageDefinition.contextBinding(binding, context);
    }

    private JsonNode definition(UUID ws, UUID id, int version) {
        var row = jdbc.sql("""
                SELECT a.kind, v.definition::text FROM application_view_version v
                JOIN application_view a ON a.workspace_id = v.workspace_id AND a.id = v.view_id
                WHERE v.workspace_id = :ws AND v.view_id = :id AND v.version = :version
                """).param("ws", ws).param("id", id).param("version", version)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional()
                .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a published page version from this workspace."));
        if (!row.getKey().equals("page")) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Only application pages can be routed.");
        return json.parse(row.getValue());
    }

    private static Extension extension(PageReferences.Extension e) {
        return new Extension(e.id(), e.version(), e.name(), e.status(), e.sha256(), e.capabilities(), e.height());
    }
}
