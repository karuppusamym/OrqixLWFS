package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.compiler.CanonicalJson;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.SubjectService.SubjectRef;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PL01 shared reusable assets (docs/build/S57). Three kinds share one index, owner and archive model: shared decision
 * tables (new, {@code decision_asset}), document templates (DOC08 registry) and connection definitions (V1). Each is
 * identified by kind and key inside one workspace, has immutable numbered versions, an owner and an archive flag.
 * <p>
 * Authorization is per kind and never derived from a reference or an owner: decisions and templates are read with
 * {@code workflow.read} and managed with {@code workflow.edit}; connections are read with {@code connection.read} and
 * managed with {@code connection.manage}. Search only covers the kinds the caller may read. Where-used also needs
 * {@code workflow.read}. Owners are labels: an owner must currently hold the manage permission, and gains nothing.
 */
@Service
public class SharedAssets {

    public static final int MAX_DECISIONS = 200;
    public static final int MAX_VERSIONS = 100;
    public static final int SEARCH_LIMIT = 300;
    static final int MAX_CONTENT_BYTES = 64 * 1024;

    /** API kind values. */
    public static final String DECISION = "decision";
    public static final String TEMPLATE = "documentTemplate";
    public static final String CONNECTION = "connection";
    static final List<String> KINDS = List.of(DECISION, TEMPLATE, CONNECTION);

    public record Summary(String kind, String key, String name, String description, SubjectRef owner,
            int latestVersion, boolean archived, int revision, Instant updatedAt, UUID id) {
    }

    public record Page(List<Summary> items, List<String> searchedKinds, boolean truncated) {
    }

    public record Version(int version, String sha256, String changeNote, SubjectRef createdBy, Instant createdAt,
            JsonNode content) {
    }

    public record Detail(Summary asset, List<Version> versions) {
    }

    public record UsageItem(UUID workflowId, String workflowKey, String workflowName, Integer draftVersion,
            Integer activeReleaseNumber, Integer activeReleaseVersion, boolean stale) {
    }

    public record Usage(String kind, String key, int latestVersion, List<UsageItem> items) {
    }

    public record CreateDecisionRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String key,
            @NotBlank @Size(max = 80) String name, @Size(max = 300) String description, @NotNull JsonNode content,
            @Size(max = 300) String changeNote) {
    }

    public record DecisionVersionRequest(@Min(1) int expectedVersion, @NotNull JsonNode content,
            @Size(max = 300) String changeNote) {
    }

    public record OwnerRequest(@NotNull UUID ownerSubjectId, @Min(0) int expectedRevision) {
    }

    public record ArchiveRequest(boolean archived, @Min(0) int expectedRevision) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final Json json;

    public SharedAssets(JdbcClient jdbc, PolicyService policy, SubjectService subjects, AuditWriter audit, Json json) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
        this.json = json;
    }

    // ---------------------------------------------------------------- permissions

    static Permission readPermission(String kind) {
        return CONNECTION.equals(kind) ? Permission.CONNECTION_READ : Permission.WORKFLOW_READ;
    }

    static Permission managePermission(String kind) {
        return CONNECTION.equals(kind) ? Permission.CONNECTION_MANAGE : Permission.WORKFLOW_EDIT;
    }

    private static String kind(String value) {
        if (value == null || !KINDS.contains(value)) {
            throw ApiException.notFound();
        }
        return value;
    }

    // ---------------------------------------------------------------- index and search

    /**
     * One row per asset of every kind. The connection branch aggregates versions per key; its header row is optional
     * (V82), so the owner falls back to the person who registered the first version.
     */
    private static final String INDEX = """
            SELECT 'decision' AS kind, a.key, a.name, a.description, a.owner_subject_id AS owner, a.latest_version,
                   a.archived_at IS NOT NULL AS archived, a.revision, a.updated_at, a.id
            FROM decision_asset a WHERE a.workspace_id = :ws
            UNION ALL
            SELECT 'documentTemplate', t.key, t.name, t.description, t.owner_subject_id, t.latest_version,
                   t.archived_at IS NOT NULL, t.revision, t.updated_at, t.id
            FROM document_template t WHERE t.workspace_id = :ws
            UNION ALL
            SELECT 'connection', c.key, left(coalesce(nullif(latest.definition->>'name', ''), c.key), 200),
                   left(latest.definition->>'description', 300), coalesce(h.owner_subject_id, first.created_by), c.latest,
                   h.archived_at IS NOT NULL, coalesce(h.revision, 0), coalesce(h.updated_at, latest.created_at), NULL::uuid
            FROM (SELECT key, max(version) AS latest, min(version) AS first FROM connection_definition
                  WHERE workspace_id = :ws GROUP BY key) c
            JOIN connection_definition latest ON latest.workspace_id = :ws AND latest.key = c.key AND latest.version = c.latest
            JOIN connection_definition first ON first.workspace_id = :ws AND first.key = c.key AND first.version = c.first
            LEFT JOIN connection_asset h ON h.workspace_id = :ws AND h.connection_key = c.key
            """;

    private record Row(String kind, String key, String name, String description, UUID owner, int latestVersion,
            boolean archived, int revision, Instant updatedAt, UUID id) {
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getString("kind"), rs.getString("key"), rs.getString("name"), rs.getString("description"),
                Sql.uuid(rs, "owner"), rs.getInt("latest_version"), rs.getBoolean("archived"), rs.getInt("revision"),
                Sql.instant(rs, "updated_at"), Sql.uuid(rs, "id"));
    }

    private List<Summary> summaries(List<Row> rows) {
        Map<UUID, String> names = subjects.displayNames(rows.stream().map(Row::owner).distinct().toList());
        return rows.stream().map(r -> new Summary(r.kind(), r.key(), r.name(), r.description(),
                subjects.ref(r.owner(), names), r.latestVersion(), r.archived(), r.revision(), r.updatedAt(), r.id()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page search(Caller caller, UUID ws, String q, String kind, String owner, boolean includeArchived) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        if (q != null && q.length() > 100) {
            throw ApiException.malformed("q must be at most 100 characters.");
        }
        UUID ownerId = owner == null ? null : Ids.parseOrNull(owner);
        if (owner != null && ownerId == null) {
            throw ApiException.malformed("owner must be a subject ID.");
        }
        if (kind != null && !KINDS.contains(kind)) {
            throw ApiException.malformed("kind must be one of " + KINDS + ".");
        }
        List<String> kinds = new ArrayList<>();
        for (String k : kind == null ? KINDS : List.of(kind)) {
            if (access.has(readPermission(k))) {
                kinds.add(k);
            }
        }
        if (kinds.isEmpty()) {
            // Nothing the caller may read: the same denial as reading any one kind without permission.
            throw policy.deny(ws, caller.subjectId(), readPermission(kind == null ? DECISION : kind).value(),
                    "listSharedAssets", false, new ApiException(ProblemCode.FORBIDDEN));
        }
        String text = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        StringBuilder sql = new StringBuilder("SELECT x.* FROM (").append(INDEX).append(") x ")
                .append("LEFT JOIN subject s ON s.id = x.owner WHERE x.kind IN (:kinds)");
        if (!includeArchived) {
            sql.append(" AND NOT x.archived");
        }
        if (ownerId != null) {
            sql.append(" AND x.owner = :owner");
        }
        if (text != null) {
            sql.append(" AND (lower(x.name) LIKE :like ESCAPE '\\' OR x.key LIKE :like ESCAPE '\\'"
                    + " OR lower(s.display_name) LIKE :like ESCAPE '\\')");
        }
        sql.append(" ORDER BY lower(x.name), x.kind, x.key LIMIT ").append(SEARCH_LIMIT + 1);
        var statement = jdbc.sql(sql.toString()).param("ws", ws).param("kinds", kinds);
        if (ownerId != null) {
            statement = statement.param("owner", ownerId);
        }
        if (text != null) {
            statement = statement.param("like", "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        List<Row> rows = statement.query(SharedAssets::row).list();
        boolean truncated = rows.size() > SEARCH_LIMIT;
        return new Page(summaries(truncated ? rows.subList(0, SEARCH_LIMIT) : rows), kinds, truncated);
    }

    private Row find(UUID ws, String kind, String key) {
        return jdbc.sql("SELECT x.* FROM (" + INDEX + ") x WHERE x.kind = :kind AND x.key = :key")
                .param("ws", ws).param("kind", kind).param("key", key).query(SharedAssets::row).optional()
                .orElseThrow(ApiException::notFound);
    }

    private WorkspaceAccess reader(Caller caller, UUID ws, String kind, String operation) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, readPermission(kind), operation, false);
        return access;
    }

    private WorkspaceAccess manager(Caller caller, UUID ws, String kind, String operation) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, managePermission(kind), operation, true);
        return access;
    }

    @Transactional(readOnly = true)
    public Detail get(Caller caller, UUID ws, String kindValue, String key) {
        String kind = kind(kindValue);
        reader(caller, ws, kind, "getSharedAsset");
        return detail(ws, kind, key);
    }

    private Detail detail(UUID ws, String kind, String key) {
        Row row = find(ws, kind, key);
        Summary summary = summaries(List.of(row)).get(0);
        record V(int version, String sha, String note, UUID by, Instant at, String content) {
        }
        String sql = switch (kind) {
            case DECISION -> """
                    SELECT version, sha256 AS sha, change_note AS note, created_by, created_at, content::text AS content
                    FROM decision_asset_version WHERE asset_id = :id ORDER BY version DESC""";
            case TEMPLATE -> """
                    SELECT version, sha256 AS sha, change_note AS note, created_by, created_at, NULL AS content
                    FROM document_template_version WHERE template_id = :id ORDER BY version DESC""";
            default -> """
                    SELECT version, trim(definition_hash) AS sha, NULL AS note, created_by, created_at, NULL AS content
                    FROM connection_definition WHERE workspace_id = :ws AND key = :key ORDER BY version DESC""";
        };
        var statement = jdbc.sql(sql);
        statement = CONNECTION.equals(kind) ? statement.param("ws", ws).param("key", key) : statement.param("id", row.id());
        List<V> rows = statement.query((rs, n) -> new V(rs.getInt("version"), rs.getString("sha"), rs.getString("note"),
                Sql.uuid(rs, "created_by"), Sql.instant(rs, "created_at"), rs.getString("content"))).list();
        Map<UUID, String> names = subjects.displayNames(rows.stream().map(V::by).distinct().toList());
        List<Version> versions = rows.stream().map(v -> new Version(v.version(), v.sha(), v.note(),
                subjects.ref(v.by(), names), v.at(), v.content() == null ? null : json.parse(v.content()))).toList();
        return new Detail(summary, versions);
    }

    // ---------------------------------------------------------------- where-used

    @Transactional(readOnly = true)
    public Usage usage(Caller caller, UUID ws, String kindValue, String key) {
        String kind = kind(kindValue);
        WorkspaceAccess access = reader(caller, ws, kind, "getSharedAssetUsage");
        policy.require(access, Permission.WORKFLOW_READ, "getSharedAssetUsage", false);
        Row asset = find(ws, kind, key);
        String path = CONNECTION.equals(kind)
                ? "$.dependencies.connections[*] ? (@.key == $key).definitionVersion"
                : "$.dependencies.assets[*] ? (@.kind == $kind && @.key == $key).version";
        String pinned = "(SELECT min(v::text::int) FROM jsonb_path_query(%s, '" + path + "', CAST(:vars AS jsonb)) v)";
        List<UsageItem> items = jdbc.sql("SELECT * FROM (SELECT w.id, w.key, w.name, r.release_number, "
                + pinned.formatted("d.definition") + " AS draft_version, "
                + pinned.formatted("r.definition") + " AS release_version "
                + """
                FROM workflow w
                JOIN workflow_draft_revision d ON d.workflow_id = w.id AND d.revision = w.draft_revision
                LEFT JOIN workflow_release r ON r.id = w.active_release_id
                WHERE w.workspace_id = :ws AND w.archived_at IS NULL) u
                WHERE u.draft_version IS NOT NULL OR u.release_version IS NOT NULL
                ORDER BY lower(u.name), u.id
                """)
                .param("ws", ws).param("vars", json.write(Map.of("kind", kind, "key", key)))
                .query((rs, n) -> {
                    Integer draft = (Integer) rs.getObject("draft_version");
                    Integer release = (Integer) rs.getObject("release_version");
                    boolean stale = (draft != null && draft < asset.latestVersion())
                            || (release != null && release < asset.latestVersion());
                    return new UsageItem(Sql.uuid(rs, "id"), rs.getString("key"), rs.getString("name"), draft,
                            release == null ? null : (Integer) rs.getObject("release_number"), release, stale);
                }).list();
        return new Usage(kind, key, asset.latestVersion(), items);
    }

    // ---------------------------------------------------------------- shared decisions

    @Transactional
    public Detail createDecision(Caller caller, UUID ws, CreateDecisionRequest request) {
        manager(caller, ws, DECISION, "createDecisionAsset");
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the shared decision a name.");
        }
        ObjectNode content = decisionContent(request.content());
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).optional();
        int count = jdbc.sql("SELECT count(*) FROM decision_asset WHERE workspace_id = :ws").param("ws", ws)
                .query(Integer.class).single();
        if (count >= MAX_DECISIONS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "A workspace can have at most " + MAX_DECISIONS + " shared decisions.");
        }
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO decision_asset (id, workspace_id, key, name, description, owner_subject_id, created_by)
                VALUES (:id, :ws, :key, :name, :description, :by, :by) ON CONFLICT DO NOTHING
                """).param("id", id).param("ws", ws).param("key", request.key()).param("name", name)
                .param("description", blankToNull(request.description())).param("by", caller.subjectId()).update();
        if (inserted != 1) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A shared decision with this key or name already exists.");
        }
        insertDecisionVersion(ws, id, 1, content, request.changeNote(), caller.subjectId());
        audit(caller, ws, "shared.decision.created", DECISION, request.key(), Map.of("version", 1,
                "rows", content.path("rows").size()));
        return detail(ws, DECISION, request.key());
    }

    @Transactional
    public Detail addDecisionVersion(Caller caller, UUID ws, String key, DecisionVersionRequest request) {
        manager(caller, ws, DECISION, "addDecisionAssetVersion");
        ObjectNode content = decisionContent(request.content());
        record Locked(UUID id, int latest, boolean archived) {
        }
        Locked row = jdbc.sql("""
                SELECT id, latest_version, archived_at IS NOT NULL AS archived FROM decision_asset
                WHERE workspace_id = :ws AND key = :key FOR UPDATE
                """).param("ws", ws).param("key", key)
                .query((rs, n) -> new Locked(Sql.uuid(rs, "id"), rs.getInt("latest_version"), rs.getBoolean("archived")))
                .optional().orElseThrow(ApiException::notFound);
        if (row.latest() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,
                    "The shared decision changed. Reload before saving.").with("currentVersion", row.latest());
        }
        if (row.archived()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Restore the shared decision before adding a version.");
        }
        if (row.latest() >= MAX_VERSIONS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "A shared decision can have at most " + MAX_VERSIONS + " versions.");
        }
        String latestHash = jdbc.sql("SELECT sha256 FROM decision_asset_version WHERE asset_id = :id AND version = :v")
                .param("id", row.id()).param("v", row.latest()).query(String.class).single();
        if (decisionHash(content).equals(latestHash)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The new version is identical to the latest version.");
        }
        int next = row.latest() + 1;
        insertDecisionVersion(ws, row.id(), next, content, request.changeNote(), caller.subjectId());
        jdbc.sql("UPDATE decision_asset SET latest_version = :v, updated_at = now() WHERE id = :id")
                .param("v", next).param("id", row.id()).update();
        audit(caller, ws, "shared.decision.versioned", DECISION, key, Map.of("version", next,
                "rows", content.path("rows").size()));
        return detail(ws, DECISION, key);
    }

    private void insertDecisionVersion(UUID ws, UUID id, int version, ObjectNode content, String note, UUID by) {
        jdbc.sql("""
                INSERT INTO decision_asset_version (workspace_id, asset_id, version, content, sha256, change_note, created_by)
                VALUES (:ws, :id, :v, CAST(:content AS jsonb), :sha, :note, :by)
                """).param("ws", ws).param("id", id).param("v", version).param("content", json.write(content))
                .param("sha", decisionHash(content)).param("note", blankToNull(note)).param("by", by).update();
    }

    /**
     * The shareable part of a decision table: hit policy, rows and default, without the workflow-local key and
     * version. Only the structure is checked here; conditions name fields of the workflow that uses the table, so the
     * compiler checks each copy in full when that workflow is validated or published.
     */
    ObjectNode decisionContent(JsonNode content) {
        List<String> problems = new ArrayList<>();
        if (content == null || !content.isObject()) {
            throw invalidContent("content must be an object with hitPolicy, rows and default.");
        }
        if (json.write(content).getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES
                || Json.depth(content) > 20) {
            throw invalidContent("The decision table is too large.");
        }
        for (String name : content.propertyNames()) {
            if (!Set.of("hitPolicy", "rows", "default").contains(name)) {
                problems.add("Unknown property " + name + ".");
            }
        }
        String hitPolicy = content.path("hitPolicy").asString("");
        if (!Set.of("UNIQUE", "FIRST").contains(hitPolicy)) {
            problems.add("hitPolicy must be UNIQUE or FIRST.");
        }
        JsonNode rows = content.path("rows");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 50) {
            problems.add("rows must list 1 to 50 rows.");
        } else {
            for (JsonNode row : rows) {
                if (!row.isObject() || !row.path("when").isObject() || !row.path("then").isObject()
                        || row.path("then").isEmpty()) {
                    problems.add("Every row needs a when condition and a then result.");
                    break;
                }
            }
        }
        JsonNode fallback = content.path("default");
        if (!fallback.isObject() || fallback.isEmpty() || fallback.size() > 10) {
            problems.add("default must set 1 to 10 results.");
        }
        if (!problems.isEmpty()) {
            throw invalidContent(String.join(" ", problems));
        }
        ObjectNode normalized = json.object();
        normalized.put("hitPolicy", hitPolicy);
        normalized.set("rows", rows.deepCopy());
        normalized.set("default", fallback.deepCopy());
        return normalized;
    }

    private static ApiException invalidContent(String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError("/content", "decisionTable", message)));
    }

    /** SHA-256 of the RFC 8785 form of hit policy, rows and default (shared with {@link AssetReferenceCheck}). */
    String decisionHash(JsonNode table) {
        ObjectNode shareable = json.object();
        shareable.set("hitPolicy", table.path("hitPolicy").deepCopy());
        shareable.set("rows", table.path("rows").deepCopy());
        shareable.set("default", table.path("default").deepCopy());
        return CanonicalJson.sha256Hex(json.write(shareable));
    }

    // ---------------------------------------------------------------- owner and archive

    private boolean canManage(SubjectService.Subject subject, UUID ws, String kind) {
        return policy.freshAccess(new Caller(subject.id(), subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(subject.id()), false), ws)
                .map(a -> a.has(managePermission(kind))).orElse(false);
    }

    /** People who may own an asset of this kind: they hold its manage permission now (at most 50 of 300 known). */
    @Transactional(readOnly = true)
    public List<SubjectRef> ownerCandidates(Caller caller, UUID ws, String kindValue) {
        policy.requireVisible(caller, ws);
        if (kindValue == null || !KINDS.contains(kindValue)) {
            throw ApiException.malformed("kind must be one of " + KINDS + ".");
        }
        manager(caller, ws, kindValue, "listSharedAssetOwnerCandidates");
        return subjects.knownSubjects(300).stream().filter(s -> !s.disabled() && canManage(s, ws, kindValue)).limit(50)
                .map(s -> new SubjectRef(s.id(), s.displayName())).toList();
    }

    @Transactional
    public Detail setOwner(Caller caller, UUID ws, String kindValue, String key, OwnerRequest request) {
        String kind = kind(kindValue);
        manager(caller, ws, kind, "setSharedAssetOwner");
        Row current = find(ws, kind, key);
        boolean eligible = subjects.find(request.ownerSubjectId()).filter(s -> !s.disabled())
                .map(s -> canManage(s, ws, kind)).orElse(false);
        if (!eligible) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "The new owner must be a person who can manage this kind of asset in this workspace.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/ownerSubjectId", "eligibility",
                            "Choose someone who can manage this asset")));
        }
        update(caller, ws, kind, key, current, request.expectedRevision(), "owner_subject_id = :owner", request.ownerSubjectId(), null);
        audit(caller, ws, "shared.asset.owner.changed", kind, key, Map.of("from", current.owner().toString(),
                "to", request.ownerSubjectId().toString()));
        return detail(ws, kind, key);
    }

    @Transactional
    public Detail setArchived(Caller caller, UUID ws, String kindValue, String key, ArchiveRequest request) {
        String kind = kind(kindValue);
        manager(caller, ws, kind, "setSharedAssetArchived");
        Row current = find(ws, kind, key);
        if (current.archived() == request.archived()) {
            if (current.revision() != request.expectedRevision()) {
                throw conflict(current.revision());
            }
            return detail(ws, kind, key);
        }
        update(caller, ws, kind, key, current, request.expectedRevision(),
                "archived_at = " + (request.archived() ? "now()" : "NULL"), null, request.archived());
        audit(caller, ws, request.archived() ? "shared.asset.archived" : "shared.asset.restored", kind, key, Map.of());
        return detail(ws, kind, key);
    }

    /** Compare-and-set on the header revision; a connection header is created on its first change (revision 0). */
    private void update(Caller caller, UUID ws, String kind, String key, Row current, int expected, String assignment,
            UUID owner, Boolean archived) {
        if (current.revision() != expected) {
            throw conflict(current.revision());
        }
        int changed;
        if (CONNECTION.equals(kind) && expected == 0) {
            changed = jdbc.sql("""
                    INSERT INTO connection_asset (workspace_id, connection_key, owner_subject_id, archived_at, updated_by)
                    VALUES (:ws, :key, :owner, CASE WHEN :archived THEN now() END, :by) ON CONFLICT DO NOTHING
                    """).param("ws", ws).param("key", key).param("owner", owner == null ? current.owner() : owner)
                    .param("archived", archived != null ? archived : current.archived()).param("by", caller.subjectId()).update();
        } else {
            String table = switch (kind) {
                case DECISION -> "decision_asset SET ";
                case TEMPLATE -> "document_template SET ";
                default -> "connection_asset SET updated_by = :by, ";
            };
            String where = CONNECTION.equals(kind) ? " WHERE workspace_id = :ws AND connection_key = :key"
                    : " WHERE workspace_id = :ws AND key = :key";
            var statement = jdbc.sql("UPDATE " + table + assignment + ", revision = revision + 1, updated_at = now()"
                    + where + " AND revision = :expected").param("ws", ws).param("key", key).param("expected", expected);
            if (owner != null) {
                statement = statement.param("owner", owner);
            }
            if (CONNECTION.equals(kind)) {
                statement = statement.param("by", caller.subjectId());
            }
            changed = statement.update();
        }
        if (changed != 1) {
            throw conflict(find(ws, kind, key).revision());
        }
    }

    private static ApiException conflict(int current) {
        return new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The asset changed. Reload before saving.")
                .with("currentRevision", current);
    }

    private void audit(Caller caller, UUID ws, String action, String kind, String key, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", kind);
        metadata.putAll(extra);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), action, "sharedAsset",
                kind + ":" + key, AuditWriter.Outcome.SUCCESS, metadata));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
