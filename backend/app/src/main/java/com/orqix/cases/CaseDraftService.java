package com.orqix.cases;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ApplicationViewQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;

/**
 * S126: the working copy of a case type (docs/26 finding C3). A draft holds what {@link CaseService#createDefinition}
 * needs, saved as often as the designer wants and in any state. {@link #check} runs every admission rule without
 * deploying; {@link #publish} checks again and deploys the next immutable version. One draft per case key; it stays
 * after publishing as the working copy of the following version.
 */
@Service
public class CaseDraftService {
    private static final int MAX_CONTENT_BYTES = 262_144;

    public record DraftInput(String key, String name, String sourceXml, JsonNode designerModel, JsonNode dataFields,
            ApplicationViewQueries.Pin viewRef, List<CaseService.WorkflowTaskMapping> workflowTasks,
            List<CaseService.WorkflowTaskMapping> caseTasks, List<CaseService.WorkflowTaskMapping> decisionTasks) {
        @Override public String toString() { return "Case type draft " + key; }
    }
    public record SaveDraft(Integer expectedVersion, String name, String sourceXml, JsonNode designerModel, JsonNode dataFields,
            ApplicationViewQueries.Pin viewRef, List<CaseService.WorkflowTaskMapping> workflowTasks,
            List<CaseService.WorkflowTaskMapping> caseTasks, List<CaseService.WorkflowTaskMapping> decisionTasks) {
        @Override public String toString() { return "Case type draft save " + expectedVersion; }
    }
    public record Draft(UUID id, String key, String name, int version, String sourceXml, JsonNode designerModel,
            JsonNode dataFields, ApplicationViewQueries.Pin viewRef, List<CaseService.WorkflowTaskMapping> workflowTasks,
            List<CaseService.WorkflowTaskMapping> caseTasks, List<CaseService.WorkflowTaskMapping> decisionTasks,
            Instant updatedAt, Integer publishedVersion, Integer publishedDraftVersion) {}
    public record DraftSummary(UUID id, String key, String name, int version, Instant updatedAt, Integer publishedVersion,
            Integer publishedDraftVersion) {}
    public record Check(int draftVersion, boolean valid, List<CaseService.CheckIssue> issues) {}
    public record Publish(Integer expectedVersion) {}

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final CaseService cases;
    private final Json json;
    private final AuditWriter audit;

    public CaseDraftService(JdbcClient jdbc, PolicyService policy, CaseService cases, Json json, AuditWriter audit) {
        this.jdbc = jdbc; this.policy = policy; this.cases = cases; this.json = json; this.audit = audit;
    }

    private void requireEdit(Caller caller, UUID ws, String operation) {
        policy.require(policy.requireMember(caller, ws), Permission.WORKFLOW_EDIT, operation, true);
    }

    @Transactional(readOnly = true)
    public List<DraftSummary> list(Caller caller, UUID ws) {
        requireEdit(caller, ws, "listCaseDefinitionDrafts");
        return jdbc.sql("""
                SELECT d.id,d.key,d.name,d.version,d.updated_at,NULLIF(c.current_version,0) AS published_version,d.published_draft_version
                FROM case_definition_draft d LEFT JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.key=d.key
                WHERE d.workspace_id=:ws ORDER BY d.updated_at DESC,d.id DESC LIMIT 200
                """).param("ws", ws).query((rs, n) -> new DraftSummary(Sql.uuid(rs, "id"), rs.getString("key"), rs.getString("name"),
                        rs.getInt("version"), rs.getTimestamp("updated_at").toInstant(), (Integer) rs.getObject("published_version"),
                        (Integer) rs.getObject("published_draft_version"))).list();
    }

    @Transactional(readOnly = true)
    public Draft get(Caller caller, UUID ws, UUID id) {
        requireEdit(caller, ws, "getCaseDefinitionDraft");
        return draft(ws, id, false);
    }

    @Transactional
    public Draft create(Caller caller, UUID ws, DraftInput request) {
        requireEdit(caller, ws, "createCaseDefinitionDraft");
        if (request == null || request.key() == null || !request.key().matches("[a-z][a-z0-9-]{1,62}"))
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the case type a key of 2 to 63 lowercase letters, digits or hyphens, starting with a letter.");
        String name = name(request.name());
        String content = content(request.designerModel(), request.dataFields(), request.viewRef(), request.workflowTasks(), request.caseTasks(), request.decisionTasks());
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO case_definition_draft(id,workspace_id,key,name,source_xml,content,created_by,updated_by)
                VALUES(:id,:ws,:key,:name,:xml,CAST(:content AS jsonb),:actor,:actor) ON CONFLICT (workspace_id,key) DO NOTHING
                """).param("id", id).param("ws", ws).param("key", request.key()).param("name", name)
                .param("xml", source(request.sourceXml())).param("content", content).param("actor", caller.subjectId()).update();
        if (inserted == 0) throw new ApiException(ProblemCode.DUPLICATE_KEY, "Case type " + request.key() + " already has a draft. Open that draft instead.");
        saved(caller, ws, id, request.key(), 1);
        return draft(ws, id, false);
    }

    @Transactional
    public Draft save(Caller caller, UUID ws, UUID id, SaveDraft request) {
        requireEdit(caller, ws, "saveCaseDefinitionDraft");
        if (request == null || request.expectedVersion() == null)
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send the draft version you edited as expectedVersion.");
        var current = draft(ws, id, true);
        if (current.version() != request.expectedVersion()) throw changed();
        String content = content(request.designerModel(), request.dataFields(), request.viewRef(), request.workflowTasks(), request.caseTasks(), request.decisionTasks());
        jdbc.sql("""
                UPDATE case_definition_draft SET name=:name,source_xml=:xml,content=CAST(:content AS jsonb),version=version+1,
                    updated_by=:actor,updated_at=now() WHERE workspace_id=:ws AND id=:id
                """).param("name", name(request.name())).param("xml", source(request.sourceXml())).param("content", content)
                .param("actor", caller.subjectId()).param("ws", ws).param("id", id).update();
        saved(caller, ws, id, current.key(), current.version() + 1);
        return draft(ws, id, false);
    }

    @Transactional
    public void delete(Caller caller, UUID ws, UUID id) {
        requireEdit(caller, ws, "deleteCaseDefinitionDraft");
        var current = draft(ws, id, true);
        jdbc.sql("DELETE FROM case_definition_draft WHERE workspace_id=:ws AND id=:id").param("ws", ws).param("id", id).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "case.draft.deleted",
                "caseDefinitionDraft", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("key", current.key())));
    }

    /** Not transactional on purpose; see {@link CaseService#checkDefinition}. Nothing is written. */
    public Check check(Caller caller, UUID ws, UUID id) {
        requireEdit(caller, ws, "checkCaseDefinitionDraft");
        var current = draft(ws, id, false);
        var issues = issues(ws, current);
        return new Check(current.version(), issues.isEmpty(), issues);
    }

    @Transactional
    public CaseService.Definition publish(Caller caller, UUID ws, UUID id, Publish request) {
        requireEdit(caller, ws, "publishCaseDefinitionDraft");
        if (request == null || request.expectedVersion() == null)
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send the draft version you checked as expectedVersion.");
        var current = draft(ws, id, true);
        if (current.version() != request.expectedVersion()) throw changed();
        var issues = issues(ws, current);
        if (!issues.isEmpty())
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The check found " + issues.size() + (issues.size() == 1 ? " problem" : " problems")
                    + ". Fix " + (issues.size() == 1 ? "it" : "them") + " before publishing. First: " + issues.getFirst().message());
        var definition = cases.createDefinition(caller, ws, create(current));
        jdbc.sql("UPDATE case_definition_draft SET published_definition_id=:definition,published_draft_version=version WHERE workspace_id=:ws AND id=:id")
                .param("definition", definition.id()).param("ws", ws).param("id", id).update();
        return definition;
    }

    private List<CaseService.CheckIssue> issues(UUID ws, Draft current) {
        String problem = null;
        List<CaseDataModel.Field> fields = null;
        try { fields = fields(current.dataFields()); }
        catch (RuntimeException ex) { problem = "Case data fields need unique keys, labels and supported types."; }
        var request = new CaseService.CreateDefinition(current.key(), current.name(), current.sourceXml(), fields,
                current.viewRef(), current.workflowTasks(), current.caseTasks(), current.decisionTasks());
        if (problem == null) return cases.checkDefinition(ws, request);
        var out = new ArrayList<CaseService.CheckIssue>();
        out.add(new CaseService.CheckIssue(ProblemCode.VALIDATION_FAILED.name(), problem));
        out.addAll(cases.checkDefinition(ws, request, true));
        return List.copyOf(out);
    }

    private CaseService.CreateDefinition create(Draft current) {
        return new CaseService.CreateDefinition(current.key(), current.name(), current.sourceXml(), fields(current.dataFields()),
                current.viewRef(), current.workflowTasks(), current.caseTasks(), current.decisionTasks());
    }

    private List<CaseDataModel.Field> fields(JsonNode node) {
        var out = new ArrayList<CaseDataModel.Field>();
        if (node != null) for (var field : node) out.add(json.convert(field, CaseDataModel.Field.class));
        return out;
    }

    private void saved(Caller caller, UUID ws, UUID id, String key, int version) {
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "case.draft.saved",
                "caseDefinitionDraft", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("key", key, "version", version)));
    }

    private static ApiException changed() {
        return new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The case type draft changed since you loaded it. Reload it and try again.");
    }

    private static String name(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 200)
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the case type a name of 1 to 200 characters.");
        return name.strip();
    }

    private static String source(String xml) {
        if (xml == null || xml.isBlank()) return null;
        if (xml.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The CMMN source must be at most 64 KiB.");
        return xml;
    }

    /** Bounded shape only; the meaning is checked by {@link #check} and at publish. */
    private String content(JsonNode designerModel, JsonNode dataFields, ApplicationViewQueries.Pin viewRef,
            List<CaseService.WorkflowTaskMapping> workflowTasks, List<CaseService.WorkflowTaskMapping> caseTasks,
            List<CaseService.WorkflowTaskMapping> decisionTasks) {
        var node = json.object();
        boolean model = designerModel != null && !designerModel.isNull();
        if (model && !designerModel.isObject()) throw new ApiException(ProblemCode.VALIDATION_FAILED, "The designer model must be an object.");
        node.set("designerModel", model ? designerModel : null);
        boolean hasFields = dataFields != null && !dataFields.isNull();
        if (hasFields && (!dataFields.isArray() || dataFields.size() > 30)) throw new ApiException(ProblemCode.VALIDATION_FAILED, "A case supports at most 30 data fields.");
        if (hasFields) for (var field : dataFields) if (!field.isObject()) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Each case data field must be an object.");
        node.set("dataFields", hasFields ? dataFields : json.mapper().createArrayNode());
        if (viewRef != null && (viewRef.id() == null || viewRef.version() < 1)) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose an exact published view version.");
        node.set("viewRef", viewRef == null ? null : json.toTree(viewRef));
        node.set("workflowTasks", mappings(workflowTasks));
        node.set("caseTasks", mappings(caseTasks));
        node.set("decisionTasks", mappings(decisionTasks));
        String text = json.write(node);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) throw new ApiException(ProblemCode.VALIDATION_FAILED, "The draft is larger than 256 KiB.");
        return text;
    }

    private JsonNode mappings(List<CaseService.WorkflowTaskMapping> mappings) {
        var list = mappings == null ? List.<CaseService.WorkflowTaskMapping>of() : mappings;
        if (list.size() > 30) throw new ApiException(ProblemCode.VALIDATION_FAILED, "A case supports at most 30 task mappings of each kind.");
        for (var mapping : list)
            if (mapping == null || mapping.taskId() == null || mapping.taskId().isBlank() || mapping.taskId().length() > 64)
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Each task mapping needs the task ID it maps.");
        return json.toTree(list.stream().map(mapping -> new CaseService.WorkflowTaskMapping(mapping.taskId(),
                mapping.inputMapping() == null ? Map.of() : mapping.inputMapping(),
                mapping.resultMapping() == null ? Map.of() : mapping.resultMapping())).toList());
    }

    private List<CaseService.WorkflowTaskMapping> readMappings(JsonNode node) {
        var out = new ArrayList<CaseService.WorkflowTaskMapping>();
        if (node != null) for (var mapping : node) out.add(json.convert(mapping, CaseService.WorkflowTaskMapping.class));
        return List.copyOf(out);
    }

    private Draft draft(UUID ws, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT d.id,d.key,d.name,d.version,d.source_xml,d.content::text AS content,d.updated_at,
                    NULLIF(c.current_version,0) AS published_version,d.published_draft_version
                FROM case_definition_draft d LEFT JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.key=d.key
                WHERE d.workspace_id=:ws AND d.id=:id
                """ + (lock ? " FOR UPDATE OF d" : "")).param("ws", ws).param("id", id).query((rs, n) -> {
                    JsonNode content = json.parse(rs.getString("content"));
                    JsonNode model = content.get("designerModel");
                    JsonNode view = content.get("viewRef");
                    return new Draft(Sql.uuid(rs, "id"), rs.getString("key"), rs.getString("name"), rs.getInt("version"),
                            rs.getString("source_xml"), model == null || model.isNull() ? null : model,
                            content.path("dataFields").isArray() ? content.get("dataFields") : json.mapper().createArrayNode(),
                            view == null || view.isNull() ? null : json.convert(view, ApplicationViewQueries.Pin.class),
                            readMappings(content.get("workflowTasks")), readMappings(content.get("caseTasks")),
                            readMappings(content.get("decisionTasks")), rs.getTimestamp("updated_at").toInstant(),
                            (Integer) rs.getObject("published_version"), (Integer) rs.getObject("published_draft_version"));
                }).optional().orElseThrow(ApiException::notFound);
    }
}
