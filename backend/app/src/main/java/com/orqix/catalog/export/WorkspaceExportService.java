package com.orqix.catalog.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Workflow exit export (docs/27-blue-ocean-discovery.md section 3, "workflow-level exit guarantee"): a workspace
 * administrator downloads the workspace, or selected workflows, as one ZIP archive in open formats that can be read
 * without Orqix - Orqix workflow JSON, BPMN 2.0 XML, DMN XML, CSV and JSON Lines - with a SHA-256 manifest.
 *
 * <p>The archive is streamed straight to the response. History and audit rows are read with a server-side cursor
 * inside one read-only REPEATABLE READ transaction, so every file describes the same snapshot and memory use does not
 * grow with history size. Secrets are never read: connection bindings are exported without their credential reference
 * and OAuth grants are not exported at all.
 *
 * <p>Every export is audited twice: {@code workspace.exit-export.started} is committed before the first byte leaves
 * (a failed audit write fails the request), and {@code workspace.exit-exported} records the outcome with counts and the
 * archive's SHA-256 (or {@code failed} when the stream broke off).
 */
@Service
public class WorkspaceExportService {

    public static final int FORMAT_VERSION = 1;
    static final int FETCH_SIZE = 500;
    static final int MAX_SELECTED_WORKFLOWS = 500;

    /** Field names removed from exported JSON definitions wherever they appear (defence in depth). */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)^(secret|secretRef|secretValue|password|clientSecret|privateKey|accessToken|refreshToken|apiKeyValue|token)$");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /** What the caller asked for. {@code workflowIds} null or empty means the whole workspace. */
    public record ExportRequest(List<UUID> workflowIds, boolean includeRunData) {
    }

    /** A checked request, ready to stream. */
    public record Plan(UUID workspaceId, String workspaceKey, String workspaceName, UUID actorId, String actorName,
            List<UUID> workflowIds, boolean includeRunData, Instant createdAt, String fileName) {

        boolean selection() {
            return !workflowIds.isEmpty();
        }
    }

    /** Totals of a finished export (audited and returned for tests). */
    public record Result(int workflows, int releases, long runs, long tasks, long decisions, long runEvents,
            long auditEvents, int files, long bytes, String sha256) {
    }

    private enum Kind { TEXT, INT, BOOL, TS, JSON }

    private record Column(String name, Kind kind) {
        static Column text(String name) {
            return new Column(name, Kind.TEXT);
        }

        static Column ts(String name) {
            return new Column(name, Kind.TS);
        }

        static Column json(String name) {
            return new Column(name, Kind.JSON);
        }

        static Column integer(String name) {
            return new Column(name, Kind.INT);
        }

        static Column bool(String name) {
            return new Column(name, Kind.BOOL);
        }
    }

    private final PolicyService policy;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate writeTx;

    public WorkspaceExportService(PolicyService policy, AuditWriter audit, Json json, Clock clock,
            DataSource dataSource, PlatformTransactionManager transactions) {
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(FETCH_SIZE);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTx = new TransactionTemplate(transactions);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------- prepare

    /**
     * Checks the caller (workspace administrators only; outsiders get 404, members without the permission 403 with an
     * audited denial) and the selection, then commits the {@code started} audit record. Nothing is streamed yet, so
     * every problem here is still an ordinary problem response.
     */
    public Plan prepare(Caller caller, UUID workspaceId, ExportRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, "exportWorkspaceArchive", true);
        List<UUID> selected = request == null || request.workflowIds() == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(request.workflowIds()));
        if (selected.size() > MAX_SELECTED_WORKFLOWS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "Select at most " + MAX_SELECTED_WORKFLOWS + " workflows, or export the whole workspace.");
        }
        if (!selected.isEmpty()) {
            List<UUID> known = jdbc.queryForList("SELECT id FROM workflow WHERE workspace_id = :ws AND id IN (:ids)",
                    new MapSqlParameterSource("ws", workspaceId).addValue("ids", selected), UUID.class);
            if (known.size() != selected.size()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "One or more selected workflows do not exist in this workspace.");
            }
        }
        boolean includeRunData = request != null && request.includeRunData();
        Instant now = clock.instant();
        WorkspaceAccess.Workspace ws = access.workspace();
        Plan plan = new Plan(workspaceId, ws.key(), ws.name(), caller.subjectId(), caller.displayName(), selected,
                includeRunData, now, "orqix-exit-" + ws.key() + "-" + FILE_STAMP.format(now) + ".zip");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", plan.selection() ? "selection" : "workspace");
        metadata.put("workflowCount", selected.size());
        metadata.put("includeRunData", includeRunData);
        metadata.put("formatVersion", FORMAT_VERSION);
        writeTx.executeWithoutResult(s -> audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER,
                caller.subjectId(), "workspace.exit-export.started", "workspace", workspaceId.toString(),
                AuditWriter.Outcome.SUCCESS, metadata)));
        return plan;
    }

    // ---------------------------------------------------------------- stream

    /**
     * Writes the archive to {@code target} (not closed) and audits the outcome. A failure part-way leaves a truncated
     * archive that no ZIP reader accepts, and is audited as {@code failed}.
     */
    public Result write(Plan plan, OutputStream target) {
        try {
            Result result = snapshot.execute(status -> {
                try {
                    return stream(plan, target);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            auditOutcome(plan, AuditWriter.Outcome.SUCCESS, result);
            return result;
        } catch (RuntimeException e) {
            try {
                auditOutcome(plan, AuditWriter.Outcome.FAILED, null);
            } catch (RuntimeException auditFailure) {
                e.addSuppressed(auditFailure);
            }
            throw e;
        }
    }

    private void auditOutcome(Plan plan, AuditWriter.Outcome outcome, Result result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", plan.selection() ? "selection" : "workspace");
        metadata.put("includeRunData", plan.includeRunData());
        if (result != null) {
            metadata.put("workflows", result.workflows());
            metadata.put("releases", result.releases());
            metadata.put("runs", result.runs());
            metadata.put("tasks", result.tasks());
            metadata.put("decisions", result.decisions());
            metadata.put("auditEvents", result.auditEvents());
            metadata.put("files", result.files());
            metadata.put("bytes", result.bytes());
            metadata.put("sha256", result.sha256());
        }
        writeTx.executeWithoutResult(s -> audit.write(new AuditWriter.Entry(plan.workspaceId(),
                AuditWriter.ActorType.USER, plan.actorId(), "workspace.exit-exported", "workspace",
                plan.workspaceId().toString(), outcome, metadata)));
    }

    private Result stream(Plan plan, OutputStream target) throws IOException {
        ArchiveWriter archive = new ArchiveWriter(target);
        Map<String, Object> counts = new LinkedHashMap<>();
        archive.text("README.md", readme(plan));
        archive.text("workspace.json", pretty(workspaceJson(plan)));
        schema(archive, "schemas/workflow-v0.schema.json", "orqix/schemas/workflow-v0.schema.json");
        schema(archive, "schemas/connection-definition-v0.schema.json",
                "orqix/schemas/connection-definition-v0.schema.json");

        int[] definitionCounts = workflows(archive, plan);
        counts.put("workflows", definitionCounts[0]);
        counts.put("releases", definitionCounts[1]);
        counts.put("forms", forms(archive, plan, "form_asset", "form_asset_version", "forms"));
        counts.put("decisionTables", forms(archive, plan, "decision_asset", "decision_asset_version", "decision-tables"));
        counts.put("connections", connections(archive, plan));

        MapSqlParameterSource params = params(plan);
        long runs = dataset(archive, "history/runs", runsSql(plan), params, runColumns(plan));
        long tasks = dataset(archive, "history/tasks", tasksSql(plan), params, TASK_COLUMNS);
        long decisions = dataset(archive, "history/decisions", decisionsSql(plan), params, DECISION_COLUMNS);
        long events = dataset(archive, "history/run-events", eventsSql(plan), params, eventColumns(plan));
        long audits = dataset(archive, "audit/audit-events", auditSql(plan), params, AUDIT_COLUMNS);
        long people = dataset(archive, "people", peopleSql(plan), params, PEOPLE_COLUMNS);
        counts.put("runs", runs);
        counts.put("tasks", tasks);
        counts.put("decisions", decisions);
        counts.put("runEvents", events);
        counts.put("auditEvents", audits);
        counts.put("people", people);

        List<ArchiveWriter.FileEntry> files = archive.manifestEntries();
        StringBuilder sums = new StringBuilder();
        for (ArchiveWriter.FileEntry file : files) {
            sums.append(file.sha256()).append("  ").append(file.path()).append('\n');
        }
        archive.text("manifest.json", pretty(manifest(plan, counts, files)));
        archive.text("SHA256SUMS", sums.toString());
        archive.finish();
        return new Result(definitionCounts[0], definitionCounts[1], runs, tasks, decisions, events, audits,
                files.size() + 2, archive.archiveBytes(), archive.archiveSha256());
    }

    private static MapSqlParameterSource params(Plan plan) {
        MapSqlParameterSource params = new MapSqlParameterSource("ws", plan.workspaceId());
        if (plan.selection()) {
            params.addValue("ids", plan.workflowIds());
            params.addValue("idTexts", plan.workflowIds().stream().map(UUID::toString).toList());
        }
        return params;
    }

    private void schema(ArchiveWriter archive, String path, String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                archive.entry(path, in::transferTo);
            }
        }
    }

    // ---------------------------------------------------------------- definitions

    /** Writes every selected workflow with its draft and released versions; returns {workflows, releases}. */
    private int[] workflows(ArchiveWriter archive, Plan plan) throws IOException {
        MapSqlParameterSource params = params(plan);
        String filter = plan.selection() ? " AND w.id IN (:ids)" : "";
        List<ObjectNode> workflows = new ArrayList<>();
        jdbc.query("""
                SELECT w.id, w.key, w.name, w.description, w.owner_subject_id, o.display_name AS owner_name,
                       w.draft_revision, w.created_at, w.updated_at, w.archived_at, ar.release_number AS active_release
                FROM workflow w
                JOIN subject o ON o.id = w.owner_subject_id
                LEFT JOIN workflow_release ar ON ar.id = w.active_release_id
                WHERE w.workspace_id = :ws""" + filter + " ORDER BY w.key", params, rs -> {
            ObjectNode node = object();
            node.put("id", rs.getString("id"));
            node.put("key", rs.getString("key"));
            node.put("name", rs.getString("name"));
            node.put("description", rs.getString("description"));
            ObjectNode owner = node.putObject("owner");
            owner.put("id", rs.getString("owner_subject_id"));
            owner.put("displayName", rs.getString("owner_name"));
            node.put("draftRevision", rs.getInt("draft_revision"));
            node.put("createdAt", ts(rs, "created_at"));
            node.put("updatedAt", ts(rs, "updated_at"));
            node.put("archivedAt", ts(rs, "archived_at"));
            Object active = rs.getObject("active_release");
            if (active == null) {
                node.putNull("activeReleaseNumber");
            } else {
                node.put("activeReleaseNumber", ((Number) active).intValue());
            }
            workflows.add(node);
        });
        int releases = 0;
        for (ObjectNode workflow : workflows) {
            UUID id = UUID.fromString(workflow.path("id").asString());
            String base = "workflows/" + workflow.path("key").asString();
            ArrayNode numbers = workflow.putArray("releaseNumbers");
            List<Object[]> releaseRows = new ArrayList<>();
            MapSqlParameterSource wf = new MapSqlParameterSource("ws", plan.workspaceId()).addValue("wf", id);
            jdbc.query("""
                    SELECT r.id, r.release_number, r.draft_revision, r.definition::text AS definition, r.schema_version,
                           r.source_hash, r.semantic_hash, r.compiler_version, r.manifest::text AS manifest,
                           r.warnings::text AS warnings, r.process_definition_id, r.release_note, r.published_by,
                           p.display_name AS published_by_name, r.published_at
                    FROM workflow_release r JOIN subject p ON p.id = r.published_by
                    WHERE r.workspace_id = :ws AND r.workflow_id = :wf ORDER BY r.release_number""", wf, rs -> {
                ObjectNode release = object();
                release.put("id", rs.getString("id"));
                release.put("workflowId", id.toString());
                release.put("workflowKey", workflow.path("key").asString());
                release.put("releaseNumber", rs.getInt("release_number"));
                release.put("draftRevision", rs.getInt("draft_revision"));
                release.put("schemaVersion", rs.getString("schema_version"));
                release.put("sourceHash", rs.getString("source_hash"));
                release.put("semanticHash", rs.getString("semantic_hash"));
                release.put("compilerVersion", rs.getString("compiler_version"));
                release.put("processDefinitionId", rs.getString("process_definition_id"));
                release.put("releaseNote", rs.getString("release_note"));
                ObjectNode by = release.putObject("publishedBy");
                by.put("id", rs.getString("published_by"));
                by.put("displayName", rs.getString("published_by_name"));
                release.put("publishedAt", ts(rs, "published_at"));
                release.put("isActive", rs.getInt("release_number") == workflow.path("activeReleaseNumber").asInt(-1));
                release.set("warnings", json.parse(rs.getString("warnings")));
                releaseRows.add(new Object[] {release, rs.getString("definition"), rs.getString("manifest")});
            });
            for (Object[] row : releaseRows) {
                ObjectNode release = (ObjectNode) row[0];
                int number = release.path("releaseNumber").asInt();
                numbers.add(number);
                String dir = base + "/releases/" + number;
                archive.text(dir + "/workflow.json", pretty(json.parse((String) row[1])));
                archive.text(dir + "/compile-manifest.json", pretty(json.parse((String) row[2])));
                ArrayNode artifactNames = release.putArray("artifacts");
                UUID releaseId = UUID.fromString(release.path("id").asString());
                List<String> skipped = new ArrayList<>();
                jdbc.query("""
                        SELECT name, media_type, content FROM release_artifact WHERE release_id = :r ORDER BY name""",
                        new MapSqlParameterSource("r", releaseId), rs -> {
                    String name = rs.getString("name");
                    String path = dir + "/" + name;
                    if (!ArchiveWriter.safePath(path) || name.equals("workflow.json") || name.equals("release.json")
                            || name.equals("compile-manifest.json")) {
                        skipped.add(name);
                        return;
                    }
                    try {
                        archive.bytes(path, rs.getBytes("content"));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    ObjectNode artifact = artifactNames.addObject();
                    artifact.put("name", name);
                    artifact.put("mediaType", rs.getString("media_type"));
                });
                if (!skipped.isEmpty()) {
                    ArrayNode s = release.putArray("artifactsNotExported");
                    skipped.forEach(s::add);
                }
                archive.text(dir + "/release.json", pretty(release));
                releases++;
            }
            List<String> draft = jdbc.queryForList("""
                    SELECT definition::text FROM workflow_draft_revision
                    WHERE workspace_id = :ws AND workflow_id = :wf ORDER BY revision DESC LIMIT 1""", wf, String.class);
            if (!draft.isEmpty()) {
                archive.text(base + "/draft.json", pretty(json.parse(draft.get(0))));
            }
            archive.text(base + "/workflow.json", pretty(workflow));
        }
        return new int[] {workflows.size(), releases};
    }

    /** Shared forms or decision tables: one metadata file and one file per version. Returns the asset count. */
    private int forms(ArchiveWriter archive, Plan plan, String table, String versionTable, String folder)
            throws IOException {
        List<ObjectNode> assets = new ArrayList<>();
        jdbc.query("SELECT a.id, a.key, a.name, a.description, a.owner_subject_id, o.display_name AS owner_name, "
                + "a.latest_version, a.archived_at, a.created_at, a.updated_at FROM " + table + " a "
                + "JOIN subject o ON o.id = a.owner_subject_id WHERE a.workspace_id = :ws ORDER BY a.key",
                new MapSqlParameterSource("ws", plan.workspaceId()), rs -> {
                    ObjectNode node = object();
                    node.put("id", rs.getString("id"));
                    node.put("key", rs.getString("key"));
                    node.put("name", rs.getString("name"));
                    node.put("description", rs.getString("description"));
                    ObjectNode owner = node.putObject("owner");
                    owner.put("id", rs.getString("owner_subject_id"));
                    owner.put("displayName", rs.getString("owner_name"));
                    node.put("latestVersion", rs.getInt("latest_version"));
                    node.put("archivedAt", ts(rs, "archived_at"));
                    node.put("createdAt", ts(rs, "created_at"));
                    node.put("updatedAt", ts(rs, "updated_at"));
                    assets.add(node);
                });
        for (ObjectNode asset : assets) {
            String dir = folder + "/" + asset.path("key").asString();
            ArrayNode versions = asset.putArray("versions");
            jdbc.query("SELECT version, content::text AS content, sha256, change_note, created_at FROM " + versionTable
                    + " WHERE workspace_id = :ws AND asset_id = :id ORDER BY version",
                    new MapSqlParameterSource("ws", plan.workspaceId())
                            .addValue("id", UUID.fromString(asset.path("id").asString())), rs -> {
                        int version = rs.getInt("version");
                        ObjectNode v = versions.addObject();
                        v.put("version", version);
                        v.put("contentSha256", rs.getString("sha256"));
                        v.put("changeNote", rs.getString("change_note"));
                        v.put("createdAt", ts(rs, "created_at"));
                        v.put("file", "v" + version + ".json");
                        try {
                            archive.text(dir + "/v" + version + ".json", pretty(json.parse(rs.getString("content"))));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            archive.text(dir + "/" + (folder.equals("forms") ? "form.json" : "decision-table.json"), pretty(asset));
        }
        return assets.size();
    }

    /** Connection definitions (secret-free by contract, scrubbed again) and bindings without credentials. */
    private int connections(ArchiveWriter archive, Plan plan) throws IOException {
        MapSqlParameterSource ws = new MapSqlParameterSource("ws", plan.workspaceId());
        Map<String, ObjectNode> connections = new LinkedHashMap<>();
        jdbc.query("""
                SELECT d.key, d.version, d.definition::text AS definition, d.definition_hash, d.created_at
                FROM connection_definition d WHERE d.workspace_id = :ws ORDER BY d.key, d.version""", ws, rs -> {
            String key = rs.getString("key");
            ObjectNode connection = connections.computeIfAbsent(key, k -> {
                ObjectNode node = object();
                node.put("key", k);
                node.putArray("versions");
                return node;
            });
            int version = rs.getInt("version");
            ObjectNode v = ((ArrayNode) connection.get("versions")).addObject();
            v.put("version", version);
            v.put("definitionHash", rs.getString("definition_hash"));
            v.put("createdAt", ts(rs, "created_at"));
            v.put("file", "v" + version + ".json");
            JsonNode definition = json.parse(rs.getString("definition"));
            int removed = scrub(definition);
            if (removed > 0) {
                v.put("secretFieldsRemoved", removed);
            }
            try {
                archive.text("connections/" + key + "/v" + version + ".json", pretty(definition));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        jdbc.query("""
                SELECT b.connection_key, b.base_url, b.auth_type, b.auth_header_name, b.enabled, b.updated_at,
                       a.archived_at
                FROM connection_binding b
                LEFT JOIN connection_asset a ON a.workspace_id = b.workspace_id AND a.connection_key = b.connection_key
                WHERE b.workspace_id = :ws""", ws, rs -> {
            ObjectNode connection = connections.get(rs.getString("connection_key"));
            if (connection == null) {
                return;
            }
            ObjectNode binding = connection.putObject("binding");
            binding.put("baseUrl", rs.getString("base_url"));
            binding.put("authType", rs.getString("auth_type"));
            binding.put("authHeaderName", rs.getString("auth_header_name"));
            binding.put("enabled", rs.getBoolean("enabled"));
            binding.put("updatedAt", ts(rs, "updated_at"));
            binding.put("credentials", "removed: the credential reference and any secret value are never exported;"
                    + " configure credentials again in the target system");
            connection.put("archivedAt", ts(rs, "archived_at"));
        });
        for (ObjectNode connection : connections.values()) {
            archive.text("connections/" + connection.path("key").asString() + "/connection.json", pretty(connection));
        }
        return connections.size();
    }

    /** Removes secret-looking fields in place; returns how many were removed. */
    static int scrub(JsonNode node) {
        int removed = 0;
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (String name : names) {
                if (SECRET_FIELD.matcher(name).matches() && object.get(name).isValueNode()) {
                    object.remove(name);
                    removed++;
                } else {
                    removed += scrub(object.get(name));
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode item : array) {
                removed += scrub(item);
            }
        }
        return removed;
    }

    // ---------------------------------------------------------------- history datasets

    private static final List<Column> TASK_COLUMNS = List.of(Column.text("taskId"), Column.text("runId"),
            Column.text("runReference"), Column.text("workflowKey"), Column.text("nodeId"), Column.text("label"),
            Column.text("candidateGroup"), Column.text("assigneeId"), Column.text("assigneeName"),
            Column.text("status"), Column.integer("priority"), Column.ts("dueAt"), Column.ts("createdAt"),
            Column.ts("claimedAt"), Column.ts("endedAt"));
    private static final List<Column> DECISION_COLUMNS = List.of(Column.text("decisionId"), Column.text("taskId"),
            Column.text("runId"), Column.text("runReference"), Column.text("workflowKey"), Column.text("nodeId"),
            Column.text("actorId"), Column.text("actorName"), Column.text("action"), Column.text("reason"),
            Column.text("comment"), Column.json("policyFlags"), Column.ts("decidedAt"));
    private static final List<Column> AUDIT_COLUMNS = List.of(Column.text("auditId"), Column.ts("occurredAt"),
            Column.text("actorType"), Column.text("actorId"), Column.text("actorName"), Column.text("action"),
            Column.text("resourceType"), Column.text("resourceId"), Column.text("outcome"),
            Column.text("correlationId"), Column.json("metadata"));
    private static final List<Column> PEOPLE_COLUMNS = List.of(Column.text("subjectId"), Column.text("displayName"),
            Column.text("email"), Column.text("issuer"), Column.text("externalSubject"), Column.bool("disabled"));

    private static List<Column> runColumns(Plan plan) {
        List<Column> columns = new ArrayList<>(List.of(Column.text("runId"), Column.text("reference"),
                Column.text("workflowId"), Column.text("workflowKey"), Column.integer("releaseNumber"),
                Column.text("businessKey"), Column.text("initiatorId"), Column.text("initiatorName"),
                Column.text("lifecycle"), Column.text("outcome"), Column.text("outcomeKind"),
                Column.json("currentSteps"), Column.ts("createdAt"), Column.ts("updatedAt"), Column.ts("endedAt")));
        if (plan.includeRunData()) {
            columns.add(Column.json("input"));
            columns.add(Column.json("output"));
        }
        return columns;
    }

    private static List<Column> eventColumns(Plan plan) {
        List<Column> columns = new ArrayList<>(List.of(Column.text("runId"), Column.integer("seq"),
                Column.text("type"), Column.text("nodeId"), Column.text("actorId"), Column.text("actorName"),
                Column.ts("occurredAt")));
        if (plan.includeRunData()) {
            columns.add(Column.json("data"));
        }
        return columns;
    }

    private static String runFilter(Plan plan, String alias) {
        return plan.selection() ? " AND " + alias + ".workflow_id IN (:ids)" : "";
    }

    private static String runsSql(Plan plan) {
        String data = plan.includeRunData() ? ", r.input::text AS \"input\", r.output::text AS \"output\"" : "";
        return """
                SELECT r.id AS "runId", r.reference AS "reference", r.workflow_id AS "workflowId",
                       w.key AS "workflowKey", rel.release_number AS "releaseNumber", r.business_key AS "businessKey",
                       r.initiator_subject_id AS "initiatorId", s.display_name AS "initiatorName",
                       r.lifecycle AS "lifecycle", r.outcome AS "outcome", r.outcome_kind AS "outcomeKind",
                       r.current_steps::text AS "currentSteps", r.created_at AS "createdAt",
                       r.updated_at AS "updatedAt", r.ended_at AS "endedAt"
                """ + data + """
                 FROM workflow_run r
                JOIN workflow w ON w.id = r.workflow_id
                JOIN workflow_release rel ON rel.id = r.release_id
                LEFT JOIN subject s ON s.id = r.initiator_subject_id
                WHERE r.workspace_id = :ws""" + runFilter(plan, "r") + " ORDER BY r.created_at, r.id";
    }

    private static String tasksSql(Plan plan) {
        return """
                SELECT t.engine_task_id AS "taskId", t.run_id AS "runId", r.reference AS "runReference",
                       w.key AS "workflowKey", t.node_id AS "nodeId", t.label AS "label",
                       t.candidate_group_key AS "candidateGroup", t.assignee_subject_id AS "assigneeId",
                       s.display_name AS "assigneeName", t.status AS "status", t.priority AS "priority",
                       t.due_at AS "dueAt", t.created_at AS "createdAt", t.claimed_at AS "claimedAt",
                       t.ended_at AS "endedAt"
                FROM task_context t
                JOIN workflow_run r ON r.id = t.run_id
                JOIN workflow w ON w.id = r.workflow_id
                LEFT JOIN subject s ON s.id = t.assignee_subject_id
                WHERE t.workspace_id = :ws""" + runFilter(plan, "r") + " ORDER BY t.created_at, t.engine_task_id";
    }

    private static String decisionsSql(Plan plan) {
        return """
                SELECT d.id AS "decisionId", d.engine_task_id AS "taskId", d.run_id AS "runId",
                       r.reference AS "runReference", w.key AS "workflowKey", d.node_id AS "nodeId",
                       d.actor_subject_id AS "actorId", s.display_name AS "actorName", d.action AS "action",
                       d.reason AS "reason", d.comment AS "comment", d.policy_flags::text AS "policyFlags",
                       d.decided_at AS "decidedAt"
                FROM task_decision d
                JOIN workflow_run r ON r.id = d.run_id
                JOIN workflow w ON w.id = r.workflow_id
                LEFT JOIN subject s ON s.id = d.actor_subject_id
                WHERE d.workspace_id = :ws""" + runFilter(plan, "r") + " ORDER BY d.decided_at, d.id";
    }

    private static String eventsSql(Plan plan) {
        String data = plan.includeRunData() ? ", e.data::text AS \"data\"" : "";
        return """
                SELECT e.run_id AS "runId", e.seq AS "seq", e.type AS "type", e.node_id AS "nodeId",
                       e.actor_subject_id AS "actorId", s.display_name AS "actorName", e.occurred_at AS "occurredAt"
                """ + data + """
                 FROM run_event e
                JOIN workflow_run r ON r.id = e.run_id
                LEFT JOIN subject s ON s.id = e.actor_subject_id
                WHERE e.workspace_id = :ws""" + runFilter(plan, "r") + " ORDER BY r.created_at, e.run_id, e.seq";
    }

    private static String auditFilter(Plan plan) {
        // Leading space added explicitly: a text block strips the indentation of its least-indented line.
        return plan.selection() ? " " + """
                AND (a.resource_id IN (:idTexts)
                   OR a.resource_id IN (SELECT id::text FROM workflow_release WHERE workspace_id = :ws AND workflow_id IN (:ids))
                   OR a.resource_id IN (SELECT id::text FROM workflow_run WHERE workspace_id = :ws AND workflow_id IN (:ids)))"""
                : "";
    }

    private static String auditSql(Plan plan) {
        return """
                SELECT a.id AS "auditId", a.occurred_at AS "occurredAt", a.actor_type AS "actorType",
                       a.actor_subject_id AS "actorId", s.display_name AS "actorName", a.action AS "action",
                       a.resource_type AS "resourceType", a.resource_id AS "resourceId", a.outcome AS "outcome",
                       a.correlation_id AS "correlationId", a.metadata::text AS "metadata"
                FROM audit_event a
                LEFT JOIN subject s ON s.id = a.actor_subject_id
                WHERE a.workspace_id = :ws""" + auditFilter(plan) + " ORDER BY a.occurred_at, a.id";
    }

    private static String peopleSql(Plan plan) {
        String w = plan.selection() ? " AND id IN (:ids)" : "";
        String r = runFilter(plan, "r");
        return """
                SELECT s.id AS "subjectId", s.display_name AS "displayName", s.email AS "email", s.issuer AS "issuer",
                       s.external_subject AS "externalSubject", (s.disabled_at IS NOT NULL) AS "disabled"
                FROM subject s WHERE s.id IN (
                    SELECT owner_subject_id FROM workflow WHERE workspace_id = :ws""" + w + """

                    UNION SELECT published_by FROM workflow_release WHERE workspace_id = :ws"""
                + (plan.selection() ? " AND workflow_id IN (:ids)" : "") + """

                    UNION SELECT r.initiator_subject_id FROM workflow_run r WHERE r.workspace_id = :ws""" + r + """

                    UNION SELECT t.assignee_subject_id FROM task_context t JOIN workflow_run r ON r.id = t.run_id
                          WHERE t.workspace_id = :ws AND t.assignee_subject_id IS NOT NULL""" + r + """

                    UNION SELECT d.actor_subject_id FROM task_decision d JOIN workflow_run r ON r.id = d.run_id
                          WHERE d.workspace_id = :ws""" + r + """

                    UNION SELECT e.actor_subject_id FROM run_event e JOIN workflow_run r ON r.id = e.run_id
                          WHERE e.workspace_id = :ws AND e.actor_subject_id IS NOT NULL""" + r + """

                    UNION SELECT a.actor_subject_id FROM audit_event a
                          WHERE a.workspace_id = :ws AND a.actor_subject_id IS NOT NULL""" + auditFilter(plan) + """
                )
                ORDER BY s.display_name, s.id""";
    }

    /** Streams one query twice into {@code <base>.csv} and {@code <base>.jsonl}; returns the row count. */
    private long dataset(ArchiveWriter archive, String base, String sql, MapSqlParameterSource params,
            List<Column> columns) throws IOException {
        long[] rows = {0};
        archive.entry(base + ".csv", out -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
            List<String> header = columns.stream().map(Column::name).toList();
            Csv.row(writer, header);
            jdbc.query(sql, params, rs -> {
                List<String> values = new ArrayList<>(columns.size());
                for (Column column : columns) {
                    values.add(csvValue(rs, column));
                }
                try {
                    Csv.row(writer, values);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                rows[0]++;
            });
            writer.flush();
        });
        ObjectWriter line = json.mapper().writer();
        archive.entry(base + ".jsonl", out -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
            jdbc.query(sql, params, rs -> {
                ObjectNode node = object();
                for (Column column : columns) {
                    jsonValue(node, rs, column);
                }
                try {
                    writer.write(line.writeValueAsString(node));
                    writer.write('\n');
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            writer.flush();
        });
        return rows[0];
    }

    private static String csvValue(ResultSet rs, Column column) throws SQLException {
        return switch (column.kind()) {
            case TS -> ts(rs, column.name());
            case BOOL -> {
                boolean value = rs.getBoolean(column.name());
                yield rs.wasNull() ? null : Boolean.toString(value);
            }
            default -> rs.getString(column.name());
        };
    }

    private void jsonValue(ObjectNode node, ResultSet rs, Column column) throws SQLException {
        String name = column.name();
        switch (column.kind()) {
            case TS -> node.put(name, ts(rs, name));
            case INT -> {
                long value = rs.getLong(name);
                if (rs.wasNull()) {
                    node.putNull(name);
                } else {
                    node.put(name, value);
                }
            }
            case BOOL -> {
                boolean value = rs.getBoolean(name);
                if (rs.wasNull()) {
                    node.putNull(name);
                } else {
                    node.put(name, value);
                }
            }
            case JSON -> {
                String text = rs.getString(name);
                if (text == null) {
                    node.putNull(name);
                } else {
                    node.set(name, json.parse(text));
                }
            }
            default -> node.put(name, rs.getString(name));
        }
    }

    private static String ts(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }

    // ---------------------------------------------------------------- manifest and README

    private ObjectNode workspaceJson(Plan plan) {
        ObjectNode node = object();
        node.put("id", plan.workspaceId().toString());
        node.put("key", plan.workspaceKey());
        node.put("name", plan.workspaceName());
        return node;
    }

    private ObjectNode manifest(Plan plan, Map<String, Object> counts, List<ArchiveWriter.FileEntry> files) {
        ObjectNode node = object();
        node.put("format", "orqix-exit-archive");
        node.put("formatVersion", FORMAT_VERSION);
        node.put("createdAt", plan.createdAt().toString());
        node.set("workspace", workspaceJson(plan));
        ObjectNode by = node.putObject("exportedBy");
        by.put("id", plan.actorId().toString());
        by.put("displayName", plan.actorName());
        node.put("scope", plan.selection() ? "selection" : "workspace");
        ArrayNode ids = node.putArray("selectedWorkflowIds");
        plan.workflowIds().forEach(id -> ids.add(id.toString()));
        node.put("includeRunData", plan.includeRunData());
        node.put("hashAlgorithm", "SHA-256");
        node.set("counts", json.mapper().valueToTree(counts));
        ArrayNode list = node.putArray("files");
        for (ArchiveWriter.FileEntry file : files) {
            ObjectNode f = list.addObject();
            f.put("path", file.path());
            f.put("sha256", file.sha256());
            f.put("bytes", file.bytes());
        }
        return node;
    }

    static String readme(Plan plan) {
        String scope = plan.selection()
                ? "selected workflows (" + plan.workflowIds().size() + ") of workspace `" + plan.workspaceKey() + "`"
                : "the whole workspace `" + plan.workspaceKey() + "`";
        return """
                # Orqix exit archive

                This archive holds %s, exported on %s by %s.
                It is self-contained: every file is in an open format and can be read without Orqix.

                ## Check the archive first

                `manifest.json` lists every file with its SHA-256 and size. `SHA256SUMS` holds the same hashes in the
                format of the `sha256sum` tool, so on Linux or macOS run `sha256sum -c SHA256SUMS` (or
                `shasum -a 256 -c SHA256SUMS`) in the unpacked folder. The archive's own SHA-256 is recorded in the
                workspace audit log (`workspace.exit-exported`), so the copy you hold can be matched to the export.

                ## Layout

                | Path | Format | Content |
                |---|---|---|
                | `workspace.json` | JSON | Workspace id, key and name |
                | `schemas/workflow-v0.schema.json` | JSON Schema 2020-12 | Schema of every Orqix workflow JSON file |
                | `schemas/connection-definition-v0.schema.json` | JSON Schema 2020-12 | Schema of connection definitions |
                | `workflows/<key>/workflow.json` | JSON | Workflow name, owner, dates, active and released versions |
                | `workflows/<key>/draft.json` | Orqix workflow JSON | The latest saved draft (may differ from releases) |
                | `workflows/<key>/releases/<n>/workflow.json` | Orqix workflow JSON | Released version `n`, exactly as published |
                | `workflows/<key>/releases/<n>/process.bpmn20.xml` | BPMN 2.0 XML | The compiled process that ran |
                | `workflows/<key>/releases/<n>/decisions/*.dmn` | DMN 1.3 XML | Decision tables of that release |
                | `workflows/<key>/releases/<n>/source-map.json` | JSON | Maps BPMN element ids back to workflow nodes |
                | `workflows/<key>/releases/<n>/compile-manifest.json` | JSON | Groups, connections and child workflows the release needs |
                | `workflows/<key>/releases/<n>/release.json` | JSON | Release number, hashes, compiler version, publisher, date |
                | `forms/<key>/form.json`, `forms/<key>/v<n>.json` | JSON | Shared forms and every version |
                | `decision-tables/<key>/decision-table.json`, `v<n>.json` | JSON | Shared decision tables and every version |
                | `connections/<key>/connection.json`, `v<n>.json` | JSON | Connection definitions and the binding **without credentials** |
                | `history/runs.csv`, `.jsonl` | CSV, JSON Lines | One row per run |
                | `history/tasks.csv`, `.jsonl` | CSV, JSON Lines | One row per human task |
                | `history/decisions.csv`, `.jsonl` | CSV, JSON Lines | Approve/reject decisions with reason and comment |
                | `history/run-events.csv`, `.jsonl` | CSV, JSON Lines | The ordered event trail of each run |
                | `audit/audit-events.csv`, `.jsonl` | CSV, JSON Lines | Workspace audit log |
                | `people.csv`, `.jsonl` | CSV, JSON Lines | Everyone referenced above (id, name, e-mail, identity provider subject) |

                ## Formats

                * **Orqix workflow JSON** follows `schemas/workflow-v0.schema.json`. It is the source of truth for a
                  workflow and can be imported again into Orqix (create a workflow, save the file as its draft, publish).
                * **BPMN 2.0** files are the standard OMG format (`http://www.omg.org/spec/BPMN/20100524/MODEL`) and
                  open in any BPMN modeler (Camunda Modeler, bpmn.io, Signavio, Flowable). Service steps are Flowable
                  delegate expressions (`${orqix...}`); another engine needs its own implementation of those steps, as
                  described by the matching node in the workflow JSON.
                * **DMN** files are standard OMG decision tables and run in any DMN 1.3 engine.
                * **CSV** files are UTF-8, comma-separated, RFC 4180 quoting, header row first. Values are raw (no
                  spreadsheet formula escaping), so open them with "import" rather than double-click when a value could
                  start with `=`, `+`, `-` or `@`. Nested values (lists, objects) are JSON text in one cell.
                * **JSON Lines** (`.jsonl`) files hold one JSON object per line with the same columns as the CSV, with
                  nested values as real JSON. Prefer these for loading into another system.
                * Timestamps are ISO 8601 in UTC (`2026-01-31T09:15:00Z`). Ids are UUIDs; tasks use the engine task id.
                * People are referenced by id; `people.csv` resolves ids to names. Names in history files are the
                  name at the time of export.

                ## What is not in the archive

                * Secrets: connection credential references, tokens, API keys and OAuth grants are never exported.
                  Re-enter credentials in the target system.
                * %s
                * File attachments of runs, generated documents and engine-internal tables (Flowable `ACT_*`).
                * When only some workflows were selected, shared forms, decision tables and connections are still
                  exported in full, and the audit log holds only entries about the selected workflows, their releases
                  and their runs.
                """.formatted(scope, plan.createdAt(), plan.actorName(),
                plan.includeRunData()
                        ? "Run input and output data and run event details ARE included (`input`, `output`, `data` columns)."
                        : "Run input and output data and run event details were not requested and are omitted.");
    }

    // ---------------------------------------------------------------- helpers

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private String pretty(JsonNode node) {
        return json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
    }
}
