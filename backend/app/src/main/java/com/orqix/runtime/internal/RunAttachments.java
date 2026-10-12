package com.orqix.runtime.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.SubjectService.SubjectRef;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.config.AttachmentSettings;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Files attached to requests (docs/build/S17 sections 1-3). Visibility follows the request data
 * ({@link RunAccess.Visible#dataVisible()}): initiator, task participants and holders of {@code run.read-data}.
 * Files are stored in PostgreSQL; their type is decided by name, declared type and bytes together
 * ({@link AttachmentTypes}); a scanner port sets the scan status; downloads of blocked files are refused.
 */
@Service
public class RunAttachments {

    /** The file part of an upload. {@code stream} is read at most {@code limit + 1} bytes. */
    public record Upload(String fileName, String contentType, InputStream stream) {
    }

    public record Attachment(UUID id, UUID runId, String fieldKey, String name, String description, String contentType,
            long sizeBytes, String sha256, String scanStatus, SubjectRef uploadedBy, Instant uploadedAt,
            Instant removedAt, SubjectRef removedBy, boolean canRemove, boolean downloadable) {
    }

    public record Limits(long maxFileBytes, int maxFiles, long maxTotalBytes, int usedFiles, long usedBytes,
            List<String> allowedTypes) {
    }

    public record AttachmentList(List<Attachment> items, Limits limits, boolean canAdd) {
    }

    /** A file chosen on a request form that has not been started yet. */
    public record Staged(UUID id, String name, String contentType, long sizeBytes, String sha256, String scanStatus,
            Instant expiresAt) {
    }

    public record Added(Attachment attachment, boolean replayed) {
    }

    public record StagedResult(Staged staged, boolean replayed) {
    }

    public record Content(byte[] bytes, String name, String sha256) {
    }

    /** Staged files a start will attach, validated and locked. */
    public record StartPlan(List<Planned> files) {
        public static final StartPlan NONE = new StartPlan(List.of());
    }

    public record Planned(UUID id, String fieldKey) {
    }

    private record Stored(String name, String description, byte[] bytes, String sha256, AttachmentTypes.Type type) {
    }

    private static final String COLUMNS = """
            a.id, a.run_id, a.field_key, a.uploaded_by, a.file_name, a.description, a.content_type, a.size_bytes, a.sha256,
            a.scan_status, a.created_at, a.expires_at, a.deleted_at, a.deleted_by
            """;

    private final JdbcClient jdbc;
    private final RunAccess access;
    private final PolicyService policy;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final RunEvents events;
    private final SubjectService subjects;
    private final AttachmentScanner scanner;
    private final AttachmentSettings settings;
    private final Clock clock;

    public RunAttachments(JdbcClient jdbc, RunAccess access, PolicyService policy, IdempotencyService idempotency,
            AuditWriter audit, RunEvents events, SubjectService subjects, AttachmentScanner scanner,
            AttachmentSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.subjects = subjects;
        this.scanner = scanner;
        this.settings = settings;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- run files

    /** Internal engine entrypoint only: output of a release-pinned document step, never a caller-supplied run ID. */
    @Transactional
    public UUID addGenerated(UUID workspaceId, UUID runId, UUID initiator, String nodeId,
            com.orqix.integration.DocumentGeneration.Generated generated) {
        lockRun(runId);
        var existing = jdbc.sql("SELECT id FROM run_attachment WHERE workspace_id = :ws AND run_id = :run AND generated_node_id = :node")
                .param("ws", workspaceId).param("run", runId).param("node", nodeId).query(UUID.class).optional();
        if (existing.isPresent()) return existing.get();
        String lifecycle = jdbc.sql("SELECT lifecycle FROM workflow_run WHERE workspace_id = :ws AND id = :id FOR NO KEY UPDATE")
                .param("ws", workspaceId).param("id", runId).query(String.class).single();
        if (!Set.of("running", "waiting", "needsAttention").contains(lifecycle))
            throw new IllegalStateException("Document generation requires an active request.");
        Stored stored = read(new Upload(generated.name(), generated.contentType(),
                new java.io.ByteArrayInputStream(generated.bytes())), "Generated by workflow");
        long[] used = usage(runId);
        if (used[0] >= settings.maxFilesPerRun() || used[1] + stored.bytes().length > settings.maxBytesPerRun())
            throw new ApiException(ProblemCode.ATTACHMENT_LIMIT_REACHED, "The generated document exceeds the request's file quota.");
        AttachmentScanner.Status scan = scanner.scan(stored.bytes());
        if (scan.blocksDownload()) throw new IllegalStateException("The generated document could not pass the configured scanner.");
        UUID id = Ids.newId();
        insert(id, workspaceId, runId, null, initiator, stored, scan, null);
        jdbc.sql("UPDATE run_attachment SET generated_node_id = :node WHERE id = :id")
                .param("node", nodeId).param("id", id).update();
        recordAdded(workspaceId, runId, initiator, id, stored, scan, false);
        return id;
    }

    @Transactional(readOnly = true)
    public AttachmentList list(Caller caller, UUID workspaceId, UUID runId) {
        RunAccess.Visible visible = access.require(caller, workspaceId, runId);
        if (!visible.dataVisible()) {
            throw ApiException.forbidden();
        }
        List<Row> rows = jdbc.sql("SELECT " + COLUMNS + """
                 FROM run_attachment a WHERE a.workspace_id = :ws AND a.run_id = :run
                 ORDER BY a.created_at, a.id
                """).param("ws", workspaceId).param("run", runId).query(RunAttachments::row).list();
        Map<UUID, String> names = subjects.displayNames(rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.uploadedBy(), r.deletedBy())).filter(java.util.Objects::nonNull)
                .distinct().toList());
        List<Attachment> items = rows.stream().map(r -> view(r, names, canRemove(visible, r))).toList();
        int usedFiles = (int) rows.stream().filter(r -> r.deletedAt() == null).count();
        long usedBytes = rows.stream().filter(r -> r.deletedAt() == null).mapToLong(Row::size).sum();
        return new AttachmentList(items, limits(usedFiles, usedBytes), canAdd(visible));
    }

    @Transactional
    public Added add(Caller caller, UUID workspaceId, UUID runId, String idempotencyKey, Upload upload,
            String description) {
        RunAccess.Visible visible = access.require(caller, workspaceId, runId);
        if (!canAddRole(visible)) {
            throw ApiException.forbidden();
        }
        if (!visible.run().isActive()) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "Files can only be added while the request is in progress.");
        }
        String key = IdempotencyService.requireKey(idempotencyKey);
        requireFile(upload);
        Stored stored = read(upload, description);
        String hash = idempotency.requestHash("addRunAttachment", Map.of("runId", runId.toString()), Map.of("sha256",
                stored.sha256(), "size", stored.bytes().length, "name", stored.name(), "description",
                stored.description() == null ? "" : stored.description()));
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "addRunAttachment", key, hash);
        if (claim.isReplay()) {
            UUID id = UUID.fromString(claim.replay().resourceId());
            return new Added(one(visible, id), true);
        }
        lockUploadOwner(caller.subjectId());
        rateLimit(caller.subjectId());
        AttachmentScanner.Status scan = scanner.scan(stored.bytes());
        lockRun(runId);
        String lifecycle = jdbc.sql("SELECT lifecycle FROM workflow_run WHERE id = :id FOR NO KEY UPDATE")
                .param("id", runId).query(String.class).single();
        if (!Set.of("running", "waiting", "needsAttention").contains(lifecycle)) {
            throw new ApiException(ProblemCode.RUN_NOT_ACTIVE, "Files can only be added while the request is in progress.");
        }
        long[] used = usage(runId);
        if (used[0] >= settings.maxFilesPerRun()) {
            throw new ApiException(ProblemCode.ATTACHMENT_LIMIT_REACHED, "This request already has "
                    + settings.maxFilesPerRun() + " files. Remove one first.");
        }
        if (used[1] + stored.bytes().length > settings.maxBytesPerRun()) {
            throw new ApiException(ProblemCode.ATTACHMENT_LIMIT_REACHED, "The files of this request would exceed "
                    + mib(settings.maxBytesPerRun()) + " in total. Remove a file first.");
        }
        UUID id = Ids.newId();
        insert(id, workspaceId, runId, null, caller.subjectId(), stored, scan, null);
        recordAdded(workspaceId, runId, caller.subjectId(), id, stored, scan, false);
        idempotency.complete(claim, "runAttachment", id.toString(), 201);
        return new Added(one(visible, id), false);
    }

    /** The file for download, or 404 (unknown or removed) / 409 (blocked by the scan). */
    @Transactional(readOnly = true)
    public Content content(Caller caller, UUID workspaceId, UUID runId, UUID attachmentId) {
        RunAccess.Visible visible = access.require(caller, workspaceId, runId);
        if (!visible.dataVisible()) {
            throw ApiException.forbidden();
        }
        Row row = find(workspaceId, runId, attachmentId);
        if (row == null || row.deletedAt() != null) {
            throw ApiException.notFound();
        }
        if (AttachmentScanner.Status.of(row.scanStatus()).blocksDownload()) {
            throw new ApiException(ProblemCode.ATTACHMENT_BLOCKED, row.scanStatus().equals("infected")
                    ? "This file was blocked because the virus scan found a problem."
                    : "This file was blocked because it could not be scanned.");
        }
        byte[] bytes = jdbc.sql("SELECT content FROM run_attachment_content WHERE attachment_id = :id")
                .param("id", attachmentId).query(byte[].class).optional().orElseThrow(ApiException::notFound);
        return new Content(bytes, row.name(), row.sha256());
    }

    /** Soft delete: the metadata stays, the bytes are deleted. Removing a removed file succeeds silently. */
    @Transactional
    public void remove(Caller caller, UUID workspaceId, UUID runId, UUID attachmentId) {
        RunAccess.Visible visible = access.require(caller, workspaceId, runId);
        if (!visible.dataVisible()) {
            throw ApiException.forbidden();
        }
        // Serialize with other attachment changes and lifecycle projection before deciding removal authority.
        lockRun(runId);
        jdbc.sql("SELECT lifecycle FROM workflow_run WHERE id = :id FOR NO KEY UPDATE")
                .param("id", runId).query(String.class).single();
        visible = access.require(caller, workspaceId, runId);
        Row row = find(workspaceId, runId, attachmentId);
        if (row == null) {
            throw ApiException.notFound();
        }
        if (!canRemove(visible, row)) {
            throw ApiException.forbidden();
        }
        if (row.deletedAt() != null) {
            return;
        }
        int changed = jdbc.sql("""
                UPDATE run_attachment SET deleted_at = :now, deleted_by = :by
                WHERE id = :id AND deleted_at IS NULL
                """).param("now", Sql.ts(clock.instant())).param("by", caller.subjectId()).param("id", attachmentId).update();
        if (changed == 0) return;
        jdbc.sql("DELETE FROM run_attachment_content WHERE attachment_id = :id").param("id", attachmentId).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "attachment.removed", "attachment", attachmentId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("runId", runId.toString(), "attachmentId", attachmentId.toString())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attachmentId", attachmentId.toString());
        data.put("name", row.name());
        events.append(workspaceId, runId, "ATTACHMENT_REMOVED", null, caller.subjectId(), data);
    }

    // ---------------------------------------------------------------- request-form uploads

    @Transactional
    public StagedResult stage(Caller caller, UUID workspaceId, String idempotencyKey, Upload upload) {
        WorkspaceAccess workspace = policy.requireVisible(caller, workspaceId);
        if (!workspace.canStartAnything()) {
            throw ApiException.forbidden();
        }
        String key = IdempotencyService.requireKey(idempotencyKey);
        requireFile(upload);
        Stored stored = read(upload, null);
        String hash = idempotency.requestHash("uploadAttachment", Map.of("workspaceId", workspaceId.toString()),
                Map.of("sha256", stored.sha256(), "size", stored.bytes().length, "name", stored.name()));
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "uploadAttachment", key, hash);
        if (claim.isReplay()) {
            return new StagedResult(staged(workspaceId, caller.subjectId(), UUID.fromString(claim.replay().resourceId())),
                    true);
        }
        lockUploadOwner(caller.subjectId());
        rateLimit(caller.subjectId());
        Instant now = clock.instant();
        int waiting = jdbc.sql("""
                SELECT count(*) FROM run_attachment
                WHERE workspace_id = :ws AND uploaded_by = :by AND run_id IS NULL AND deleted_at IS NULL
                  AND expires_at > :now
                """).param("ws", workspaceId).param("by", caller.subjectId()).param("now", Sql.ts(now))
                .query(Integer.class).single();
        if (waiting >= settings.maxStagedPerSubject()) {
            throw new ApiException(ProblemCode.ATTACHMENT_LIMIT_REACHED, "You have " + waiting
                    + " files waiting on request forms. Start those requests or remove a file first.");
        }
        AttachmentScanner.Status scan = scanner.scan(stored.bytes());
        UUID id = Ids.newId();
        insert(id, workspaceId, null, null, caller.subjectId(), stored, scan, now.plus(settings.stagedTtl()));
        recordAdded(workspaceId, null, caller.subjectId(), id, stored, scan, true);
        idempotency.complete(claim, "attachmentUpload", id.toString(), 201);
        return new StagedResult(staged(workspaceId, caller.subjectId(), id), false);
    }

    /** Removes a file chosen on a form (never attached). Unknown or foreign IDs are 404. */
    @Transactional
    public void discard(Caller caller, UUID workspaceId, UUID uploadId) {
        policy.requireVisible(caller, workspaceId);
        Row row = jdbc.sql("SELECT " + COLUMNS + """
                 FROM run_attachment a WHERE a.workspace_id = :ws AND a.id = :id AND a.run_id IS NULL
                   AND a.uploaded_by = :by AND a.deleted_at IS NULL FOR UPDATE
                """).param("ws", workspaceId).param("id", uploadId).param("by", caller.subjectId())
                .query(RunAttachments::row).optional().orElseThrow(ApiException::notFound);
        jdbc.sql("DELETE FROM run_attachment_content WHERE attachment_id = :id").param("id", row.id()).update();
        // Retain admission history through the staged TTL so discard cannot reset the per-minute upload limit.
        jdbc.sql("UPDATE run_attachment SET deleted_at = :now, deleted_by = :by WHERE id = :id")
                .param("now", Sql.ts(clock.instant())).param("by", caller.subjectId()).param("id", row.id()).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "attachment.removed", "attachment", row.id().toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("attachmentId", row.id().toString())));
    }

    // ---------------------------------------------------------------- start

    /**
     * Validates the files a request form refers to (fields of {@code format: attachment}) and locks them: they must be
     * the caller's own, unexpired, not blocked, and fit the field's file types and size. Called before the run is
     * inserted; failures are 422 field errors on {@code /input/<field>}.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public StartPlan planStart(Caller caller, UUID workspaceId, JsonNode inputSchema, JsonNode input) {
        List<Planned> planned = new ArrayList<>();
        List<ApiException.FieldError> errors = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        long totalBytes = 0;
        // Serializes competing starts by this uploader before taking file locks in definition order.
        lockUploadOwner(caller.subjectId());
        Instant now = clock.instant();
        for (Map.Entry<String, JsonNode> field : inputSchema.path("properties").properties()) {
            JsonNode schema = field.getValue();
            if (!"attachment".equals(schema.path("format").asString(null)) || !input.path(field.getKey()).isString()) {
                continue;
            }
            String pointer = "/input/" + field.getKey();
            UUID id = UUID.fromString(input.path(field.getKey()).asString());
            Row row = jdbc.sql("SELECT " + COLUMNS + """
                     FROM run_attachment a WHERE a.workspace_id = :ws AND a.id = :id AND a.run_id IS NULL
                       AND a.uploaded_by = :by AND a.deleted_at IS NULL AND a.expires_at > :now FOR UPDATE
                    """).param("ws", workspaceId).param("id", id).param("by", caller.subjectId())
                    .param("now", Sql.ts(now)).query(RunAttachments::row).optional().orElse(null);
            if (row == null || !seen.add(id)) {
                errors.add(new ApiException.FieldError(pointer, "attachment",
                        "This file is no longer available. Choose it again."));
                continue;
            }
            String problem = fits(schema, row);
            if (problem != null) {
                errors.add(new ApiException.FieldError(pointer, "attachment", problem));
                continue;
            }
            planned.add(new Planned(id, field.getKey()));
            totalBytes += row.size();
        }
        if (!planned.isEmpty() && (planned.size() > settings.maxFilesPerRun() || totalBytes > settings.maxBytesPerRun())) {
            errors.add(new ApiException.FieldError("/input/" + planned.get(planned.size() - 1).fieldKey(), "attachmentLimit",
                    "The request files exceed the limit of " + settings.maxFilesPerRun() + " files or "
                            + mib(settings.maxBytesPerRun()) + " in total."));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some values are not valid.").withFieldErrors(errors);
        }
        return planned.isEmpty() ? StartPlan.NONE : new StartPlan(planned);
    }

    /** Attaches the planned files to the new run and records them on its timeline. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void attachAtStart(StartPlan plan, UUID workspaceId, UUID runId, UUID actor) {
        for (Planned file : plan.files()) {
            jdbc.sql("""
                    UPDATE run_attachment SET run_id = :run, field_key = :field, expires_at = NULL
                    WHERE id = :id AND workspace_id = :ws AND run_id IS NULL
                    """).param("run", runId).param("field", file.fieldKey()).param("id", file.id()).param("ws", workspaceId)
                    .update();
            Row row = find(workspaceId, runId, file.id());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attachmentId", file.id().toString());
            data.put("name", row.name());
            data.put("sizeBytes", row.size());
            data.put("contentType", row.contentType());
            data.put("fieldKey", file.fieldKey());
            events.append(workspaceId, runId, "ATTACHMENT_ADDED", null, actor, data);
        }
    }

    private String fits(JsonNode schema, Row row) {
        AttachmentTypes.Type type = typeOf(row.contentType());
        JsonNode accept = schema.path("acceptTypes");
        if (accept.isArray() && type != null) {
            boolean ok = false;
            List<String> labels = new ArrayList<>();
            for (JsonNode a : accept) {
                ok |= a.asString().equals(type.key());
                AttachmentTypes.Type t = AttachmentTypes.Type.ofKey(a.asString()).orElse(null);
                labels.add(t == null ? a.asString() : t.label());
            }
            if (!ok) {
                return "Choose one of: " + String.join(", ", labels) + ".";
            }
        }
        long max = schema.path("maxFileBytes").asLong(settings.maxFileBytes());
        if (row.size() > Math.min(max, settings.maxFileBytes())) {
            return "Choose a file of at most " + mib(Math.min(max, settings.maxFileBytes())) + ".";
        }
        if (AttachmentScanner.Status.of(row.scanStatus()).blocksDownload()) {
            return "This file was blocked by the virus scan. Choose another file.";
        }
        return null;
    }

    // ---------------------------------------------------------------- cleanup

    /** Deletes files that were chosen on a form but never attached to a started request. Safe on several pods. */
    @Scheduled(fixedDelayString = "${orqix.attachments.cleanup-delay:15m}")
    @Transactional
    public void cleanup() {
        deleteExpired();
    }

    /** @return the number of expired staged files removed */
    @Transactional
    public int deleteExpired() {
        List<UUID> expired = jdbc.sql("""
                SELECT id FROM run_attachment WHERE run_id IS NULL AND expires_at < :now
                ORDER BY expires_at LIMIT 200 FOR UPDATE SKIP LOCKED
                """).param("now", Sql.ts(clock.instant())).query(UUID.class).list();
        for (UUID id : expired) {
            jdbc.sql("DELETE FROM run_attachment_content WHERE attachment_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM run_attachment WHERE id = :id").param("id", id).update();
        }
        return expired.size();
    }

    // ---------------------------------------------------------------- internals

    private record Row(UUID id, UUID runId, String fieldKey, UUID uploadedBy, String name, String description,
            String contentType, long size, String sha256, String scanStatus, Instant createdAt, Instant expiresAt,
            Instant deletedAt, UUID deletedBy) {
    }

    private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "run_id"), rs.getString("field_key"), Sql.uuid(rs, "uploaded_by"),
                rs.getString("file_name"), rs.getString("description"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("scan_status"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "expires_at"), Sql.instant(rs, "deleted_at"),
                Sql.uuid(rs, "deleted_by"));
    }

    private Row find(UUID workspaceId, UUID runId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run_attachment a WHERE a.workspace_id = :ws AND a.run_id = :run AND a.id = :id")
                .param("ws", workspaceId).param("run", runId).param("id", id).query(RunAttachments::row).optional()
                .orElse(null);
    }

    private Attachment one(RunAccess.Visible visible, UUID id) {
        Row row = find(visible.run().workspaceId(), visible.run().id(), id);
        Map<UUID, String> names = subjects.displayNames(java.util.stream.Stream.of(row.uploadedBy(), row.deletedBy())
                .filter(java.util.Objects::nonNull).toList());
        return view(row, names, canRemove(visible, row));
    }

    private Staged staged(UUID workspaceId, UUID owner, UUID id) {
        Row row = jdbc.sql("SELECT " + COLUMNS + " FROM run_attachment a WHERE a.workspace_id = :ws AND a.id = :id AND a.uploaded_by = :by")
                .param("ws", workspaceId).param("id", id).param("by", owner).query(RunAttachments::row).optional()
                .orElseThrow(ApiException::notFound);
        return new Staged(row.id(), row.name(), row.contentType(), row.size(), row.sha256(), row.scanStatus(),
                row.expiresAt());
    }

    private Attachment view(Row r, Map<UUID, String> names, boolean canRemove) {
        boolean removed = r.deletedAt() != null;
        return new Attachment(r.id(), r.runId(), r.fieldKey(), r.name(), r.description(), r.contentType(), r.size(),
                r.sha256(), r.scanStatus(), subjects.ref(r.uploadedBy(), names), r.createdAt(), r.deletedAt(),
                r.deletedBy() == null ? null : subjects.ref(r.deletedBy(), names), canRemove && !removed,
                !removed && !AttachmentScanner.Status.of(r.scanStatus()).blocksDownload());
    }

    private Limits limits(int usedFiles, long usedBytes) {
        List<String> types = new ArrayList<>();
        for (AttachmentTypes.Type t : AttachmentTypes.Type.values()) {
            types.add(t.key());
        }
        return new Limits(settings.maxFileBytes(), settings.maxFilesPerRun(), settings.maxBytesPerRun(), usedFiles,
                usedBytes, types);
    }

    /** Adding follows the rules of run comments: you must see the data and take part (or operate) and the run must be active. */
    private static boolean canAddRole(RunAccess.Visible visible) {
        return visible.dataVisible()
                && (visible.initiator() || visible.participant() || visible.access().has(Permission.RUN_OPERATE));
    }

    private static boolean canAdd(RunAccess.Visible visible) {
        return canAddRole(visible) && visible.run().isActive();
    }

    /** The initiator while the request runs, and operators at any time. */
    private static boolean canRemove(RunAccess.Visible visible, Row row) {
        return (visible.initiator() && visible.run().isActive()) || visible.access().has(Permission.RUN_OPERATE);
    }

    private void requireFile(Upload upload) {
        if (upload == null || upload.stream() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a file.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/file", "required", "Choose a file.")));
        }
    }

    /** Counts uploads of the last minute across staged and attached files, so the limit holds on every pod. */
    private void rateLimit(UUID subject) {
        int recent = jdbc.sql("SELECT count(*) FROM run_attachment WHERE uploaded_by = :by AND created_at > :since")
                .param("by", subject).param("since", Sql.ts(clock.instant().minusSeconds(60))).query(Integer.class)
                .single();
        if (recent >= settings.uploadsPerMinute()) {
            throw new ApiException(ProblemCode.RATE_LIMITED, "Too many uploads. Try again in a minute.")
                    .withHeader("Retry-After", "30");
        }
    }

    private void lockUploadOwner(UUID subject) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "attachment-upload:" + subject).query((rs, n) -> 1).list();
    }

    private Stored read(Upload upload, String description) {
        String name = AttachmentTypes.sanitizeName(upload.fileName());
        String note = description == null ? null : description.strip();
        if (note != null && (note.length() > 200 || note.codePoints().anyMatch(Character::isISOControl))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The description is not valid.").withFieldErrors(
                    List.of(new ApiException.FieldError("/description", "maxLength",
                            "Enter at most 200 characters of plain text.")));
        }
        if (note != null && note.isEmpty()) {
            note = null;
        }
        byte[] bytes;
        try (InputStream in = upload.stream()) {
            bytes = in.readNBytes((int) settings.maxFileBytes() + 1);
        } catch (IOException e) {
            throw ApiException.malformed("The file could not be read.");
        }
        if (bytes.length > settings.maxFileBytes()) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,
                    "The file is larger than " + mib(settings.maxFileBytes()) + ".");
        }
        if (bytes.length == 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The file is empty.").withFieldErrors(
                    List.of(new ApiException.FieldError("/file", "required", "The file is empty.")));
        }
        AttachmentTypes.Detection detection = AttachmentTypes.detect(name, upload.contentType(), bytes);
        if (!detection.accepted()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, detection.refusal().message()).withFieldErrors(
                    List.of(new ApiException.FieldError("/file", "type", detection.refusal().message())));
        }
        return new Stored(name, note, bytes, sha256(bytes), detection.type());
    }

    private void insert(UUID id, UUID workspaceId, UUID runId, String fieldKey, UUID by, Stored stored,
            AttachmentScanner.Status scan, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO run_attachment (id, workspace_id, run_id, field_key, uploaded_by, file_name, description,
                                            content_type, size_bytes, sha256, scan_status, created_at, expires_at)
                VALUES (:id, :ws, :run, :field, :by, :name, :description, :type, :size, :sha, :scan, :now, :expires)
                """).param("id", id).param("ws", workspaceId).param("run", runId).param("field", fieldKey)
                .param("by", by).param("name", stored.name()).param("description", stored.description())
                .param("type", stored.type().contentType()).param("size", (long) stored.bytes().length)
                .param("sha", stored.sha256()).param("scan", scan.value()).param("now", Sql.ts(clock.instant()))
                .param("expires", Sql.ts(expiresAt)).update();
        jdbc.sql("INSERT INTO run_attachment_content (attachment_id, content) VALUES (:id, :content)")
                .param("id", id).param("content", stored.bytes()).update();
    }

    /** Audit (metadata only, never the file name) and, for a run, the timeline event. */
    private void recordAdded(UUID workspaceId, UUID runId, UUID actor, UUID id, Stored stored,
            AttachmentScanner.Status scan, boolean staged) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", runId == null ? null : runId.toString());
        metadata.put("attachmentId", id.toString());
        metadata.put("sizeBytes", stored.bytes().length);
        metadata.put("contentType", stored.type().contentType());
        metadata.put("scanStatus", scan.value());
        metadata.put("staged", staged);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, actor, "attachment.added",
                "attachment", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        if (runId != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attachmentId", id.toString());
            data.put("name", stored.name());
            data.put("description", stored.description());
            data.put("sizeBytes", stored.bytes().length);
            data.put("contentType", stored.type().contentType());
            events.append(workspaceId, runId, "ATTACHMENT_ADDED", null, actor, data);
        }
    }

    /** Serializes quota checks of one run across pods for the rest of the transaction. */
    private void lockRun(UUID runId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "attachments:" + runId)
                .query((rs, n) -> 1).list();
    }

    private long[] usage(UUID runId) {
        return jdbc.sql("SELECT count(*), coalesce(sum(size_bytes), 0) FROM run_attachment WHERE run_id = :run AND deleted_at IS NULL")
                .param("run", runId).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
    }

    private static AttachmentTypes.Type typeOf(String contentType) {
        for (AttachmentTypes.Type t : AttachmentTypes.Type.values()) {
            if (t.contentType().equals(contentType)) {
                return t;
            }
        }
        return null;
    }

    private static String mib(long bytes) {
        return bytes % (1024 * 1024) == 0 ? bytes / (1024 * 1024) + " MiB"
                : String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
