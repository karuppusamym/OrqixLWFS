package com.orqix.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Workspace view library. Drafts are revisioned; published snapshots are append-only. */
@Service
public class ApplicationViews {
    public static final int MAX_VIEWS = 200;
    public static final int MAX_VERSIONS = 100;

    public record Summary(UUID id, String name, String description, String kind, int draftRevision,
            Integer publishedVersion, Instant createdAt, Instant updatedAt) { }
    public record Version(int version, Instant publishedAt) { }
    public record Detail(UUID id, String name, String description, int draftRevision, Integer publishedVersion,
            JsonNode definition, List<Version> versions) { }
    public record PublishedVersion(UUID viewId, int version, String name, String description,
            JsonNode definition, Instant publishedAt) { }
    public record Page(List<Summary> items, int viewLimit) { }
    public record Create(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
            @NotNull JsonNode definition) { }
    public record Copy(@NotBlank @Size(max = 80) String name) { }
    public record Save(@Min(1) int expectedRevision, @NotBlank @Size(max = 80) String name,
            @Size(max = 300) String description, @NotNull JsonNode definition) { }
    public record Publish(@Min(1) int expectedRevision) { }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final Json json;
    private final AuditWriter audit;
    private final PageReferences pages;

    public ApplicationViews(JdbcClient jdbc, PolicyService policy, Json json, AuditWriter audit, PageReferences pages) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.json = json;
        this.audit = audit;
        this.pages = pages;
    }

    /** Pages also need their pinned views, workflows and extensions checked (S111); publication requires approval. */
    private String validate(UUID ws, JsonNode definition, boolean publishing) {
        String kind = ViewDefinition.validate(json, definition);
        if (kind.equals("page")) pages.check(ws, definition, publishing);
        return kind;
    }

    private void require(Caller caller, UUID ws, Permission permission, String operation) {
        policy.require(policy.requireMember(caller, ws), permission, operation, permission != Permission.WORKFLOW_READ);
    }

    private static final String SUMMARY = """
            SELECT id, name, description, kind, draft_revision, published_version, created_at, updated_at
            FROM application_view WHERE workspace_id = :ws
            """;

    private static Summary summary(ResultSet rs, int n) throws SQLException {
        return new Summary(Sql.uuid(rs, "id"), rs.getString("name"), rs.getString("description"), rs.getString("kind"),
                rs.getInt("draft_revision"), (Integer) rs.getObject("published_version"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }

    @Transactional(readOnly = true)
    public Page list(Caller caller, UUID ws) {
        require(caller, ws, Permission.WORKFLOW_READ, "listApplicationViews");
        return new Page(jdbc.sql(SUMMARY + " ORDER BY lower(name), id").param("ws", ws)
                .query(ApplicationViews::summary).list(), MAX_VIEWS);
    }

    @Transactional(readOnly = true)
    public Detail get(Caller caller, UUID ws, UUID id) {
        require(caller, ws, Permission.WORKFLOW_READ, "getApplicationView");
        return detail(ws, id);
    }

    @Transactional(readOnly = true)
    public PublishedVersion version(Caller caller, UUID ws, UUID id, int version) {
        require(caller, ws, Permission.WORKFLOW_READ, "getApplicationViewVersion");
        if (version < 1) throw ApiException.notFound();
        return jdbc.sql("""
                SELECT v.view_id, v.version, v.name, v.description, v.definition::text AS definition, v.published_at
                FROM application_view_version v JOIN application_view a ON a.id = v.view_id
                WHERE a.workspace_id = :ws AND v.workspace_id = :ws AND v.view_id = :id AND v.version = :version
                """).param("ws", ws).param("id", id).param("version", version)
                .query((rs, n) -> new PublishedVersion(Sql.uuid(rs, "view_id"), rs.getInt("version"),
                        rs.getString("name"), rs.getString("description"), json.parse(rs.getString("definition")),
                        Sql.instant(rs, "published_at"))).optional().orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Detail create(Caller caller, UUID ws, Create request) {
        require(caller, ws, Permission.WORKFLOW_EDIT, "createApplicationView");
        String kind = validate(ws, request.definition(), false);
        return createRow(caller, ws, request.name(), request.description(), kind, request.definition(), "view.created");
    }

    @Transactional
    public Detail copy(Caller caller, UUID ws, UUID sourceId, Copy request) {
        require(caller, ws, Permission.WORKFLOW_EDIT, "copyApplicationView");
        Locked source = lock(ws, sourceId);
        return createRow(caller, ws, request.name(), source.description(), source.kind(), source.definition(), "view.copied");
    }

    private Detail createRow(Caller caller, UUID ws, String name, String description, String kind,
            JsonNode definition, String action) {
        String cleanName = name(name);
        String cleanDescription = description(description);
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).single();
        int count = jdbc.sql("SELECT count(*) FROM application_view WHERE workspace_id = :ws")
                .param("ws", ws).query(Integer.class).single();
        if (count >= MAX_VIEWS) throw invalid("A workspace can have at most " + MAX_VIEWS + " views.");
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO application_view (id, workspace_id, name, description, kind, draft_definition, created_by)
                    VALUES (:id, :ws, :name, :description, :kind, CAST(:definition AS jsonb), :by)
                    """).param("id", id).param("ws", ws).param("name", cleanName)
                    .param("description", cleanDescription).param("kind", kind)
                    .param("definition", json.write(definition)).param("by", caller.subjectId()).update();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A view with this name already exists.");
        }
        audit(caller, ws, action, id, Map.of("revision", 1));
        return detail(ws, id);
    }

    @Transactional
    public Detail save(Caller caller, UUID ws, UUID id, Save request) {
        require(caller, ws, Permission.WORKFLOW_EDIT, "saveApplicationView");
        String kind = validate(ws, request.definition(), false);
        Locked current = lock(ws, id);
        expected(current, request.expectedRevision());
        if (!kind.equals(current.kind())) throw invalid("A view kind cannot change; copy the view instead.");
        int next = current.revision() + 1;
        try {
            jdbc.sql("""
                    UPDATE application_view SET name = :name, description = :description,
                    draft_definition = CAST(:definition AS jsonb), draft_revision = :next, updated_at = now()
                    WHERE workspace_id = :ws AND id = :id
                    """).param("ws", ws).param("id", id).param("name", name(request.name()))
                    .param("description", description(request.description()))
                    .param("definition", json.write(request.definition())).param("next", next).update();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A view with this name already exists.");
        }
        audit(caller, ws, "view.draft.saved", id, Map.of("revision", next));
        return detail(ws, id);
    }

    @Transactional
    public Detail publish(Caller caller, UUID ws, UUID id, Publish request) {
        require(caller, ws, Permission.WORKFLOW_PUBLISH, "publishApplicationView");
        Locked current = lock(ws, id);
        expected(current, request.expectedRevision());
        if (current.publishedDraftRevision() != null && current.publishedDraftRevision() == current.revision()) {
            throw invalid("This draft revision is already published.");
        }
        int next = current.publishedVersion() == null ? 1 : current.publishedVersion() + 1;
        if (next > MAX_VERSIONS) throw invalid("A view can have at most " + MAX_VERSIONS + " published versions.");
        validate(ws, current.definition(), true);
        jdbc.sql("""
                INSERT INTO application_view_version (workspace_id, view_id, version, name, description, definition, published_by)
                VALUES (:ws, :id, :version, :name, :description, CAST(:definition AS jsonb), :by)
                """).param("ws", ws).param("id", id).param("version", next).param("name", current.name())
                .param("description", current.description()).param("definition", json.write(current.definition()))
                .param("by", caller.subjectId()).update();
        jdbc.sql("""
                UPDATE application_view SET published_version = :version, published_draft_revision = :revision,
                updated_at = now() WHERE workspace_id = :ws AND id = :id
                """).param("ws", ws).param("id", id).param("version", next).param("revision", current.revision()).update();
        audit(caller, ws, "view.published", id, Map.of("version", next));
        return detail(ws, id);
    }

    private record Locked(String name, String description, String kind, int revision, Integer publishedVersion,
            Integer publishedDraftRevision, JsonNode definition) { }

    private Locked lock(UUID ws, UUID id) {
        return jdbc.sql("""
                SELECT name, description, kind, draft_revision, published_version, published_draft_revision,
                       draft_definition::text AS draft_definition
                FROM application_view WHERE workspace_id = :ws AND id = :id FOR UPDATE
                """).param("ws", ws).param("id", id).query((rs, n) -> new Locked(rs.getString("name"),
                        rs.getString("description"), rs.getString("kind"), rs.getInt("draft_revision"),
                        (Integer) rs.getObject("published_version"), (Integer) rs.getObject("published_draft_revision"),
                        json.parse(rs.getString("draft_definition"))))
                .optional().orElseThrow(ApiException::notFound);
    }

    private Detail detail(UUID ws, UUID id) {
        return jdbc.sql("""
                SELECT id, name, description, draft_revision, published_version,
                       draft_definition::text AS draft_definition
                FROM application_view WHERE workspace_id = :ws AND id = :id
                """).param("ws", ws).param("id", id).query((rs, n) -> new Detail(Sql.uuid(rs, "id"),
                        rs.getString("name"), rs.getString("description"), rs.getInt("draft_revision"),
                        (Integer) rs.getObject("published_version"), json.parse(rs.getString("draft_definition")),
                        jdbc.sql("SELECT version, published_at FROM application_view_version WHERE workspace_id = :ws AND view_id = :id ORDER BY version DESC")
                                .param("ws", ws).param("id", id).query((v, x) -> new Version(v.getInt("version"),
                                        Sql.instant(v, "published_at"))).list()))
                .optional().orElseThrow(ApiException::notFound);
    }

    private static void expected(Locked row, int revision) {
        if (row.revision() != revision) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,
                "The view changed. Reload before saving or publishing.").with("currentRevision", row.revision());
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 80) {
            throw invalid("Give the view a trimmed name of at most 80 characters.");
        }
        return value;
    }

    private static String description(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.equals(value.trim()) || value.length() > 300) throw invalid("Description must be trimmed and at most 300 characters.");
        return value;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message);
    }

    private void audit(Caller caller, UUID ws, String action, UUID id, Map<String, Object> metadata) {
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), action,
                "applicationView", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }
}
