package com.orqix.migration.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.*;
import com.orqix.integration.DocumentExtraction;
import com.orqix.integration.MicrosoftGraphDocuments;
import com.orqix.migration.blueprint.*;
import com.orqix.migration.camunda.Report;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pega Blueprint PDF import (docs/build/S91): bounded upload, page-by-page text in the isolated extraction worker, a
 * deterministic proposed transcription, human editing as immutable revisions, optional AI proposals, and the
 * conversion inputs for {@link MigrationService}. Logs and audit carry ids, counts and hashes only, never page text.
 */
@Service
public class BlueprintImports {
    public static final String FORMAT = "pegaBlueprint";
    static final long EXTRACTION_SECONDS = 60;

    public record PageView(int number, String text, String sha256, int unclassifiedLines) { }
    public record Layout(String id, String revision) { }
    public record View(UUID migrationId, String status, int revision, String transcriptionSha256, String origin,
            SubjectService.SubjectRef savedBy, Instant savedAt, Layout layout, int pageCount, boolean javascript,
            boolean embeddedFiles, List<PageView> pages, JsonNode transcription, Report.CompatibilityReport report) { }
    public record AiProposal(JsonNode transcription, Report.CompatibilityReport report, String profile, String model, int basedOnRevision) { }
    record Conversion(ObjectNode definition, Report.CompatibilityReport report, Map<String, String> sourceMap) { }

    private record Doc(int pageCount, boolean javascript, boolean embedded, String layoutId, String layoutRevision) { }
    private record Revision(int revision, JsonNode transcription, String sha256, String origin, UUID savedBy, Instant savedAt) { }
    private record Job(String status, String format, String sourceHash) { }

    private final JdbcClient jdbc; private final Json json; private final PolicyService policy; private final AuditWriter audit;
    private final DocumentExtraction extraction; private final SubjectService subjects; private final BlueprintAi ai;
    private final BlueprintLayout layout = new GenericOutlineLayout();
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public BlueprintImports(JdbcClient jdbc, Json json, PolicyService policy, AuditWriter audit, DocumentExtraction extraction,
            SubjectService subjects, BlueprintAi ai, org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.jdbc = jdbc; this.json = json; this.policy = policy; this.audit = audit; this.extraction = extraction; this.subjects = subjects; this.ai = ai;
    }

    private void authorize(Caller caller, UUID ws, boolean edit, String operation) {
        var access = policy.requireVisible(caller, ws);
        policy.require(access, edit ? Permission.WORKFLOW_EDIT : Permission.WORKFLOW_READ, operation, false);
    }

    /**
     * Upload: validates and extracts before anything is stored; a refused PDF leaves no job. Extraction runs outside any
     * database transaction (it may take up to the worker deadline); storage is one short transaction.
     */
    public UUID upload(Caller caller, UUID ws, String fileName, byte[] bytes) {
        authorize(caller, ws, true, "uploadBlueprintMigration");
        String name = fileName == null ? "" : fileName.replaceAll("[\\p{Cc}/\\\\]", "").strip();
        if (name.isEmpty() || name.length() > 200) throw ApiException.malformed("file must have a name of 1 to 200 characters.");
        if (bytes == null || bytes.length == 0) throw ApiException.malformed("file is required.");
        if (bytes.length > DocumentExtraction.BLUEPRINT_MAX_FILE_BYTES)
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The PDF is larger than 25 MiB.");
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new ApiException(ProblemCode.SOURCE_INVALID, "The file is not a PDF document (it does not start with %PDF-).");
        DocumentExtraction.PdfPages extracted;
        try {
            extracted = extraction.extractPdfPages(bytes, System.nanoTime() + TimeUnit.SECONDS.toNanos(EXTRACTION_SECONDS));
        } catch (MicrosoftGraphDocuments.Failure e) {
            throw switch (e.code()) {
                case "DOCUMENT_ENCRYPTED" -> new ApiException(ProblemCode.SOURCE_INVALID, "The PDF is encrypted or does not allow text extraction. Export the blueprint without a password or restrictions.");
                case "DOCUMENT_EXTRACTION_LIMIT_EXCEEDED" -> new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The PDF has more than " + DocumentExtraction.BLUEPRINT_MAX_PAGES + " pages or more than 1 MiB of text.");
                case "DOCUMENT_CONTENT_TOO_LARGE" -> new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The PDF is larger than 25 MiB.");
                case "DOCUMENT_EXTRACTION_TIMEOUT", "DOCUMENT_EXTRACTION_BUSY", "DOCUMENT_EXTRACTION_UNAVAILABLE", "DOCUMENT_EXTRACTION_INTERRUPTED" ->
                        new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The PDF could not be read in time. Nothing was saved; try again.");
                default -> new ApiException(ProblemCode.SOURCE_INVALID, "The PDF is damaged or not a readable PDF document.");
            };
        }
        List<BlueprintLayout.Page> pages = new ArrayList<>();
        for (int i = 0; i < extracted.pages().size(); i++) {
            String text = extracted.pages().get(i);
            pages.add(new BlueprintLayout.Page(i + 1, text, Json.sha256Hex(text)));
        }
        BlueprintLayout.Result result = layout.transcribe(pages);
        return transactions.execute(status -> store(caller, ws, name, bytes, extracted, pages, result));
    }

    private UUID store(Caller caller, UUID ws, String name, byte[] bytes, DocumentExtraction.PdfPages extracted, List<BlueprintLayout.Page> pages, BlueprintLayout.Result result) {
        authorize(caller, ws, true, "uploadBlueprintMigration");
        Transcription transcription = result.transcription();
        UUID id = Ids.newId();
        String hash = Json.sha256Hex(bytes);
        Doc doc = new Doc(pages.size(), extracted.javascript(), extracted.embeddedFiles(), layout.id(), layout.revision());
        Report.CompatibilityReport report = BlueprintFindings.report(transcription, facts(doc, pages.stream().map(p -> p.text()).toList(), result.unclassified()), hash, false, false, Map.of());
        jdbc.sql("""
            INSERT INTO migration_job(id,workspace_id,created_by,file_name,source_format,source_sha256,source,adapter_version,report,status)
            VALUES(:id,:ws,:actor,:file,'pegaBlueprint',:hash,:source,:adapter,CAST(:report AS jsonb),'analyzed')
            """).param("id", id).param("ws", ws).param("actor", caller.subjectId()).param("file", name).param("hash", hash).param("source", bytes)
            .param("adapter", report.adapterVersion()).param("report", json.write(report)).update();
        jdbc.sql("INSERT INTO migration_blueprint_document(workspace_id,migration_id,page_count,javascript,embedded_files,layout_id,layout_revision) VALUES(:ws,:id,:n,:js,:emb,:layout,:rev)")
            .param("ws", ws).param("id", id).param("n", doc.pageCount()).param("js", doc.javascript()).param("emb", doc.embedded())
            .param("layout", doc.layoutId()).param("rev", doc.layoutRevision()).update();
        for (var page : pages)
            jdbc.sql("INSERT INTO migration_blueprint_page(workspace_id,migration_id,page_number,text,text_sha256,unclassified_lines) VALUES(:ws,:id,:n,:text,:sha,:u)")
                .param("ws", ws).param("id", id).param("n", page.number()).param("text", page.text()).param("sha", page.sha256())
                .param("u", result.unclassified().get(page.number() - 1)).update();
        String sha = insertRevision(caller, ws, id, 1, transcription, "extracted");
        audit(caller, ws, id, "migration.uploaded", Map.of("migrationId", id.toString(), "sourceSystem", FORMAT, "sourceSha256", hash,
                "adapterVersion", report.adapterVersion(), "pageCount", pages.size(), "elementCount", transcription.elements().size(), "transcriptionSha256", sha));
        return id;
    }

    @Transactional(readOnly = true)
    public View view(Caller caller, UUID ws, UUID id) {
        authorize(caller, ws, false, "getBlueprintTranscription");
        Job job = job(ws, id, false);
        Doc doc = doc(ws, id);
        Revision current = latest(id);
        List<PageView> pages = jdbc.sql("SELECT page_number,text,text_sha256,unclassified_lines FROM migration_blueprint_page WHERE workspace_id=:ws AND migration_id=:id ORDER BY page_number")
            .param("ws", ws).param("id", id).query((rs, n) -> new PageView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4))).list();
        Report.CompatibilityReport report = jdbc.sql("SELECT report::text FROM migration_job WHERE workspace_id=:ws AND id=:id").param("ws", ws).param("id", id)
            .query((rs, n) -> json.convert(json.parse(rs.getString(1)), Report.CompatibilityReport.class)).single();
        return new View(id, job.status(), current.revision(), current.sha256(), current.origin(),
            subjects.find(current.savedBy()).map(s -> new SubjectService.SubjectRef(current.savedBy(), s.displayName())).orElse(null), current.savedAt(),
            new Layout(doc.layoutId(), doc.layoutRevision()), doc.pageCount(), doc.javascript(), doc.embedded(), pages, current.transcription(), report);
    }

    /**
     * Saves an edited transcription as the next immutable revision. An element whose content differs from what the
     * layout extracted, or that the layout did not extract, cannot stay "mapped": it becomes "manuallyResolved".
     */
    @Transactional
    public View save(Caller caller, UUID ws, UUID id, int expectedRevision, JsonNode submitted) {
        authorize(caller, ws, true, "saveBlueprintTranscription");
        Job job = job(ws, id, true);
        if (!"analyzed".equals(job.status())) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "This blueprint was already converted; its transcription can no longer change.");
        Revision current = latest(id);
        if (current.revision() != expectedRevision) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The transcription changed since you loaded it. Reload it before saving.");
        Transcription edited = Transcription.parse(submitted);
        Map<String, JsonNode> original = elementNodes(revision(id, 1).transcription());
        Map<String, JsonNode> now = elementNodes(edited.toJson(json));
        Transcription normalized = edited.withDispositions(e -> Transcription.MAPPED.equals(e.meta().disposition())
                && !sameContent(original.get(e.id()), now.get(e.id())) ? Transcription.MANUAL : e.meta().disposition());
        String sha = insertRevision(caller, ws, id, current.revision() + 1, normalized, "edited");
        Report.CompatibilityReport report = BlueprintFindings.report(normalized, facts(ws, id), job.sourceHash(), false, false, Map.of());
        jdbc.sql("UPDATE migration_job SET report=CAST(:report AS jsonb),updated_at=now() WHERE workspace_id=:ws AND id=:id")
            .param("report", json.write(report)).param("ws", ws).param("id", id).update();
        audit(caller, ws, id, "migration.blueprint.saved", Map.of("migrationId", id.toString(), "revision", current.revision() + 1,
                "transcriptionSha256", sha, "elementCount", normalized.elements().size(),
                "openFindings", report.issues().stream().filter(Report.Issue::blocksPublishing).count()));
        return view(caller, ws, id);
    }

    /**
     * An AI-structured proposal of the stored page texts; returned for review, never stored or applied. Deliberately not
     * one transaction: no database connection is held while the model answers (each read and the audit commit alone).
     */
    public AiProposal aiProposal(Caller caller, UUID ws, UUID id, String profile) {
        authorize(caller, ws, true, "proposeBlueprintTranscription");
        Job job = job(ws, id, false);
        List<BlueprintLayout.Page> pages = jdbc.sql("SELECT page_number,text,text_sha256 FROM migration_blueprint_page WHERE workspace_id=:ws AND migration_id=:id ORDER BY page_number")
            .param("ws", ws).param("id", id).query((rs, n) -> new BlueprintLayout.Page(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
        BlueprintAi.Proposal proposal = ai.propose(caller, ws, pages, profile);
        Report.CompatibilityReport report = BlueprintFindings.report(proposal.transcription(), facts(ws, id), job.sourceHash(), false, false, Map.of());
        audit(caller, ws, id, "migration.blueprint.ai.proposed", Map.of("migrationId", id.toString(), "profile", proposal.profile(), "model", proposal.model(),
                "elementCount", proposal.transcription().elements().size()));
        return new AiProposal(proposal.transcription().toJson(json), report, proposal.profile(), proposal.model(), latest(id).revision());
    }

    /** Conversion inputs: the request must name the reviewed transcription's hash; the transcription must have no open finding. */
    Conversion prepare(UUID ws, UUID id, String sourceHash, JsonNode request) {
        for (String member : List.of("approvalRoutes", "inputSchema", "serviceTaskMappings", "serviceGroupMappings", "childWorkflowMappings", "decisionMappings", "formMappings", "decisionId"))
            if (request.has(member)) throw ApiException.malformed(member + " is not used for a blueprint; routes, request fields and steps come from the reviewed transcription.");
        Revision current = latest(id);
        String supplied = request.path("transcriptionSha256").asString("");
        if (supplied.isEmpty()) throw ApiException.malformed("transcriptionSha256 is required: name the reviewed transcription revision to convert.");
        if (!supplied.equals(current.sha256())) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The transcription changed after it was reviewed. Reload and review it again.");
        Transcription t = Transcription.parse(current.transcription());
        boolean ackInfo = request.path("acknowledgeInfo").asBoolean(false), ackReview = request.path("acknowledgeReview").asBoolean(false);
        var facts = facts(ws, id);
        Report.CompatibilityReport checked = BlueprintFindings.report(t, facts, sourceHash, ackInfo, ackReview, Map.of());
        if (!checked.publishable()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED, "Resolve every transcription finding and acknowledge exclusions before converting. No workflow was created.")
            .with("openItems", checked.issues().stream().filter(i -> Report.OPEN.equals(i.resolutionStatus()) && !Report.REVIEW.equals(i.severity())).toList());
        var converted = BlueprintConverter.convert(t, request, json);
        if (!converted.converterReport().publishable()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED, "Map every role to a workspace group before converting. No workflow was created.")
            .with("openItems", converted.converterReport().issues().stream().filter(i -> Report.OPEN.equals(i.resolutionStatus())).toList());
        return new Conversion(converted.definition(), BlueprintFindings.report(t, facts, sourceHash, ackInfo, ackReview, converted.sourceMap()), converted.sourceMap());
    }

    // ------------------------------------------------------------------------------------------------ storage

    private String insertRevision(Caller caller, UUID ws, UUID id, int revision, Transcription t, String origin) {
        ObjectNode node = t.toJson(json);
        String sha = Json.canonicalSha256(json.write(node));
        jdbc.sql("INSERT INTO migration_blueprint_transcription(workspace_id,migration_id,revision,transcription,transcription_sha256,origin,saved_by) VALUES(:ws,:id,:rev,CAST(:t AS jsonb),:sha,:origin,:by)")
            .param("ws", ws).param("id", id).param("rev", revision).param("t", json.write(node)).param("sha", sha).param("origin", origin).param("by", caller.subjectId()).update();
        return sha;
    }
    private Job job(UUID ws, UUID id, boolean lock) {
        Job job = jdbc.sql("SELECT status,source_format,source_sha256 FROM migration_job WHERE workspace_id=:ws AND id=:id" + (lock ? " FOR UPDATE" : ""))
            .param("ws", ws).param("id", id).query((rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getString(3))).optional().orElseThrow(ApiException::notFound);
        if (!FORMAT.equals(job.format())) throw ApiException.notFound();
        return job;
    }
    private Doc doc(UUID ws, UUID id) {
        return jdbc.sql("SELECT page_count,javascript,embedded_files,layout_id,layout_revision FROM migration_blueprint_document WHERE workspace_id=:ws AND migration_id=:id")
            .param("ws", ws).param("id", id).query((rs, n) -> new Doc(rs.getInt(1), rs.getBoolean(2), rs.getBoolean(3), rs.getString(4), rs.getString(5))).optional().orElseThrow(ApiException::notFound);
    }
    private Revision latest(UUID id) { return revision(id, null); }
    private Revision revision(UUID id, Integer number) {
        return jdbc.sql("SELECT revision,transcription::text,transcription_sha256,origin,saved_by,saved_at FROM migration_blueprint_transcription WHERE migration_id=:id"
                + (number == null ? " ORDER BY revision DESC LIMIT 1" : " AND revision=:rev"))
            .param("id", id).params(number == null ? Map.of() : Map.of("rev", number))
            .query((rs, n) -> new Revision(rs.getInt(1), json.parse(rs.getString(2)), rs.getString(3), rs.getString(4), Sql.uuid(rs, "saved_by"), Sql.instant(rs, "saved_at")))
            .optional().orElseThrow(ApiException::notFound);
    }
    private BlueprintFindings.DocumentFacts facts(UUID ws, UUID id) {
        Doc doc = doc(ws, id);
        List<Integer> unclassified = new ArrayList<>(); List<Boolean> empty = new ArrayList<>();
        jdbc.sql("SELECT unclassified_lines,text FROM migration_blueprint_page WHERE workspace_id=:ws AND migration_id=:id ORDER BY page_number").param("ws", ws).param("id", id)
            .query((rs, n) -> { unclassified.add(rs.getInt(1)); empty.add(rs.getString(2).isBlank()); return n; }).list();
        return new BlueprintFindings.DocumentFacts(doc.pageCount(), unclassified, empty, doc.javascript(), doc.embedded(), doc.layoutId(), doc.layoutRevision());
    }
    private static BlueprintFindings.DocumentFacts facts(Doc doc, List<String> texts, List<Integer> unclassified) {
        return new BlueprintFindings.DocumentFacts(doc.pageCount(), unclassified, texts.stream().map(String::isBlank).toList(), doc.javascript(), doc.embedded(), doc.layoutId(), doc.layoutRevision());
    }
    /** Element id to its JSON, from a stored or serialized transcription. */
    private static Map<String, JsonNode> elementNodes(JsonNode root) {
        Map<String, JsonNode> nodes = new HashMap<>();
        if (root.path("caseType").isObject()) nodes.put("caseType", root.get("caseType"));
        for (String list : List.of("stages", "roles", "fields", "steps", "decisions", "slas", "outcomes", "integrations"))
            for (JsonNode item : root.path(list)) nodes.put(item.path("id").asString(), item);
        return nodes;
    }
    private static boolean sameContent(JsonNode before, JsonNode after) {
        if (before == null || after == null) return false;
        ObjectNode a = ((ObjectNode) before).deepCopy(), b = ((ObjectNode) after).deepCopy();
        for (ObjectNode o : List.of(a, b)) { o.remove("disposition"); o.remove("dispositionReason"); }
        return a.equals(b);
    }
    private void audit(Caller caller, UUID ws, UUID id, String action, Map<String, Object> metadata) {
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), action, "migration", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }
}
