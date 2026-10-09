package com.orqix.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.json.Json;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/** Catalog tables: workflow, draft revisions, releases, artifacts. Every query is scoped by workspace. */
@Repository
public class CatalogStore {

    public record WorkflowRow(UUID id, UUID workspaceId, String key, String name, String description, UUID owner,
            int draftRevision, UUID activeReleaseId, int version, Instant createdAt, Instant updatedAt,
            Integer activeReleaseNumber, Instant activePublishedAt) {
    }

    public record DraftRow(UUID workflowId, int revision, String definitionJson, String schemaVersion, UUID savedBy,
            Instant savedAt) {
    }

    public record ReleaseRow(UUID id, UUID workspaceId, UUID workflowId, int releaseNumber, int draftRevision,
            String definitionJson, String schemaVersion, String sourceHash, String semanticHash, String compilerVersion,
            String manifestJson, String sourceMapJson, String warningsJson, String engineDeploymentId,
            String processDefinitionId, String releaseNote, UUID publishedBy, Instant publishedAt) {
    }

    public record Artifact(String name, String mediaType, byte[] content, String sha256) {
    }

    /**
     * A workflow another workflow may start (S12): its key and name, and the facts of its active release (all null
     * when it has none).
     */
    public record CallableRow(String key, String name, UUID activeReleaseId, Integer releaseNumber, String semanticHash,
            String definitionJson, String manifestJson) {
    }

    private static final String WORKFLOW_COLUMNS = """
            w.id, w.workspace_id, w.key, w.name, w.description, w.owner_subject_id, w.draft_revision, w.active_release_id,
            w.version, w.created_at, w.updated_at, r.release_number AS active_release_number,
            r.published_at AS active_published_at
            """;
    private static final String WORKFLOW_FROM = """
            FROM workflow w LEFT JOIN workflow_release r ON r.workspace_id = w.workspace_id AND r.id = w.active_release_id
            """;
    private static final String RELEASE_COLUMNS = """
            id, workspace_id, workflow_id, release_number, draft_revision, definition::text AS definition, schema_version,
            source_hash, semantic_hash, compiler_version, manifest::text AS manifest, source_map::text AS source_map,
            warnings::text AS warnings, engine_deployment_id, process_definition_id, release_note, published_by,
            published_at
            """;

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public CatalogStore(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- workflows

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertWorkflow(UUID id, UUID workspaceId, String key, String name, String description, UUID owner) {
        Instant now = clock.instant();
        return jdbc.sql("""
                INSERT INTO workflow (id, workspace_id, key, name, description, owner_subject_id, draft_revision, version,
                                      created_by, created_at, updated_at)
                VALUES (:id, :ws, :key, :name, :description, :owner, 1, 1, :owner, :now, :now)
                ON CONFLICT (workspace_id, key) DO NOTHING
                """)
                .param("id", id).param("ws", workspaceId).param("key", key).param("name", name)
                .param("description", description).param("owner", owner).param("now", Sql.ts(now))
                .update() == 1;
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowRow> workflow(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("SELECT " + WORKFLOW_COLUMNS + WORKFLOW_FROM + " WHERE w.workspace_id = :ws AND w.id = :id AND w.archived_at IS NULL")
                .param("ws", workspaceId).param("id", workflowId).query(this::mapWorkflow).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WorkflowRow> lockWorkflow(UUID workspaceId, UUID workflowId) {
        jdbc.sql("SELECT id FROM workflow WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", workspaceId).param("id", workflowId).query(UUID.class).optional();
        return workflow(workspaceId, workflowId);
    }

    /** The listed workflows of the workspace (not archived) with their active release, for pinning children. */
    @Transactional(readOnly = true)
    public List<CallableRow> callable(UUID workspaceId, java.util.Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT w.key, w.name, w.active_release_id, r.release_number, r.semantic_hash,
                       r.definition::text AS definition, r.manifest::text AS manifest
                FROM workflow w LEFT JOIN workflow_release r ON r.workspace_id = w.workspace_id AND r.id = w.active_release_id
                WHERE w.workspace_id = :ws AND w.archived_at IS NULL AND w.key IN (:keys)
                ORDER BY w.key
                """)
                .param("ws", workspaceId).param("keys", List.copyOf(keys))
                .query((rs, n) -> new CallableRow(rs.getString("key"), rs.getString("name"),
                        Sql.uuid(rs, "active_release_id"), (Integer) rs.getObject("release_number"),
                        rs.getString("semantic_hash"), rs.getString("definition"), rs.getString("manifest")))
                .list();
    }

    /** Keyset page (created_at DESC, id DESC), {@code limit + 1} rows. */
    @Transactional(readOnly = true)
    public List<WorkflowRow> workflows(UUID workspaceId, String query, boolean onlyActive, java.util.Set<UUID> restrictTo,
            CursorCodec.Position after, int limitPlusOne) {
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase().replace("%", "\\%")
                .replace("_", "\\_") + "%";
        return jdbc.sql("SELECT " + WORKFLOW_COLUMNS + WORKFLOW_FROM + """
                 WHERE w.workspace_id = :ws AND w.archived_at IS NULL
                   AND (CAST(:like AS text) IS NULL OR lower(w.name) LIKE :like OR lower(w.key) LIKE :like)
                   AND (:onlyActive = false OR w.active_release_id IS NOT NULL)
                   AND (:unrestricted = true OR w.id IN (:restrictTo))
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (w.created_at, w.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY w.created_at DESC, w.id DESC
                 LIMIT :limit
                """)
                .param("ws", workspaceId).param("like", like).param("onlyActive", onlyActive)
                .param("unrestricted", restrictTo == null)
                .param("restrictTo", restrictTo == null || restrictTo.isEmpty() ? List.of(new UUID(0, 0)) : List.copyOf(restrictTo))
                .param("afterAt", after == null ? null : Sql.ts(after.at()))
                .param("afterId", after == null ? null : UUID.fromString(after.id()))
                .param("limit", limitPlusOne)
                .query(this::mapWorkflow).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean advanceDraft(UUID workspaceId, UUID workflowId, int expectedRevision) {
        return jdbc.sql("""
                UPDATE workflow SET draft_revision = draft_revision + 1, updated_at = :now
                WHERE workspace_id = :ws AND id = :id AND draft_revision = :expected
                """)
                .param("now", Sql.ts(clock.instant())).param("ws", workspaceId).param("id", workflowId)
                .param("expected", expectedRevision).update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean setActiveRelease(UUID workspaceId, UUID workflowId, UUID releaseId, int expectedVersion) {
        return jdbc.sql("""
                UPDATE workflow SET active_release_id = :release, version = version + 1, updated_at = :now
                WHERE workspace_id = :ws AND id = :id AND version = :expected
                """)
                .param("release", releaseId).param("now", Sql.ts(clock.instant())).param("ws", workspaceId)
                .param("id", workflowId).param("expected", expectedVersion).update() == 1;
    }

    // ---------------------------------------------------------------- drafts

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertDraft(UUID workspaceId, UUID workflowId, int revision, String definitionJson, String schemaVersion,
            UUID savedBy) {
        jdbc.sql("""
                INSERT INTO workflow_draft_revision (workspace_id, workflow_id, revision, definition, schema_version,
                                                     saved_by, saved_at)
                VALUES (:ws, :wf, :revision, CAST(:definition AS jsonb), :schemaVersion, :savedBy, :now)
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("revision", revision)
                .param("definition", definitionJson).param("schemaVersion", schemaVersion).param("savedBy", savedBy)
                .param("now", Sql.ts(clock.instant())).update();
    }

    @Transactional(readOnly = true)
    public Optional<DraftRow> draft(UUID workspaceId, UUID workflowId, int revision) {
        return jdbc.sql("""
                SELECT workflow_id, revision, definition::text AS definition, schema_version, saved_by, saved_at
                FROM workflow_draft_revision WHERE workspace_id = :ws AND workflow_id = :wf AND revision = :revision
                """)
                .param("ws", workspaceId).param("wf", workflowId).param("revision", revision)
                .query((rs, n) -> new DraftRow(Sql.uuid(rs, "workflow_id"), rs.getInt("revision"),
                        rs.getString("definition"), rs.getString("schema_version"), Sql.uuid(rs, "saved_by"),
                        Sql.instant(rs, "saved_at")))
                .optional();
    }

    // ---------------------------------------------------------------- releases

    @Transactional(propagation = Propagation.MANDATORY)
    public int nextReleaseNumber(UUID workspaceId, UUID workflowId) {
        return jdbc.sql("SELECT COALESCE(max(release_number), 0) + 1 FROM workflow_release WHERE workspace_id = :ws AND workflow_id = :wf")
                .param("ws", workspaceId).param("wf", workflowId).query(Integer.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertRelease(ReleaseRow r) {
        jdbc.sql("""
                INSERT INTO workflow_release (id, workspace_id, workflow_id, release_number, draft_revision, definition,
                    schema_version, source_hash, semantic_hash, compiler_version, manifest, source_map, warnings,
                    engine_deployment_id, process_definition_id, release_note, published_by, published_at)
                VALUES (:id, :ws, :wf, :number, :draft, CAST(:definition AS jsonb), :schemaVersion, :sourceHash,
                    :semanticHash, :compilerVersion, CAST(:manifest AS jsonb), CAST(:sourceMap AS jsonb),
                    CAST(:warnings AS jsonb), :deployment, :pd, :note, :by, :at)
                """)
                .param("id", r.id()).param("ws", r.workspaceId()).param("wf", r.workflowId())
                .param("number", r.releaseNumber()).param("draft", r.draftRevision())
                .param("definition", r.definitionJson()).param("schemaVersion", r.schemaVersion())
                .param("sourceHash", r.sourceHash()).param("semanticHash", r.semanticHash())
                .param("compilerVersion", r.compilerVersion()).param("manifest", r.manifestJson())
                .param("sourceMap", r.sourceMapJson()).param("warnings", r.warningsJson())
                .param("deployment", r.engineDeploymentId()).param("pd", r.processDefinitionId())
                .param("note", r.releaseNote()).param("by", r.publishedBy()).param("at", Sql.ts(r.publishedAt()))
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertArtifact(UUID releaseId, Artifact artifact) {
        jdbc.sql("""
                INSERT INTO release_artifact (release_id, name, media_type, content, sha256)
                VALUES (:release, :name, :media, :content, :sha)
                """)
                .param("release", releaseId).param("name", artifact.name()).param("media", artifact.mediaType())
                .param("content", artifact.content()).param("sha", artifact.sha256()).update();
    }

    @Transactional(readOnly = true)
    public Optional<ReleaseRow> release(UUID workspaceId, UUID workflowId, UUID releaseId) {
        return jdbc.sql("SELECT " + RELEASE_COLUMNS + " FROM workflow_release WHERE workspace_id = :ws AND workflow_id = :wf AND id = :id")
                .param("ws", workspaceId).param("wf", workflowId).param("id", releaseId).query(this::mapRelease)
                .optional();
    }

    @Transactional(readOnly = true)
    public List<ReleaseRow> releases(UUID workspaceId, UUID workflowId, CursorCodec.Position after, int limitPlusOne) {
        return jdbc.sql("SELECT " + RELEASE_COLUMNS + """
                 FROM workflow_release WHERE workspace_id = :ws AND workflow_id = :wf
                   AND (CAST(:afterAt AS timestamptz) IS NULL
                        OR (published_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY published_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("ws", workspaceId).param("wf", workflowId)
                .param("afterAt", after == null ? null : Sql.ts(after.at()))
                .param("afterId", after == null ? null : UUID.fromString(after.id()))
                .param("limit", limitPlusOne).query(this::mapRelease).list();
    }

    @Transactional(readOnly = true)
    public List<Artifact> artifacts(UUID releaseId) {
        return jdbc.sql("SELECT name, media_type, content, sha256 FROM release_artifact WHERE release_id = :id ORDER BY name")
                .param("id", releaseId)
                .query((rs, n) -> new Artifact(rs.getString("name"), rs.getString("media_type"), rs.getBytes("content"),
                        rs.getString("sha256").trim()))
                .list();
    }

    private WorkflowRow mapWorkflow(ResultSet rs, int n) throws SQLException {
        return new WorkflowRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("key"), rs.getString("name"),
                rs.getString("description"), Sql.uuid(rs, "owner_subject_id"), rs.getInt("draft_revision"),
                Sql.uuid(rs, "active_release_id"), rs.getInt("version"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), (Integer) rs.getObject("active_release_number"),
                Sql.instant(rs, "active_published_at"));
    }

    private ReleaseRow mapRelease(ResultSet rs, int n) throws SQLException {
        return new ReleaseRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "workflow_id"),
                rs.getInt("release_number"), rs.getInt("draft_revision"), rs.getString("definition"),
                rs.getString("schema_version"), rs.getString("source_hash").trim(), rs.getString("semantic_hash").trim(),
                rs.getString("compiler_version"), rs.getString("manifest"), rs.getString("source_map"),
                rs.getString("warnings"), rs.getString("engine_deployment_id"), rs.getString("process_definition_id"),
                rs.getString("release_note"), Sql.uuid(rs, "published_by"), Sql.instant(rs, "published_at"));
    }

    public JsonNode parse(String text) {
        return json.parse(text);
    }
}
