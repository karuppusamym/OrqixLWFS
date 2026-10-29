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
            Instant decidedAt, String decisionNote, String entry, List<FileInfo> files) { }
    public record Create(String name, String description) { }
    /** A bundle file as submitted: a flat name and its bytes in base64 (S116). */
    public record FileUpload(String path, String content) { }
    public record FileInfo(String path, String contentType, int size, String sha256) { }
    public record Submit(String source, JsonNode capabilities, Integer height, List<FileUpload> files, String entry) {
        public Submit(String source, JsonNode capabilities, Integer height) {
            this(source, capabilities, height, null, null);
        }
    }
    /** A file served to a frame; only for an approved or pending version and a valid, unexpired signed path. */
    public record FileContent(String contentType, byte[] content) { }

    public static final int MAX_FILES = 30;
    public static final int MAX_FILE_BYTES = 262_144;
    public static final int MAX_BUNDLE_BYTES = 1_048_576;
    static final java.time.Duration FILE_URL_LIFETIME = java.time.Duration.ofHours(12);
    static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,60}\\.(js|mjs|css|woff2|woff|png|jpg|jpeg|webp|gif)");
    static final Pattern TOKEN = Pattern.compile("([0-9]{1,12})\\.([0-9a-f]{64})");
    private static final Map<String, String> TYPES = Map.of("js", "text/javascript", "mjs", "text/javascript",
            "css", "text/css", "woff2", "font/woff2", "woff", "font/woff", "png", "image/png", "jpg", "image/jpeg",
            "jpeg", "image/jpeg", "webp", "image/webp", "gif", "image/gif");
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
        String source = request.source() == null ? "" : request.source();
        List<Bytes> files = files(request.files());
        String entry = request.entry() == null || request.entry().isBlank() ? null : request.entry();
        if (entry != null && (!entry.matches("[a-z0-9][a-z0-9_-]{0,60}\\.(js|mjs)") || files.stream().noneMatch(f -> f.path().equals(entry)))) {
            throw invalid("The entry must name one of the bundle's JavaScript files.");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > 65_536 || source.isBlank() && entry == null) {
            throw invalid("The bundle source must contain 1 to 65536 bytes, or name an entry module.");
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
        // One hash covers the inline source, the entry and every file, so approval approves exactly these bytes.
        StringBuilder manifest = new StringBuilder(Json.sha256Hex(source));
        if (entry != null) manifest.append("\nentry:").append(entry);
        files.stream().sorted(java.util.Comparator.comparing(Bytes::path))
                .forEach(f -> manifest.append('\n').append(f.path()).append(':').append(f.sha256()));
        String sha = files.isEmpty() ? Json.sha256Hex(source) : Json.sha256Hex(manifest.toString());
        byte[] key = null;
        if (!files.isEmpty()) {
            key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
        }
        jdbc.sql("""
                INSERT INTO ui_extension_version (workspace_id, extension_id, version, source, sha256, capabilities, height, submitted_by, entry, file_key)
                VALUES (:ws, :id, :version, :source, :sha, CAST(:capabilities AS jsonb), :height, :by, :entry, :key)
                """).param("ws", ws).param("id", id).param("version", next).param("source", source).param("sha", sha)
                .param("capabilities", json.write(capabilities)).param("height", height).param("by", caller.subjectId())
                .param("entry", entry).param("key", key).update();
        for (Bytes file : files) {
            jdbc.sql("""
                    INSERT INTO ui_extension_file (workspace_id, extension_id, version, path, content_type, content, sha256)
                    VALUES (:ws, :id, :version, :path, :type, :content, :sha)
                    """).param("ws", ws).param("id", id).param("version", next).param("path", file.path())
                    .param("type", file.contentType()).param("content", file.content()).param("sha", file.sha256()).update();
        }
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
        return frame(caller, ws, id, version, null);
    }

    /** {@code origin} is the application's own origin, used to give the frame absolute, signed file URLs (S116). */
    @Transactional(readOnly = true)
    public UiExtensionFrame.Document frame(Caller caller, UUID ws, UUID id, int version, String origin) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        record Row(String status, String source, String entry, byte[] key) { }
        Row row = jdbc.sql("""
                SELECT status, source, entry, file_key FROM ui_extension_version WHERE workspace_id = :ws AND extension_id = :id AND version = :version
                """).param("ws", ws).param("id", id).param("version", version)
                .query((rs, n) -> new Row(rs.getString("status"), rs.getString("source"), rs.getString("entry"), rs.getBytes("file_key")))
                .optional().orElseThrow(ApiException::notFound);
        boolean reviewer = access.has(Permission.WORKFLOW_EDIT) || access.has(Permission.WORKSPACE_MANAGE_ACCESS);
        boolean allowed = row.status().equals("approved") && access.member() || row.status().equals("pending") && reviewer;
        if (!allowed) throw new ApiException(ProblemCode.FORBIDDEN, "This extension version is not approved for use.");
        if (row.key() == null) return UiExtensionFrame.build(row.source());
        long expires = java.time.Instant.now().plus(FILE_URL_LIFETIME).getEpochSecond();
        String files = (origin == null ? "" : origin) + "/api/v1/workspaces/" + ws + "/ui-extensions/" + id + "/versions/" + version
                + "/files/" + expires + "." + mac(row.key(), ws, id, version, expires) + "/";
        String integrity = row.entry() == null ? null : jdbc.sql("""
                SELECT sha256 FROM ui_extension_file WHERE extension_id = :id AND version = :version AND path = :path
                """).param("id", id).param("version", version).param("path", row.entry()).query(String.class).single();
        return UiExtensionFrame.build(row.source(), files, row.entry(),
                integrity == null ? null : java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(integrity)));
    }

    /**
     * A file of a bundle for its frame (S116). The signed path is the credential, because a sandboxed frame's requests
     * carry no session; it is minted only by {@link #frame} for someone allowed to load that version, and expires.
     */
    @Transactional(readOnly = true)
    public FileContent file(UUID ws, UUID id, int version, String token, String path) {
        var match = token == null ? null : TOKEN.matcher(token);
        if (match == null || !match.matches() || path == null || !FILE.matcher(path).matches()) throw ApiException.notFound();
        long expires = Long.parseLong(match.group(1));
        if (expires < java.time.Instant.now().getEpochSecond()) throw ApiException.notFound();
        byte[] key = jdbc.sql("""
                SELECT file_key FROM ui_extension_version WHERE workspace_id = :ws AND extension_id = :id AND version = :version
                AND status IN ('approved', 'pending') AND file_key IS NOT NULL
                """).param("ws", ws).param("id", id).param("version", version).query((rs, n) -> rs.getBytes(1)).optional()
                .orElseThrow(ApiException::notFound);
        if (!java.security.MessageDigest.isEqual(mac(key, ws, id, version, expires).getBytes(StandardCharsets.US_ASCII),
                match.group(2).getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.notFound();
        }
        return jdbc.sql("""
                SELECT content_type, content FROM ui_extension_file
                WHERE workspace_id = :ws AND extension_id = :id AND version = :version AND path = :path
                """).param("ws", ws).param("id", id).param("version", version).param("path", path)
                .query((rs, n) -> new FileContent(rs.getString(1), rs.getBytes(2))).optional().orElseThrow(ApiException::notFound);
    }

    private static String mac(byte[] key, UUID ws, UUID id, int version, long expires) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal((ws + "|" + id + "|" + version + "|" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Bytes(String path, String contentType, byte[] content, String sha256) { }

    /** Flat names of allowed types, 1 to 256 KiB each, at most 30 files and 1 MiB in all; script and style text is UTF-8. */
    private static List<Bytes> files(List<FileUpload> uploads) {
        if (uploads == null || uploads.isEmpty()) return List.of();
        if (uploads.size() > MAX_FILES) throw invalid("A bundle can have at most " + MAX_FILES + " files.");
        List<Bytes> files = new java.util.ArrayList<>();
        Set<String> paths = new HashSet<>();
        int total = 0;
        for (FileUpload upload : uploads) {
            String path = upload == null ? null : upload.path();
            if (path == null || !FILE.matcher(path).matches() || !paths.add(path)) {
                throw invalid("File " + path + " needs a distinct lower-case name ending in .js, .mjs, .css, .woff2, .woff, .png, .jpg, .jpeg, .webp or .gif.");
            }
            byte[] content;
            try {
                content = java.util.Base64.getDecoder().decode(upload.content() == null ? "" : upload.content());
            } catch (IllegalArgumentException e) {
                throw invalid("File " + path + " must be sent as base64.");
            }
            if (content.length < 1 || content.length > MAX_FILE_BYTES) throw invalid("File " + path + " must contain 1 byte to 256 KiB.");
            total += content.length;
            String extension = path.substring(path.lastIndexOf('.') + 1);
            if (Set.of("js", "mjs", "css").contains(extension)) {
                try {
                    StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content));
                } catch (java.nio.charset.CharacterCodingException e) {
                    throw invalid("File " + path + " must be UTF-8 text.");
                }
            }
            String sha;
            try {
                sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            files.add(new Bytes(path, TYPES.get(extension), content, sha));
        }
        if (total > MAX_BUNDLE_BYTES) throw invalid("A bundle's files can hold at most 1 MiB in all.");
        return files;
    }

    static JsonNode capabilitiesShape(Json json, JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("Declare capabilities with bindings and actions.");
        for (String key : node.propertyNames()) {
            if (!key.equals("bindings") && !key.equals("actions")) throw invalid("capabilities/" + key + " is not supported.");
        }
        JsonNode bindings = node.path("bindings");
        JsonNode actions = node.path("actions");
        if (!bindings.isArray() || bindings.size() > 20 || !actions.isArray() || actions.size() > 15) {
            throw invalid("Declare at most 20 bindings and 15 actions.");
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
                            s.decidedAt(), s.decisionNote(), rs.getString("entry"), List.of());
                }).optional().map(d -> new VersionDetail(d.extensionId(), d.name(), d.source(), d.version(), d.status(),
                        d.sha256(), d.capabilities(), d.height(), d.submittedBy(), d.submittedAt(), d.decidedBy(), d.decidedAt(),
                        d.decisionNote(), d.entry(), jdbc.sql("""
                                SELECT path, content_type, octet_length(content) AS size, sha256 FROM ui_extension_file
                                WHERE extension_id = :id AND version = :version ORDER BY path
                                """).param("id", id).param("version", version).query((r, n) -> new FileInfo(r.getString("path"),
                                        r.getString("content_type"), r.getInt("size"), r.getString("sha256"))).list()))
                .orElseThrow(ApiException::notFound);
    }

    private static final String VERSION_SQL = """
            SELECT v.version, v.status, v.sha256, v.capabilities::text AS capabilities, v.height, v.source, v.entry, e.name,
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
