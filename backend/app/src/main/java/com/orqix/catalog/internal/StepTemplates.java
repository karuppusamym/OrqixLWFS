package com.orqix.catalog.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Workspace step templates (CG-S03, docs/build/S141): presets saved from a configured step, next to the connector
 * package templates of S133. Each change is a new immutable version (step, SHA-256, note, who, when); readers get the
 * latest active version. A template can be shared with the organization's other workspaces, where it is offered
 * read-only. The step is checked against the workflow schema (inside a generated one-step workflow) when it is saved;
 * references to request fields, groups or connections are checked again where the template is used, when that
 * workflow is validated and published. Search matches the name, description, step type and the operation (for example
 * the connection and operation of a Call a system step).
 */
@Service
public class StepTemplates {

    static final Set<String> REFUSED_TYPES = Set.of("start", "end");
    static final int MAX_STEP_BYTES = 64 * 1024;
    /** Config members whose values describe what the step does, in this order. */
    static final List<String> OPERATION_MEMBERS = List.of("connectionRef", "operation", "topic", "adapter", "kind",
            "decisionRef", "skillRef", "workflowRef", "templateRef", "candidateGroupRef");

    public record TemplateView(UUID id, UUID workspaceId, String workspaceKey, String key, String name,
            String description, String stepType, String operation, boolean shared, boolean own, String status,
            int latestVersion, JsonNode step, String sha256, SubjectService.SubjectRef updatedBy, Instant updatedAt) {
    }

    public record TemplateList(List<TemplateView> items) {
    }

    public record VersionView(int version, String sha256, String note, SubjectService.SubjectRef createdBy,
            Instant createdAt) {
    }

    public record TemplateDetail(TemplateView template, List<VersionView> versions) {
    }

    public record CreateRequest(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String key,
            @NotBlank @Size(max = 100) String name, @Size(max = 500) String description, Boolean shared,
            @NotNull JsonNode step, @Size(max = 500) String note) {
        public boolean isShared() {
            return Boolean.TRUE.equals(shared);
        }
    }

    public record VersionRequest(@NotNull @Min(1) Integer expectedVersion, @NotNull JsonNode step,
            @Size(max = 500) String note) {
    }

    public record UpdateRequest(@NotNull @Min(1) Integer expectedVersion, @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description, boolean shared,
            @NotNull @Pattern(regexp = "active|archived") String status) {
    }

    private final PolicyService policy;
    private final SubjectService subjects;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public StepTemplates(PolicyService policy, SubjectService subjects, WorkflowCompiler compiler,
            CompileContextFactory contexts, AuditWriter audit, JdbcClient jdbc, Json json, Clock clock) {
        this.policy = policy;
        this.subjects = subjects;
        this.compiler = compiler;
        this.contexts = contexts;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** The workspace's own templates and the active shared templates of the organization's other workspaces. */
    @Transactional(readOnly = true)
    public TemplateList list(Caller caller, UUID workspaceId, String q, boolean includeArchived) {
        WorkspaceAccess access = require(caller, workspaceId, Permission.WORKFLOW_READ, "listStepTemplates", false);
        String text = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (text.length() > 100) {
            throw ApiException.malformed("q is at most 100 characters.");
        }
        List<TemplateView> items = rows("""
                WHERE (t.workspace_id = :ws AND (t.status = 'active' OR :archived))
                   OR (t.shared AND t.status = 'active' AND t.workspace_id <> :ws AND w.organization_id = :org)
                """, Map.of("ws", workspaceId, "archived", includeArchived, "org", access.workspace().organizationId()),
                workspaceId);
        if (!text.isEmpty()) {
            items = items.stream().filter(t -> matches(t, text)).toList();
        }
        return new TemplateList(items.stream().limit(200).toList());
    }

    @Transactional(readOnly = true)
    public TemplateDetail get(Caller caller, UUID workspaceId, String key) {
        require(caller, workspaceId, Permission.WORKFLOW_READ, "getStepTemplate", false);
        TemplateView view = own(workspaceId, key);
        List<VersionView> versions = new ArrayList<>();
        record Row(int version, String sha, String note, UUID by, Instant at) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT version, sha256, note, created_by, created_at FROM step_template_version
                WHERE template_id = :id ORDER BY version DESC
                """).param("id", view.id())
                .query((rs, n) -> new Row(rs.getInt("version"), rs.getString("sha256"), rs.getString("note"),
                        Sql.uuid(rs, "created_by"), Sql.instant(rs, "created_at"))).list();
        Map<UUID, String> names = subjects.displayNames(rows.stream().map(Row::by).toList());
        rows.forEach(r -> versions.add(new VersionView(r.version(), r.sha(), r.note(), subjects.ref(r.by(), names), r.at())));
        return new TemplateDetail(view, versions);
    }

    @Transactional
    public TemplateView create(Caller caller, UUID workspaceId, CreateRequest request) {
        WorkspaceAccess access = require(caller, workspaceId, Permission.WORKFLOW_EDIT, "createStepTemplate", true);
        ObjectNode step = checked(access, request.step());
        String sha = json.canonicalSha256(step);
        UUID id = Ids.newId();
        Instant now = clock.instant();
        try {
            jdbc.sql("""
                    INSERT INTO step_template (id, workspace_id, key, name, description, step_type, operation, shared,
                                               latest_version, created_by, created_at, updated_by, updated_at)
                    VALUES (:id, :ws, :key, :name, :description, :type, :operation, :shared, 1, :by, :now, :by, :now)
                    """).param("id", id).param("ws", workspaceId).param("key", request.key())
                    .param("name", request.name().strip()).param("description", blankToNull(request.description()))
                    .param("type", step.path("type").asString()).param("operation", operation(step))
                    .param("shared", request.isShared()).param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A step template with this key already exists.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/key", "unique", "Already in use")));
        }
        insertVersion(id, 1, step, sha, request.note(), caller.subjectId(), now);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", request.key());
        metadata.put("version", 1);
        metadata.put("stepType", step.path("type").asString());
        metadata.put("shared", request.isShared());
        metadata.put("sha256", sha);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.step-template-created", "stepTemplate", request.key(), AuditWriter.Outcome.SUCCESS, metadata));
        return own(workspaceId, request.key());
    }

    @Transactional
    public TemplateView addVersion(Caller caller, UUID workspaceId, String key, VersionRequest request) {
        WorkspaceAccess access = require(caller, workspaceId, Permission.WORKFLOW_EDIT, "addStepTemplateVersion", true);
        TemplateView current = lockOwn(workspaceId, key, request.expectedVersion());
        ObjectNode step = checked(access, request.step());
        if (!step.path("type").asString().equals(current.stepType())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A new version keeps the step type.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/step/type", "const",
                            "Must stay " + current.stepType())));
        }
        String sha = json.canonicalSha256(step);
        int version = current.latestVersion() + 1;
        Instant now = clock.instant();
        insertVersion(current.id(), version, step, sha, request.note(), caller.subjectId(), now);
        jdbc.sql("""
                UPDATE step_template SET latest_version = :v, operation = :operation, updated_by = :by, updated_at = :now
                WHERE id = :id
                """).param("v", version).param("operation", operation(step)).param("by", caller.subjectId())
                .param("now", Sql.ts(now)).param("id", current.id()).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.step-template-versioned", "stepTemplate", key, AuditWriter.Outcome.SUCCESS,
                Map.of("key", key, "version", version, "sha256", sha)));
        return own(workspaceId, key);
    }

    @Transactional
    public TemplateView update(Caller caller, UUID workspaceId, String key, UpdateRequest request) {
        require(caller, workspaceId, Permission.WORKFLOW_EDIT, "updateStepTemplate", true);
        TemplateView current = lockOwn(workspaceId, key, request.expectedVersion());
        jdbc.sql("""
                UPDATE step_template SET name = :name, description = :description, shared = :shared, status = :status,
                       updated_by = :by, updated_at = :now WHERE id = :id
                """).param("name", request.name().strip()).param("description", blankToNull(request.description()))
                .param("shared", request.shared()).param("status", request.status()).param("by", caller.subjectId())
                .param("now", Sql.ts(clock.instant())).param("id", current.id()).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.step-template-updated", "stepTemplate", key, AuditWriter.Outcome.SUCCESS,
                Map.of("key", key, "shared", request.shared(), "status", request.status())));
        return own(workspaceId, key);
    }

    // ------------------------------------------------------------------ checks

    /** The stored form of a step: type, label, optional description and config; no id, position or routes. */
    private ObjectNode checked(WorkspaceAccess access, JsonNode given) {
        if (!given.isObject()) {
            throw invalid("/step", "type", "The step must be an object.");
        }
        String type = given.path("type").asString("");
        if (type.isEmpty() || REFUSED_TYPES.contains(type)) {
            throw invalid("/step/type", "enum", "Choose a step other than start or end.");
        }
        ObjectNode step = json.object();
        step.put("type", type);
        String label = given.path("label").asString("").strip();
        step.put("label", label.isEmpty() ? type : label);
        if (given.path("description").isString()) {
            step.put("description", given.path("description").asString());
        }
        step.set("config", given.path("config").isObject() ? given.path("config").deepCopy() : json.object());
        if (json.write(step).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STEP_BYTES) {
            throw invalid("/step", "maxLength", "The step is larger than 64 KiB.");
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (ValidationIssue issue : compiler.validate(json.write(wrapper(step)), contexts.create(access.workspace())).issues()) {
            if ("WF_SCHEMA_INVALID".equals(issue.code()) && issue.path() != null && issue.path().startsWith("/nodes/1")) {
                errors.add(new ApiException.FieldError("/step" + issue.path().substring("/nodes/1".length()), "schema",
                        issue.message()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The step does not match the workflow schema.")
                    .withFieldErrors(errors.stream().limit(20).toList());
        }
        return step;
    }

    /** A one-step workflow around the template, valid apart from the step, so schema problems point into it. */
    private ObjectNode wrapper(ObjectNode step) {
        ObjectNode wf = json.object();
        wf.put("schemaVersion", "0.1").put("workflowKey", "step-template-check").put("name", "Step template check");
        wf.set("metadata", json.object().put("description", "Generated to check a step template."));
        wf.set("inputSchema", json.object().put("type", "object").put("additionalProperties", false)
                .<ObjectNode>set("required", json.mapper().createArrayNode()).set("properties", json.object()));
        wf.set("inputUi", json.object().<ObjectNode>set("order", json.mapper().createArrayNode()).set("widgets", json.object()));
        wf.set("outputSchema", json.object().put("type", "object").put("additionalProperties", false)
                .set("properties", json.object()));
        wf.set("triggers", json.mapper().createArrayNode().add("manual"));
        wf.set("decisions", json.mapper().createArrayNode());
        wf.set("execution", json.object().put("maxConcurrentRuns", 10).put("maxAttempts", 3).put("retryDelaySeconds", 30));
        wf.set("security", json.object().put("selfApproval", "deny"));
        wf.set("dependencies", json.object().set("connections", json.mapper().createArrayNode()));
        wf.set("outputMapping", json.object());
        ArrayNode nodes = json.mapper().createArrayNode();
        nodes.add(json.object().put("id", "start").put("type", "start").put("label", "Start").set("config", json.object()));
        ObjectNode copy = step.deepCopy();
        copy.put("id", "templateStep");
        nodes.add(copy);
        ObjectNode end = json.object().put("id", "done").put("type", "end").put("label", "Done");
        end.set("config", json.object().put("businessOutcome", "Done").put("outcomeKind", "success"));
        nodes.add(end);
        wf.set("nodes", nodes);
        ArrayNode transitions = json.mapper().createArrayNode();
        transitions.add(json.object().put("id", "t1").put("from", "start").put("to", "templateStep").put("on", "next"));
        transitions.add(json.object().put("id", "t2").put("from", "templateStep").put("to", "done").put("on", "next"));
        wf.set("transitions", transitions);
        return wf;
    }

    static String operation(JsonNode step) {
        List<String> parts = new ArrayList<>();
        JsonNode config = step.path("config");
        for (String member : OPERATION_MEMBERS) {
            if (config.path(member).isString() && !config.path(member).asString().isBlank()) {
                parts.add(config.path(member).asString());
            }
        }
        String text = String.join(" · ", parts);
        return text.isEmpty() ? null : text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static boolean matches(TemplateView t, String q) {
        for (String value : new String[] {t.name(), t.description(), t.stepType(), t.operation(), t.key()}) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ store

    private void insertVersion(UUID id, int version, ObjectNode step, String sha, String note, UUID by, Instant now) {
        jdbc.sql("""
                INSERT INTO step_template_version (template_id, version, step, sha256, note, created_by, created_at)
                VALUES (:id, :v, CAST(:step AS jsonb), :sha, :note, :by, :now)
                """).param("id", id).param("v", version).param("step", json.write(step)).param("sha", sha)
                .param("note", blankToNull(note)).param("by", by).param("now", Sql.ts(now)).update();
    }

    private TemplateView own(UUID workspaceId, String key) {
        return rows("WHERE t.workspace_id = :ws AND t.key = :key", Map.of("ws", workspaceId, "key", key), workspaceId)
                .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private TemplateView lockOwn(UUID workspaceId, String key, int expectedVersion) {
        Integer latest = jdbc.sql("SELECT latest_version FROM step_template WHERE workspace_id = :ws AND key = :key FOR UPDATE")
                .param("ws", workspaceId).param("key", key).query(Integer.class).optional()
                .orElseThrow(ApiException::notFound);
        if (latest != expectedVersion) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The template changed since you loaded it.")
                    .with("currentVersion", latest);
        }
        return own(workspaceId, key);
    }

    private List<TemplateView> rows(String where, Map<String, Object> params, UUID viewer) {
        record Row(UUID id, UUID ws, String wsKey, String key, String name, String description, String type,
                String operation, boolean shared, String status, int latest, String step, String sha, UUID by,
                Instant at) {
        }
        var spec = jdbc.sql("""
                SELECT t.id, t.workspace_id, w.key AS workspace_key, t.key, t.name, t.description, t.step_type,
                       t.operation, t.shared, t.status, t.latest_version, v.step::text AS step, v.sha256, t.updated_by,
                       t.updated_at
                FROM step_template t
                JOIN workspace w ON w.id = t.workspace_id
                JOIN step_template_version v ON v.template_id = t.id AND v.version = t.latest_version
                """ + where + " ORDER BY t.workspace_id = :viewer DESC, lower(t.name), t.key LIMIT 500");
        for (var e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<Row> rows = spec.param("viewer", viewer)
                .query((rs, n) -> new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("workspace_key"),
                        rs.getString("key"), rs.getString("name"), rs.getString("description"), rs.getString("step_type"),
                        rs.getString("operation"), rs.getBoolean("shared"), rs.getString("status"),
                        rs.getInt("latest_version"), rs.getString("step"), rs.getString("sha256"),
                        Sql.uuid(rs, "updated_by"), Sql.instant(rs, "updated_at")))
                .list();
        Map<UUID, String> names = subjects.displayNames(rows.stream().map(Row::by).toList());
        return rows.stream().map(r -> new TemplateView(r.id(), r.ws(), r.wsKey(), r.key(), r.name(), r.description(),
                r.type(), r.operation(), r.shared(), r.ws().equals(viewer), r.status(), r.latest(), json.parse(r.step()),
                r.sha(), subjects.ref(r.by(), names), r.at())).toList();
    }

    private WorkspaceAccess require(Caller caller, UUID workspaceId, Permission permission, String operationId,
            boolean write) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, permission, operationId, write);
        return access;
    }

    private static ApiException invalid(String pointer, String code, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, code, message)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
