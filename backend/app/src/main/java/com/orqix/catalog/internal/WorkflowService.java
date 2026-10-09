package com.orqix.catalog.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.SubjectService.SubjectRef;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.pagination.Limits;
import com.orqix.shared.pagination.Page;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Workflows, templates, drafts, validation and decision testing (B03.4, S3 sections 6 and 8, S4). */
@Service
public class WorkflowService {

    private final CatalogStore store;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final TemplateCatalog templates;
    private final WorkflowCompiler compiler;
    private final DecisionEvaluator evaluator;
    private final CompileContextFactory contexts;
    private final AuditWriter audit;
    private final CursorCodec cursors;
    private final OrqixProperties properties;
    private final Json json;

    public WorkflowService(CatalogStore store, PolicyService policy, SubjectService subjects, TemplateCatalog templates,
            WorkflowCompiler compiler, DecisionEvaluator evaluator, CompileContextFactory contexts, AuditWriter audit,
            CursorCodec cursors, OrqixProperties properties, Json json) {
        this.store = store;
        this.policy = policy;
        this.subjects = subjects;
        this.templates = templates;
        this.compiler = compiler;
        this.evaluator = evaluator;
        this.contexts = contexts;
        this.audit = audit;
        this.cursors = cursors;
        this.properties = properties;
        this.json = json;
    }

    // ---------------------------------------------------------------- templates

    @Transactional(readOnly = true)
    public CatalogDtos.TemplateList listTemplates(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, "listTemplates", false);
        return new CatalogDtos.TemplateList(templates.all().stream()
                .map(t -> new CatalogDtos.Template(t.id(), t.name(), t.description(), t.typicalOwner(), t.requiredInputs(),
                        t.requiredGroups(), t.requiredConnections(), t.requiredWorkflows()))
                .toList());
    }

    // ---------------------------------------------------------------- workflows

    @Transactional(readOnly = true)
    public CatalogDtos.WorkflowPage listWorkflows(Caller caller, UUID workspaceId, String q, boolean startable,
            String cursor, Integer limit) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        boolean reader = access.has(Permission.WORKFLOW_READ);
        if (!reader && !access.canStartAnything()) {
            throw policy.deny(workspaceId, caller.subjectId(), "workflow.read", "listWorkflows", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        if (q != null && q.length() > 100) {
            throw ApiException.malformed("q must be at most 100 characters.");
        }
        int size = Limits.of(limit);
        String filterKey = "listWorkflows|" + workspaceId + "|" + (q == null ? "" : q) + "|" + startable + "|" + reader;
        CursorCodec.Position after = cursors.decode(cursor, filterKey);
        // Starters without workflow.read only see workflows they may start now (active release + grant).
        boolean onlyStartable = startable || !reader;
        Set<UUID> restrictTo = onlyStartable && !access.has(Permission.WORKFLOW_START) ? access.workflowStartGrants() : null;
        List<CatalogStore.WorkflowRow> rows = store.workflows(workspaceId, q, onlyStartable, restrictTo, after, size + 1);
        Page.Result<CatalogStore.WorkflowRow> page = Page.of(rows, size,
                row -> new CursorCodec.Position(row.createdAt(), row.id().toString()), cursors, filterKey);
        Map<UUID, String> names = subjects.displayNames(page.items().stream().map(CatalogStore.WorkflowRow::owner).toList());
        return new CatalogDtos.WorkflowPage(page.items().stream().map(row -> summary(row, access, names)).toList(),
                page.nextCursor());
    }

    @Transactional
    public CatalogDtos.WorkflowView createWorkflow(Caller caller, UUID workspaceId, CatalogDtos.CreateWorkflowRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, "createWorkflow", false);
        String definitionJson;
        if (request.templateId() != null) {
            TemplateCatalog.Template template = templates.find(request.templateId())
                    .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown template.")
                            .withFieldErrors(List.of(new ApiException.FieldError("/templateId", "enum",
                                    "Unknown template"))));
            ObjectNode definition = (ObjectNode) json.parse(template.definitionJson());
            definition.put("workflowKey", request.key());
            definition.put("name", request.name());
            if (request.description() != null) {
                ((ObjectNode) definition.withObjectProperty("metadata")).put("description", request.description());
            }
            definitionJson = json.write(definition);
        } else {
            definitionJson = json.write(emptyDefinition(request));
        }
        UUID id = Ids.newId();
        if (!store.insertWorkflow(id, workspaceId, request.key(), request.name(), request.description(), caller.subjectId())) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A workflow with this key already exists.");
        }
        store.insertDraft(workspaceId, id, 1, definitionJson, "0.1", caller.subjectId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", request.key());
        metadata.put("templateId", request.templateId());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.created",
                "workflow", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return view(store.workflow(workspaceId, id).orElseThrow(), access);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.WorkflowView getWorkflow(Caller caller, UUID workspaceId, UUID workflowId) {
        Visible visible = visible(caller, workspaceId, workflowId, "getWorkflow");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "getWorkflow", false);
        return view(visible.workflow(), visible.access());
    }

    // ---------------------------------------------------------------- drafts

    @Transactional(readOnly = true)
    public CatalogDtos.WorkflowDraft getDraft(Caller caller, UUID workspaceId, UUID workflowId) {
        Visible visible = visible(caller, workspaceId, workflowId, "getDraft");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "getDraft", false);
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, visible.workflow().draftRevision()).orElseThrow();
        return draftView(draft);
    }

    @Transactional
    public CatalogDtos.WorkflowDraft saveDraft(Caller caller, UUID workspaceId, UUID workflowId, String ifMatch,
            CatalogDtos.SaveDraftRequest request) {
        Visible visible = visible(caller, workspaceId, workflowId, "saveDraft");
        policy.require(visible.access(), Permission.WORKFLOW_EDIT, "saveDraft", false);
        JsonNode definition = request.definition();
        if (definition == null || !definition.isObject()) {
            throw ApiException.malformed("definition must be a JSON object.");
        }
        if (Json.depth(definition) > properties.limits().jsonMaxDepth()) {
            throw ApiException.malformed("The definition is nested deeper than " + properties.limits().jsonMaxDepth()
                    + " levels.");
        }
        String definitionJson = json.write(definition);
        if (definitionJson.getBytes(StandardCharsets.UTF_8).length > properties.limits().draftMaxBytes()) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The definition exceeds 256 KiB.");
        }
        if (!"0.1".equals(definition.path("schemaVersion").asString(null))) {
            throw ApiException.malformed("Unsupported schemaVersion; expected \"0.1\".");
        }
        JsonNode workflowKey = definition.get("workflowKey");
        if (workflowKey != null && !visible.workflow().key().equals(workflowKey.asString(null))) {
            throw ApiException.malformed("definition.workflowKey must equal the workflow key.");
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ProblemCode.PRECONDITION_REQUIRED);
        }
        int current = visible.workflow().draftRevision();
        Integer expected = parseEtag(ifMatch);
        if (expected == null || expected != current || !store.advanceDraft(workspaceId, workflowId, current)) {
            int latest = store.workflow(workspaceId, workflowId).map(CatalogStore.WorkflowRow::draftRevision).orElse(current);
            throw new ApiException(ProblemCode.STALE_REVISION, "The draft was saved by someone else.")
                    .with("currentRevision", latest);
        }
        int revision = current + 1;
        store.insertDraft(workspaceId, workflowId, revision, definitionJson, "0.1", caller.subjectId());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.draft.saved", "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("revision", revision)));
        return draftView(store.draft(workspaceId, workflowId, revision).orElseThrow());
    }

    @Transactional(readOnly = true)
    public CatalogDtos.ValidationReport validateDraft(Caller caller, UUID workspaceId, UUID workflowId, int revision) {
        Visible visible = visible(caller, workspaceId, workflowId, "validateDraft");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "validateDraft", false);
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, revision).orElseThrow(ApiException::notFound);
        CompileContext context = contexts.create(visible.access().workspace(), draft.definitionJson());
        ValidationReport report = compiler.validate(draft.definitionJson(), context);
        return new CatalogDtos.ValidationReport(revision, report.compilerVersion(), report.publishable(),
                report.semanticHash(), issues(report.issues()));
    }

    @Transactional(readOnly = true)
    public CatalogDtos.DecisionEvaluateResult evaluateDecision(Caller caller, UUID workspaceId, UUID workflowId,
            String decisionKey, CatalogDtos.DecisionEvaluateRequest request) {
        Visible visible = visible(caller, workspaceId, workflowId, "evaluateDecision");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "evaluateDecision", false);
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, request.revision())
                .orElseThrow(ApiException::notFound);
        boolean exists = false;
        for (JsonNode table : json.parse(draft.definitionJson()).path("decisions")) {
            exists |= decisionKey.equals(table.path("key").asString(null));
        }
        if (!exists) {
            throw ApiException.notFound();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<ApiException.FieldError> errors = new ArrayList<>();
        request.values().forEach((fieldRef, value) -> {
            Object scalar = Json.scalar(value);
            if (scalar == null) {
                errors.add(new ApiException.FieldError("/values/" + escape(fieldRef), "type",
                        "Use a string, number or boolean."));
            } else {
                values.put(fieldRef, scalar);
            }
        });
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED).withFieldErrors(errors);
        }
        try {
            DecisionEvaluator.Result result = evaluator.evaluate(draft.definitionJson(), decisionKey, values);
            Map<String, Object> output = new LinkedHashMap<>();
            result.output().forEach((k, v) -> output.put(k, v instanceof BigDecimal d ? d : v));
            return new CatalogDtos.DecisionEvaluateResult(result.matchedRow(), result.usedDefault(), output);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The sample values do not fit the decision table.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/values", "type",
                            "A tested field is missing or has the wrong type")));
        }
    }

    // ---------------------------------------------------------------- helpers

    record Visible(WorkspaceAccess access, CatalogStore.WorkflowRow workflow) {
    }

    /** Loads the workflow by (workspace, id); 404 unless the caller may read workflows or start this one. */
    Visible visible(Caller caller, UUID workspaceId, UUID workflowId, String operationId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        CatalogStore.WorkflowRow workflow = store.workflow(workspaceId, workflowId).orElseThrow(ApiException::notFound);
        if (!access.has(Permission.WORKFLOW_READ) && !canStart(access, workflow)
                && !access.has(Permission.WORKFLOW_EDIT) && !access.has(Permission.WORKFLOW_PUBLISH)) {
            throw ApiException.notFound();
        }
        return new Visible(access, workflow);
    }

    static boolean canStart(WorkspaceAccess access, CatalogStore.WorkflowRow workflow) {
        return workflow.activeReleaseId() != null && access.canStart(workflow.id());
    }

    CatalogDtos.WorkflowView view(CatalogStore.WorkflowRow row, WorkspaceAccess access) {
        Map<UUID, String> names = subjects.displayNames(List.of(row.owner()));
        CatalogDtos.WorkflowView summary = summary(row, access, names);
        return new CatalogDtos.WorkflowView(summary.id(), summary.key(), summary.name(), summary.description(),
                summary.owner(), summary.draftRevision(), summary.activeRelease(), summary.canStart(),
                summary.updatedAt(), row.version(), row.createdAt(),
                Permission.apiValues(access.permissions(), access.canStartAnything()));
    }

    private CatalogDtos.WorkflowView summary(CatalogStore.WorkflowRow row, WorkspaceAccess access, Map<UUID, String> names) {
        CatalogDtos.ActiveReleaseRef active = row.activeReleaseId() == null ? null
                : new CatalogDtos.ActiveReleaseRef(row.activeReleaseId(), row.activeReleaseNumber(), row.activePublishedAt());
        return new CatalogDtos.WorkflowView(row.id(), row.key(), row.name(), row.description(),
                subjects.ref(row.owner(), names), row.draftRevision(), active, canStart(access, row), row.updatedAt(),
                null, null, null);
    }

    private CatalogDtos.WorkflowDraft draftView(CatalogStore.DraftRow draft) {
        return new CatalogDtos.WorkflowDraft(draft.workflowId(), draft.revision(), draft.savedAt(),
                subjects.ref(draft.savedBy()), json.parse(draft.definitionJson()));
    }

    public static List<CatalogDtos.Issue> issues(List<ValidationIssue> issues) {
        return issues.stream().map(i -> new CatalogDtos.Issue(i.code(), i.severity().apiValue(), i.message(), i.path(),
                i.nodeId(), i.transitionId())).toList();
    }

    public static String etag(int revision) {
        return "\"r" + revision + "\"";
    }

    static Integer parseEtag(String ifMatch) {
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.startsWith("r")) {
            return null;
        }
        try {
            return Integer.parseInt(value.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ObjectNode emptyDefinition(CatalogDtos.CreateWorkflowRequest request) {
        ObjectNode definition = json.object();
        definition.put("schemaVersion", "0.1");
        definition.put("workflowKey", request.key());
        definition.put("name", request.name());
        definition.putObject("metadata").put("description", request.description() == null ? "" : request.description());
        ObjectNode input = definition.putObject("inputSchema");
        input.put("type", "object");
        input.put("additionalProperties", false);
        input.putObject("properties");
        ObjectNode output = definition.putObject("outputSchema");
        output.put("type", "object");
        output.put("additionalProperties", false);
        output.putObject("properties");
        definition.putArray("triggers").add("manual");
        definition.putArray("nodes");
        definition.putArray("transitions");
        definition.putArray("decisions");
        ObjectNode execution = definition.putObject("execution");
        execution.put("maxConcurrentRuns", 100);
        execution.put("maxAttempts", 3);
        execution.put("retryDelaySeconds", 30);
        definition.putObject("security").put("selfApproval", "deny");
        definition.putObject("dependencies").putArray("connections");
        definition.putObject("outputMapping");
        return definition;
    }

    private static String escape(String pointerSegment) {
        return pointerSegment.replace("~", "~0").replace("/", "~1");
    }

    @SuppressWarnings("unused")
    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}
