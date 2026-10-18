package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.SubjectService.SubjectRef;
import com.orqix.integration.DocumentGeneration;
import com.orqix.shared.Ids;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DOC08 (docs/build/S52): the workspace library of named, versioned document templates. Reading needs
 * {@code workflow.read}; creating, versioning and archiving need {@code workflow.edit}. Versions are immutable and
 * numbered 1..n; adding one sends the latest version the caller saw. A workflow step keeps the text it was published
 * with, so the registry never changes a published release.
 */
@Service
public class DocumentTemplates {

    public static final int MAX_TEMPLATES = 200;
    public static final int MAX_VERSIONS = 100;

    public record Version(int version, String format, String body, List<String> placeholders, String sha256,
            String changeNote, SubjectRef createdBy, Instant createdAt) {
    }

    public record Summary(UUID id, String key, String name, String description, int latestVersion, String format,
            int placeholderCount, boolean archived, Instant createdAt, Instant updatedAt, SubjectRef owner, int revision) {
    }

    public record Detail(UUID id, String key, String name, String description, int latestVersion, boolean archived,
            Instant createdAt, Instant updatedAt, List<Version> versions, SubjectRef owner, int revision) {
    }

    public record Page(List<Summary> items, int templateLimit) {
    }

    public record CreateRequest(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
            @NotBlank @Pattern(regexp = "pdf|docx") String format, @NotBlank @Size(max = 16000) String body,
            @Size(max = 300) String changeNote,
            @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String key) {
    }

    public record VersionRequest(@Min(1) int expectedVersion, @NotBlank @Pattern(regexp = "pdf|docx") String format,
            @NotBlank @Size(max = 16000) String body, @Size(max = 300) String changeNote) {
    }

    public record ArchiveRequest(boolean archived) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;

    public DocumentTemplates(JdbcClient jdbc, PolicyService policy, SubjectService subjects, AuditWriter audit) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
    }

    private void reader(Caller caller, UUID ws, String operation) {
        policy.require(policy.requireVisible(caller, ws), Permission.WORKFLOW_READ, operation, false);
    }

    private void editor(Caller caller, UUID ws, String operation) {
        policy.require(policy.requireVisible(caller, ws), Permission.WORKFLOW_EDIT, operation, true);
    }

    private static final String SUMMARY = """
            SELECT t.id, t.key, t.name, t.description, t.latest_version, t.archived_at, t.created_at, t.updated_at,
                   t.owner_subject_id, t.revision, v.format, cardinality(v.placeholders) AS placeholder_count
            FROM document_template t
            JOIN document_template_version v ON v.template_id = t.id AND v.version = t.latest_version
            """;

    private static Summary summary(ResultSet rs, int n) throws SQLException {
        return new Summary(Sql.uuid(rs, "id"), rs.getString("key"), rs.getString("name"), rs.getString("description"),
                rs.getInt("latest_version"), rs.getString("format"), rs.getInt("placeholder_count"),
                rs.getTimestamp("archived_at") != null, Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                new SubjectRef(Sql.uuid(rs, "owner_subject_id"), null), rs.getInt("revision"));
    }

    private List<Summary> named(List<Summary> items) {
        Map<UUID, String> names = subjects.displayNames(items.stream().map(s -> s.owner().id()).distinct().toList());
        return items.stream().map(s -> new Summary(s.id(), s.key(), s.name(), s.description(), s.latestVersion(),
                s.format(), s.placeholderCount(), s.archived(), s.createdAt(), s.updatedAt(),
                subjects.ref(s.owner().id(), names), s.revision())).toList();
    }

    @Transactional(readOnly = true)
    public Page list(Caller caller, UUID ws, boolean includeArchived) {
        reader(caller, ws, "listDocumentTemplates");
        List<Summary> items = jdbc.sql(SUMMARY + " WHERE t.workspace_id = :ws"
                + (includeArchived ? "" : " AND t.archived_at IS NULL") + " ORDER BY lower(t.name), t.id")
                .param("ws", ws).query(DocumentTemplates::summary).list();
        return new Page(named(items), MAX_TEMPLATES);
    }

    @Transactional(readOnly = true)
    public Detail get(Caller caller, UUID ws, UUID id) {
        reader(caller, ws, "getDocumentTemplate");
        return detail(ws, id);
    }

    @Transactional
    public Detail create(Caller caller, UUID ws, CreateRequest request) {
        editor(caller, ws, "createDocumentTemplate");
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the template a name.");
        }
        List<String> placeholders = placeholders(request.body());
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).optional();
        int count = jdbc.sql("SELECT count(*) FROM document_template WHERE workspace_id = :ws").param("ws", ws)
                .query(Integer.class).single();
        if (count >= MAX_TEMPLATES) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "A workspace can have at most " + MAX_TEMPLATES + " document templates.");
        }
        UUID id = Ids.newId();
        // The key identifies the template in versioned workflow references (S57). It is derived from the name when not
        // given; an explicit key that is taken is a conflict, a derived one gets a number.
        String key = request.key() != null ? request.key() : freeKey(ws, name);
        int inserted = jdbc.sql("""
                INSERT INTO document_template (id, workspace_id, key, name, description, owner_subject_id, created_by)
                VALUES (:id, :ws, :key, :name, :description, :by, :by) ON CONFLICT DO NOTHING
                """).param("id", id).param("ws", ws).param("key", key).param("name", name)
                .param("description", blankToNull(request.description())).param("by", caller.subjectId()).update();
        if (inserted != 1) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A template with this name or key already exists.");
        }
        insertVersion(ws, id, 1, request.format(), request.body(), placeholders, request.changeNote(), caller.subjectId());
        audit(caller, ws, "document.template.created", id, Map.of("version", 1, "placeholders", placeholders.size()));
        return detail(ws, id);
    }

    @Transactional
    public Detail addVersion(Caller caller, UUID ws, UUID id, VersionRequest request) {
        editor(caller, ws, "addDocumentTemplateVersion");
        List<String> placeholders = placeholders(request.body());
        Locked row = lock(ws, id);
        if (row.latest() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The template changed. Reload before saving.")
                    .with("currentVersion", row.latest());
        }
        if (row.archived()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Restore the template before adding a version.");
        }
        if (row.latest() >= MAX_VERSIONS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "A template can have at most " + MAX_VERSIONS + " versions.");
        }
        String latestHash = jdbc.sql("SELECT sha256 FROM document_template_version WHERE template_id = :id AND version = :v")
                .param("id", id).param("v", row.latest()).query(String.class).single();
        if (sha256(request.body(), request.format()).equals(latestHash)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The new version is identical to the latest version.");
        }
        int next = row.latest() + 1;
        insertVersion(ws, id, next, request.format(), request.body(), placeholders, request.changeNote(), caller.subjectId());
        jdbc.sql("UPDATE document_template SET latest_version = :v, updated_at = now() WHERE id = :id")
                .param("v", next).param("id", id).update();
        audit(caller, ws, "document.template.versioned", id, Map.of("version", next, "placeholders", placeholders.size()));
        return detail(ws, id);
    }

    @Transactional
    public Detail setArchived(Caller caller, UUID ws, UUID id, ArchiveRequest request) {
        editor(caller, ws, "setDocumentTemplateArchived");
        Locked row = lock(ws, id);
        if (row.archived() != request.archived()) {
            jdbc.sql("UPDATE document_template SET archived_at = :at, revision = revision + 1, updated_at = now() WHERE id = :id")
                    .param("at", request.archived() ? Sql.ts(Instant.now()) : null).param("id", id).update();
            audit(caller, ws, request.archived() ? "document.template.archived" : "document.template.restored", id, Map.of());
        }
        return detail(ws, id);
    }

    // ---------------------------------------------------------------- internals

    private record Locked(int latest, boolean archived) {
    }

    private Locked lock(UUID ws, UUID id) {
        return jdbc.sql("SELECT latest_version, archived_at IS NOT NULL AS archived FROM document_template WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", ws).param("id", id)
                .query((rs, n) -> new Locked(rs.getInt("latest_version"), rs.getBoolean("archived")))
                .optional().orElseThrow(ApiException::notFound);
    }

    private record Row(int version, String format, String body, List<String> placeholders, String sha, String note,
            UUID by, Instant at) {
    }

    private Detail detail(UUID ws, UUID id) {
        Summary s = jdbc.sql(SUMMARY + " WHERE t.workspace_id = :ws AND t.id = :id").param("ws", ws).param("id", id)
                .query(DocumentTemplates::summary).optional().orElseThrow(ApiException::notFound);
        List<Row> rows = jdbc.sql("""
                SELECT version, format, body, placeholders, sha256, change_note, created_by, created_at
                FROM document_template_version WHERE template_id = :id ORDER BY version DESC
                """).param("id", id).query((rs, n) -> new Row(rs.getInt("version"), rs.getString("format"),
                        rs.getString("body"), List.of((String[]) rs.getArray("placeholders").getArray()),
                        rs.getString("sha256"), rs.getString("change_note"), Sql.uuid(rs, "created_by"),
                        Sql.instant(rs, "created_at"))).list();
        List<UUID> people = new ArrayList<>(rows.stream().map(Row::by).distinct().toList());
        people.add(s.owner().id());
        Map<UUID, String> names = subjects.displayNames(people);
        List<Version> versions = new ArrayList<>();
        for (Row r : rows) {
            versions.add(new Version(r.version(), r.format(), r.body(), r.placeholders(), r.sha(), r.note(),
                    subjects.ref(r.by(), names), r.at()));
        }
        return new Detail(s.id(), s.key(), s.name(), s.description(), s.latestVersion(), s.archived(), s.createdAt(),
                s.updatedAt(), versions, subjects.ref(s.owner().id(), names), s.revision());
    }

    private void insertVersion(UUID ws, UUID id, int version, String format, String body, List<String> placeholders,
            String note, UUID by) {
        jdbc.sql("""
                INSERT INTO document_template_version (workspace_id, template_id, version, format, body, placeholders,
                                                       sha256, change_note, created_by)
                VALUES (:ws, :id, :v, :format, :body, :placeholders, :sha, :note, :by)
                """).param("ws", ws).param("id", id).param("v", version).param("format", format).param("body", body)
                .param("placeholders", placeholders.toArray(new String[0])).param("sha", sha256(body, format))
                .param("note", blankToNull(note)).param("by", by).update();
    }

    private static List<String> placeholders(String body) {
        try {
            return DocumentGeneration.placeholders(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private void audit(Caller caller, UUID ws, String action, UUID id, Map<String, Object> metadata) {
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), action,
                "documentTemplate", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }

    /** A key derived from the name: lower-case letters, digits and dashes, numbered when already taken. */
    private String freeKey(UUID ws, String name) {
        String base = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = ("template-" + base).replaceAll("-+$", "");
        }
        if (base.length() > 56) {
            base = base.substring(0, 56).replaceAll("-+$", "");
        }
        List<String> taken = jdbc.sql("SELECT key FROM document_template WHERE workspace_id = :ws AND key LIKE :prefix")
                .param("ws", ws).param("prefix", base + "%").query(String.class).list();
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + "-" + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(String body, String format) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((format + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
