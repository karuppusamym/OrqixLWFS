package com.orqix.catalog.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.GroupDirectory;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.identity.SubjectService;
import com.orqix.integration.ConnectionService;
import com.orqix.notification.NotificationService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PL02 (docs/build/S54): environment stages and promotion of a release definition to a later stage of the same
 * organization. Promotion never publishes, never copies bindings, secrets or group membership and never changes the
 * source: it checks the definition against the target's own connections, groups and called workflows, then saves it as a
 * draft in the target, where the normal publish gates (validation, warnings, tests, migration review) still apply.
 */
@Service
public class PromotionService {

    public static final List<String> STAGES = List.of("development", "test", "staging", "production");

    public record Environment(UUID workspaceId, String stage, boolean explicit, int version, boolean requireApproval) {
    }

    public record SetEnvironmentRequest(@NotNull String stage, @Min(0) int expectedVersion, Boolean requireApproval) {
    }

    public record PromoteRequest(@NotNull UUID targetWorkspaceId, Integer expectedDraftRevision) {
    }

    public record ConnectionCheck(String key, int definitionVersion, String status) {
    }

    public record GroupCheck(String key, boolean exists, int memberCount) {
    }

    public record WorkflowCheck(String workflowKey, boolean publishedInTarget) {
    }

    public record TargetWorkflow(boolean exists, UUID id, Integer draftRevision) {
    }

    public record RequestPromotionBody(@NotNull UUID targetWorkspaceId, Integer expectedDraftRevision,
            @jakarta.validation.constraints.Size(max = 500) String note) {
    }

    public record DecisionBody(@jakarta.validation.constraints.Size(max = 500) String note) {
    }

    public record PromotionRequestView(UUID id, String status, String sourceWorkspaceKey, String workflowKey,
            int releaseNumber, UUID targetWorkspaceId, Integer expectedDraftRevision, String note, UUID requestedById,
            String requestedBy, Instant requestedAt, String decidedBy, Instant decidedAt, String decisionNote,
            UUID promotionId) {
    }

    public record Report(boolean compatible, String sourceStage, String targetStage, boolean approvalRequired,
            TargetWorkflow targetWorkflow,
            List<ConnectionCheck> connections, List<GroupCheck> groups, List<WorkflowCheck> workflows,
            List<CatalogDtos.Issue> issues) {
    }

    public record Promotion(UUID id, UUID targetWorkspaceId, UUID targetWorkflowId, int draftRevision,
            boolean createdWorkflow, Report report) {
    }

    public record PromotionRecord(UUID id, String direction, String sourceWorkspaceKey, String targetWorkspaceKey,
            String workflowKey, int releaseNumber, int targetDraftRevision, boolean createdWorkflow, Instant promotedAt,
            String promotedBy, String approvedBy) {
    }

    private final JdbcClient jdbc;
    private final CatalogStore store;
    private final WorkflowService workflows;
    private final PolicyService policy;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final ConnectionService connections;
    private final GroupDirectory groups;
    private final AuditWriter audit;
    private final SubjectService subjects;
    private final NotificationService notifications;
    private final Json json;
    private final Clock clock;

    public PromotionService(JdbcClient jdbc, CatalogStore store, WorkflowService workflows, PolicyService policy,
            WorkflowCompiler compiler, CompileContextFactory contexts, ConnectionService connections,
            GroupDirectory groups, AuditWriter audit, SubjectService subjects, NotificationService notifications,
            Json json, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.workflows = workflows;
        this.policy = policy;
        this.compiler = compiler;
        this.contexts = contexts;
        this.connections = connections;
        this.groups = groups;
        this.audit = audit;
        this.subjects = subjects;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- environment

    @Transactional(readOnly = true)
    public Environment environment(Caller caller, UUID ws) {
        policy.requireVisible(caller, ws);
        return load(ws, policy.requireVisible(caller, ws).workspace().isTest());
    }

    private Environment load(UUID ws, boolean isTest) {
        return jdbc.sql("SELECT stage, version, promotion_approval FROM workspace_environment WHERE workspace_id = :ws")
                .param("ws", ws)
                .query((rs, n) -> new Environment(ws, rs.getString("stage"), true, rs.getInt("version"),
                        rs.getBoolean("promotion_approval")))
                .optional().orElseGet(() -> new Environment(ws, isTest ? "test" : "production", false, 0, false));
    }

    @Transactional
    public Environment setEnvironment(Caller caller, UUID ws, SetEnvironmentRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, "setWorkspaceEnvironment", true);
        if (!STAGES.contains(request.stage())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The stage must be one of " + STAGES + ".")
                    .withFieldErrors(List.of(new ApiException.FieldError("/stage", "enum", "Unknown stage")));
        }
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).optional();
        Environment current = load(ws, access.workspace().isTest());
        if (current.version() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The environment changed since you loaded it.")
                    .with("currentVersion", current.version());
        }
        // The gate exists only for production; leaving production switches it off so it cannot return unnoticed.
        boolean gate = request.requireApproval() != null ? request.requireApproval() : current.requireApproval();
        if (request.requireApproval() != null && request.requireApproval() && !"production".equals(request.stage())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Promotion approval can only be required for a production workspace.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/requireApproval", "enum", "Only production workspaces")));
        }
        if (!"production".equals(request.stage())) {
            gate = false;
        }
        if (current.explicit()) {
            jdbc.sql("""
                    UPDATE workspace_environment SET stage = :stage, promotion_approval = :gate, version = version + 1,
                           updated_by = :by, updated_at = now()
                    WHERE workspace_id = :ws
                    """).param("stage", request.stage()).param("gate", gate).param("by", caller.subjectId())
                    .param("ws", ws).update();
        } else {
            jdbc.sql("""
                    INSERT INTO workspace_environment (workspace_id, stage, promotion_approval, updated_by)
                    VALUES (:ws, :stage, :gate, :by)
                    """).param("ws", ws).param("stage", request.stage()).param("gate", gate)
                    .param("by", caller.subjectId()).update();
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "workspace.environment.changed", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("from", current.stage(), "to", request.stage(), "requireApproval", gate)));
        return load(ws, access.workspace().isTest());
    }

    // ---------------------------------------------------------------- compatibility and promotion

    private record Prepared(WorkspaceAccess source, WorkspaceAccess target, CatalogStore.WorkflowRow workflow,
            CatalogStore.ReleaseRow release, Report report) {
    }

    @Transactional(readOnly = true)
    public Report check(Caller caller, UUID ws, UUID wf, UUID releaseId, PromoteRequest request) {
        return prepare(caller, ws, wf, releaseId, request, "checkReleasePromotion").report();
    }

    @Transactional
    public Promotion promote(Caller caller, UUID ws, UUID wf, UUID releaseId, PromoteRequest request) {
        Prepared p = prepare(caller, ws, wf, releaseId, request, "promoteRelease");
        Report report = p.report();
        if (report.approvalRequired()) {
            throw new ApiException(ProblemCode.PROMOTION_APPROVAL_REQUIRED,
                    "The target requires approval: request the promotion and a second person approves it.");
        }
        if (!report.compatible()) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The release cannot be promoted: fix the findings in the target.")
                    .with("issues", report.issues());
        }
        return execute(caller.subjectId(), null, caller.subjectId(), null, ws, wf, releaseId,
                p.source().workspace().organizationId(), p.source().workspace().key(), p.workflow(), p.release(),
                request.targetWorkspaceId(), request.expectedDraftRevision(), report);
    }

    /**
     * Saves the draft in the target and records provenance. {@code actor} did the work (the caller, or the approver);
     * {@code promotedBy} is the person whose request it is; {@code approvedBy} is set only for an approved request.
     */
    private Promotion execute(UUID actor, UUID requestId, UUID promotedBy, UUID approvedBy, UUID ws, UUID wf,
            UUID releaseId, UUID organizationId, String sourceWorkspaceKey, CatalogStore.WorkflowRow workflow,
            CatalogStore.ReleaseRow release, UUID target, Integer expectedDraftRevision, Report report) {
        JsonNode source = json.parse(release.definitionJson());
        ObjectNode definition = (ObjectNode) source.deepCopy();
        definition.put("workflowKey", workflow.key());
        String definitionJson = json.write(definition);

        UUID targetId;
        int revision;
        boolean created = !report.targetWorkflow().exists();
        if (created) {
            targetId = Ids.newId();
            if (expectedDraftRevision != null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "The target workflow does not exist yet, so there is no draft revision to expect.");
            }
            if (!store.insertWorkflow(targetId, target, workflow.key(), workflow.name(), workflow.description(), promotedBy)) {
                throw new ApiException(ProblemCode.DUPLICATE_KEY, "A workflow with this key already exists in the target.");
            }
            revision = 1;
            store.insertDraft(target, targetId, revision, definitionJson, "0.1", promotedBy);
        } else {
            targetId = report.targetWorkflow().id();
            CatalogStore.WorkflowRow existing = store.lockWorkflow(target, targetId).orElseThrow(ApiException::notFound);
            if (expectedDraftRevision == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "The target already has this workflow; send expectedDraftRevision (" + existing.draftRevision() + ").");
            }
            if (expectedDraftRevision != existing.draftRevision() || !store.advanceDraft(target, targetId, existing.draftRevision())) {
                throw new ApiException(ProblemCode.STALE_REVISION, "The target draft was saved by someone else.")
                        .with("currentRevision", existing.draftRevision());
            }
            revision = existing.draftRevision() + 1;
            store.insertDraft(target, targetId, revision, definitionJson, "0.1", promotedBy);
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO release_promotion (id, organization_id, source_workspace_id, source_workspace_key,
                    source_workflow_key, source_release_number, source_semantic_hash, target_workspace_id,
                    target_workflow_id, target_draft_revision, created_target, report, promoted_by, approved_by,
                    approval_request_id)
                VALUES (:id, :org, :sws, :swk, :wk, :rn, :hash, :tws, :twf, :rev, :created, CAST(:report AS jsonb), :by,
                        :approvedBy, :requestId)
                """).param("id", id).param("org", organizationId).param("sws", ws)
                .param("swk", sourceWorkspaceKey).param("wk", workflow.key())
                .param("rn", release.releaseNumber()).param("hash", release.semanticHash())
                .param("tws", target).param("twf", targetId).param("rev", revision).param("created", created)
                .param("report", json.write(json.mapper().valueToTree(report))).param("by", promotedBy)
                .param("approvedBy", approvedBy).param("requestId", requestId).update();
        Map<String, Object> sourceMeta = new LinkedHashMap<>();
        sourceMeta.put("releaseNumber", release.releaseNumber());
        sourceMeta.put("targetWorkspaceId", target.toString());
        sourceMeta.put("targetWorkflowId", targetId.toString());
        sourceMeta.put("draftRevision", revision);
        sourceMeta.put("createdWorkflow", created);
        sourceMeta.put("requestId", requestId == null ? null : requestId.toString());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, actor, "release.promoted", "release",
                releaseId.toString(), AuditWriter.Outcome.SUCCESS, sourceMeta));
        Map<String, Object> targetMeta = new LinkedHashMap<>();
        targetMeta.put("releaseNumber", release.releaseNumber());
        targetMeta.put("sourceWorkspaceId", ws.toString());
        targetMeta.put("targetWorkflowId", targetId.toString());
        targetMeta.put("draftRevision", revision);
        targetMeta.put("createdWorkflow", created);
        targetMeta.put("requestId", requestId == null ? null : requestId.toString());
        targetMeta.put("approvedBy", approvedBy == null ? null : approvedBy.toString());
        audit.write(new AuditWriter.Entry(target, AuditWriter.ActorType.USER, actor, "release.promotion.received",
                "workflow", targetId.toString(), AuditWriter.Outcome.SUCCESS, targetMeta));
        return new Promotion(id, target, targetId, revision, created, report);
    }

    private Prepared prepare(Caller caller, UUID ws, UUID wf, UUID releaseId, PromoteRequest request, String operation) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, operation);
        CatalogStore.ReleaseRow release = store.release(ws, wf, releaseId).orElseThrow(ApiException::notFound);
        // Definitions leave the workspace, so the same right as exporting a release is needed.
        policy.require(visible.access(), Permission.WORKFLOW_EXPORT, operation, true);
        UUID target = request.targetWorkspaceId();
        if (target.equals(ws)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a different workspace as the promotion target.");
        }
        WorkspaceAccess targetAccess = policy.requireVisible(caller, target);
        policy.require(targetAccess, Permission.WORKFLOW_EDIT, operation, true);
        if (!targetAccess.workspace().organizationId().equals(visible.access().workspace().organizationId())) {
            // Another organization must not be probed from here.
            throw ApiException.notFound();
        }
        String sourceStage = load(ws, visible.access().workspace().isTest()).stage();
        String targetStage = load(target, targetAccess.workspace().isTest()).stage();
        if (STAGES.indexOf(targetStage) <= STAGES.indexOf(sourceStage)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Promotion moves to a later stage: the target is "
                    + targetStage + " and the source is " + sourceStage + ".");
        }
        return new Prepared(visible.access(), targetAccess, visible.workflow(), release,
                report(release, target, targetAccess, sourceStage, targetStage));
    }

    private Report report(CatalogStore.ReleaseRow release, UUID target, WorkspaceAccess targetAccess, String sourceStage,
            String targetStage) {
        JsonNode manifest = json.parse(release.manifestJson());
        List<ConnectionCheck> connectionChecks = new ArrayList<>();
        for (JsonNode c : manifest.path("connections")) {
            String key = c.path("key").asString();
            int version = c.path("definitionVersion").asInt();
            String hash = c.path("definitionHash").asString("");
            var definition = connections.definition(target, key, version);
            String status;
            if (definition.isEmpty()) {
                status = "definitionMissing";
            } else if (!hash.equals(definition.get().definitionHash())) {
                status = "definitionDiffers";
            } else if (connections.binding(target, key).filter(ConnectionService.Binding::enabled).isEmpty()) {
                status = "bindingMissing";
            } else {
                status = "ready";
            }
            connectionChecks.add(new ConnectionCheck(key, version, status));
        }
        Map<String, GroupDirectory.Group> targetGroups = new LinkedHashMap<>();
        groups.groups(target).forEach(g -> targetGroups.put(g.key(), g));
        List<GroupCheck> groupChecks = new ArrayList<>();
        for (JsonNode g : manifest.path("groups")) {
            GroupDirectory.Group found = targetGroups.get(g.asString());
            groupChecks.add(new GroupCheck(g.asString(), found != null, found == null ? 0 : found.members().size()));
        }
        Set<String> childKeys = new LinkedHashSet<>();
        manifest.path("workflows").forEach(w -> childKeys.add(w.path("workflowKey").asString()));
        Map<String, Boolean> published = new LinkedHashMap<>();
        for (CatalogStore.CallableRow row : store.callable(target, childKeys)) {
            published.put(row.key(), row.activeReleaseId() != null);
        }
        List<WorkflowCheck> workflowChecks = new ArrayList<>();
        childKeys.forEach(k -> workflowChecks.add(new WorkflowCheck(k, published.getOrDefault(k, false))));

        // The authoritative answer is the compiler run against the target's own facts.
        CompileContext context = contexts.create(targetAccess.workspace(), release.definitionJson());
        ValidationReport validation = compiler.validate(release.definitionJson(), context);

        TargetWorkflow targetWorkflow = jdbc.sql("""
                SELECT id, draft_revision FROM workflow WHERE workspace_id = :ws AND key = :key AND archived_at IS NULL
                """).param("ws", target).param("key", json.parse(release.definitionJson()).path("workflowKey").asString(""))
                .query((rs, n) -> new TargetWorkflow(true, rs.getObject("id", UUID.class), rs.getInt("draft_revision")))
                .optional().orElse(new TargetWorkflow(false, null, null));
        boolean gated = "production".equals(targetStage)
                && load(target, targetAccess.workspace().isTest()).requireApproval();
        return new Report(validation.publishable(), sourceStage, targetStage, gated, targetWorkflow, connectionChecks,
                groupChecks, workflowChecks, WorkflowService.issues(validation.issues()));
    }

    @Transactional(readOnly = true)
    public List<PromotionRecord> history(Caller caller, UUID ws, UUID wf) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "listWorkflowPromotions");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "listWorkflowPromotions", false);
        String key = visible.workflow().key();
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT p.id, p.source_workspace_id, p.source_workspace_key, p.source_workflow_key, p.source_release_number,
                       p.target_workspace_id, tw.key AS target_workspace_key, p.target_draft_revision, p.created_target,
                       p.promoted_at, s.display_name AS promoted_by, a.display_name AS approved_by, p.target_workflow_id
                FROM release_promotion p JOIN workspace tw ON tw.id = p.target_workspace_id
                     JOIN subject s ON s.id = p.promoted_by LEFT JOIN subject a ON a.id = p.approved_by
                WHERE (p.target_workspace_id = :ws AND p.target_workflow_id = :wf)
                   OR (p.source_workspace_id = :ws AND p.source_workflow_key = :key)
                ORDER BY p.promoted_at DESC, p.id DESC LIMIT 50
                """).param("ws", ws).param("wf", wf).param("key", key).query().listOfRows();
        List<PromotionRecord> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean incoming = ws.equals(row.get("target_workspace_id")) && wf.equals(row.get("target_workflow_id"));
            UUID otherWorkspace = (UUID) row.get(incoming ? "source_workspace_id" : "target_workspace_id");
            // An outgoing promotion is only listed to someone who can also see the target workspace.
            if (!incoming && policy.freshAccess(caller, otherWorkspace).filter(WorkspaceAccess::visible).isEmpty()) {
                continue;
            }
            result.add(new PromotionRecord((UUID) row.get("id"), incoming ? "incoming" : "outgoing",
                    (String) row.get("source_workspace_key"), (String) row.get("target_workspace_key"),
                    (String) row.get("source_workflow_key"), ((Number) row.get("source_release_number")).intValue(),
                    ((Number) row.get("target_draft_revision")).intValue(), (Boolean) row.get("created_target"),
                    ((java.sql.Timestamp) row.get("promoted_at")).toInstant(), (String) row.get("promoted_by"),
                    (String) row.get("approved_by")));
        }
        return result;
    }

    // ---------------------------------------------------------------- approval gate (docs/build/S74)

    private static final String REQUEST_SELECT = """
            SELECT r.id, r.status, r.source_workspace_key, r.source_workflow_key, r.source_release_number,
                   r.target_workspace_id, r.expected_draft_revision, r.request_note, r.requested_by AS requested_by_id,
                   rb.display_name AS requested_by, r.requested_at, db.display_name AS decided_by, r.decided_at,
                   r.decision_note, r.promotion_id
            FROM promotion_request r JOIN subject rb ON rb.id = r.requested_by LEFT JOIN subject db ON db.id = r.decided_by
            """;

    private PromotionRequestView mapRequest(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Integer expected = rs.getObject("expected_draft_revision") == null ? null : rs.getInt("expected_draft_revision");
        java.sql.Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new PromotionRequestView(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("source_workspace_key"), rs.getString("source_workflow_key"),
                rs.getInt("source_release_number"), rs.getObject("target_workspace_id", UUID.class), expected,
                rs.getString("request_note"), rs.getObject("requested_by_id", UUID.class), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(), rs.getString("decision_note"),
                rs.getObject("promotion_id", UUID.class));
    }

    private PromotionRequestView requestView(UUID ws, UUID id) {
        return jdbc.sql(REQUEST_SELECT + " WHERE r.id = :id AND r.target_workspace_id = :ws").param("id", id)
                .param("ws", ws).query(this::mapRequest).optional().orElseThrow(ApiException::notFound);
    }

    private record LockedRequest(UUID id, String status, UUID organizationId, UUID sourceWorkspaceId,
            String sourceWorkspaceKey, UUID sourceWorkflowId, UUID sourceReleaseId, int releaseNumber,
            Integer expectedDraftRevision, UUID requestedBy) {
    }

    private LockedRequest lockRequest(UUID ws, UUID id) {
        return jdbc.sql("""
                SELECT id, status, organization_id, source_workspace_id, source_workspace_key, source_workflow_id,
                       source_release_id, source_release_number, expected_draft_revision, requested_by
                FROM promotion_request WHERE id = :id AND target_workspace_id = :ws FOR UPDATE
                """).param("id", id).param("ws", ws).query((rs, n) -> new LockedRequest(rs.getObject("id", UUID.class),
                        rs.getString("status"), rs.getObject("organization_id", UUID.class),
                        rs.getObject("source_workspace_id", UUID.class), rs.getString("source_workspace_key"),
                        rs.getObject("source_workflow_id", UUID.class), rs.getObject("source_release_id", UUID.class),
                        rs.getInt("source_release_number"),
                        rs.getObject("expected_draft_revision") == null ? null : rs.getInt("expected_draft_revision"),
                        rs.getObject("requested_by", UUID.class)))
                .optional().orElseThrow(ApiException::notFound);
    }

    /** Asks for a promotion into a workspace that requires approval. Same rights and checks as a direct promotion. */
    @Transactional
    public PromotionRequestView requestPromotion(Caller caller, UUID ws, UUID wf, UUID releaseId,
            RequestPromotionBody body) {
        PromoteRequest request = new PromoteRequest(body.targetWorkspaceId(), body.expectedDraftRevision());
        Prepared p = prepare(caller, ws, wf, releaseId, request, "requestReleasePromotion");
        Report report = p.report();
        UUID target = body.targetWorkspaceId();
        if (!report.approvalRequired()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "The target does not require approval: promote the release directly.");
        }
        if (!report.compatible()) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The release cannot be promoted: fix the findings in the target.")
                    .with("issues", report.issues());
        }
        if (!report.targetWorkflow().exists() && body.expectedDraftRevision() != null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "The target workflow does not exist yet, so there is no draft revision to expect.");
        }
        if (report.targetWorkflow().exists()) {
            if (body.expectedDraftRevision() == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "The target already has this workflow; send expectedDraftRevision ("
                        + report.targetWorkflow().draftRevision() + ").");
            }
            if (!body.expectedDraftRevision().equals(report.targetWorkflow().draftRevision())) {
                throw new ApiException(ProblemCode.STALE_REVISION, "The target draft was saved by someone else.")
                        .with("currentRevision", report.targetWorkflow().draftRevision());
            }
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO promotion_request (id, organization_id, source_workspace_id, source_workspace_key,
                        source_workflow_id, source_workflow_key, source_release_id, source_release_number,
                        target_workspace_id, expected_draft_revision, status, request_note, report, requested_by)
                    VALUES (:id, :org, :sws, :swk, :swf, :wk, :rel, :rn, :tws, :rev, 'pending', :note,
                            CAST(:report AS jsonb), :by)
                    """).param("id", id).param("org", p.source().workspace().organizationId()).param("sws", ws)
                    .param("swk", p.source().workspace().key()).param("swf", wf).param("wk", p.workflow().key())
                    .param("rel", releaseId).param("rn", p.release().releaseNumber()).param("tws", target)
                    .param("rev", body.expectedDraftRevision()).param("note", blankToNull(body.note()))
                    .param("report", json.write(json.mapper().valueToTree(report))).param("by", caller.subjectId())
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY,
                    "A promotion request for this workflow is already waiting for approval in the target.");
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "release.promotion.requested", "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("releaseNumber", p.release().releaseNumber(), "targetWorkspaceId", target.toString(),
                        "requestId", id.toString())));
        int notified = notifyApprovers(target, id, caller, p.workflow().key(), p.release().releaseNumber(),
                p.source().workspace().key());
        audit.write(new AuditWriter.Entry(target, AuditWriter.ActorType.USER, caller.subjectId(),
                "release.promotion.approval-requested", "promotionRequest", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("releaseNumber", p.release().releaseNumber(), "sourceWorkspaceId", ws.toString(),
                        "requestId", id.toString(), "approversNotified", notified)));
        return requestView(target, id);
    }

    /** Most people one promotion request notifies (the first known people who may approve, never the requester). */
    static final int MAX_APPROVERS_NOTIFIED = 50;

    /**
     * PL02a (S106): an in-app notification to each person who may approve promotions in the target right now (fresh
     * access, explicit grant), never the requester. The message names the workflow, release and source workspace only.
     *
     * @return how many people were notified
     */
    private int notifyApprovers(UUID target, UUID requestId, Caller requester, String workflowKey, int releaseNumber,
            String sourceWorkspaceKey) {
        int notified = 0;
        for (UUID candidate : approverCandidates(target, requester.subjectId())) {
            if (notified >= MAX_APPROVERS_NOTIFIED) {
                break;
            }
            SubjectService.Subject s = subjects.find(candidate).orElse(null);
            if (s == null || s.disabled()) {
                continue;
            }
            boolean approver = policy.freshAccess(new Caller(s.id(), s.displayName(), s.email(), s.issuer(),
                    s.externalSubject(), subjects.storedGroups(s.id()), false), target)
                    .filter(WorkspaceAccess::visible)
                    .map(a -> a.has(Permission.WORKFLOW_APPROVE_PROMOTION)).orElse(false);
            if (approver && notifications.createPromotion(target, s.id(), null, NotificationService.Kind.PROMOTION_REQUEST,
                    "promotion-request:" + requestId + ":" + s.id(), requester.displayName() + " asks to promote "
                            + workflowKey + " release " + releaseNumber + " from " + sourceWorkspaceKey + ".")) {
                notified++;
            }
        }
        return notified;
    }

    /**
     * The people the target's {@code workflow.approve-promotion} grants name, directly or through an identity-provider
     * group they belong to (no role carries that permission). Enabled people only, never the requester, in a stable
     * order and bounded. The caller still checks each one's fresh access, so a grant alone never notifies anyone.
     */
    private List<UUID> approverCandidates(UUID target, UUID requester) {
        return jdbc.sql("""
                SELECT s.id FROM subject s
                WHERE s.disabled_at IS NULL AND s.id <> :requester
                  AND (s.id IN (SELECT CAST(pg.principal_value AS uuid) FROM permission_grant pg
                                WHERE pg.workspace_id = :ws AND pg.permission = 'workflow.approve-promotion'
                                  AND pg.workflow_id IS NULL AND pg.principal_type = 'subject')
                       OR jsonb_exists_any(s.idp_groups, ARRAY(SELECT pg.principal_value FROM permission_grant pg
                                WHERE pg.workspace_id = :ws AND pg.permission = 'workflow.approve-promotion'
                                  AND pg.workflow_id IS NULL AND pg.principal_type = 'idpGroup')))
                ORDER BY s.id LIMIT :limit
                """).param("ws", target).param("requester", requester).param("limit", MAX_APPROVERS_NOTIFIED * 4)
                .query(UUID.class).list();
    }

    /** PL02a (S106): tells the requester, in the source workspace, that the request was approved or rejected. */
    private void notifyRequester(LockedRequest request, UUID target, boolean approved, String note) {
        String workflowKey = jdbc.sql("SELECT source_workflow_key FROM promotion_request WHERE id = :id")
                .param("id", request.id()).query(String.class).single();
        String targetKey = jdbc.sql("SELECT key FROM workspace WHERE id = :id").param("id", target)
                .query(String.class).single();
        boolean workflowStillThere = store.workflow(request.sourceWorkspaceId(), request.sourceWorkflowId()).isPresent();
        notifications.createPromotion(request.sourceWorkspaceId(), request.requestedBy(),
                workflowStillThere ? request.sourceWorkflowId() : null, NotificationService.Kind.PROMOTION_DECISION,
                "promotion-decision:" + request.id(), "Your promotion of " + workflowKey + " release "
                        + request.releaseNumber() + " to " + targetKey + " was " + (approved ? "approved" : "rejected")
                        + (note == null ? "." : ": " + note));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Requests addressed to this workspace: the pending ones by default, or the newest 50 of any status. */
    @Transactional(readOnly = true)
    public List<PromotionRequestView> requests(Caller caller, UUID ws, String status) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        if (!access.has(Permission.WORKFLOW_READ) && !access.has(Permission.WORKFLOW_EDIT)
                && !access.has(Permission.WORKFLOW_APPROVE_PROMOTION)) {
            throw policy.deny(ws, access.subjectId(), Permission.WORKFLOW_READ.value(), "listPromotionRequests", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        if (status != null && !List.of("pending", "all").contains(status)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The status must be pending or all.");
        }
        String filter = "all".equals(status) ? "" : " AND r.status = 'pending'";
        return jdbc.sql(REQUEST_SELECT + " WHERE r.target_workspace_id = :ws" + filter
                + " ORDER BY r.requested_at DESC, r.id DESC LIMIT 50").param("ws", ws).query(this::mapRequest).list();
    }

    /**
     * A second person with the explicit grant in the target approves. The requester can never approve or reject
     * (separation of duties, also enforced by a database constraint); there is no automatic approval. The target is
     * checked again now, and a stale draft revision leaves the request pending.
     */
    @Transactional
    public PromotionRequestView approve(Caller caller, UUID ws, UUID requestId, DecisionBody body) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_APPROVE_PROMOTION, "approvePromotionRequest", true);
        LockedRequest request = lockRequest(ws, requestId);
        requirePending(request);
        requireOtherPerson(caller, ws, request, "approvePromotionRequest");
        CatalogStore.WorkflowRow workflow = store.workflow(request.sourceWorkspaceId(), request.sourceWorkflowId())
                .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "The source workflow no longer exists."));
        CatalogStore.ReleaseRow release = store.release(request.sourceWorkspaceId(), request.sourceWorkflowId(),
                request.sourceReleaseId()).orElseThrow(ApiException::notFound);
        boolean sourceIsTest = jdbc.sql("SELECT is_test FROM workspace WHERE id = :id").param("id", request.sourceWorkspaceId())
                .query(Boolean.class).single();
        String sourceStage = load(request.sourceWorkspaceId(), sourceIsTest).stage();
        String targetStage = load(ws, access.workspace().isTest()).stage();
        if (STAGES.indexOf(targetStage) <= STAGES.indexOf(sourceStage)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Promotion moves to a later stage: the target is "
                    + targetStage + " and the source is " + sourceStage + ".");
        }
        Report report = report(release, ws, access, sourceStage, targetStage);
        if (!report.compatible()) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The release cannot be promoted now: the target no longer fits it.")
                    .with("issues", report.issues());
        }
        Promotion promotion = execute(caller.subjectId(), requestId, request.requestedBy(), caller.subjectId(),
                request.sourceWorkspaceId(), request.sourceWorkflowId(), request.sourceReleaseId(),
                request.organizationId(), request.sourceWorkspaceKey(), workflow, release, ws,
                request.expectedDraftRevision(), report);
        jdbc.sql("""
                UPDATE promotion_request SET status = 'approved', decided_by = :by, decided_at = now(),
                       decision_note = :note, promotion_id = :promotion WHERE id = :id
                """).param("by", caller.subjectId()).param("note", blankToNull(body == null ? null : body.note()))
                .param("promotion", promotion.id()).param("id", requestId).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "release.promotion.approved", "promotionRequest", requestId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("releaseNumber", request.releaseNumber(), "requestId", requestId.toString(),
                        "requestedBy", request.requestedBy().toString(), "draftRevision", promotion.draftRevision())));
        notifyRequester(request, ws, true, blankToNull(body == null ? null : body.note()));
        return requestView(ws, requestId);
    }

    @Transactional
    public PromotionRequestView reject(Caller caller, UUID ws, UUID requestId, DecisionBody body) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_APPROVE_PROMOTION, "rejectPromotionRequest", true);
        if (body == null || blankToNull(body.note()) == null) {
            throw new ApiException(ProblemCode.REASON_REQUIRED, "Say why the promotion is rejected.");
        }
        LockedRequest request = lockRequest(ws, requestId);
        requirePending(request);
        requireOtherPerson(caller, ws, request, "rejectPromotionRequest");
        jdbc.sql("""
                UPDATE promotion_request SET status = 'rejected', decided_by = :by, decided_at = now(), decision_note = :note
                WHERE id = :id
                """).param("by", caller.subjectId()).param("note", blankToNull(body.note())).param("id", requestId).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "release.promotion.rejected", "promotionRequest", requestId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("releaseNumber", request.releaseNumber(), "requestId", requestId.toString(),
                        "requestedBy", request.requestedBy().toString())));
        notifyRequester(request, ws, false, blankToNull(body.note()));
        return requestView(ws, requestId);
    }

    /** Only the person who asked can withdraw a request that is still waiting. */
    @Transactional
    public PromotionRequestView cancel(Caller caller, UUID ws, UUID requestId) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        LockedRequest request = lockRequest(ws, requestId);
        if (!request.requestedBy().equals(caller.subjectId())) {
            throw policy.deny(ws, access.subjectId(), Permission.WORKFLOW_EDIT.value(), "cancelPromotionRequest", true,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        requirePending(request);
        jdbc.sql("UPDATE promotion_request SET status = 'cancelled', decided_by = :by, decided_at = now() WHERE id = :id")
                .param("by", caller.subjectId()).param("id", requestId).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "release.promotion.cancelled", "promotionRequest", requestId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("releaseNumber", request.releaseNumber(), "requestId", requestId.toString())));
        return requestView(ws, requestId);
    }

    private void requirePending(LockedRequest request) {
        if (!"pending".equals(request.status())) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The request was already " + request.status() + ".");
        }
    }

    private void requireOtherPerson(Caller caller, UUID ws, LockedRequest request, String operation) {
        if (request.requestedBy().equals(caller.subjectId())) {
            throw policy.deny(ws, caller.subjectId(), Permission.WORKFLOW_APPROVE_PROMOTION.value(), operation, true,
                    new ApiException(ProblemCode.SELF_APPROVAL_DENIED));
        }
    }
}
