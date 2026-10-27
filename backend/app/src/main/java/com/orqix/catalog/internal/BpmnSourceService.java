package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.BpmnSource;
import com.orqix.compiler.CompilationException;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.CompiledWorkflow;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * BPMN source of a workflow draft (UI03, docs/build/S60). Permissions follow the YAML source mode of S53: anyone who
 * may read the draft sees its BPMN ({@code workflow.read}); checking and applying edited BPMN are editing
 * ({@code workflow.edit}), and applying saves a normal draft revision with the same If-Match rules, size limits and
 * audit event as the studio's autosave. Nothing is published or validated away: the saved draft still goes through
 * Check workflow and the publish gates.
 */
@Service
public class BpmnSourceService {

    private final CatalogStore store;
    private final WorkflowService workflows;
    private final PolicyService policy;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final Json json;
    private final AuditWriter audit;

    public BpmnSourceService(CatalogStore store, WorkflowService workflows, PolicyService policy,
            WorkflowCompiler compiler, CompileContextFactory contexts, Json json, AuditWriter audit) {
        this.store = store;
        this.workflows = workflows;
        this.policy = policy;
        this.compiler = compiler;
        this.contexts = contexts;
        this.json = json;
        this.audit = audit;
    }

    /** The compiled BPMN of a draft revision (latest when {@code revision} is null) and its round-trip result. */
    @Transactional(readOnly = true)
    public CatalogDtos.DraftBpmn getDraftBpmn(Caller caller, UUID workspaceId, UUID workflowId, Integer revision) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "getDraftBpmn");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "getDraftBpmn", false);
        int rev = revision == null ? visible.workflow().draftRevision() : revision;
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, rev).orElseThrow(ApiException::notFound);
        String definition = draft.definitionJson();
        WorkspaceAccess.Workspace workspace = visible.access().workspace();
        try {
            CompiledWorkflow compiled = compiler.compile(definition, contexts.create(workspace, definition));
            BpmnSource.Export export = BpmnSource.export(definition, compiled);
            return new CatalogDtos.DraftBpmn(rev, compiler.version(), true, export.bpmnXml(), export.diagram(),
                    new CatalogDtos.BpmnRoundTrip(export.identical(), export.differences().stream()
                            .map(d -> new CatalogDtos.BpmnDifference(d.path(), d.message())).toList()),
                    export.keptFromDraft(), List.of());
        } catch (CompilationException e) {
            return new CatalogDtos.DraftBpmn(rev, compiler.version(), false, null, false,
                    new CatalogDtos.BpmnRoundTrip(false, List.of()), keptFromDraft(definition),
                    WorkflowService.issues(blockers(e)));
        } catch (RuntimeException e) {
            // A draft that is not even readable JSON has no BPMN form; it is reported like a blocked draft.
            return new CatalogDtos.DraftBpmn(rev, compiler.version(), false, null, false,
                    new CatalogDtos.BpmnRoundTrip(false, List.of()), List.of(), List.of());
        }
    }

    /** A downloadable file: a safe name and the bytes (UTF-8 BPMN). */
    public record BpmnFile(String fileName, byte[] content) {
    }

    /**
     * The {@code .bpmn} file of a draft revision (STU04, docs/build/S97): the BPMN publishing would deploy, with a complete
     * diagram. Anyone who may read the draft ({@code workflow.read}) may download it; recorded as an export with ids only.
     * A draft with blocking issues has no BPMN (422 {@code WORKFLOW_INVALID}).
     */
    @Transactional
    public BpmnFile exportDraft(Caller caller, UUID workspaceId, UUID workflowId, Integer revision) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "exportDraftBpmn");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "exportDraftBpmn", false);
        int rev = revision == null ? visible.workflow().draftRevision() : revision;
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, rev).orElseThrow(ApiException::notFound);
        String definition = draft.definitionJson();
        CompiledWorkflow compiled;
        try {
            compiled = compiler.compile(definition, contexts.create(visible.access().workspace(), definition));
        } catch (CompilationException | IllegalArgumentException e) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID,
                    "The draft has blocking issues, so there is no BPMN to download. Run Check workflow to see them.");
        }
        String file = BpmnSource.downloadFile(compiled.bpmnXml(), definition,
                "draft of workflow " + workflowId + ", revision " + rev + ", workspace " + workspaceId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", "bpmn");
        metadata.put("revision", rev);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.exported", "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new BpmnFile(safeName(visible.workflow().key()) + "-draft-" + rev + ".bpmn",
                file.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The {@code .bpmn} file of a published release: the BPMN exactly as deployed (the stored artifact), with a complete
     * diagram. Needs {@code workflow.export} like the release bundle; recorded as an export with ids only.
     */
    @Transactional
    public BpmnFile exportRelease(Caller caller, UUID workspaceId, UUID workflowId, UUID releaseId) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "exportReleaseBpmn");
        // Permission first, then the release: a caller without workflow.export learns nothing about which releases exist.
        policy.require(visible.access(), Permission.WORKFLOW_EXPORT, "exportReleaseBpmn", true);
        CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, releaseId)
                .orElseThrow(ApiException::notFound);
        byte[] stored = store.artifacts(releaseId).stream().filter(a -> "process.bpmn20.xml".equals(a.name()))
                .findFirst().orElseThrow(ApiException::notFound).content();
        String file = BpmnSource.downloadFile(new String(stored, StandardCharsets.UTF_8), release.definitionJson(),
                "release " + releaseId + " (version " + release.releaseNumber() + ") of workflow " + workflowId
                        + ", workspace " + workspaceId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", "bpmn");
        metadata.put("releaseNumber", release.releaseNumber());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.exported", "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new BpmnFile(safeName(visible.workflow().key()) + "-r" + release.releaseNumber() + ".bpmn",
                file.getBytes(StandardCharsets.UTF_8));
    }

    /** Workflow keys are already restricted; the file name is cut to letters, digits, dot, dash and underscore anyway. */
    static String safeName(String key) {
        String safe = key == null ? "" : key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return safe.isEmpty() || safe.startsWith(".") ? "workflow" + safe : safe;
    }

    /** Checks edited BPMN against a draft revision without saving anything. */
    @Transactional(readOnly = true)
    public CatalogDtos.BpmnCheck checkDraftBpmn(Caller caller, UUID workspaceId, UUID workflowId,
            CatalogDtos.BpmnCheckRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "checkDraftBpmn");
        policy.require(visible.access(), Permission.WORKFLOW_EDIT, "checkDraftBpmn", false);
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, request.revision())
                .orElseThrow(ApiException::notFound);
        BpmnSource.Check check = check(visible.access().workspace(), request.bpmn(), draft.definitionJson());
        return view(request.revision(), check);
    }

    /**
     * Applies edited BPMN to the latest draft revision named by If-Match. Refused with 422 VALIDATION_FAILED (one field
     * error per problem, code = problem kind) unless the check passes; 412 when the draft moved on.
     */
    @Transactional
    public CatalogDtos.WorkflowDraft applyDraftBpmn(Caller caller, UUID workspaceId, UUID workflowId, String ifMatch,
            CatalogDtos.BpmnApplyRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "applyDraftBpmn");
        policy.require(visible.access(), Permission.WORKFLOW_EDIT, "applyDraftBpmn", false);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ProblemCode.PRECONDITION_REQUIRED);
        }
        int current = visible.workflow().draftRevision();
        Integer expected = WorkflowService.parseEtag(ifMatch);
        if (expected == null || expected != current) {
            throw new ApiException(ProblemCode.STALE_REVISION, "The draft was saved by someone else.")
                    .with("currentRevision", current);
        }
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, current).orElseThrow(ApiException::notFound);
        BpmnSource.Check check = check(visible.access().workspace(), request.bpmn(), draft.definitionJson());
        if (!check.applicable()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The BPMN cannot be applied: "
                    + check.problems().get(0).message())
                    .withFieldErrors(check.problems().stream().limit(100)
                            .map(p -> new ApiException.FieldError("/bpmn", p.kind(),
                                    (p.elementId() == null ? "" : p.elementId() + ": ") + p.message()))
                            .toList());
        }
        return workflows.saveDraft(caller, workspaceId, workflowId, ifMatch,
                new CatalogDtos.SaveDraftRequest(json.parse(check.definitionJson())), "applyDraftBpmn", "bpmn");
    }

    private BpmnSource.Check check(WorkspaceAccess.Workspace workspace, String bpmn, String definition) {
        try {
            return BpmnSource.check(bpmn, definition, compiler, d -> context(workspace, d));
        } catch (IllegalArgumentException e) {
            return new BpmnSource.Check(false, List.of(new BpmnSource.Problem("draftRoundTrip", null,
                    "The current draft is not a readable workflow document, so BPMN cannot be applied to it.")),
                    List.of(), List.of(), null, List.of());
        }
    }

    private CompileContext context(WorkspaceAccess.Workspace workspace, String definition) {
        return contexts.create(workspace, definition);
    }

    private static CatalogDtos.BpmnCheck view(int revision, BpmnSource.Check check) {
        return new CatalogDtos.BpmnCheck(revision, check.applicable(),
                check.problems().stream().map(p -> new CatalogDtos.BpmnProblem(p.kind(), p.elementId(), p.message()))
                        .toList(),
                check.notes(), check.changes().stream().map(c -> new CatalogDtos.BpmnChange(c.kind(), c.what())).toList());
    }

    private static List<ValidationIssue> blockers(CompilationException e) {
        return e.report().issues().stream().filter(i -> i.severity() == Severity.BLOCKER).toList();
    }

    private static List<String> keptFromDraft(String definition) {
        try {
            return BpmnSource.keptFromDraft(definition, false);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
