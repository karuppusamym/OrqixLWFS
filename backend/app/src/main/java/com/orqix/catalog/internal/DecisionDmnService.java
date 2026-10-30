package com.orqix.catalog.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.DecisionTables;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Standard DMN download and upload of decision tables (docs/build/S121).
 * <p>
 * Download writes the portable DMN 1.3 form ({@link DecisionTables#exportDmn}) of a draft table ({@code workflow.read}),
 * of a published release's table ({@code workflow.export}, like the release BPMN) or of a shared decision version
 * ({@code workflow.read}); draft and release downloads are audited as {@code workflow.exported} with ids only.
 * <p>
 * Upload reads one decision table ({@link DecisionDmn}). Into a draft it is a draft edit ({@code workflow.edit},
 * If-Match) that replaces hit policy, rows and default of one table and is saved only when the compiler finds no
 * blocker in the table: the same validation as publish and CSV import. For a shared decision the file is converted
 * and returned for review; the caller creates the decision or its next version with the existing endpoints.
 */
@Service
public class DecisionDmnService {

    private final WorkflowService workflows;
    private final CatalogStore store;
    private final PolicyService policy;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final SharedAssets assets;
    private final AuditWriter audit;
    private final Json json;

    public DecisionDmnService(WorkflowService workflows, CatalogStore store, PolicyService policy, WorkflowCompiler compiler,
            CompileContextFactory contexts, SharedAssets assets, AuditWriter audit, Json json) {
        this.workflows = workflows;
        this.store = store;
        this.policy = policy;
        this.compiler = compiler;
        this.contexts = contexts;
        this.assets = assets;
        this.audit = audit;
        this.json = json;
    }

    /** A DMN file to download. */
    public record DmnFile(String filename, byte[] content) {
    }

    /** A converted file: the decisions it holds, the one read (null when a choice is needed) and its table. */
    public record Converted(List<DecisionDmn.Choice> decisions, String decisionId, String name, JsonNode content,
            List<String> notes) {
    }

    // ---------------------------------------------------------------- download

    @Transactional
    public DmnFile exportDraft(Caller caller, UUID ws, UUID workflowId, String decisionKey, Integer revision) {
        String operationId = "exportDecisionDmn";
        WorkflowService.Visible visible = workflows.visible(caller, ws, workflowId, operationId);
        policy.require(visible.access(), Permission.WORKFLOW_READ, operationId, false);
        int rev = revision == null ? visible.workflow().draftRevision() : revision;
        CatalogStore.DraftRow draft = store.draft(ws, workflowId, rev).orElseThrow(ApiException::notFound);
        JsonNode definition = json.parse(draft.definitionJson());
        JsonNode table = table(definition, decisionKey);
        byte[] file = render(table, decisionKey, stepLabel(definition, decisionKey));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", "dmn");
        metadata.put("revision", rev);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.exported",
                "workflow", workflowId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new DmnFile(BpmnSourceService.safeName(visible.workflow().key() + "-" + decisionKey) + "-draft-" + rev
                + ".dmn", file);
    }

    @Transactional
    public DmnFile exportRelease(Caller caller, UUID ws, UUID workflowId, UUID releaseId, String decisionKey) {
        String operationId = "exportReleaseDecisionDmn";
        WorkflowService.Visible visible = workflows.visible(caller, ws, workflowId, operationId);
        // Permission first, then the release: a caller without workflow.export learns nothing about which releases exist.
        policy.require(visible.access(), Permission.WORKFLOW_EXPORT, operationId, true);
        CatalogStore.ReleaseRow release = store.release(ws, workflowId, releaseId).orElseThrow(ApiException::notFound);
        JsonNode definition = json.parse(release.definitionJson());
        JsonNode table = table(definition, decisionKey);
        byte[] file = render(table, decisionKey, stepLabel(definition, decisionKey));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", "dmn");
        metadata.put("releaseNumber", release.releaseNumber());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.exported",
                "release", releaseId.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return new DmnFile(BpmnSourceService.safeName(visible.workflow().key() + "-" + decisionKey) + "-r"
                + release.releaseNumber() + ".dmn", file);
    }

    @Transactional(readOnly = true)
    public DmnFile exportShared(Caller caller, UUID ws, String assetKey, int version) {
        SharedAssets.Detail detail = assets.get(caller, ws, SharedAssets.DECISION, assetKey);
        SharedAssets.Version v = detail.versions().stream().filter(x -> x.version() == version).findFirst()
                .orElseThrow(ApiException::notFound);
        byte[] file = render(v.content(), assetKey, detail.asset().name());
        return new DmnFile(BpmnSourceService.safeName(assetKey) + "-v" + version + ".dmn", file);
    }

    private byte[] render(JsonNode table, String key, String name) {
        try {
            return DecisionTables.exportDmn(json.write(table), key, name).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This decision table cannot be written as DMN: "
                    + e.getMessage() + ". Fix the table, then download it again.");
        }
    }

    // ---------------------------------------------------------------- upload

    /**
     * Replaces hit policy, aggregation, priority, rows and default of a decision table of the latest draft with the
     * decision table of a DMN file. The key and version of the table stay.
     *
     * @param body the raw request body; read (bounded) only after the caller is authorized
     */
    @Transactional
    public CatalogDtos.WorkflowDraft importIntoDraft(Caller caller, UUID ws, UUID workflowId, String decisionKey,
            String decisionId, String ifMatch, String contentType, InputStream body) {
        String operationId = "importDecisionDmn";
        WorkflowService.Visible visible = workflows.visible(caller, ws, workflowId, operationId);
        policy.require(visible.access(), Permission.WORKFLOW_EDIT, operationId, false);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ProblemCode.PRECONDITION_REQUIRED);
        }
        int current = visible.workflow().draftRevision();
        Integer expected = WorkflowService.parseEtag(ifMatch);
        if (expected == null || expected != current) {
            throw new ApiException(ProblemCode.STALE_REVISION, "The draft was saved by someone else.")
                    .with("currentRevision", current);
        }
        CatalogStore.DraftRow draft = store.draft(ws, workflowId, current).orElseThrow(ApiException::notFound);
        ObjectNode definition = (ObjectNode) json.parse(draft.definitionJson());
        int index = indexOf(definition, decisionKey);
        if (index < 0) {
            throw ApiException.notFound();
        }
        DecisionDmn.Result read = read(contentType, body, decisionId);
        if (read.table() == null) {
            throw choose(read.decisions());
        }
        ObjectNode old = (ObjectNode) definition.path("decisions").get(index);
        ObjectNode table = json.object();
        table.set("key", old.get("key"));
        table.set("version", old.get("version"));
        read.table().properties().forEach(e -> table.set(e.getKey(), e.getValue()));
        ((ArrayNode) definition.get("decisions")).set(index, table);

        // The compiler's own validation of the draft with the table replaced: nothing weaker than publish.
        String candidate = json.write(definition);
        ValidationReport report = compiler.validate(candidate, contexts.create(visible.access().workspace(), candidate));
        String prefix = "/decisions/" + index;
        List<ApiException.FieldError> problems = new ArrayList<>();
        boolean elsewhere = false;
        for (ValidationIssue issue : report.issues()) {
            if (issue.severity() != Severity.BLOCKER) {
                continue;
            }
            String path = issue.path() == null ? "" : issue.path();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                problems.add(new ApiException.FieldError("/dmn" + path.substring(prefix.length()), issue.code(),
                        rulePlace(path.substring(prefix.length())) + issue.message()));
            } else if (issue.code().equals("WF_SCHEMA_INVALID") || issue.code().startsWith("WF_JSON")
                    || issue.code().equals("WF_PAYLOAD_TOO_LARGE")) {
                elsewhere = true;
            }
        }
        if (problems.isEmpty() && elsewhere) {
            problems.add(new ApiException.FieldError("/dmn", "WF_SCHEMA_INVALID", "The draft has structural errors outside"
                    + " this table, so the table cannot be checked completely. Run Check workflow, fix them, then import again."));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The DMN file cannot be imported: "
                    + problems.get(0).message()).withFieldErrors(problems.stream().limit(DecisionDmn.MAX_PROBLEMS).toList());
        }
        return workflows.saveDraft(caller, ws, workflowId, ifMatch, new CatalogDtos.SaveDraftRequest(json.parse(candidate)),
                operationId, "decisionDmn");
    }

    /**
     * Converts the decision table of a DMN file into shared decision content for review ({@code workflow.edit}, like
     * creating a shared decision). Nothing is stored.
     */
    @Transactional(readOnly = true)
    public Converted convertForShared(Caller caller, UUID ws, String decisionId, String contentType, InputStream body) {
        String operationId = "convertDecisionDmn";
        policy.require(policy.requireVisible(caller, ws), Permission.WORKFLOW_EDIT, operationId, false);
        DecisionDmn.Result read = read(contentType, body, decisionId);
        JsonNode content = read.table() == null ? null : assets.decisionContent(read.table());
        return new Converted(read.decisions(), read.decisionId(), read.name(), content, read.notes());
    }

    private DecisionDmn.Result read(String contentType, InputStream body, String decisionId) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        if (!type.equals("application/xml") && !type.equals("text/xml")) {
            throw ApiException.malformed("Send the file as Content-Type: application/xml.");
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes(DecisionDmn.MAX_BYTES + 1);
        } catch (IOException e) {
            throw ApiException.malformed("The file could not be read.");
        }
        if (bytes.length > DecisionDmn.MAX_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The file is larger than " + DecisionDmn.MAX_BYTES / 1024
                    + " KiB.");
        }
        try {
            return DecisionDmn.read(new String(bytes, StandardCharsets.UTF_8), decisionId);
        } catch (DecisionDmn.Invalid e) {
            ApiException problem = new ApiException(ProblemCode.VALIDATION_FAILED, "The DMN file cannot be imported: "
                    + e.problems().get(0).where() + ": " + e.problems().get(0).message())
                    .withFieldErrors(e.problems().stream().map(p -> new ApiException.FieldError("/dmn", "DMN_NOT_IMPORTED",
                            p.where() + ": " + p.message())).toList());
            return throwWithDecisions(problem, e.decisions());
        }
    }

    private static DecisionDmn.Result throwWithDecisions(ApiException problem, List<DecisionDmn.Choice> decisions) {
        if (!decisions.isEmpty()) {
            problem.with("decisions", decisions.stream().map(d -> Map.of("id", d.id(), "name", d.name(), "table", d.table()))
                    .toList());
        }
        throw problem;
    }

    private static ApiException choose(List<DecisionDmn.Choice> decisions) {
        ApiException problem = new ApiException(ProblemCode.VALIDATION_FAILED, "The file has " + decisions.size()
                + " decisions. Choose the one to import with decisionId.");
        problem.with("decisions", decisions.stream().map(d -> Map.of("id", d.id(), "name", d.name(), "table", d.table()))
                .toList());
        return problem;
    }

    /** "Rule 3: " for a compiler path inside a row (rows are the file's rules in order). */
    private static String rulePlace(String path) {
        if (path.startsWith("/rows/")) {
            String[] parts = path.split("/");
            try {
                return "Rule " + (Integer.parseInt(parts[2]) + 1) + ": ";
            } catch (RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    private static JsonNode table(JsonNode definition, String decisionKey) {
        for (JsonNode table : definition.path("decisions")) {
            if (decisionKey.equals(table.path("key").asString(null))) {
                return table;
            }
        }
        throw ApiException.notFound();
    }

    private static int indexOf(JsonNode definition, String decisionKey) {
        JsonNode tables = definition.path("decisions");
        for (int i = 0; i < tables.size(); i++) {
            if (decisionKey.equals(tables.get(i).path("key").asString(null))) {
                return i;
            }
        }
        return -1;
    }

    /** The label of the first decision step that uses the table, as the decision name; null when none does. */
    private static String stepLabel(JsonNode definition, String decisionKey) {
        for (JsonNode node : definition.path("nodes")) {
            if ("decision".equals(node.path("type").asString(null))
                    && decisionKey.equals(node.path("config").path("decisionRef").asString(null))) {
                return node.path("label").asString(null);
            }
        }
        return null;
    }
}
