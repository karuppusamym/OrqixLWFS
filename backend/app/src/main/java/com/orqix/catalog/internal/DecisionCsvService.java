package com.orqix.catalog.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.api.CatalogDtos;
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
 * CSV export and import of one decision table of a workflow draft (G3, docs/build/S89-decision-csv-io.md).
 *
 * <p>Permissions follow the other draft operations: export and template need {@code workflow.read}; import is a draft
 * edit ({@code workflow.edit}) with If-Match, and saves a normal draft revision (audit {@code workflow.draft.saved},
 * source {@code decisionCsv}). Import never publishes. A file is applied only when the compiler finds no blocker in the
 * resulting table: the very same decision validation as publish and DMN import (hit-policy overlap, field types,
 * output shape, literal safety) is run on the draft with the table replaced.
 */
@Service
public class DecisionCsvService {

    private final WorkflowService workflows;
    private final CatalogStore store;
    private final PolicyService policy;
    private final WorkflowCompiler compiler;
    private final CompileContextFactory contexts;
    private final Json json;

    public DecisionCsvService(WorkflowService workflows, CatalogStore store, PolicyService policy,
            WorkflowCompiler compiler, CompileContextFactory contexts, Json json) {
        this.workflows = workflows;
        this.store = store;
        this.policy = policy;
        this.compiler = compiler;
        this.contexts = contexts;
        this.json = json;
    }

    /** A CSV file to download. */
    public record CsvFile(String filename, String text) {
    }

    @Transactional(readOnly = true)
    public CsvFile export(Caller caller, UUID workspaceId, UUID workflowId, String decisionKey, Integer revision) {
        return render(caller, workspaceId, workflowId, decisionKey, revision, false, "exportDecisionCsv");
    }

    @Transactional(readOnly = true)
    public CsvFile template(Caller caller, UUID workspaceId, UUID workflowId, String decisionKey) {
        return render(caller, workspaceId, workflowId, decisionKey, null, true, "getDecisionCsvTemplate");
    }

    private CsvFile render(Caller caller, UUID workspaceId, UUID workflowId, String decisionKey, Integer revision,
            boolean template, String operationId) {
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, operationId);
        policy.require(visible.access(), Permission.WORKFLOW_READ, operationId, false);
        int rev = revision == null ? visible.workflow().draftRevision() : revision;
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, rev).orElseThrow(ApiException::notFound);
        JsonNode table = find(json.parse(draft.definitionJson()), decisionKey);
        if (table == null) {
            throw ApiException.notFound();
        }
        try {
            String text = template ? DecisionCsv.template(table) : DecisionCsv.export(table);
            return new CsvFile(visible.workflow().key() + "-" + decisionKey + (template ? "-template" : "") + ".csv", text);
        } catch (DecisionCsv.NotRepresentable e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This decision table cannot be written as CSV: "
                    + e.reasons().get(0))
                    .withFieldErrors(e.reasons().stream().limit(DecisionCsv.MAX_PROBLEMS)
                            .map(r -> new ApiException.FieldError("/decisions/" + decisionKey, "notRepresentable", r))
                            .toList());
        }
    }

    /**
     * Replaces the rows, hit policy and default of a decision table of the latest draft with the content of a CSV file.
     *
     * @param body the raw request body; read (bounded) only after the caller is authorized
     */
    @Transactional
    public CatalogDtos.WorkflowDraft importCsv(Caller caller, UUID workspaceId, UUID workflowId, String decisionKey,
            String ifMatch, String contentType, InputStream body) {
        String operationId = "importDecisionCsv";
        WorkflowService.Visible visible = workflows.visible(caller, workspaceId, workflowId, operationId);
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
        CatalogStore.DraftRow draft = store.draft(workspaceId, workflowId, current).orElseThrow(ApiException::notFound);
        ObjectNode definition = (ObjectNode) json.parse(draft.definitionJson());
        int index = indexOf(definition, decisionKey);
        if (index < 0) {
            throw ApiException.notFound();
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        if (!type.equals("text/csv")) {
            throw ApiException.malformed("Send the file as Content-Type: text/csv; charset=UTF-8.");
        }
        byte[] bytes = read(body);

        DecisionCsv.Parsed parsed = DecisionCsv.parse(bytes);
        if (!parsed.ok()) {
            throw refused(parsed.problems(), "The CSV file cannot be imported: " + describe(parsed.problems().get(0)));
        }
        // Same table identity, new content.
        ObjectNode old = (ObjectNode) definition.path("decisions").get(index);
        ObjectNode table = json.object();
        table.set("key", old.get("key"));
        table.set("version", old.get("version"));
        table.set("hitPolicy", parsed.table().get("hitPolicy"));
        table.set("rows", parsed.table().get("rows"));
        table.set("default", parsed.table().get("default"));
        ((ArrayNode) definition.get("decisions")).set(index, table);

        // The compiler's own validation of the draft with the table replaced: nothing weaker than publish.
        String candidate = json.write(definition);
        ValidationReport report = compiler.validate(candidate, contexts.create(visible.access().workspace(), candidate));
        String prefix = "/decisions/" + index;
        List<DecisionCsv.Problem> problems = new ArrayList<>();
        boolean elsewhere = false;
        for (ValidationIssue issue : report.issues()) {
            if (issue.severity() != Severity.BLOCKER) {
                continue;
            }
            String path = issue.path() == null ? "" : issue.path();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                int[] at = parsed.locator().locate(path.substring(prefix.length()));
                problems.add(new DecisionCsv.Problem(at[0], at[1], issue.code(), issue.message()));
            } else if (issue.code().equals("WF_SCHEMA_INVALID") || issue.code().startsWith("WF_JSON")
                    || issue.code().equals("WF_PAYLOAD_TOO_LARGE")) {
                elsewhere = true;
            }
        }
        if (problems.isEmpty() && elsewhere) {
            // The compiler stops at the document structure, so the table could not be checked completely.
            problems.add(new DecisionCsv.Problem(1, 1, "WF_SCHEMA_INVALID", "The draft has structural errors outside this"
                    + " table, so the table cannot be checked completely. Run Check workflow, fix them, then import again."));
        }
        if (!problems.isEmpty()) {
            problems.sort(java.util.Comparator.comparingInt(DecisionCsv.Problem::line)
                    .thenComparingInt(DecisionCsv.Problem::column));
            throw refused(problems, "The CSV file cannot be imported: " + describe(problems.get(0)));
        }
        return workflows.saveDraft(caller, workspaceId, workflowId, ifMatch,
                new CatalogDtos.SaveDraftRequest(json.parse(candidate)), operationId, "decisionCsv");
    }

    private static ApiException refused(List<DecisionCsv.Problem> problems, String detail) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, detail).withFieldErrors(problems.stream()
                .limit(DecisionCsv.MAX_PROBLEMS)
                .map(p -> new ApiException.FieldError("/csv/lines/" + p.line() + "/columns/" + p.column(), p.code(),
                        p.message()))
                .toList());
    }

    private static String describe(DecisionCsv.Problem p) {
        return "line " + p.line() + ", column " + p.column() + ": " + p.message();
    }

    private static byte[] read(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(DecisionCsv.MAX_BYTES + 1);
            if (bytes.length > DecisionCsv.MAX_BYTES) {
                throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,
                        "The file is larger than " + DecisionCsv.MAX_BYTES / 1024 + " KiB.");
            }
            return bytes;
        } catch (IOException e) {
            throw ApiException.malformed("The file could not be read.");
        }
    }

    private static JsonNode find(JsonNode definition, String decisionKey) {
        for (JsonNode table : definition.path("decisions")) {
            if (decisionKey.equals(table.path("key").asString(null))) {
                return table;
            }
        }
        return null;
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
}
