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
import com.orqix.integration.ConnectionService;
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

    public record Environment(UUID workspaceId, String stage, boolean explicit, int version) {
    }

    public record SetEnvironmentRequest(@NotNull String stage, @Min(0) int expectedVersion) {
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

    public record Report(boolean compatible, String sourceStage, String targetStage, TargetWorkflow targetWorkflow,
            List<ConnectionCheck> connections, List<GroupCheck> groups, List<WorkflowCheck> workflows,
            List<CatalogDtos.Issue> issues) {
    }

    public record Promotion(UUID id, UUID targetWorkspaceId, UUID targetWorkflowId, int draftRevision,
            boolean createdWorkflow, Report report) {
    }

    public record PromotionRecord(UUID id, String direction, String sourceWorkspaceKey, String targetWorkspaceKey,
            String workflowKey, int releaseNumber, int targetDraftRevision, boolean createdWorkflow, Instant promotedAt,
            String promotedBy) {
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
    private final Json json;
    private final Clock clock;

    public PromotionService(JdbcClient jdbc, CatalogStore store, WorkflowService workflows, PolicyService policy,
            WorkflowCompiler compiler, CompileContextFactory contexts, ConnectionService connections,
            GroupDirectory groups, AuditWriter audit, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.workflows = workflows;
        this.policy = policy;
        this.compiler = compiler;
        this.contexts = contexts;
        this.connections = connections;
        this.groups = groups;
        this.audit = audit;
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
        return jdbc.sql("SELECT stage, version FROM workspace_environment WHERE workspace_id = :ws").param("ws", ws)
                .query((rs, n) -> new Environment(ws, rs.getString("stage"), true, rs.getInt("version")))
                .optional().orElseGet(() -> new Environment(ws, isTest ? "test" : "production", false, 0));
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
        if (current.explicit()) {
            jdbc.sql("""
                    UPDATE workspace_environment SET stage = :stage, version = version + 1, updated_by = :by, updated_at = now()
                    WHERE workspace_id = :ws
                    """).param("stage", request.stage()).param("by", caller.subjectId()).param("ws", ws).update();
        } else {
            jdbc.sql("INSERT INTO workspace_environment (workspace_id, stage, updated_by) VALUES (:ws, :stage, :by)")
                    .param("ws", ws).param("stage", request.stage()).param("by", caller.subjectId()).update();
        }
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "workspace.environment.changed", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("from", current.stage(), "to", request.stage())));
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
        if (!report.compatible()) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The release cannot be promoted: fix the findings in the target.")
                    .with("issues", report.issues());
        }
        UUID target = request.targetWorkspaceId();
        JsonNode source = json.parse(p.release().definitionJson());
        ObjectNode definition = (ObjectNode) source.deepCopy();
        definition.put("workflowKey", p.workflow().key());
        String definitionJson = json.write(definition);

        UUID targetId;
        int revision;
        boolean created = !report.targetWorkflow().exists();
        if (created) {
            targetId = Ids.newId();
            if (request.expectedDraftRevision() != null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "The target workflow does not exist yet, so there is no draft revision to expect.");
            }
            if (!store.insertWorkflow(targetId, target, p.workflow().key(), p.workflow().name(), p.workflow().description(),
                    caller.subjectId())) {
                throw new ApiException(ProblemCode.DUPLICATE_KEY, "A workflow with this key already exists in the target.");
            }
            revision = 1;
            store.insertDraft(target, targetId, revision, definitionJson, "0.1", caller.subjectId());
        } else {
            targetId = report.targetWorkflow().id();
            CatalogStore.WorkflowRow existing = store.lockWorkflow(target, targetId).orElseThrow(ApiException::notFound);
            Integer expected = request.expectedDraftRevision();
            if (expected == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "The target already has this workflow; send expectedDraftRevision (" + existing.draftRevision() + ").");
            }
            if (expected != existing.draftRevision() || !store.advanceDraft(target, targetId, existing.draftRevision())) {
                throw new ApiException(ProblemCode.STALE_REVISION, "The target draft was saved by someone else.")
                        .with("currentRevision", existing.draftRevision());
            }
            revision = existing.draftRevision() + 1;
            store.insertDraft(target, targetId, revision, definitionJson, "0.1", caller.subjectId());
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO release_promotion (id, organization_id, source_workspace_id, source_workspace_key,
                    source_workflow_key, source_release_number, source_semantic_hash, target_workspace_id,
                    target_workflow_id, target_draft_revision, created_target, report, promoted_by)
                VALUES (:id, :org, :sws, :swk, :wk, :rn, :hash, :tws, :twf, :rev, :created, CAST(:report AS jsonb), :by)
                """).param("id", id).param("org", p.source().workspace().organizationId()).param("sws", ws)
                .param("swk", p.source().workspace().key()).param("wk", p.workflow().key())
                .param("rn", p.release().releaseNumber()).param("hash", p.release().semanticHash())
                .param("tws", target).param("twf", targetId).param("rev", revision).param("created", created)
                .param("report", json.write(json.mapper().valueToTree(report))).param("by", caller.subjectId()).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "release.promoted",
                "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS, Map.of("releaseNumber",
                        p.release().releaseNumber(), "targetWorkspaceId", target.toString(), "targetWorkflowId",
                        targetId.toString(), "draftRevision", revision, "createdWorkflow", created)));
        audit.write(new AuditWriter.Entry(target, AuditWriter.ActorType.USER, caller.subjectId(),
                "release.promotion.received", "workflow", targetId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("releaseNumber", p.release().releaseNumber(), "sourceWorkspaceId", ws.toString(),
                        "targetWorkflowId", targetId.toString(), "draftRevision", revision, "createdWorkflow", created)));
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
        return new Report(validation.publishable(), sourceStage, targetStage, targetWorkflow, connectionChecks,
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
                       p.promoted_at, s.display_name AS promoted_by, p.target_workflow_id
                FROM release_promotion p JOIN workspace tw ON tw.id = p.target_workspace_id
                     JOIN subject s ON s.id = p.promoted_by
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
                    ((java.sql.Timestamp) row.get("promoted_at")).toInstant(), (String) row.get("promoted_by")));
        }
        return result;
    }
}
