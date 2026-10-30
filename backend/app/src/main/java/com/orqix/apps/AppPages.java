package com.orqix.apps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.cases.CaseService;
import com.orqix.catalog.ApplicationPages;
import com.orqix.catalog.ApplicationViewQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.PageActions;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Page routes, the routed page payload, saved drafts and server-filtered extension data (S111). */
@Service
public class AppPages {
    static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{0,38}[a-z0-9]");
    static final Pattern CONTEXT = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public record Route(String slug, int revision, String title, ApplicationViewQueries.Pin page, Instant changedAt) { }
    public record RouteList(List<Route> items, boolean canAssign) { }
    public record Assign(Integer expectedRevision, ApplicationViewQueries.Pin viewRef) { }
    public record HistoryItem(int revision, ApplicationViewQueries.Pin page, UUID changedBy, Instant changedAt) { }
    public record History(List<HistoryItem> items) { }
    public record PageBody(UUID id, int version, JsonNode definition) { }
    public record ExtensionUse(UUID id, int version, String name, String status, String sha256, JsonNode capabilities,
            int height) { }
    public record Grants(List<UUID> startWorkflows, boolean taskAct, boolean readCases) { }
    public record Runtime(String slug, int revision, PageBody page, List<ApplicationViewQueries.Pinned> views,
            List<ExtensionUse> extensions, Grants grants) { }
    public record Draft(String componentId, JsonNode values, Instant savedAt) { }
    public record Drafts(List<Draft> items) { }
    public record ComponentData(JsonNode values) { }

    /** The route row as stored; {@code page} is null after the route was removed. */
    record Stored(int revision, ApplicationViewQueries.Pin page) { }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final ApplicationPages pages;
    private final PageActions actions;
    private final CaseService cases;
    private final Json json;
    private final AuditWriter audit;

    public AppPages(JdbcClient jdbc, PolicyService policy, ApplicationPages pages, PageActions actions, CaseService cases,
            Json json, AuditWriter audit) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.pages = pages;
        this.actions = actions;
        this.cases = cases;
        this.json = json;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public RouteList list(Caller caller, UUID ws) {
        WorkspaceAccess access = policy.requireMember(caller, ws);
        boolean designer = access.has(Permission.WORKFLOW_READ);
        List<Route> routes = jdbc.sql("""
                SELECT slug, revision, title, view_id, view_version, changed_at FROM application_page_route
                WHERE workspace_id = :ws ORDER BY slug
                """).param("ws", ws).query((rs, n) -> new Route(rs.getString("slug"), rs.getInt("revision"),
                        rs.getString("title"), rs.getObject("view_id") == null ? null
                                : new ApplicationViewQueries.Pin(rs.getObject("view_id", UUID.class), rs.getInt("view_version")),
                        Sql.instant(rs, "changed_at"))).list();
        return new RouteList(routes.stream().filter(r -> designer || r.page() != null).toList(),
                access.has(Permission.WORKFLOW_PUBLISH));
    }

    @Transactional
    public Route assign(Caller caller, UUID ws, String slug, Assign request) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_PUBLISH, "assignAppPage", true);
        slug(slug);
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Supply the route revision you loaded.");
        }
        ApplicationPages.Page page = request.viewRef() == null ? null
                : pages.requireRoutable(ws, request.viewRef().id(), request.viewRef().version());
        Stored current = stored(ws, slug, true);
        int revision = current == null ? 0 : current.revision();
        if (revision != request.expectedRevision()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The route changed. Reload before pinning another version.")
                    .with("currentRevision", revision);
        }
        if (page == null && (current == null || current.page() == null)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This route has no page to remove.");
        }
        int next = Math.addExact(revision, 1);
        UUID viewId = page == null ? null : page.id();
        Integer version = page == null ? null : page.version();
        String title = page == null ? null : page.title();
        try {
            if (current == null) {
                jdbc.sql("""
                        INSERT INTO application_page_route (workspace_id, slug, revision, view_id, view_version, title, changed_by)
                        VALUES (:ws, :slug, :revision, :id, :version, :title, :by)
                        """).param("ws", ws).param("slug", slug).param("revision", next).param("id", viewId)
                        .param("version", version).param("title", title).param("by", caller.subjectId()).update();
            } else {
                jdbc.sql("""
                        UPDATE application_page_route SET revision = :revision, view_id = :id, view_version = :version,
                        title = :title, changed_by = :by, changed_at = now() WHERE workspace_id = :ws AND slug = :slug
                        """).param("ws", ws).param("slug", slug).param("revision", next).param("id", viewId)
                        .param("version", version).param("title", title).param("by", caller.subjectId()).update();
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The route changed. Reload before pinning another version.");
        }
        jdbc.sql("""
                INSERT INTO application_page_route_history (workspace_id, slug, revision, view_id, view_version, changed_by)
                VALUES (:ws, :slug, :revision, :id, :version, :by)
                """).param("ws", ws).param("slug", slug).param("revision", next).param("id", viewId).param("version", version)
                .param("by", caller.subjectId()).update();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("slug", slug);
        metadata.put("revision", next);
        if (version != null) metadata.put("version", version);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "page.route-assigned",
                "applicationPageRoute", slug, AuditWriter.Outcome.SUCCESS, metadata));
        return route(ws, slug);
    }

    @Transactional(readOnly = true)
    public History history(Caller caller, UUID ws, String slug) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_READ, "listAppPageHistory", false);
        slug(slug);
        if (stored(ws, slug, false) == null) throw ApiException.notFound();
        return new History(jdbc.sql("""
                SELECT revision, view_id, view_version, changed_by, changed_at FROM application_page_route_history
                WHERE workspace_id = :ws AND slug = :slug ORDER BY revision DESC
                """).param("ws", ws).param("slug", slug).query((rs, n) -> new HistoryItem(rs.getInt("revision"),
                        rs.getObject("view_id") == null ? null
                                : new ApplicationViewQueries.Pin(rs.getObject("view_id", UUID.class), rs.getInt("view_version")),
                        rs.getObject("changed_by", UUID.class), Sql.instant(rs, "changed_at"))).list());
    }

    @Transactional(readOnly = true)
    public Runtime runtime(Caller caller, UUID ws, String slug) {
        WorkspaceAccess access = policy.requireMember(caller, ws);
        Routed routed = routed(ws, slug);
        ApplicationPages.Page page = routed.page();
        Map<String, ApplicationViewQueries.Pinned> views = new LinkedHashMap<>();
        page.views().values().forEach(v -> views.putIfAbsent(v.id() + ":" + v.version(), v));
        Map<String, ExtensionUse> extensions = new LinkedHashMap<>();
        page.extensions().values().forEach(e -> extensions.putIfAbsent(e.id() + ":" + e.version(), new ExtensionUse(e.id(),
                e.version(), e.name(), e.status(), e.sha256(), e.capabilities(), e.height())));
        List<UUID> start = new ArrayList<>();
        for (JsonNode component : page.components().values()) {
            if (component.has("workflowId")) {
                UUID workflow = UUID.fromString(component.path("workflowId").asString());
                if (access.canStart(workflow) && !start.contains(workflow)) start.add(workflow);
            }
        }
        boolean readCases = access.has(Permission.TASK_ACT) || access.has(Permission.RUN_READ_DATA) || access.canStartAnything();
        return new Runtime(slug, routed.revision(), new PageBody(page.id(), page.version(), page.definition()),
                List.copyOf(views.values()), List.copyOf(extensions.values()),
                new Grants(start, access.has(Permission.TASK_ACT), readCases));
    }

    @Transactional(readOnly = true)
    public Drafts drafts(Caller caller, UUID ws, String slug) {
        policy.requireMember(caller, ws);
        Routed routed = routed(ws, slug);
        return new Drafts(jdbc.sql("""
                SELECT component_id, draft_values::text AS draft_values, saved_at FROM application_page_draft
                WHERE workspace_id = :ws AND view_id = :page AND subject_id = :me ORDER BY component_id
                """).param("ws", ws).param("page", routed.page().id()).param("me", caller.subjectId())
                .query((rs, n) -> new Draft(rs.getString("component_id"), json.parse(rs.getString("draft_values")),
                        Sql.instant(rs, "saved_at"))).list().stream()
                .filter(d -> routed.page().views().containsKey(d.componentId())).toList());
    }

    /** Only the bindings granted to this extension component, read with the caller's own task or case access. */
    public ComponentData componentData(Caller caller, UUID ws, String slug, String componentId, String context) {
        policy.requireMember(caller, ws);
        Routed routed = routed(ws, slug);
        ApplicationPages.Page page = routed.page();
        JsonNode component = page.components().get(componentId);
        if (component == null || !"extension".equals(component.path("type").asString())) throw ApiException.notFound();
        ApplicationPages.Extension extension = page.extensions().get(componentId);
        if (extension == null || !extension.status().equals("approved")) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This extension version is no longer approved.");
        }
        ObjectNode values = json.object();
        if (component.path("bindings").isEmpty() || page.context().equals("none")) return new ComponentData(values);
        if (context == null || !CONTEXT.matcher(context).matches()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Open the page for a " + page.context() + ".");
        }
        if (page.context().equals("task")) {
            PageActions.Task task = actions.task(caller, ws, context);
            for (JsonNode binding : component.path("bindings")) {
                String name = binding.asString();
                String field = name.substring(name.indexOf('.') + 1);
                if (name.startsWith("task.")) values.set(name, json.toTree(task.summary().get(field)));
                else if (task.requestInput() != null && task.requestInput().has(field)) values.set(name, task.requestInput().get(field));
            }
        } else {
            CaseService.CaseData data = cases.data(caller, ws, uuid(context));
            for (JsonNode binding : component.path("bindings")) {
                String name = binding.asString();
                String field = name.substring(name.indexOf('.') + 1);
                if (data.values() != null && data.values().has(field)) values.set(name, data.values().get(field));
            }
        }
        return new ComponentData(values);
    }

    record Routed(int revision, ApplicationPages.Page page) { }

    Routed routed(UUID ws, String slug) {
        slug(slug);
        Stored stored = stored(ws, slug, false);
        if (stored == null || stored.page() == null) throw ApiException.notFound();
        return new Routed(stored.revision(), pages.load(ws, stored.page().id(), stored.page().version()));
    }

    private Stored stored(UUID ws, String slug, boolean lock) {
        return jdbc.sql("SELECT revision, view_id, view_version FROM application_page_route WHERE workspace_id = :ws AND slug = :slug"
                + (lock ? " FOR UPDATE" : "")).param("ws", ws).param("slug", slug)
                .query((rs, n) -> new Stored(rs.getInt("revision"), rs.getObject("view_id") == null ? null
                        : new ApplicationViewQueries.Pin(rs.getObject("view_id", UUID.class), rs.getInt("view_version"))))
                .optional().orElse(null);
    }

    private Route route(UUID ws, String slug) {
        return jdbc.sql("""
                SELECT slug, revision, title, view_id, view_version, changed_at FROM application_page_route
                WHERE workspace_id = :ws AND slug = :slug
                """).param("ws", ws).param("slug", slug).query((rs, n) -> new Route(rs.getString("slug"), rs.getInt("revision"),
                        rs.getString("title"), rs.getObject("view_id") == null ? null
                                : new ApplicationViewQueries.Pin(rs.getObject("view_id", UUID.class), rs.getInt("view_version")),
                        Sql.instant(rs, "changed_at"))).single();
    }

    static void slug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) throw ApiException.notFound();
    }

    static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound();
        }
    }
}
