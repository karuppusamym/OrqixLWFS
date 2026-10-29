package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * UI extension registry (S111). Designers register bundles and submit immutable versions with declared capabilities;
 * a workspace administrator other than the submitter approves, rejects or later revokes each version. Only approved
 * versions can be published on pages or served to members. Capabilities bound what a page may grant, never what the
 * caller may do: every action still runs through the authorized chain operation.
 */
@Service
public class UiExtensions {
    public static final int MAX_EXTENSIONS = 50;
    public static final int MAX_VERSIONS = 50;
    private static final Pattern BINDING = Pattern.compile("(task|request|case)\\.[A-Za-z][A-Za-z0-9_-]{0,63}");

    public record Subject(UUID id, String displayName) { }
    public record VersionSummary(int version, String status, String sha256, JsonNode capabilities, int height,
            Subject submittedBy, Instant submittedAt, Subject decidedBy, Instant decidedAt, String decisionNote) { }
    public record Extension(UUID id, String name, String description, Instant createdAt, List<VersionSummary> versions) { }
    public record ExtensionList(List<Extension> items, boolean canApprove) { }
    public record VersionDetail(UUID extensionId, String name, String source, int version, String status, String sha256,
            JsonNode capabilities, int height, Subject submittedBy, Instant submittedAt, Subject decidedBy,
            Instant decidedAt, String decisionNote) { }
    public record Create(String name, String description) { }
    public record Submit(String source, JsonNode capabilities, Integer height) { }
    public record Decide(String decision, String note, String expectedStatus) { }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final Json json;
    private final AuditWriter audit;

    public UiExtensions(JdbcClient jdbc, PolicyService policy, Json json, AuditWriter audit) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.json = json;
        this.audit = audit;
    }

    private WorkspaceAccess readable(Caller caller, UUID ws) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        if (!access.has(Permission.WORKFLOW_READ) && !access.has(Permission.WORKSPACE_MANAGE_ACCESS)) {
            throw ApiException.forbidden();
        }
        return access;
    }

    @Transactional(readOnly = true)
    public ExtensionList list(Caller caller, UUID ws) {
        WorkspaceAccess access = readable(caller, ws);
        List<Extension> items = jdbc.sql("""
                SELECT id, name, description, created_at FROM ui_extension WHERE workspace_id = :ws ORDER BY lower(name), id
                """).param("ws", ws).query((rs, n) -> new Extension(Sql.uuid(rs, "id"), rs.getString("name"),
                        rs.getString("description"), Sql.instant(rs, "created_at"), List.of())).list();
        return new ExtensionList(items.stream().map(e -> new Extension(e.id(), e.name(), e.description(), e.createdAt(),
                versions(ws, e.id()))).toList(), access.has(Permission.WORKSPACE_MANAGE_ACCESS));
    }

    @Transactional(readOnly = true)
    public VersionDetail version(Caller caller, UUID ws, UUID id, int version) {
        readable(caller, ws);
        return detail(ws, id, version);
    }

    @Transactional
    public Extension create(Caller caller, UUID ws, Create request) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_EDIT, "createUiExtension", true);
        if (request == null) throw invalid("Name the extension.");
        String name = request.name();
        if (name == null || name.isBlank() || !name.equals(name.trim()) || name.length() > 80) {
            throw invalid("Give the extension a trimmed name of at most 80 characters.");
        }
        String description = request.description() == null || request.description().isBlank() ? null : request.description();
        if (description != null && (!description.equals(description.trim()) || description.length() > 300)) {
            throw invalid("Description must be trimmed and at most 300 characters.");
        }
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).single();
        int count = jdbc.sql("SELECT count(*) FROM ui_extension WHERE workspace_id = :ws").param("ws", ws)
                .query(Integer.class).single();
        if (count >= MAX_EXTENSIONS) throw invalid("A workspace can register at most " + MAX_EXTENSIONS + " extensions.");
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO ui_extension (id, workspace_id, name, description, created_by)
                    VALUES (:id, :ws, :name, :description, :by)
                    """).param("id", id).param("ws", ws).param("name", name).param("description", description)
                    .param("by", caller.subjectId()).update();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "An extension with this name already exists.");
        }
        write(caller, ws, "extension.created", id, Map.of("name", name));
        return new Extension(id, name, description, jdbc.sql("SELECT created_at FROM ui_extension WHERE id = :id")
                .param("id", id).query((rs, n) -> Sql.instant(rs, "created_at")).single(), List.of());
    }

    @Transactional
    public VersionDetail submit(Caller caller, UUID ws, UUID id, Submit request) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_EDIT, "submitUiExtensionVersion", true);
        jdbc.sql("SELECT id FROM ui_extension WHERE workspace_id = :ws AND id = :id FOR UPDATE").param("ws", ws)
                .param("id", id).query(UUID.class).optional().orElseThrow(ApiException::notFound);
        if (request == null) throw invalid("Supply the bundle source and capabilities.");
        String source = request.source();
        if (source == null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw invalid("The bundle source must contain 1 to 65536 bytes.");
        }
        if (!UiExtensionFrame.embeddable(source)) {
            throw invalid("The bundle source cannot contain script or comment markup.");
        }
        JsonNode capabilities = capabilities(request.capabilities());
        int height = request.height() == null ? 240 : request.height();
        if (height < 80 || height > 800) throw invalid("Height must be 80 to 800 pixels.");
        int next = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM ui_extension_version WHERE extension_id = :id")
                .param("id", id).query(Integer.class).single();
        if (next > MAX_VERSIONS) throw invalid("An extension can have at most " + MAX_VERSIONS + " versions.");
        String sha = Json.sha256Hex(source);
        jdbc.sql("""
                INSERT INTO ui_extension_version (workspace_id, extension_id, version, source, sha256, capabilities, height, submitted_by)
                VALUES (:ws, :id, :version, :source, :sha, CAST(:capabilities AS jsonb), :height, :by)
                """).param("ws", ws).param("id", id).param("version", next).param("source", source).param("sha", sha)
                .param("capabilities", json.write(capabilities)).param("height", height).param("by", caller.subjectId()).update();
        write(caller, ws, "extension.version.submitted", id, Map.of("version", next, "sha256", sha));
        return detail(ws, id, next);
    }

    @Transactional
    public VersionDetail decide(Caller caller, UUID ws, UUID id, int version, Decide request) {
        policy.require(policy.requireVisible(caller, ws), Permission.WORKSPACE_MANAGE_ACCESS, "decideUiExtensionVersion", true);
        var row = jdbc.sql("""
                SELECT status, submitted_by FROM ui_extension_version
                WHERE workspace_id = :ws AND extension_id = :id AND version = :version FOR UPDATE
                """).param("ws", ws).param("id", id).param("version", version)
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getObject("submitted_by", UUID.class)))
                .optional().orElseThrow(ApiException::notFound);
        if (request == null || request.note() == null || request.note().isBlank() || request.note().length() > 500
                || !request.note().equals(request.note().trim())) {
            throw invalid("Give a trimmed decision note of at most 500 characters.");
        }
        String decision = request.decision();
        if (!row.getKey().equals(request.expectedStatus())) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The extension version changed. Reload before deciding.")
                    .with("currentStatus", row.getKey());
        }
        if (row.getValue().equals(caller.subjectId())) {
            throw new ApiException(ProblemCode.FORBIDDEN, "The person who submitted an extension version cannot decide it.");
        }
        String next = switch (row.getKey() + ":" + decision) {
            case "pending:approve" -> "approved";
            case "pending:reject" -> "rejected";
            case "approved:revoke" -> "revoked";
            default -> throw invalid("A " + row.getKey() + " version cannot be " + (decision == null ? "decided" : decision + "d") + ".");
        };
        jdbc.sql("""
                UPDATE ui_extension_version SET status = :status, decided_by = :by, decided_at = now(), decision_note = :note
                WHERE workspace_id = :ws AND extension_id = :id AND version = :version
                """).param("status", next).param("by", caller.subjectId()).param("note", request.note()).param("ws", ws)
                .param("id", id).param("version", version).update();
        int seq = jdbc.sql("SELECT coalesce(max(seq), 0) + 1 FROM ui_extension_decision WHERE extension_id = :id AND version = :version")
                .param("id", id).param("version", version).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO ui_extension_decision (workspace_id, extension_id, version, seq, decision, note, decided_by)
                VALUES (:ws, :id, :version, :seq, :decision, :note, :by)
                """).param("ws", ws).param("id", id).param("version", version).param("seq", seq).param("decision", decision)
                .param("note", request.note()).param("by", caller.subjectId()).update();
        write(caller, ws, "extension.version.decided", id, Map.of("version", version, "decision", decision));
        return detail(ws, id, version);
    }

    /** Approved versions for members; pending versions only for reviewers (designers and workspace administrators). */
    @Transactional(readOnly = true)
    public UiExtensionFrame.Document frame(Caller caller, UUID ws, UUID id, int version) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        var row = jdbc.sql("""
                SELECT status, source FROM ui_extension_version WHERE workspace_id = :ws AND extension_id = :id AND version = :version
                """).param("ws", ws).param("id", id).param("version", version)
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getString("source")))
                .optional().orElseThrow(ApiException::notFound);
        boolean reviewer = access.has(Permission.WORKFLOW_EDIT) || access.has(Permission.WORKSPACE_MANAGE_ACCESS);
        boolean allowed = row.getKey().equals("approved") && access.member() || row.getKey().equals("pending") && reviewer;
        if (!allowed) throw new ApiException(ProblemCode.FORBIDDEN, "This extension version is not approved for use.");
        return UiExtensionFrame.build(row.getValue());
    }

    static JsonNode capabilitiesShape(Json json, JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("Declare capabilities with bindings and actions.");
        for (String key : node.propertyNames()) {
            if (!key.equals("bindings") && !key.equals("actions")) throw invalid("capabilities/" + key + " is not supported.");
        }
        JsonNode bindings = node.path("bindings");
        JsonNode actions = node.path("actions");
        if (!bindings.isArray() || bindings.size() > 20 || !actions.isArray() || actions.size() > 7) {
            throw invalid("Declare at most 20 bindings and 7 actions.");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode binding : bindings) {
            String value = binding.isString() ? binding.asString() : "";
            boolean task = value.startsWith("task.") && !PageDefinition.TASK_FIELDS.contains(value.substring(5));
            if (!BINDING.matcher(value).matches() || task || !seen.add(value)) {
                throw invalid("Binding " + value + " is not a distinct task summary, request or case field.");
            }
        }
        for (JsonNode action : actions) {
            String value = action.isString() ? action.asString() : "";
            if (!PageDefinition.STEP_ACTIONS.contains(value) || !seen.add("action:" + value)) {
                throw invalid("Action " + value + " is not a distinct allowlisted chain action.");
            }
        }
        var clean = json.object();
        clean.set("bindings", bindings);
        clean.set("actions", actions);
        return clean;
    }

    private JsonNode capabilities(JsonNode node) {
        return capabilitiesShape(json, node);
    }

    private List<VersionSummary> versions(UUID ws, UUID id) {
        return jdbc.sql(VERSION_SQL + " AND v.extension_id = :id ORDER BY v.version DESC").param("ws", ws).param("id", id)
                .query((rs, n) -> summary(rs)).list();
    }

    private VersionDetail detail(UUID ws, UUID id, int version) {
        return jdbc.sql(VERSION_SQL + " AND v.extension_id = :id AND v.version = :version").param("ws", ws).param("id", id)
                .param("version", version).query((rs, n) -> {
                    VersionSummary s = summary(rs);
                    return new VersionDetail(id, rs.getString("name"), rs.getString("source"), s.version(), s.status(),
                            s.sha256(), s.capabilities(), s.height(), s.submittedBy(), s.submittedAt(), s.decidedBy(),
                            s.decidedAt(), s.decisionNote());
                }).optional().orElseThrow(ApiException::notFound);
    }

    private static final String VERSION_SQL = """
            SELECT v.version, v.status, v.sha256, v.capabilities::text AS capabilities, v.height, v.source, e.name,
                   v.submitted_by, s.display_name AS submitted_name, v.submitted_at,
                   v.decided_by, d.display_name AS decided_name, v.decided_at, v.decision_note
            FROM ui_extension_version v
            JOIN ui_extension e ON e.workspace_id = v.workspace_id AND e.id = v.extension_id
            JOIN subject s ON s.id = v.submitted_by
            LEFT JOIN subject d ON d.id = v.decided_by
            WHERE v.workspace_id = :ws
            """;

    private VersionSummary summary(ResultSet rs) throws SQLException {
        UUID decidedBy = rs.getObject("decided_by", UUID.class);
        return new VersionSummary(rs.getInt("version"), rs.getString("status"), rs.getString("sha256"),
                json.parse(rs.getString("capabilities")), rs.getInt("height"),
                new Subject(rs.getObject("submitted_by", UUID.class), rs.getString("submitted_name")),
                Sql.instant(rs, "submitted_at"), decidedBy == null ? null : new Subject(decidedBy, rs.getString("decided_name")),
                rs.getObject("decided_at") == null ? null : Sql.instant(rs, "decided_at"), rs.getString("decision_note"));
    }

    private static ApiException invalid(String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message);
    }

    private void write(Caller caller, UUID ws, String action, UUID id, Map<String, Object> metadata) {
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), action, "uiExtension",
                id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }
}
