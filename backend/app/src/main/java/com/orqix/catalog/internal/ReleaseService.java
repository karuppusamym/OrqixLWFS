package com.orqix.catalog.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.EngineDeployer;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.CompilationException;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.CompiledWorkflow;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ConnectionService;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.pagination.CursorCodec;
import com.orqix.shared.pagination.Limits;
import com.orqix.shared.pagination.Page;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Publish, releases, active release, export and the start form (B03.5, B03.6, S4 sections 10-11). */
@Service
public class ReleaseService {

    /** Outcome of publish: the release plus whether this was an idempotent replay. */
    public record Published(CatalogDtos.ReleaseView release, boolean replayed) {
    }

    /** Export bundle. */
    public record Bundle(String fileName, byte[] zip) {
    }

    private final CatalogStore store;
    private final WorkflowService workflows;
    private final PolicyService policy;
    private final SubjectService subjects;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final EngineDeployer deployer;
    private final IdempotencyService idempotency;
    private final ConnectionService connections;
    private final AuditWriter audit;
    private final CursorCodec cursors;
    private final Json json;
    private final Clock clock;

    public ReleaseService(CatalogStore store, WorkflowService workflows, PolicyService policy, SubjectService subjects,
            WorkflowCompiler compiler, CompileContextFactory contexts, EngineDeployer deployer,
            IdempotencyService idempotency, ConnectionService connections, AuditWriter audit, CursorCodec cursors,
            Json json, Clock clock) {
        this.store = store;
        this.workflows = workflows;
        this.policy = policy;
        this.subjects = subjects;
        this.compiler = compiler;
        this.contexts = contexts;
        this.deployer = deployer;
        this.idempotency = idempotency;
        this.connections = connections;
        this.audit = audit;
        this.cursors = cursors;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- publish

    @Transactional
    public Published publish(Caller caller, UUID workspaceId, UUID workflowId, String idempotencyKey,
            CatalogDtos.PublishRequest request) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "publishDraft");
        WorkspaceAccess access = visible.access();
        policy.require(access, Permission.WORKFLOW_PUBLISH, "publishDraft", true);

        String hash = idempotency.requestHash("publishDraft",
                Map.of("workspaceId", workspaceId.toString(), "workflowId", workflowId.toString()), request);
        IdempotencyService.Claim claim = idempotency.claim(workspaceId, caller.subjectId(), "publishDraft", key, hash);
        if (claim.isReplay()) {
            UUID releaseId = UUID.fromString(claim.replay().resourceId());
            CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, releaseId).orElseThrow();
            return new Published(releaseView(release, store.workflow(workspaceId, workflowId).orElseThrow(), true), true);
        }

        CatalogStore.WorkflowRow workflow = store.lockWorkflow(workspaceId, workflowId).orElseThrow(ApiException::notFound);
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, request.draftRevision())
                .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "The draft revision does not exist.")
                        .withFieldErrors(List.of(new ApiException.FieldError("/draftRevision", "minimum",
                                "Unknown draft revision"))));
        CompileContext context = contexts.create(access.workspace(), draft.definitionJson());
        ValidationReport report = compiler.validate(draft.definitionJson(), context);
        if (!report.publishable()) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "Fix the blocking issues before publishing.")
                    .with("issues", WorkflowService.issues(report.issues()));
        }
        boolean acknowledged = Boolean.TRUE.equals(request.acknowledgeWarnings());
        if (!report.warnings().isEmpty() && !acknowledged) {
            throw new ApiException(ProblemCode.WARNINGS_NOT_ACKNOWLEDGED, "Review and acknowledge the warnings.")
                    .with("issues", WorkflowService.issues(report.warnings()));
        }
        CompiledWorkflow compiled;
        try {
            compiled = compiler.compile(draft.definitionJson(), context);
        } catch (CompilationException e) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "Fix the blocking issues before publishing.")
                    .with("issues", WorkflowService.issues(e.report().issues()));
        }

        int number = store.nextReleaseNumber(workspaceId, workflowId);
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put("process.bpmn20.xml", compiled.bpmnXml().getBytes(StandardCharsets.UTF_8));
        compiled.dmnResources().forEach((name, xml) -> resources.put(name, xml.getBytes(StandardCharsets.UTF_8)));
        EngineDeployer.Deployed deployed = deployer.deploy(workflow.key() + " r" + number, workflowId.toString(),
                access.workspace().engineTenantId(), resources);

        UUID releaseId = Ids.newId();
        Instant now = clock.instant();
        ObjectNode manifest = manifest(releaseId, workflow.key(), compiled, deployed.processDefinitionId());
        store.insertRelease(new CatalogStore.ReleaseRow(releaseId, workspaceId, workflowId, number, draft.revision(),
                draft.definitionJson(), "0.1", compiled.sourceHash(), compiled.semanticHash(), compiled.compilerVersion(),
                json.write(manifest), compiled.sourceMapJson(),
                json.write(WorkflowService.issues(compiled.warnings())), deployed.deploymentId(),
                deployed.processDefinitionId(), request.releaseNote(), caller.subjectId(), now));
        resources.forEach((name, bytes) -> store.insertArtifact(releaseId, new CatalogStore.Artifact(name,
                "application/xml", bytes, Json.sha256Hex(bytes))));
        byte[] sourceMap = compiled.sourceMapJson().getBytes(StandardCharsets.UTF_8);
        store.insertArtifact(releaseId, new CatalogStore.Artifact("source-map.json", "application/json", sourceMap,
                Json.sha256Hex(sourceMap)));
        if (request.activate() == null || request.activate()) {
            if (!store.setActiveRelease(workspaceId, workflowId, releaseId, workflow.version())) {
                throw new IllegalStateException("Workflow version changed while locked");
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("releaseNumber", number);
        metadata.put("draftRevision", draft.revision());
        metadata.put("semanticHash", compiled.semanticHash());
        metadata.put("warningsAcknowledged", acknowledged);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.published", "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        idempotency.complete(claim, "release", releaseId.toString(), 201);
        CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, releaseId).orElseThrow();
        return new Published(releaseView(release, store.workflow(workspaceId, workflowId).orElseThrow(), true), false);
    }

    private ObjectNode manifest(UUID releaseId, String workflowKey, CompiledWorkflow compiled, String processDefinitionId) {
        ObjectNode manifest = json.object();
        manifest.put("releaseId", releaseId.toString());
        manifest.put("workflowKey", workflowKey);
        manifest.put("schemaVersion", "0.1");
        manifest.put("compilerVersion", compiled.compilerVersion());
        manifest.put("sourceHash", compiled.sourceHash());
        manifest.put("semanticHash", compiled.semanticHash());
        manifest.put("bpmnHash", compiled.bpmnHash());
        ObjectNode dmnHashes = manifest.putObject("dmnHashes");
        new TreeMap<>(compiled.dmnHashes()).forEach(dmnHashes::put);
        manifest.put("processDefinitionId", processDefinitionId);
        ArrayNode decisions = manifest.putArray("decisions");
        compiled.decisions().forEach(d -> decisions.addObject().put("key", d.key()).put("version", d.version()));
        ArrayNode connectionRefs = manifest.putArray("connections");
        compiled.connections().forEach(c -> connectionRefs.addObject().put("key", c.key())
                .put("definitionVersion", c.definitionVersion()).put("definitionHash", c.definitionHash()));
        ArrayNode groups = manifest.putArray("groups");
        compiled.groups().forEach(groups::add);
        // Child workflows pinned at publish time (S12): the release id of each started workflow's active release.
        ArrayNode children = manifest.putArray("workflows");
        compiled.children().forEach(c -> children.addObject().put("nodeId", c.nodeId())
                .put("workflowKey", c.workflowKey()).put("releaseId", c.releaseId())
                .put("releaseNumber", c.releaseNumber()).put("semanticHash", c.semanticHash()));
        ArrayNode closure = manifest.putArray("callClosure");
        compiled.callClosure().forEach(closure::add);
        manifest.put("callHeight", compiled.callHeight());
        return manifest;
    }

    // ---------------------------------------------------------------- releases

    @Transactional(readOnly = true)
    public CatalogDtos.ReleasePage listReleases(Caller caller, UUID workspaceId, UUID workflowId, String cursor,
            Integer limit) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "listReleases");
        policy.require(visible.access(), Permission.WORKFLOW_READ, "listReleases", false);
        int size = Limits.of(limit);
        String filterKey = "listReleases|" + workspaceId + "|" + workflowId;
        List<CatalogStore.ReleaseRow> rows = store.releases(workspaceId, workflowId, cursors.decode(cursor, filterKey),
                size + 1);
        Page.Result<CatalogStore.ReleaseRow> page = Page.of(rows, size,
                r -> new CursorCodec.Position(r.publishedAt(), r.id().toString()), cursors, filterKey);
        return new CatalogDtos.ReleasePage(page.items().stream().map(r -> releaseView(r, visible.workflow(), false))
                .toList(), page.nextCursor());
    }

    @Transactional(readOnly = true)
    public CatalogDtos.ReleaseView getRelease(Caller caller, UUID workspaceId, UUID workflowId, UUID releaseId) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "getRelease");
        CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, releaseId)
                .orElseThrow(ApiException::notFound);
        policy.require(visible.access(), Permission.WORKFLOW_READ, "getRelease", false);
        return releaseView(release, visible.workflow(), true);
    }

    @Transactional
    public CatalogDtos.WorkflowView setActiveRelease(Caller caller, UUID workspaceId, UUID workflowId,
            CatalogDtos.SetActiveReleaseRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "setActiveRelease");
        policy.require(visible.access(), Permission.WORKFLOW_PUBLISH, "setActiveRelease", true);
        CatalogStore.WorkflowRow workflow = store.lockWorkflow(workspaceId, workflowId).orElseThrow(ApiException::notFound);
        CatalogStore.ReleaseRow target = store.release(workspaceId, workflowId, request.releaseId())
                .orElseThrow(ApiException::notFound);
        if (workflow.version() != request.expectedVersion()
                || !store.setActiveRelease(workspaceId, workflowId, target.id(), request.expectedVersion())) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The workflow changed since you loaded it.")
                    .with("currentVersion", workflow.version());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fromReleaseNumber", workflow.activeReleaseNumber());
        metadata.put("toReleaseNumber", target.releaseNumber());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.active-release.changed", "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS,
                metadata));
        return workflows.view(store.workflow(workspaceId, workflowId).orElseThrow(), visible.access());
    }

    // ---------------------------------------------------------------- export (S4 section 11)

    @Transactional
    public Bundle export(Caller caller, UUID workspaceId, UUID workflowId, UUID releaseId) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "exportRelease");
        CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, releaseId)
                .orElseThrow(ApiException::notFound);
        policy.require(visible.access(), Permission.WORKFLOW_EXPORT, "exportRelease", true);
        Map<String, byte[]> entries = new TreeMap<>();
        entries.put("manifest.json", pretty(release.manifestJson()));
        entries.put("canonical.json", pretty(release.definitionJson()));
        for (CatalogStore.Artifact artifact : store.artifacts(releaseId)) {
            entries.put(artifact.name(), artifact.content());
        }
        JsonNode manifest = json.parse(release.manifestJson());
        StringBuilder bindings = new StringBuilder();
        bindings.append("# Bindings required for ").append(manifest.path("workflowKey").asString()).append(" release ")
                .append(release.releaseNumber()).append("\n\n")
                .append("This bundle contains definitions only. Configure these in the target workspace before ")
                .append("publishing; bindings and secrets are never exported.\n\n## Task-assignment groups\n\n");
        for (JsonNode group : manifest.path("groups")) {
            bindings.append("- `").append(group.asString()).append("`\n");
        }
        bindings.append("\n## Connections\n\n");
        for (JsonNode connection : manifest.path("connections")) {
            String key = connection.path("key").asString();
            int version = connection.path("definitionVersion").asInt();
            bindings.append("- `").append(key).append("` definition version ").append(version)
                    .append(" (binding: base URL and credential reference in the target environment)\n");
            connections.definition(workspaceId, key, version).ifPresent(d -> entries.put(
                    "connections/" + key + "-v" + version + ".json", pretty(json.write(d.definition().document()))));
        }
        if (manifest.path("workflows").size() > 0) {
            bindings.append("\n## Workflows started by this workflow\n\n");
            for (JsonNode pin : manifest.path("workflows")) {
                bindings.append("- `").append(pin.path("workflowKey").asString()).append("` (pinned to release ")
                        .append(pin.path("releaseNumber").asInt()).append(" in the source environment; publish the same")
                        .append(" workflow in the target workspace first and publish this workflow again to pin it)\n");
            }
        }
        entries.put("BINDINGS.md", bindings.toString().getBytes(StandardCharsets.UTF_8));
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "workflow.exported", "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("releaseNumber", release.releaseNumber())));
        return new Bundle(visible.workflow().key() + "-r" + release.releaseNumber() + ".zip", zip(entries));
    }

    private byte[] pretty(String jsonText) {
        return (json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(json.parse(jsonText)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Deterministic ZIP: sorted entries, fixed timestamps, no extra fields. */
    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- start form

    @Transactional(readOnly = true)
    public CatalogDtos.StartForm startForm(Caller caller, UUID workspaceId, UUID workflowId) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, "getStartForm");
        CatalogStore.WorkflowRow workflow = visible.workflow();
        if (!visible.access().canStart(workflowId)) {
            throw policy.deny(workspaceId, caller.subjectId(), "workflow.start", "getStartForm", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        if (workflow.activeReleaseId() == null) {
            throw new ApiException(ProblemCode.NO_ACTIVE_RELEASE, "This workflow has not been published yet.");
        }
        CatalogStore.ReleaseRow release = store.release(workspaceId, workflowId, workflow.activeReleaseId()).orElseThrow();
        JsonNode definition = json.parse(release.definitionJson());
        JsonNode inputUi = definition.get("inputUi");
        return new CatalogDtos.StartForm(workflowId, workflow.name(), release.id(), release.releaseNumber(),
                definition.path("inputSchema"), inputUi, visible.access().workspace().isTest());
    }

    // ---------------------------------------------------------------- views

    CatalogDtos.ReleaseView releaseView(CatalogStore.ReleaseRow release, CatalogStore.WorkflowRow workflow,
            boolean detail) {
        boolean active = release.id().equals(workflow.activeReleaseId());
        SubjectService.SubjectRef publishedBy = subjects.ref(release.publishedBy());
        if (!detail) {
            return new CatalogDtos.ReleaseView(release.id(), release.workflowId(), release.releaseNumber(),
                    release.draftRevision(), release.publishedAt(), publishedBy, active, release.compilerVersion(),
                    release.releaseNote(), null, null, null, null, null);
        }
        List<CatalogDtos.Issue> warnings = new ArrayList<>();
        for (JsonNode w : json.parse(release.warningsJson())) {
            warnings.add(new CatalogDtos.Issue(w.path("code").asString(), w.path("severity").asString(),
                    w.path("message").asString(), w.path("path").asString(), w.path("nodeId").asString(null),
                    w.path("transitionId").asString(null)));
        }
        return new CatalogDtos.ReleaseView(release.id(), release.workflowId(), release.releaseNumber(),
                release.draftRevision(), release.publishedAt(), publishedBy, active, release.compilerVersion(),
                release.releaseNote(), release.sourceHash(), release.semanticHash(), warnings,
                json.parse(release.manifestJson()), json.parse(release.definitionJson()));
    }
}
