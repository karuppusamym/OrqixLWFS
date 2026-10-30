package com.orqix.cases;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.cmmn.api.CmmnRepositoryService;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.api.CmmnTaskService;
import org.flowable.cmmn.api.CmmnHistoryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ApplicationViewQueries;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.runtime.CaseWorkflowRuns;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

/** Orqix authorization and persistence boundary for a deliberately restricted executable CMMN subset. */
@Service
public class CaseService {
    public record Definition(UUID id, String key, String name, int version, Instant createdAt, List<CaseDataModel.Field> dataFields, ApplicationViewQueries.Pinned viewBinding) {}
    public record DefinitionDetail(UUID id, String key, String name, int version, Instant createdAt, String sourceXml, List<CaseDataModel.Field> dataFields, ApplicationViewQueries.Pinned viewBinding, List<WorkflowPin> workflowTasks, List<ChildPin> caseTasks, List<DecisionPin> decisionTasks) {}
    /** The shared decision table version a decision task evaluates, pinned at publication, with explicit mappings. */
    public record DecisionPin(String taskId, UUID assetId, String decisionKey, String decisionName, int decisionVersion,
            Map<String,String> inputMapping, Map<String,String> resultMapping) {}
    /** The child case definition version a case task starts, pinned at publication, with explicit data mappings. */
    public record ChildPin(String taskId, UUID childDefinitionId, String childKey, String childName, int childVersion,
            Map<String,String> inputMapping, Map<String,String> resultMapping) {}
    /** Explicit mappings of one workflow task: workflow input field from case data field, case data field from workflow result. */
    public record WorkflowTaskMapping(String taskId, Map<String,String> inputMapping, Map<String,String> resultMapping) {}
    /** The release a workflow task of a published definition runs, pinned at publication. */
    public record WorkflowPin(String taskId, UUID workflowId, String workflowKey, String workflowName, UUID releaseId,
            int releaseNumber, Map<String,String> inputMapping, Map<String,String> resultMapping) {}
    /** The latest attempt to run the workflow of an active or finished workflow task. */
    public record WorkflowStatus(int attempt, String status, UUID runId, String reference, String outcome, String detail, UUID caseId) {}
    public record Task(String id, String name, String status, List<String> candidateGroups, boolean eligible) {}
    public record Instance(UUID id, UUID definitionId, int definitionVersion, String status, int version,
            List<Task> tasks, Instant createdAt, Boolean canRead) {}
    public record Result<T>(T value, boolean replayed) {}
    public record CreateDefinition(String key, String name, String sourceXml, List<CaseDataModel.Field> dataFields, ApplicationViewQueries.Pin viewRef, List<WorkflowTaskMapping> workflowTasks, List<WorkflowTaskMapping> caseTasks, List<WorkflowTaskMapping> decisionTasks) {
        public CreateDefinition(String key,String name,String sourceXml,List<CaseDataModel.Field> dataFields,ApplicationViewQueries.Pin viewRef,List<WorkflowTaskMapping> workflowTasks,List<WorkflowTaskMapping> caseTasks) { this(key,name,sourceXml,dataFields,viewRef,workflowTasks,caseTasks,null); }
        public CreateDefinition(String key,String name,String sourceXml,List<CaseDataModel.Field> dataFields,ApplicationViewQueries.Pin viewRef,List<WorkflowTaskMapping> workflowTasks) { this(key,name,sourceXml,dataFields,viewRef,workflowTasks,null,null); }
        public CreateDefinition(String key,String name,String sourceXml,List<CaseDataModel.Field> dataFields,ApplicationViewQueries.Pin viewRef) { this(key,name,sourceXml,dataFields,viewRef,null,null,null); }
        public CreateDefinition(String key,String name,String sourceXml,List<CaseDataModel.Field> dataFields) { this(key,name,sourceXml,dataFields,null,null,null,null); }
        public CreateDefinition(String key,String name,String sourceXml) { this(key,name,sourceXml,null,null,null,null,null); }
    }
    public record StartCase(tools.jackson.databind.JsonNode data) {
        @Override public String toString() { return "Start case [data redacted]"; }
    }
    public record UpdateData(int expectedVersion,String reason,tools.jackson.databind.JsonNode values) {
        @Override public String toString() { return "Case data update [data and reason redacted]"; }
    }
    public record DataChange(int version,tools.jackson.databind.JsonNode before,tools.jackson.databind.JsonNode after) {
        @Override public String toString() { return "Case data change " + version + " [data redacted]"; }
    }
    public record CaseData(List<CaseDataModel.Field> fields,tools.jackson.databind.JsonNode values,int version,boolean canEdit,List<DataChange> changes, ApplicationViewQueries.Pinned viewBinding) {
        @Override public String toString() { return "Case data " + version + " [data redacted]"; }
    }
    public record CompleteTask(int expectedVersion) {}
    public record TerminateCase(int expectedVersion, String reason) {
        @Override public String toString() { return "Case termination [reason redacted]"; }
    }
    public record CaseCommand(int expectedVersion, String reason) {
        @Override public String toString() { return "Case command [reason redacted]"; }
    }
    public record PlanItem(String id, String elementId, String name, String type, String state,
            String parentStageId, Instant createdAt, Instant endedAt, boolean required, Map<String,Boolean> allowedActions,
            WorkflowStatus workflow) {}
    public record Milestone(String id, String elementId, String name, Instant achievedAt) {}
    public record CaseEvent(int version, String action, String planItemId, String fromStatus, String toStatus,
            UUID actorId, String actorName, String reason, Instant occurredAt) {
        @Override public String toString() { return "Case event " + version + " " + action + " [reason redacted]"; }
    }
    public record PlanOverview(List<PlanItem> items, List<Milestone> milestones, List<CaseEvent> events, boolean canClose) {}

    private record DefinitionRow(UUID id, UUID workspaceId, String key, String name, int version,
            Instant createdAt, String sourceXml, String engineDefinitionId, List<CaseDataModel.Field> dataFields, ApplicationViewQueries.Pinned viewBinding) {
        Definition summary() { return new Definition(id, key, name, version, createdAt, dataFields, viewBinding); }
        DefinitionDetail detail(List<WorkflowPin> pins, List<ChildPin> children, List<DecisionPin> decisions) { return new DefinitionDetail(id, key, name, version, createdAt, sourceXml, dataFields, viewBinding, pins, children, decisions); }
    }
    private record InstanceRow(UUID id, UUID definitionId, int definitionVersion, String engineInstanceId,
            String status, int version, Instant createdAt, UUID startedBy) {}

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final CmmnSubsetValidator validator;
    private final CmmnRepositoryService repository;
    private final CmmnRuntimeService runtime;
    private final CmmnTaskService tasks;
    private final CmmnHistoryService history;
    private final IdempotencyService idempotency;
    private final Json json;
    private final AuditWriter audit;
    private final CaseDataModel dataModel;
    private final ApplicationViewQueries viewQueries;
    private final Map<UUID,Map<String,Set<String>>> groupsByDefinition = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Map<String,Set<String>>> eventGroupsByDefinition = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Set<String>> repeatedByDefinition = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Set<String>> requiredByDefinition = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID,Map<String,CmmnSubsetValidator.Discretionary>> discretionaryByDefinition = new java.util.concurrent.ConcurrentHashMap<>();
    private final ReleaseQueries releases;
    private final CaseWorkflowRuns workflowRuns;
    private final com.orqix.compiler.DecisionEvaluator decisions;

    public CaseService(JdbcClient jdbc, PolicyService policy, CmmnSubsetValidator validator,
            CmmnRepositoryService repository, CmmnRuntimeService runtime, CmmnTaskService tasks,
            CmmnHistoryService history, IdempotencyService idempotency, Json json, AuditWriter audit, CaseDataModel dataModel, ApplicationViewQueries viewQueries,
            ReleaseQueries releases, CaseWorkflowRuns workflowRuns, com.orqix.compiler.DecisionEvaluator decisions) {
        this.jdbc=jdbc; this.policy=policy; this.validator=validator; this.repository=repository;
        this.runtime=runtime; this.tasks=tasks; this.idempotency=idempotency; this.json=json; this.audit=audit;
        this.history=history; this.dataModel=dataModel; this.viewQueries=viewQueries;
        this.releases=releases; this.workflowRuns=workflowRuns; this.decisions=decisions;
    }

    private WorkspaceAccess access(Caller caller, UUID workspaceId, Permission permission, String operation) {
        var access = policy.requireMember(caller, workspaceId);
        policy.require(access, permission, operation, true);
        return access;
    }

    @Transactional
    public Definition createDefinition(Caller caller, UUID ws, CreateDefinition request) {
        var access = access(caller, ws, Permission.WORKFLOW_EDIT, "createCaseDefinition");
        requireKeyAndName(request);
        String xml = request.sourceXml();
        var dataFields=dataModel.fields(request.dataFields());
        var view = request.viewRef() == null ? null : viewQueries.require(ws,request.viewRef());
        if (view != null) CaseViewBinding.validate(dataFields,view.definition());
        Set<String> groups = validator.validate(xml, request.key());
        requireGroups(ws, groups);
        var pins = pinWorkflows(ws, xml, dataFields, request.workflowTasks());
        var childPins = pinChildCases(ws, request.key(), xml, dataFields, request.caseTasks());
        var decisionPins = pinDecisions(ws, xml, dataFields, request.decisionTasks());
        requireDeclaredDataFields(xml, dataFields);
        boolean hasTimers = !validator.timers(xml).isEmpty();
        UUID catalog = Ids.newId();
        jdbc.sql("INSERT INTO case_catalog(id,workspace_id,key) VALUES(:id,:ws,:key) ON CONFLICT (workspace_id,key) DO NOTHING")
                .param("id",catalog).param("ws",ws).param("key",request.key()).update();
        var existing = jdbc.sql("SELECT id,current_version FROM case_catalog WHERE workspace_id=:ws AND key=:key FOR UPDATE")
                .param("ws",ws).param("key",request.key())
                .query((rs,n) -> Map.entry(Sql.uuid(rs,"id"),rs.getInt("current_version"))).single();
        int version = existing.getValue() + 1;
        org.flowable.cmmn.api.repository.CmmnDeployment deployment;
        try {
            deployment = repository.createDeployment().name("orqix-case-" + request.key() + "-v" + version)
                    .tenantId(access.workspace().engineTenantId()).addString(request.key() + ".cmmn",validator.engineXml(xml)).deploy();
        } catch (org.flowable.common.engine.api.FlowableException ex) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "The CMMN engine rejected this model.");
        }
        var engineDefinition = repository.createCaseDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        if (engineDefinition == null || !request.key().equals(engineDefinition.getKey()))
            throw new ApiException(ProblemCode.WORKFLOW_INVALID, "CMMN deployment did not produce the expected case.");
        UUID id = Ids.newId();
        String sha;
        try { sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
        jdbc.sql("""
                INSERT INTO case_definition(id,workspace_id,catalog_id,version,name,source_xml,source_sha256,
                    engine_definition_id,engine_deployment_id,created_by,data_fields,has_timers)
                VALUES(:id,:ws,:catalog,:version,:name,:xml,:sha,:engine,:deployment,:actor,CAST(:fields AS jsonb),:timers)
                """).param("timers",hasTimers)
                .param("id",id).param("ws",ws).param("catalog",existing.getKey()).param("version",version)
                .param("name",request.name().strip()).param("xml",xml).param("sha",sha)
                .param("engine",engineDefinition.getId()).param("deployment",deployment.getId())
                .param("actor",caller.subjectId()).param("fields",json.write(dataFields)).update();
        if (view != null) jdbc.sql("INSERT INTO case_definition_view(definition_id,workspace_id,view_id,view_version,definition) VALUES(:id,:ws,:view,:version,CAST(:definition AS jsonb))")
                .param("id",id).param("ws",ws).param("view",view.id()).param("version",view.version()).param("definition",json.write(view.definition())).update();
        for (var pin : pins) jdbc.sql("""
                INSERT INTO case_definition_workflow(workspace_id,definition_id,task_id,workflow_id,release_id,input_mapping,result_mapping)
                VALUES(:ws,:id,:task,:workflow,:release,CAST(:input AS jsonb),CAST(:result AS jsonb))
                """).param("ws",ws).param("id",id).param("task",pin.taskId()).param("workflow",pin.workflowId())
                .param("release",pin.releaseId()).param("input",json.write(pin.inputMapping())).param("result",json.write(pin.resultMapping())).update();
        for (var pin : decisionPins) jdbc.sql("""
                INSERT INTO case_definition_decision(workspace_id,definition_id,task_id,asset_id,asset_version,input_mapping,result_mapping)
                VALUES(:ws,:id,:task,:asset,:version,CAST(:input AS jsonb),CAST(:result AS jsonb))
                """).param("ws",ws).param("id",id).param("task",pin.taskId()).param("asset",pin.assetId()).param("version",pin.decisionVersion())
                .param("input",json.write(pin.inputMapping())).param("result",json.write(pin.resultMapping())).update();
        for (var pin : childPins) jdbc.sql("""
                INSERT INTO case_definition_child(workspace_id,definition_id,task_id,child_definition_id,input_mapping,result_mapping)
                VALUES(:ws,:id,:task,:child,CAST(:input AS jsonb),CAST(:result AS jsonb))
                """).param("ws",ws).param("id",id).param("task",pin.taskId()).param("child",pin.childDefinitionId())
                .param("input",json.write(pin.inputMapping())).param("result",json.write(pin.resultMapping())).update();
        jdbc.sql("UPDATE case_catalog SET current_version=:v WHERE id=:id")
                .param("v",version).param("id",existing.getKey()).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"case.definition.created",
                "caseDefinition",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("version",version,"workflowTasks",pins.size())));
        return definition(ws,id).summary();
    }

    private static void requireKeyAndName(CreateDefinition request) {
        if (request == null || request.key() == null || !request.key().matches("[a-z][a-z0-9-]{1,62}")
                || request.name() == null || request.name().isBlank() || request.name().length() > 200)
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case key or name is invalid.");
    }

    private void requireGroups(UUID ws, Set<String> groups) {
        if (groups.isEmpty()) return;
        long found = jdbc.sql("SELECT count(*) FROM workspace_group WHERE workspace_id=:ws AND key IN (:groups)")
                .param("ws",ws).param("groups",groups).query(Long.class).single();
        if (found != groups.size())
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Every candidate group must exist in this workspace.");
    }

    private void requireDeclaredDataFields(String xml, List<CaseDataModel.Field> dataFields) {
        var keys = dataFields.stream().map(CaseDataModel.Field::key).collect(java.util.stream.Collectors.toSet());
        for (String field : validator.fileItems(xml).values())
            if (!keys.contains(field)) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case file item " + field + " must be a declared case data field.");
        for (String field : validator.conditionFields(xml))
            if (!keys.contains(field)) throw new ApiException(ProblemCode.VALIDATION_FAILED, "An if-part reads case data field " + field + ", which this definition does not declare.");
    }

    /** One problem the check found: the problem code the publish would answer with, and its message. */
    public record CheckIssue(String code, String message) {}

    /**
     * S126: every admission rule of {@link #createDefinition} without deploying or writing anything. Rules that do not
     * depend on each other are all run, so the designer sees every problem at once; a rule whose input failed is skipped.
     * Engine deployment itself is not tried, so a model the engine still rejects is reported only when publishing.
     * Deliberately not transactional: a refused rule inside a read-only query helper must not mark a shared
     * transaction rollback-only, since the check carries on after it.
     */
    public List<CheckIssue> checkDefinition(UUID ws, CreateDefinition request) {
        return checkDefinition(ws, request, false);
    }

    /** As above; with {@code dataFieldsUnreadable} the caller already reported the data fields, and rules that need them are skipped. */
    public List<CheckIssue> checkDefinition(UUID ws, CreateDefinition request, boolean dataFieldsUnreadable) {
        var issues = new ArrayList<CheckIssue>();
        boolean named = attempt(issues, () -> { requireKeyAndName(request); return true; }) != null;
        var dataFields = dataFieldsUnreadable ? null : attempt(issues, () -> dataModel.fields(request.dataFields()));
        if (dataFields != null && request.viewRef() != null)
            attempt(issues, () -> { CaseViewBinding.validate(dataFields, viewQueries.require(ws, request.viewRef()).definition()); return true; });
        String xml = request.sourceXml();
        if (xml == null || xml.isBlank()) {
            issues.add(new CheckIssue(ProblemCode.WORKFLOW_INVALID.name(), "The case type has no CMMN model yet. Draw the case plan or upload a CMMN file."));
            return List.copyOf(issues);
        }
        if (!named) return List.copyOf(issues);
        var groups = attempt(issues, () -> validator.validate(xml, request.key()));
        if (groups == null) return List.copyOf(issues);
        attempt(issues, () -> { requireGroups(ws, groups); return true; });
        if (dataFields == null) return List.copyOf(issues);
        attempt(issues, () -> pinWorkflows(ws, xml, dataFields, request.workflowTasks()));
        attempt(issues, () -> pinChildCases(ws, request.key(), xml, dataFields, request.caseTasks()));
        attempt(issues, () -> pinDecisions(ws, xml, dataFields, request.decisionTasks()));
        attempt(issues, () -> { requireDeclaredDataFields(xml, dataFields); return true; });
        return List.copyOf(issues);
    }

    private static <T> T attempt(List<CheckIssue> issues, java.util.function.Supplier<T> rule) {
        try { return rule.get(); }
        catch (ApiException ex) {
            issues.add(new CheckIssue(ex.code().name(), ex.detail() == null ? ex.code().title() : ex.detail()));
            return null;
        }
    }

    private static final Set<String> RESULT_SOURCES = Set.of("outcome","outcomeKind","reference");

    /** Every process task names a published workspace workflow; its active release is pinned with checked mappings. */
    private List<WorkflowPin> pinWorkflows(UUID ws, String xml, List<CaseDataModel.Field> fields, List<WorkflowTaskMapping> mappings) {
        var tasks = validator.workflowTasks(xml);
        var byTask = new java.util.LinkedHashMap<String,WorkflowTaskMapping>();
        for (var mapping : mappings == null ? List.<WorkflowTaskMapping>of() : mappings) {
            if (mapping == null || mapping.taskId() == null || byTask.put(mapping.taskId(), mapping) != null)
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give each workflow task one mapping.");
        }
        if (!byTask.keySet().equals(tasks.keySet()))
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workflow task mappings must match the workflow tasks of the model exactly: " + tasks.keySet() + ".");
        var types = new java.util.HashMap<String,String>();
        for (var field : fields) types.put(field.key(), switch (field.type()) { case "text","date","choice" -> "string"; default -> field.type(); });
        var pins = new ArrayList<WorkflowPin>();
        for (var task : tasks.entrySet()) {
            var workflow = jdbc.sql("SELECT id,name,active_release_id FROM workflow WHERE workspace_id=:ws AND key=:key AND archived_at IS NULL")
                    .param("ws",ws).param("key",task.getValue())
                    .query((rs,n)->new Object[]{Sql.uuid(rs,"id"),rs.getString("name"),Sql.uuid(rs,"active_release_id")}).optional()
                    .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Workflow task " + task.getKey() + " names workflow \"" + task.getValue() + "\", which does not exist in this workspace."));
            if (workflow[2] == null)
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workflow \"" + workflow[1] + "\" has not been published yet, so task " + task.getKey() + " cannot pin a release.");
            UUID releaseId = (UUID) workflow[2];
            var release = releases.require(ws, releaseId);
            var available = workflowRuns.fields(ws, releaseId);
            var mapping = byTask.get(task.getKey());
            var input = mapping.inputMapping() == null ? Map.<String,String>of() : Map.copyOf(mapping.inputMapping());
            var result = mapping.resultMapping() == null ? Map.<String,String>of() : Map.copyOf(mapping.resultMapping());
            if (input.size() > 50 || result.size() > 30) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Too many mapped fields for task " + task.getKey() + ".");
            for (var e : input.entrySet()) {
                if (!available.inputs().contains(e.getKey()))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "\"" + release.workflowName() + "\" has no request field " + e.getKey() + " (task " + task.getKey() + ").");
                String from = types.get(e.getValue());
                if (from == null) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Task " + task.getKey() + " maps unknown case data field " + e.getValue() + ".");
                if (!compatible(from, available.inputTypes().get(e.getKey())))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case field " + e.getValue() + " (" + from + ") cannot fill request field " + e.getKey() + " (" + available.inputTypes().get(e.getKey()) + ").");
            }
            for (String required : available.required())
                if (!input.containsKey(required))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Task " + task.getKey() + " must map the required request field " + required + " of \"" + release.workflowName() + "\".");
            for (var e : result.entrySet()) {
                String to = types.get(e.getKey());
                if (to == null) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Task " + task.getKey() + " writes unknown case data field " + e.getKey() + ".");
                String sourceType = RESULT_SOURCES.contains(e.getValue()) ? "string" : available.outputTypes().get(e.getValue());
                if (sourceType == null)
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "\"" + release.workflowName() + "\" has no result field " + e.getValue() + " (task " + task.getKey() + ").");
                if (!compatible(sourceType, to))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Result " + e.getValue() + " (" + sourceType + ") cannot fill case field " + e.getKey() + " (" + to + ").");
            }
            pins.add(new WorkflowPin(task.getKey(), (UUID) workflow[0], task.getValue(), release.workflowName(), releaseId,
                    release.releaseNumber(), new java.util.TreeMap<>(input), new java.util.TreeMap<>(result)));
        }
        return List.copyOf(pins);
    }

    /** Every case task names a published case definition of this workspace; its current version is pinned with checked mappings. */
    private List<ChildPin> pinChildCases(UUID ws, String parentKey, String xml, List<CaseDataModel.Field> fields, List<WorkflowTaskMapping> mappings) {
        var tasks = validator.caseTasks(xml);
        var byTask = new java.util.LinkedHashMap<String,WorkflowTaskMapping>();
        for (var mapping : mappings == null ? List.<WorkflowTaskMapping>of() : mappings)
            if (mapping == null || mapping.taskId() == null || byTask.put(mapping.taskId(), mapping) != null)
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give each case task one mapping.");
        if (!byTask.keySet().equals(tasks.keySet()))
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case task mappings must match the case tasks of the model exactly: " + tasks.keySet() + ".");
        var parentTypes = types(fields);
        var pins = new ArrayList<ChildPin>();
        for (var task : tasks.entrySet()) {
            if (task.getValue().equals(parentKey)) throw new ApiException(ProblemCode.VALIDATION_FAILED, "A case cannot start itself.");
            var child = jdbc.sql("""
                    SELECT d.id FROM case_definition d JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.id=d.catalog_id
                    WHERE d.workspace_id=:ws AND c.key=:key AND d.version=c.current_version
                    """).param("ws",ws).param("key",task.getValue()).query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Case task " + task.getKey() + " names case \"" + task.getValue() + "\", which has no published definition in this workspace."));
            var childDefinition = definition(ws, child);
            var childTypes = types(childDefinition.dataFields());
            var mapping = byTask.get(task.getKey());
            var input = mapping.inputMapping() == null ? Map.<String,String>of() : Map.copyOf(mapping.inputMapping());
            var result = mapping.resultMapping() == null ? Map.<String,String>of() : Map.copyOf(mapping.resultMapping());
            for (var e : input.entrySet()) {
                String to = childTypes.get(e.getKey()), from = parentTypes.get(e.getValue());
                if (to == null || from == null || !compatible(from, to))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case task " + task.getKey() + " cannot fill child field " + e.getKey() + " from " + e.getValue() + ".");
            }
            for (var field : childDefinition.dataFields())
                if (Boolean.TRUE.equals(field.required()) && !input.containsKey(field.key()))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case task " + task.getKey() + " must fill the required field " + field.key() + " of \"" + childDefinition.name() + "\".");
            for (var e : result.entrySet()) {
                String to = parentTypes.get(e.getKey()), from = "outcome".equals(e.getValue()) ? "string" : childTypes.get(e.getValue());
                if (to == null || from == null || !compatible(from, to))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case task " + task.getKey() + " cannot write " + e.getKey() + " from child field " + e.getValue() + ".");
            }
            pins.add(new ChildPin(task.getKey(), child, task.getValue(), childDefinition.name(), childDefinition.version(),
                    new java.util.TreeMap<>(input), new java.util.TreeMap<>(result)));
        }
        return List.copyOf(pins);
    }

    /** Every decision task names a shared decision table of this workspace; its latest version is pinned with checked mappings. */
    private List<DecisionPin> pinDecisions(UUID ws, String xml, List<CaseDataModel.Field> fields, List<WorkflowTaskMapping> mappings) {
        var tasks = validator.decisionTasks(xml);
        var byTask = new java.util.LinkedHashMap<String,WorkflowTaskMapping>();
        for (var mapping : mappings == null ? List.<WorkflowTaskMapping>of() : mappings)
            if (mapping == null || mapping.taskId() == null || byTask.put(mapping.taskId(), mapping) != null)
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give each decision task one mapping.");
        if (!byTask.keySet().equals(tasks.keySet()))
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Decision task mappings must match the decision tasks of the model exactly: " + tasks.keySet() + ".");
        var caseTypes = types(fields);
        var pins = new ArrayList<DecisionPin>();
        for (var task : tasks.entrySet()) {
            var asset = jdbc.sql("""
                    SELECT a.id,a.name,a.latest_version,a.archived_at IS NOT NULL AS archived,v.content::text AS content
                    FROM decision_asset a JOIN decision_asset_version v ON v.asset_id=a.id AND v.version=a.latest_version
                    WHERE a.workspace_id=:ws AND a.key=:key
                    """).param("ws",ws).param("key",task.getValue()).query((rs,n)->new Object[]{Sql.uuid(rs,"id"),rs.getString("name"),
                            rs.getInt("latest_version"),rs.getBoolean("archived"),json.parse(rs.getString("content"))}).optional()
                    .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Decision task " + task.getKey() + " names shared decision \"" + task.getValue() + "\", which does not exist in this workspace."));
            if ((Boolean) asset[3]) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Shared decision \"" + asset[1] + "\" is archived.");
            var content = (tools.jackson.databind.JsonNode) asset[4];
            var fieldRefs = new java.util.TreeSet<String>(); collectFieldRefs(content.path("rows"), fieldRefs);
            var outputs = new java.util.HashMap<String,String>();
            for (var row : content.path("rows")) row.path("then").properties().forEach(e -> outputs.putIfAbsent(e.getKey(), literalType(e.getValue())));
            content.path("default").properties().forEach(e -> outputs.putIfAbsent(e.getKey(), literalType(e.getValue())));
            var mapping = byTask.get(task.getKey());
            var input = mapping.inputMapping() == null ? Map.<String,String>of() : Map.copyOf(mapping.inputMapping());
            var result = mapping.resultMapping() == null ? Map.<String,String>of() : Map.copyOf(mapping.resultMapping());
            if (!input.keySet().equals(fieldRefs))
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Decision task " + task.getKey() + " must fill exactly the fields the table reads: " + fieldRefs + ".");
            for (var e : input.entrySet()) if (!caseTypes.containsKey(e.getValue()))
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Decision task " + task.getKey() + " reads unknown case data field " + e.getValue() + ".");
            for (var e : result.entrySet()) {
                String to = caseTypes.get(e.getKey()), from = outputs.get(e.getValue());
                if (to == null || from == null || !compatible(from, to))
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Decision task " + task.getKey() + " cannot write " + e.getKey() + " from result " + e.getValue() + ".");
            }
            pins.add(new DecisionPin(task.getKey(), (UUID) asset[0], task.getValue(), (String) asset[1], (Integer) asset[2],
                    new java.util.TreeMap<>(input), new java.util.TreeMap<>(result)));
        }
        return List.copyOf(pins);
    }

    private static void collectFieldRefs(tools.jackson.databind.JsonNode node, Set<String> into) {
        if (node.isObject()) {
            if (node.path("fieldRef").isString()) into.add(node.path("fieldRef").asString());
            node.properties().forEach(e -> { if (!"then".equals(e.getKey())) collectFieldRefs(e.getValue(), into); });
        } else if (node.isArray()) for (var child : node) collectFieldRefs(child, into);
    }

    private static String literalType(tools.jackson.databind.JsonNode value) {
        return value.isBoolean() ? "boolean" : value.isIntegralNumber() ? "integer" : value.isNumber() ? "number" : "string";
    }

    private List<DecisionPin> decisionPins(UUID ws, UUID definitionId) {
        return jdbc.sql("""
                SELECT p.task_id,p.asset_id,a.key,a.name,p.asset_version,p.input_mapping::text AS input,p.result_mapping::text AS result
                FROM case_definition_decision p JOIN decision_asset a ON a.workspace_id=p.workspace_id AND a.id=p.asset_id
                WHERE p.workspace_id=:ws AND p.definition_id=:id ORDER BY p.task_id
                """).param("ws",ws).param("id",definitionId).query((rs,n)->new DecisionPin(rs.getString("task_id"),Sql.uuid(rs,"asset_id"),
                        rs.getString("key"),rs.getString("name"),rs.getInt("asset_version"),stringMap(rs.getString("input")),stringMap(rs.getString("result")))).list();
    }

    /**
     * Case file item events (CMMN 5.2): create when a case data field gets its first value, update when a value changes.
     * The engine model holds a hidden user event per event; Orqix completes the available ones that match.
     */
    private void fireFileTriggers(UUID ws, UUID caseId, tools.jackson.databind.JsonNode before, tools.jackson.databind.JsonNode after, UUID actor) {
        var row = instanceRow(ws, caseId, false);
        if (!row.status().equals("active")) return;
        var triggers = validator.fileTriggers(source(ws, row.definitionId()));
        if (triggers.isEmpty()) return;
        int fired = 0;
        for (var listener : runtime.createUserEventListenerInstanceQuery().caseInstanceId(row.engineInstanceId()).list()) {
            var trigger = triggers.get(listener.getPlanItemDefinitionId());
            if (trigger == null || !"available".equals(listener.getState())) continue;
            var was = before == null ? null : before.get(trigger.field());
            var now = after == null ? null : after.get(trigger.field());
            boolean hadValue = was != null && !was.isNull(), hasValue = now != null && !now.isNull();
            boolean matches = "create".equals(trigger.event()) ? !hadValue && hasValue : hadValue && hasValue && !was.equals(now);
            if (!matches) continue;
            runtime.completeUserEventListenerInstance(listener.getId());
            systemEvent(ws, caseId, "caseFileEvent", listener.getId(), actor, null);
            fired++;
        }
        if (fired > 0 && runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult() == null)
            jdbc.sql("UPDATE case_instance SET status='completed',completed_at=now() WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",caseId).update();
    }

    private static Map<String,String> types(List<CaseDataModel.Field> fields) {
        var types = new java.util.HashMap<String,String>();
        for (var field : fields) types.put(field.key(), switch (field.type()) { case "text","date","choice" -> "string"; default -> field.type(); });
        return types;
    }

    private List<ChildPin> childPins(UUID ws, UUID definitionId) {
        return jdbc.sql("""
                SELECT p.task_id,p.child_definition_id,c.key,d.name,d.version,p.input_mapping::text AS input,p.result_mapping::text AS result
                FROM case_definition_child p JOIN case_definition d ON d.workspace_id=p.workspace_id AND d.id=p.child_definition_id
                JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.id=d.catalog_id
                WHERE p.workspace_id=:ws AND p.definition_id=:id ORDER BY p.task_id
                """).param("ws",ws).param("id",definitionId).query((rs,n)->new ChildPin(rs.getString("task_id"),Sql.uuid(rs,"child_definition_id"),
                        rs.getString("key"),rs.getString("name"),rs.getInt("version"),stringMap(rs.getString("input")),stringMap(rs.getString("result")))).list();
    }

    /** Engine variables cd_<field> for the fields if-parts read; every other field stays out of the engine. */
    private Map<String,Object> conditionVariables(UUID ws, UUID definitionId, tools.jackson.databind.JsonNode data) {
        var out = new java.util.HashMap<String,Object>();
        for (String field : validator.conditionFields(source(ws, definitionId))) {
            var value = data == null ? null : data.get(field);
            out.put("cd_" + field, value == null || value.isNull() ? null : value.isNumber() ? value.decimalValue()
                    : value.isBoolean() ? (Object) value.asBoolean() : value.asString());
        }
        return out;
    }

    /** After case data changed: mirror the fields if-parts read and let the engine evaluate data-triggered sentries. */
    private void afterDataChange(UUID ws, UUID caseId, tools.jackson.databind.JsonNode before, tools.jackson.databind.JsonNode values, UUID actor) {
        var row = instanceRow(ws, caseId, false);
        if (!row.status().equals("active")) return;
        fireFileTriggers(ws, caseId, before, values, actor);
        row = instanceRow(ws, caseId, false);
        var variables = conditionVariables(ws, row.definitionId(), values);
        if (!row.status().equals("active")) return;
        if (!variables.isEmpty()) {
            runtime.setVariables(row.engineInstanceId(), variables);
            runtime.evaluateCriteria(row.engineInstanceId());
        }
        if (runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult() == null)
            jdbc.sql("UPDATE case_instance SET status='completed',completed_at=now() WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",caseId).update();
        else dispatchWorkflows(ws, caseId, actor);
    }

    private static boolean compatible(String from, String to) {
        return from.equals(to) || "integer".equals(from) && "number".equals(to);
    }

    private List<WorkflowPin> pins(UUID ws, UUID definitionId) {
        return jdbc.sql("""
                SELECT p.task_id,p.workflow_id,w.key,w.name,p.release_id,r.release_number,p.input_mapping::text AS input,p.result_mapping::text AS result
                FROM case_definition_workflow p JOIN workflow w ON w.workspace_id=p.workspace_id AND w.id=p.workflow_id
                JOIN workflow_release r ON r.workflow_id=p.workflow_id AND r.id=p.release_id
                WHERE p.workspace_id=:ws AND p.definition_id=:id ORDER BY p.task_id
                """).param("ws",ws).param("id",definitionId).query((rs,n)->new WorkflowPin(rs.getString("task_id"),Sql.uuid(rs,"workflow_id"),
                        rs.getString("key"),rs.getString("name"),Sql.uuid(rs,"release_id"),rs.getInt("release_number"),
                        stringMap(rs.getString("input")),stringMap(rs.getString("result")))).list();
    }

    private Map<String,String> stringMap(String source) {
        var out=new java.util.TreeMap<String,String>();
        json.parse(source).properties().forEach(e->out.put(e.getKey(),e.getValue().asString()));
        return out;
    }

    @Transactional(readOnly=true)
    public List<Definition> definitions(Caller caller, UUID ws) {
        var membership=policy.requireMember(caller,ws);
        if (!membership.has(Permission.WORKFLOW_READ) && !membership.has(Permission.WORKFLOW_START))
            throw ApiException.forbidden();
        return jdbc.sql("""
                SELECT d.id,d.workspace_id,c.key,d.name,d.version,d.created_at,d.source_xml,d.engine_definition_id,d.data_fields::text AS data_fields,
                    CASE WHEN v.definition_id IS NULL THEN NULL ELSE jsonb_build_object('id',v.view_id,'version',v.view_version,'definition',v.definition)::text END AS view_binding
                FROM case_definition d JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.id=d.catalog_id
                LEFT JOIN case_definition_view v ON v.workspace_id=d.workspace_id AND v.definition_id=d.id
                WHERE d.workspace_id=:ws ORDER BY d.created_at DESC,d.id DESC LIMIT 100
                """).param("ws",ws).query(this::mapDefinition).list().stream().map(DefinitionRow::summary).toList();
    }

    @Transactional(readOnly=true)
    public DefinitionDetail definition(Caller caller, UUID ws, UUID id) {
        access(caller,ws,Permission.WORKFLOW_EDIT,"getCaseDefinition");
        return definition(ws,id).detail(pins(ws,id),childPins(ws,id),decisionPins(ws,id));
    }

    private DefinitionRow definition(UUID ws, UUID id) {
        return jdbc.sql("""
                SELECT d.id,d.workspace_id,c.key,d.name,d.version,d.created_at,d.source_xml,d.engine_definition_id,d.data_fields::text AS data_fields,
                    CASE WHEN v.definition_id IS NULL THEN NULL ELSE jsonb_build_object('id',v.view_id,'version',v.view_version,'definition',v.definition)::text END AS view_binding
                FROM case_definition d JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.id=d.catalog_id
                LEFT JOIN case_definition_view v ON v.workspace_id=d.workspace_id AND v.definition_id=d.id
                WHERE d.workspace_id=:ws AND d.id=:id
                """).param("ws",ws).param("id",id).query(this::mapDefinition).optional().orElseThrow(ApiException::notFound);
    }

    private DefinitionRow mapDefinition(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DefinitionRow(Sql.uuid(rs,"id"),Sql.uuid(rs,"workspace_id"),rs.getString("key"),
                rs.getString("name"),rs.getInt("version"),Sql.instant(rs,"created_at"),
                rs.getString("source_xml"),rs.getString("engine_definition_id"),dataModel.readFields(rs.getString("data_fields")),
                rs.getString("view_binding")==null ? null : json.convert(json.parse(rs.getString("view_binding")),ApplicationViewQueries.Pinned.class));
    }

    @Transactional
    public Result<Instance> start(Caller caller, UUID ws, UUID definitionId, String key) {
        return start(caller,ws,definitionId,key,null);
    }

    @Transactional
    public Result<Instance> start(Caller caller, UUID ws, UUID definitionId, String key, StartCase request) {
        var access = access(caller,ws,Permission.WORKFLOW_START,"startCase");
        var definition = definition(ws,definitionId);
        var initialData=dataModel.values(definition.dataFields(),request==null?null:request.data());
        String operation="startCase";
        var claim=idempotency.claim(ws,caller.subjectId(),operation,IdempotencyService.requireKey(key),
                idempotency.requestHash(operation,Map.of("definitionId",definitionId.toString()),initialData.isEmpty()?Map.of():initialData));
        if (claim.isReplay()) return new Result<>(replayed(claim,access),true);
        // Workflow tasks start their runs on behalf of the case starter, who therefore needs the right to start them.
        for (var pin : pins(ws,definitionId)) {
            if (!access.canStart(pin.workflowId()))
                throw policy.deny(ws, caller.subjectId(), "workflow.start", "startCase", false,
                        new ApiException(ProblemCode.FORBIDDEN, "This case starts \"" + pin.workflowName()
                                + "\" for you, and you are not allowed to start that workflow."));
        }
        UUID id=Ids.newId();
        var engine=runtime.createCaseInstanceBuilder().caseDefinitionId(definition.engineDefinitionId())
                .tenantId(access.workspace().engineTenantId()).businessKey(id.toString())
                .variables(conditionVariables(ws,definitionId,initialData)).start();
        boolean engineActive = runtime.createCaseInstanceQuery().caseInstanceId(engine.getId()).singleResult()!=null;
        jdbc.sql("INSERT INTO case_instance(id,workspace_id,definition_id,engine_instance_id,status,started_by,completed_at,data_values) VALUES(:id,:ws,:definition,:engine,:status,:actor,CASE WHEN :active THEN NULL ELSE now() END,CAST(:data AS jsonb))")
                .param("id",id).param("ws",ws).param("definition",definitionId).param("engine",engine.getId())
                .param("actor",caller.subjectId()).param("status",engineActive?"active":"completed").param("active",engineActive).param("data",json.write(initialData)).update();
        appendEvent(ws,id,1,"started",null,null,engineActive?"active":"completed",caller,null);
        appendDataEvent(ws,id,1,json.object(),initialData);
        fireFileTriggers(ws,id,json.object(),initialData,caller.subjectId());
        dispatchWorkflows(ws,id,caller.subjectId());
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"case.instance.started",
                "caseInstance",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("definitionId",definitionId.toString(),"version",definition.version())));
        Instance response=instance(ws,id,access);
        saveResponse(claim,id,List.of(),response);
        idempotency.complete(claim,"caseInstance",id.toString(),201);
        return new Result<>(response,false);
    }

    @Transactional(readOnly=true)
    public List<Instance> instances(Caller caller, UUID ws) {
        var access=policy.requireMember(caller,ws);
        if (!access.has(Permission.RUN_READ) && !access.has(Permission.WORKFLOW_START)
                && !access.has(Permission.TASK_ACT)) throw ApiException.forbidden();
        var rows=jdbc.sql("""
                SELECT i.id,i.definition_id,d.version AS definition_version,i.engine_instance_id,i.status,
                    i.version,i.created_at,i.started_by FROM case_instance i JOIN case_definition d
                    ON d.workspace_id=i.workspace_id AND d.id=i.definition_id
                WHERE i.workspace_id=:ws ORDER BY i.created_at DESC,i.id DESC LIMIT 200
                """).param("ws",ws).query((rs,n)->new InstanceRow(Sql.uuid(rs,"id"),Sql.uuid(rs,"definition_id"),
                        rs.getInt("definition_version"),rs.getString("engine_instance_id"),rs.getString("status"),
                        rs.getInt("version"),Sql.instant(rs,"created_at"),Sql.uuid(rs,"started_by"))).list();
        return rows.stream().filter(row -> canRead(row,access)).limit(50)
                .map(row -> instance(ws,row.id(),access)).toList();
    }

    @Transactional(readOnly=true)
    public Instance instance(Caller caller, UUID ws, UUID id) {
        var access=policy.requireMember(caller,ws);
        var row=instanceRow(ws,id,false);
        if (!canRead(row,access)) throw ApiException.notFound();
        return instance(ws,id,access);
    }

    private boolean canRead(InstanceRow row, WorkspaceAccess access) {
        if (access.has(Permission.RUN_READ) || row.startedBy().equals(access.subjectId())) return true;
        if (!access.has(Permission.TASK_ACT) || !Set.of("active","suspended").contains(row.status())) return false;
        for (var task : tasks.createTaskQuery().caseInstanceId(row.engineInstanceId()).list()) {
            var groups=tasks.getIdentityLinksForTask(task.getId()).stream()
                    .filter(link -> "candidate".equals(link.getType()) && link.getGroupId()!=null)
                    .map(org.flowable.identitylink.api.IdentityLink::getGroupId).toList();
            if (eligible(task.getAssignee(),groups,access)) return true;
        }
        for (var item : runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).list()) {
            if (org.flowable.cmmn.api.runtime.PlanItemDefinitionType.HUMAN_TASK.equals(item.getPlanItemDefinitionType()) && Set.of("enabled","disabled").contains(item.getState())
                    && humanGroups(row,access.workspaceId()).getOrDefault(item.getPlanItemDefinitionId(),Set.of()).stream().anyMatch(access.groupKeys()::contains))
                return true;
        }
        for (var listener : runtime.createUserEventListenerInstanceQuery().caseInstanceId(row.engineInstanceId()).list()) {
            if (userEventGroups(row,access.workspaceId()).getOrDefault(listener.getPlanItemDefinitionId(),Set.of()).stream().anyMatch(access.groupKeys()::contains)) return true;
        }
        return false;
    }

    /** Read only engine history, including completed/terminated stages and milestones after the case ends. */
    @Transactional(readOnly=true)
    public PlanOverview plan(Caller caller, UUID ws, UUID id) {
        var access = policy.requireMember(caller, ws);
        var row = instanceRow(ws, id, false);
        if (!canRead(row, access)) throw ApiException.notFound();
        var planners = discretionary(row, ws);
        var workflowIds = callTaskIds(row, ws);
        var childIds = childTaskIds(row, ws);
        var decisionIds = decisionTaskIds(row, ws);
        var hiddenFileEvents = validator.fileTriggers(source(ws, row.definitionId())).keySet();
        var statuses = workflowIds.isEmpty() ? Map.<String,WorkflowStatus>of() : workflowStatuses(ws, id);
        var items = history.createHistoricPlanItemInstanceQuery().planItemInstanceCaseInstanceId(row.engineInstanceId())
                .list().stream().filter(item -> !hiddenFileEvents.contains(item.getPlanItemDefinitionId())).map(item -> {
                    var planner = planners.get(item.getPlanItemDefinitionId());
                    // A discretionary item is shown as itself: available to plan, then planned (its plan item follows).
                    if (planner != null) return new PlanItem(item.getId(), planner.id(), planner.name(), "discretionaryItem",
                            "completed".equals(item.getState()) ? "planned" : "available".equals(item.getState()) ? "available" : "notPlanned",
                            item.getStageInstanceId(), instant(item.getCreateTime()), instant(item.getEndedTime()), false,
                            actions(row,item.getId(),access), null);
                    boolean workflow = workflowIds.contains(item.getPlanItemDefinitionId());
                    return new PlanItem(item.getId(), item.getElementId(), item.getName(),
                        workflow ? (childIds.contains(item.getPlanItemDefinitionId()) ? "caseTask" : decisionIds.contains(item.getPlanItemDefinitionId()) ? "decisionTask" : "processTask") : item.getPlanItemDefinitionType(), item.getState(), item.getStageInstanceId(),
                        instant(item.getCreateTime()), instant(item.getEndedTime()), required(row,item.getElementId(),access.workspaceId()), actions(row,item.getId(),access),
                        workflow ? statuses.get(item.getId()) : null);
                })
                .sorted(Comparator.comparing(PlanItem::elementId).thenComparing(PlanItem::type).thenComparing(PlanItem::id)).toList();
        var milestones = history.createHistoricMilestoneInstanceQuery().milestoneInstanceCaseInstanceId(row.engineInstanceId())
                .list().stream().map(item -> new Milestone(item.getId(), item.getElementId(), item.getName(),
                        instant(item.getTimeStamp())))
                .sorted(Comparator.comparing(Milestone::achievedAt).thenComparing(Milestone::id)).toList();
        var engine=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult();
        var events=jdbc.sql("""
                SELECT e.version,e.action,e.plan_item_id,e.from_status,e.to_status,e.actor_subject_id,s.display_name,
                    e.reason,e.occurred_at FROM case_event e LEFT JOIN subject s ON s.id=e.actor_subject_id
                WHERE e.workspace_id=:ws AND e.case_id=:id ORDER BY e.version DESC LIMIT 200
                """).param("ws",ws).param("id",id).query((rs,n)->new CaseEvent(rs.getInt("version"),rs.getString("action"),
                        rs.getString("plan_item_id"),rs.getString("from_status"),rs.getString("to_status"),Sql.uuid(rs,"actor_subject_id"),
                        rs.getString("display_name"),access.has(Permission.RUN_READ_DATA)?rs.getString("reason"):null,
                        Sql.instant(rs,"occurred_at"))).list();
        return new PlanOverview(items, milestones, events, row.status().equals("active") && access.has(Permission.RUN_OPERATE)
                && engine!=null && closable(row,ws,null));
    }

    private static Instant instant(java.util.Date date) { return date == null ? null : date.toInstant(); }

    private Instance instance(UUID ws, UUID id, WorkspaceAccess access) {
        var row=instanceRow(ws,id,false);
        List<Task> visibleTasks = new ArrayList<>();
        if (Set.of("active","suspended").contains(row.status())) {
            for (var task : tasks.createTaskQuery().caseInstanceId(row.engineInstanceId()).list()) {
                var groups=tasks.getIdentityLinksForTask(task.getId()).stream()
                        .filter(link -> "candidate".equals(link.getType()) && link.getGroupId()!=null)
                        .map(org.flowable.identitylink.api.IdentityLink::getGroupId).distinct().sorted().toList();
                boolean eligible=eligible(task.getAssignee(),groups,access);
                visibleTasks.add(new Task(task.getId(),task.getName(),"available",groups,eligible && row.status().equals("active")));
            }
        }
        visibleTasks.sort(Comparator.comparing(Task::id));
        return new Instance(row.id(),row.definitionId(),row.definitionVersion(),row.status(),row.version(),
                List.copyOf(visibleTasks),row.createdAt(),canRead(row,access));
    }

    private static boolean eligible(String assignee, List<String> groups, WorkspaceAccess access) {
        return assignee == null ? groups.stream().anyMatch(access.groupKeys()::contains)
                : assignee.equals(access.subjectId().toString());
    }

    private InstanceRow instanceRow(UUID ws, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT i.id,i.definition_id,d.version AS definition_version,i.engine_instance_id,i.status,
                    i.version,i.created_at,i.started_by FROM case_instance i JOIN case_definition d
                    ON d.workspace_id=i.workspace_id AND d.id=i.definition_id
                WHERE i.workspace_id=:ws AND i.id=:id
                """ + (lock ? " FOR UPDATE OF i" : ""))
                .param("ws",ws).param("id",id)
                .query((rs,n)->new InstanceRow(Sql.uuid(rs,"id"),Sql.uuid(rs,"definition_id"),
                        rs.getInt("definition_version"),rs.getString("engine_instance_id"),rs.getString("status"),
                        rs.getInt("version"),Sql.instant(rs,"created_at"),Sql.uuid(rs,"started_by")))
                .optional().orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Result<Instance> complete(Caller caller, UUID ws, UUID id, String taskId, String key, CompleteTask request) {
        var access=access(caller,ws,Permission.TASK_ACT,"completeCaseTask");
        if (request == null || request.expectedVersion() < 1) throw new ApiException(ProblemCode.VALIDATION_FAILED);
        var row=instanceRow(ws,id,true);
        String operation="completeCaseTask";
        var claim=idempotency.claim(ws,caller.subjectId(),operation,IdempotencyService.requireKey(key),
                idempotency.requestHash(operation,Map.of("caseId",id.toString(),"taskId",taskId),request));
        if (claim.isReplay()) return new Result<>(replayed(claim,access),true);
        if (row.version()!=request.expectedVersion()) throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT);
        requireActive(row);
        var task=tasks.createTaskQuery().taskId(taskId).caseInstanceId(row.engineInstanceId()).singleResult();
        if (task == null) throw ApiException.notFound();
        var groups=tasks.getIdentityLinksForTask(taskId).stream()
                .filter(link -> "candidate".equals(link.getType()) && link.getGroupId()!=null)
                .map(org.flowable.identitylink.api.IdentityLink::getGroupId).toList();
        if (!eligible(task.getAssignee(),groups,access)) throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        String planItemId=task.getSubScopeId();
        tasks.complete(taskId);
        boolean active=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null;
        jdbc.sql("UPDATE case_instance SET version=version+1,status=:status,completed_at=CASE WHEN :active THEN NULL ELSE now() END WHERE workspace_id=:ws AND id=:id")
                .param("status",active?"active":"completed").param("active",active).param("ws",ws).param("id",id).update();
        appendEvent(ws,id,row.version()+1,"taskCompleted",planItemId,row.status(),active?"active":"completed",caller,null);
        if (active) dispatchWorkflows(ws,id,caller.subjectId());
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"case.task.completed",
                "caseInstance",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("taskId",taskId,"version",row.version()+1)));
        Instance response=instance(ws,id,access);
        saveResponse(claim,id,groups,response);
        idempotency.complete(claim,"caseInstance",id.toString(),200);
        return new Result<>(response,false);
    }

    private void saveResponse(IdempotencyService.Claim claim, UUID caseId, List<String> candidateGroups, Instance response) {
        jdbc.sql("""
                INSERT INTO case_command_response(workspace_id,actor_subject_id,operation,idem_key,case_id,candidate_groups,response)
                VALUES(:ws,:actor,:operation,:key,:caseId,CAST(:groups AS jsonb),CAST(:response AS jsonb))
                """).param("ws",claim.workspaceId()).param("actor",claim.principalId())
                .param("operation",claim.operation()).param("key",claim.key()).param("caseId",caseId)
                .param("groups",json.write(candidateGroups)).param("response",json.write(response)).update();
    }

    /** Termination is distinct from successful completion and is serialized with task decisions. */
    @Transactional
    public Result<Instance> terminate(Caller caller, UUID ws, UUID id, String key, TerminateCase request) {
        var access = access(caller, ws, Permission.RUN_OPERATE, "terminateCaseInstance");
        if (request == null || request.expectedVersion() < 1 || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 1000)
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Supply the current case version and a reason of 1 to 1000 characters.");
        var row = instanceRow(ws, id, true);
        String operation = "terminateCaseInstance";
        var claim = idempotency.claim(ws, caller.subjectId(), operation, IdempotencyService.requireKey(key),
                idempotency.requestHash(operation, Map.of("caseId", id.toString()), request));
        if (claim.isReplay()) return new Result<>(replayed(claim, access), true);
        if (row.version() != request.expectedVersion()) throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT);
        if (!Set.of("active","suspended").contains(row.status())) throw new ApiException(ProblemCode.TASK_NOT_OPEN);
        runtime.terminateCaseInstance(row.engineInstanceId());
        cancelWorkflowRuns(ws,id,null,"The case was terminated.");
        jdbc.sql("UPDATE case_instance SET version=version+1,status='terminated',completed_at=now() WHERE workspace_id=:ws AND id=:id")
                .param("ws", ws).param("id", id).update();
        // The reason is retained separately under run-read-data authorization, never in audit metadata.
        jdbc.sql("INSERT INTO case_termination(workspace_id,case_id,reason,terminated_by) VALUES(:ws,:id,:reason,:actor)")
                .param("ws", ws).param("id", id).param("reason", request.reason().strip()).param("actor", caller.subjectId()).update();
        appendEvent(ws,id,row.version()+1,"terminated",null,row.status(),"terminated",caller,request.reason().strip());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "case.instance.terminated",
                "caseInstance", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("version", row.version() + 1)));
        Instance response = instance(ws, id, access);
        saveResponse(claim, id, List.of(), response);
        idempotency.complete(claim, "caseInstance", id.toString(), 200);
        return new Result<>(response, false);
    }

    private Map<String,Set<String>> humanGroups(InstanceRow row,UUID ws) {
        if(groupsByDefinition.size()>=200 && !groupsByDefinition.containsKey(row.definitionId())) groupsByDefinition.clear();
        return groupsByDefinition.computeIfAbsent(row.definitionId(),ignored -> {
            String source=jdbc.sql("SELECT source_xml FROM case_definition WHERE workspace_id=:ws AND id=:id")
                    .param("ws",ws).param("id",row.definitionId()).query(String.class).single();
            return validator.humanTaskGroups(source);
        });
    }

    private Map<String,Set<String>> userEventGroups(InstanceRow row,UUID ws) {
        if(eventGroupsByDefinition.size()>=200 && !eventGroupsByDefinition.containsKey(row.definitionId())) eventGroupsByDefinition.clear();
        return eventGroupsByDefinition.computeIfAbsent(row.definitionId(),ignored -> {
            String source=jdbc.sql("SELECT source_xml FROM case_definition WHERE workspace_id=:ws AND id=:id")
                    .param("ws",ws).param("id",row.definitionId()).query(String.class).single();
            return validator.userEventGroups(source);
        });
    }

    private boolean repeated(InstanceRow row,String elementId,UUID ws) {
        if(repeatedByDefinition.size()>=200 && !repeatedByDefinition.containsKey(row.definitionId())) repeatedByDefinition.clear();
        return repeatedByDefinition.computeIfAbsent(row.definitionId(),ignored -> {
            String source=jdbc.sql("SELECT source_xml FROM case_definition WHERE workspace_id=:ws AND id=:id")
                    .param("ws",ws).param("id",row.definitionId()).query(String.class).single();
            return validator.repeatedPlanItems(source);
        }).contains(elementId);
    }

    private boolean required(InstanceRow row,String elementId,UUID ws) {
        if(requiredByDefinition.size()>=200 && !requiredByDefinition.containsKey(row.definitionId())) requiredByDefinition.clear();
        return requiredByDefinition.computeIfAbsent(row.definitionId(),ignored -> {
            String source=jdbc.sql("SELECT source_xml FROM case_definition WHERE workspace_id=:ws AND id=:id")
                    .param("ws",ws).param("id",row.definitionId()).query(String.class).single();
            return validator.requiredPlanItems(source);
        }).contains(elementId);
    }

    private Map<String,Boolean> actions(InstanceRow row,String itemId,WorkspaceAccess access) {
        if(!row.status().equals("active")) return Map.of();
        var item=runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).planItemInstanceId(itemId).singleResult();
        if(item==null) return Map.of();
        boolean human=org.flowable.cmmn.api.runtime.PlanItemDefinitionType.HUMAN_TASK.equals(item.getPlanItemDefinitionType());
        boolean worker=human && access.has(Permission.TASK_ACT) && humanGroups(row,access.workspaceId())
                .getOrDefault(item.getPlanItemDefinitionId(),Set.of()).stream().anyMatch(access.groupKeys()::contains);
        boolean eventWorker=org.flowable.cmmn.api.runtime.PlanItemDefinitionType.USER_EVENT_LISTENER.equals(item.getPlanItemDefinitionType())
                && access.has(Permission.TASK_ACT) && userEventGroups(row,access.workspaceId()).getOrDefault(item.getPlanItemDefinitionId(),Set.of()).stream().anyMatch(access.groupKeys()::contains);
        boolean operator=access.has(Permission.RUN_OPERATE);
        boolean planner=discretionary(row,access.workspaceId()).containsKey(item.getPlanItemDefinitionId());
        boolean workflow=callTaskIds(row,access.workspaceId()).contains(item.getPlanItemDefinitionId());
        String latest=workflow?latestStatus(access.workspaceId(),itemId):null;
        var result=new java.util.LinkedHashMap<String,Boolean>();
        result.put("occur",eventWorker && !planner && "available".equals(item.getState()));
        result.put("plan",eventWorker && planner && "available".equals(item.getState()));
        result.put("start",worker && "enabled".equals(item.getState()));
        result.put("enable",worker && "disabled".equals(item.getState()));
        result.put("disable",worker && "enabled".equals(item.getState()) && !required(row,item.getElementId(),access.workspaceId()));
        result.put("activateStage",operator && item.isStage() && "enabled".equals(item.getState()));
        result.put("completeStage",operator && item.isStage() && "active".equals(item.getState()) && closable(row,access.workspaceId(),itemId));
        result.put("terminate",operator && (human || workflow || item.isStage()) && !repeated(row,item.getElementId(),access.workspaceId()) && Set.of("active","enabled","disabled","available").contains(item.getState()));
        result.put("retryWorkflow",operator && workflow && "active".equals(item.getState()) && Set.of("failed","cancelled").contains(String.valueOf(latest)) && latestAttempt(access.workspaceId(),itemId)<20);
        return Map.copyOf(result);
    }

    private Map<String,CmmnSubsetValidator.Discretionary> discretionary(InstanceRow row,UUID ws) {
        if(discretionaryByDefinition.size()>=200 && !discretionaryByDefinition.containsKey(row.definitionId())) discretionaryByDefinition.clear();
        return discretionaryByDefinition.computeIfAbsent(row.definitionId(),ignored -> validator.discretionaryItems(source(ws,row.definitionId())));
    }

    private String source(UUID ws,UUID definitionId) {
        return jdbc.sql("SELECT source_xml FROM case_definition WHERE workspace_id=:ws AND id=:id")
                .param("ws",ws).param("id",definitionId).query(String.class).single();
    }

    private Set<String> workflowTaskIds(InstanceRow row,UUID ws) {
        return Set.copyOf(jdbc.sql("SELECT task_id FROM case_definition_workflow WHERE workspace_id=:ws AND definition_id=:id")
                .param("ws",ws).param("id",row.definitionId()).query(String.class).list());
    }

    private Set<String> childTaskIds(InstanceRow row,UUID ws) {
        return Set.copyOf(jdbc.sql("SELECT task_id FROM case_definition_child WHERE workspace_id=:ws AND definition_id=:id")
                .param("ws",ws).param("id",row.definitionId()).query(String.class).list());
    }

    private Set<String> decisionTaskIds(InstanceRow row,UUID ws) {
        return Set.copyOf(jdbc.sql("SELECT task_id FROM case_definition_decision WHERE workspace_id=:ws AND definition_id=:id")
                .param("ws",ws).param("id",row.definitionId()).query(String.class).list());
    }

    /** Workflow, case and decision tasks: blocking tasks Orqix drives by starting a run, starting a child case or evaluating a table. */
    private Set<String> callTaskIds(InstanceRow row,UUID ws) {
        var all=new java.util.HashSet<>(workflowTaskIds(row,ws)); all.addAll(childTaskIds(row,ws)); all.addAll(decisionTaskIds(row,ws)); return Set.copyOf(all);
    }

    // ---------------------------------------------------------------- workflow tasks (WM04, S110)

    private int latestAttempt(UUID ws,String planItemInstanceId) {
        return jdbc.sql("SELECT coalesce(max(attempt),0) FROM case_workflow_task WHERE workspace_id=:ws AND plan_item_instance_id=:item")
                .param("ws",ws).param("item",planItemInstanceId).query(Integer.class).single();
    }

    private String latestStatus(UUID ws,String planItemInstanceId) {
        return jdbc.sql("SELECT status FROM case_workflow_task WHERE workspace_id=:ws AND plan_item_instance_id=:item ORDER BY attempt DESC LIMIT 1")
                .param("ws",ws).param("item",planItemInstanceId).query(String.class).optional().orElse(null);
    }

    private Map<String,WorkflowStatus> workflowStatuses(UUID ws,UUID caseId) {
        var out=new java.util.HashMap<String,WorkflowStatus>();
        jdbc.sql("""
                SELECT DISTINCT ON (t.plan_item_instance_id) t.plan_item_instance_id,t.attempt,t.status,t.run_id,r.reference,t.outcome,t.detail,t.child_case_id
                FROM case_workflow_task t LEFT JOIN workflow_run r ON r.workspace_id=t.workspace_id AND r.id=t.run_id
                WHERE t.workspace_id=:ws AND t.case_id=:id ORDER BY t.plan_item_instance_id,t.attempt DESC
                """).param("ws",ws).param("id",caseId).query((rs,n)->Map.entry(rs.getString(1),new WorkflowStatus(rs.getInt("attempt"),
                        rs.getString("status"),Sql.uuid(rs,"run_id"),rs.getString("reference"),rs.getString("outcome"),rs.getString("detail"),Sql.uuid(rs,"child_case_id"))))
                .list().forEach(e->out.put(e.getKey(),e.getValue()));
        return out;
    }

    /** Appends a case event caused by the platform (a workflow start or result) and returns its version. */
    private int systemEvent(UUID ws,UUID caseId,String action,String itemId,UUID actor,String reason) {
        var current=jdbc.sql("UPDATE case_instance SET version=version+1 WHERE workspace_id=:ws AND id=:id RETURNING version,status")
                .param("ws",ws).param("id",caseId).query((rs,n)->Map.entry(rs.getInt(1),rs.getString(2))).single();
        jdbc.sql("""
                INSERT INTO case_event(workspace_id,case_id,version,action,plan_item_id,from_status,to_status,actor_subject_id,reason)
                VALUES(:ws,:id,:version,:action,:item,:status,:status,:actor,:reason)
                """).param("ws",ws).param("id",caseId).param("version",current.getKey()).param("action",action).param("item",itemId)
                .param("status",current.getValue()).param("actor",actor).param("reason",reason==null?null:reason.length()>1000?reason.substring(0,1000):reason).update();
        return current.getKey();
    }

    /**
     * Starts the pinned workflow of every active workflow task that has no attempt yet, and cancels runs whose task is no
     * longer active (a stage exit or termination). The caller holds the case lock.
     */
    private void dispatchWorkflows(UUID ws,UUID caseId,UUID actor) {
        // A decision completes at once and may release more work, so dispatch repeats (bounded) until nothing new starts.
        for(int round=0;round<40;round++) if(!dispatchOnce(ws,caseId,actor)) return;
    }

    private boolean dispatchOnce(UUID ws,UUID caseId,UUID actor) {
        var row=instanceRow(ws,caseId,false);
        if(!row.status().equals("active")) return false;
        var taskIds=callTaskIds(row,ws);
        if(taskIds.isEmpty()) return false;
        var decisionIds=decisionTaskIds(row,ws);
        boolean again=false;
        var active=new java.util.HashMap<String,String>();
        for(var item:runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).planItemInstanceStateActive().list())
            if(taskIds.contains(item.getPlanItemDefinitionId())) active.put(item.getId(),item.getPlanItemDefinitionId());
        for(var running:jdbc.sql("SELECT plan_item_instance_id FROM case_workflow_task WHERE workspace_id=:ws AND case_id=:id AND status='running'")
                .param("ws",ws).param("id",caseId).query(String.class).list())
            if(!active.containsKey(running)) cancelWorkflowRuns(ws,caseId,running,"The case task that started this request has ended.");
        for(var entry:new java.util.TreeMap<>(active).entrySet())
            if(latestAttempt(ws,entry.getKey())==0) {
                startWorkflowAttempt(ws,caseId,entry.getKey(),entry.getValue(),1,actor);
                if(decisionIds.contains(entry.getValue())) again=true;
            }
        return again;
    }

    private void startWorkflowAttempt(UUID ws,UUID caseId,String planItemInstanceId,String taskId,int attempt,UUID actor) {
        var row=instanceRow(ws,caseId,false);
        if(childTaskIds(row,ws).contains(taskId)) { startChildAttempt(ws,row,planItemInstanceId,taskId,attempt,actor); return; }
        if(decisionTaskIds(row,ws).contains(taskId)) { evaluateDecision(ws,row,planItemInstanceId,taskId,attempt,actor); return; }
        var pin=pins(ws,row.definitionId()).stream().filter(p->p.taskId().equals(taskId)).findFirst().orElseThrow();
        var data=json.parse(jdbc.sql("SELECT data_values::text FROM case_instance WHERE workspace_id=:ws AND id=:id")
                .param("ws",ws).param("id",caseId).query(String.class).single());
        var input=json.object();
        pin.inputMapping().forEach((field,source)->{ var value=data.get(source); if(value!=null && !value.isNull()) input.set(field,value); });
        UUID id=Ids.newId();
        try {
            var launched=workflowRuns.start(ws,pin.releaseId(),row.startedBy(),input,caseId,planItemInstanceId);
            jdbc.sql("INSERT INTO case_workflow_task(id,workspace_id,case_id,plan_item_instance_id,task_id,attempt,run_id,status) VALUES(:id,:ws,:case,:item,:task,:attempt,:run,'running')")
                    .param("id",id).param("ws",ws).param("case",caseId).param("item",planItemInstanceId).param("task",taskId)
                    .param("attempt",attempt).param("run",launched.runId()).update();
            systemEvent(ws,caseId,"workflowStarted",planItemInstanceId,actor,null);
        } catch(CaseWorkflowRuns.NotStarted ex) {
            jdbc.sql("INSERT INTO case_workflow_task(id,workspace_id,case_id,plan_item_instance_id,task_id,attempt,status,detail,ended_at) VALUES(:id,:ws,:case,:item,:task,:attempt,'failed',:detail,now())")
                    .param("id",id).param("ws",ws).param("case",caseId).param("item",planItemInstanceId).param("task",taskId)
                    .param("attempt",attempt).param("detail",truncate(ex.getMessage())).update();
            systemEvent(ws,caseId,"workflowNotStarted",planItemInstanceId,actor,null);
        }
    }

    /** Starts the pinned child case of an active case task, on behalf of the parent's starter. */
    private void startChildAttempt(UUID ws,InstanceRow parent,String planItemInstanceId,String taskId,int attempt,UUID actor) {
        var pin=childPins(ws,parent.definitionId()).stream().filter(p->p.taskId().equals(taskId)).findFirst().orElseThrow();
        var data=json.parse(jdbc.sql("SELECT data_values::text FROM case_instance WHERE workspace_id=:ws AND id=:id")
                .param("ws",ws).param("id",parent.id()).query(String.class).single());
        int depth=jdbc.sql("SELECT depth FROM case_instance WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",parent.id()).query(Integer.class).single();
        var input=json.object();
        pin.inputMapping().forEach((field,source)->{ var value=data.get(source); if(value!=null && !value.isNull()) input.set(field,value); });
        UUID taskRow=Ids.newId();
        String problem=null;
        tools.jackson.databind.JsonNode childData=null;
        var child=definition(ws,pin.childDefinitionId());
        if(depth>=3) problem="Cases can be nested at most three levels deep.";
        else try { childData=dataModel.values(child.dataFields(),input); }
        catch(ApiException ex) { problem="The case data mapped to \""+child.name()+"\" does not fit it: "+ex.getMessage(); }
        if(problem!=null) {
            jdbc.sql("INSERT INTO case_workflow_task(id,workspace_id,case_id,plan_item_instance_id,task_id,attempt,status,detail,ended_at) VALUES(:id,:ws,:case,:item,:task,:attempt,'failed',:detail,now())")
                    .param("id",taskRow).param("ws",ws).param("case",parent.id()).param("item",planItemInstanceId).param("task",taskId)
                    .param("attempt",attempt).param("detail",truncate(problem)).update();
            systemEvent(ws,parent.id(),"childCaseNotStarted",planItemInstanceId,actor,null);
            return;
        }
        UUID id=Ids.newId();
        String tenant=jdbc.sql("SELECT engine_tenant_id FROM workspace WHERE id=:ws").param("ws",ws).query(String.class).single();
        var engine=runtime.createCaseInstanceBuilder().caseDefinitionId(child.engineDefinitionId()).tenantId(tenant).businessKey(id.toString())
                .variables(conditionVariables(ws,child.id(),childData)).start();
        boolean active=runtime.createCaseInstanceQuery().caseInstanceId(engine.getId()).singleResult()!=null;
        jdbc.sql("""
                INSERT INTO case_instance(id,workspace_id,definition_id,engine_instance_id,status,started_by,completed_at,data_values,parent_case_id,parent_plan_item_id,depth)
                VALUES(:id,:ws,:definition,:engine,:status,:actor,CASE WHEN :active THEN NULL ELSE now() END,CAST(:data AS jsonb),:parent,:item,:depth)
                """).param("id",id).param("ws",ws).param("definition",child.id()).param("engine",engine.getId()).param("status",active?"active":"completed")
                .param("actor",parent.startedBy()).param("active",active).param("data",json.write(childData)).param("parent",parent.id())
                .param("item",planItemInstanceId).param("depth",depth+1).update();
        appendEvent(ws,id,1,"started",null,null,active?"active":"completed",parent.startedBy(),null);
        appendDataEvent(ws,id,1,json.object(),childData);
        fireFileTriggers(ws,id,json.object(),childData,parent.startedBy());
        jdbc.sql("INSERT INTO case_workflow_task(id,workspace_id,case_id,plan_item_instance_id,task_id,attempt,child_case_id,status) VALUES(:id,:ws,:case,:item,:task,:attempt,:child,'running')")
                .param("id",taskRow).param("ws",ws).param("case",parent.id()).param("item",planItemInstanceId).param("task",taskId)
                .param("attempt",attempt).param("child",id).update();
        systemEvent(ws,parent.id(),"childCaseStarted",planItemInstanceId,actor,null);
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.SYSTEM,null,"case.instance.started","caseInstance",id.toString(),
                AuditWriter.Outcome.SUCCESS,Map.of("definitionId",child.id().toString(),"version",child.version())));
        if(active) dispatchWorkflows(ws,id,parent.startedBy());
    }

    /** Evaluates the pinned decision table of an active decision task at once and writes its result into case data. */
    private void evaluateDecision(UUID ws,InstanceRow row,String planItemInstanceId,String taskId,int attempt,UUID actor) {
        var pin=decisionPins(ws,row.definitionId()).stream().filter(p->p.taskId().equals(taskId)).findFirst().orElseThrow();
        var table=(tools.jackson.databind.node.ObjectNode)json.parse(jdbc.sql("SELECT content::text FROM decision_asset_version WHERE asset_id=:a AND version=:v")
                .param("a",pin.assetId()).param("v",pin.decisionVersion()).query(String.class).single());
        table.put("key","case-decision");
        if(!table.has("version")) table.put("version",1);
        var definition=json.object(); definition.putArray("decisions").add(table);
        var before=json.parse(jdbc.sql("SELECT data_values::text FROM case_instance WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",row.id()).query(String.class).single());
        var values=new java.util.HashMap<String,Object>();
        values.put("$asOf",java.time.LocalDate.now());
        String problem=null;
        for(var e:pin.inputMapping().entrySet()) {
            var value=before.get(e.getValue());
            if(value==null || value.isNull()) { problem="Case data field "+e.getValue()+" has no value for the decision."; break; }
            values.put(e.getKey(),value.isNumber()?value.decimalValue():value.isBoolean()?(Object)value.asBoolean():value.asString());
        }
        com.orqix.compiler.DecisionEvaluator.Result result=null;
        tools.jackson.databind.JsonNode after=null;
        if(problem==null) try {
            result=decisions.evaluate(json.write(definition),"case-decision",values);
            var proposed=(tools.jackson.databind.node.ObjectNode)before.deepCopy();
            for(var e:pin.resultMapping().entrySet()) {
                Object out=result.output().get(e.getValue());
                if(out!=null) proposed.set(e.getKey(),json.parse(json.write(out)));
            }
            after=dataModel.values(definition(ws,row.definitionId()).dataFields(),proposed);
        } catch(IllegalArgumentException ex) { problem="The decision could not be evaluated: "+ex.getMessage(); }
        catch(ApiException ex) { problem="The decision result does not fit the case data: "+ex.getMessage(); }
        UUID taskRow=Ids.newId();
        if(problem!=null) {
            jdbc.sql("INSERT INTO case_workflow_task(id,workspace_id,case_id,plan_item_instance_id,task_id,attempt,status,detail,ended_at) VALUES(:id,:ws,:case,:item,:task,:attempt,'failed',:detail,now())")
                    .param("id",taskRow).param("ws",ws).param("case",row.id()).param("item",planItemInstanceId).param("task",taskId)
                    .param("attempt",attempt).param("detail",truncate(problem)).update();
            systemEvent(ws,row.id(),"decisionNotEvaluated",planItemInstanceId,actor,null);
            return;
        }
        String outcome=result.usedDefault()?"Default result":"Row "+(result.matchedRow()+1);
        int version=systemEvent(ws,row.id(),"decisionEvaluated",planItemInstanceId,actor,null);
        if(!after.equals(before)) {
            jdbc.sql("UPDATE case_instance SET data_values=CAST(:data AS jsonb) WHERE workspace_id=:ws AND id=:id").param("data",json.write(after)).param("ws",ws).param("id",row.id()).update();
            appendDataEvent(ws,row.id(),version,before,after);
            var variables=conditionVariables(ws,row.definitionId(),after);
            if(!variables.isEmpty()) runtime.setVariables(row.engineInstanceId(),variables);
        }
        jdbc.sql("INSERT INTO case_workflow_task(id,workspace_id,case_id,plan_item_instance_id,task_id,attempt,decision_asset_id,status,outcome,ended_at) VALUES(:id,:ws,:case,:item,:task,:attempt,:asset,'completed',:outcome,now())")
                .param("id",taskRow).param("ws",ws).param("case",row.id()).param("item",planItemInstanceId).param("task",taskId)
                .param("attempt",attempt).param("asset",pin.assetId()).param("outcome",outcome).update();
        runtime.triggerPlanItemInstance(planItemInstanceId);
        boolean alive=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null;
        if(alive) runtime.evaluateCriteria(row.engineInstanceId());
        else jdbc.sql("UPDATE case_instance SET status='completed',completed_at=now() WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",row.id()).update();
        if(alive && !after.equals(before)) fireFileTriggers(ws,row.id(),before,after,actor);
    }

    /** Ends a child case (and what it started) because its parent task or case ended. */
    private void terminateChildCase(UUID ws,UUID childId,String reason) {
        var child=instanceRow(ws,childId,true);
        if(!Set.of("active","suspended").contains(child.status())) return;
        if(runtime.createCaseInstanceQuery().caseInstanceId(child.engineInstanceId()).singleResult()!=null) runtime.terminateCaseInstance(child.engineInstanceId());
        cancelWorkflowRuns(ws,childId,null,reason);
        jdbc.sql("UPDATE case_instance SET version=version+1,status='terminated',completed_at=now() WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",childId).update();
        jdbc.sql("INSERT INTO case_termination(workspace_id,case_id,reason,terminated_by) VALUES(:ws,:id,:reason,:actor)")
                .param("ws",ws).param("id",childId).param("reason",reason).param("actor",child.startedBy()).update();
        appendEvent(ws,childId,child.version()+1,"terminated",null,child.status(),"terminated",child.startedBy(),reason);
    }

    // ---------------------------------------------------------------- timers (S113)

    @Transactional(readOnly=true)
    public List<UUID[]> casesWithTimers(int limit) {
        return jdbc.sql("""
                SELECT i.workspace_id,i.id FROM case_instance i JOIN case_definition d ON d.workspace_id=i.workspace_id AND d.id=i.definition_id
                WHERE d.has_timers AND i.status='active' ORDER BY i.created_at LIMIT :limit
                """).param("limit",limit).query((rs,n)->new UUID[]{Sql.uuid(rs,"workspace_id"),Sql.uuid(rs,"id")}).list();
    }

    /**
     * Records timer occurrences the engine fired on its own (in its job executor, outside any Orqix command) in the
     * case history, then starts the work they released. Each occurrence is recorded once.
     */
    @Transactional
    public int syncTimers(UUID ws,UUID caseId) {
        var row=instanceRow(ws,caseId,true);
        if(!row.status().equals("active")) return 0;
        var recorded=new java.util.HashSet<>(jdbc.sql("SELECT plan_item_id FROM case_event WHERE workspace_id=:ws AND case_id=:id AND action='timerOccurred'")
                .param("ws",ws).param("id",caseId).query(String.class).list());
        int added=0;
        for(var item:history.createHistoricPlanItemInstanceQuery().planItemInstanceCaseInstanceId(row.engineInstanceId()).list()) {
            if(!"timereventlistener".equalsIgnoreCase(item.getPlanItemDefinitionType()) || !"completed".equals(item.getState()) || recorded.contains(item.getId())) continue;
            systemEvent(ws,caseId,"timerOccurred",item.getId(),row.startedBy(),null);
            added++;
        }
        if(added==0) return 0;
        if(runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()==null)
            jdbc.sql("UPDATE case_instance SET status='completed',completed_at=now() WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",caseId).update();
        else dispatchWorkflows(ws,caseId,row.startedBy());
        return added;
    }

    private static String truncate(String value) { return value==null?null:value.length()>1000?value.substring(0,1000):value; }

    /** Cancels running workflows of one plan item (or of the whole case when planItemInstanceId is null). */
    private void cancelWorkflowRuns(UUID ws,UUID caseId,String planItemInstanceId,String reason) {
        var running=jdbc.sql("SELECT id,run_id,child_case_id FROM case_workflow_task WHERE workspace_id=:ws AND case_id=:id AND status='running'"
                        +(planItemInstanceId==null?"":" AND plan_item_instance_id=:item")+" FOR UPDATE")
                .param("ws",ws).param("id",caseId);
        if(planItemInstanceId!=null) running=running.param("item",planItemInstanceId);
        for(var task:running.query((rs,n)->new UUID[]{Sql.uuid(rs,"id"),Sql.uuid(rs,"run_id"),Sql.uuid(rs,"child_case_id")}).list()) {
            if(task[2]!=null) terminateChildCase(ws,task[2],reason); else workflowRuns.cancel(ws,task[1],reason);
            jdbc.sql("UPDATE case_workflow_task SET status='cancelled',detail=:detail,ended_at=now() WHERE id=:id")
                    .param("id",task[0]).param("detail",reason).update();
        }
    }

    /**
     * Applies the results of workflow runs that ended (called by {@link CaseWorkflowReconciler}). A result completes its
     * task only while the case is active and the task is still active; otherwise it is fenced and never applied. A held
     * case keeps the result until it resumes.
     */
    @Transactional(readOnly=true)
    public List<UUID> runningWorkflowTasks(int limit) {
        return jdbc.sql("SELECT id FROM case_workflow_task WHERE status='running' ORDER BY started_at LIMIT :limit")
                .param("limit",limit).query(UUID.class).list();
    }

    /** One task per transaction; the case is locked before the task row, in the same order as case commands. */
    @Transactional
    public boolean reconcileWorkflowTask(UUID workflowTaskId) {
        var candidate=jdbc.sql("SELECT workspace_id,case_id FROM case_workflow_task WHERE id=:id AND status='running'")
                .param("id",workflowTaskId).query((rs,n)->new UUID[]{Sql.uuid(rs,"workspace_id"),Sql.uuid(rs,"case_id")}).optional();
        if(candidate.isEmpty()) return false;
        instanceRow(candidate.get()[0],candidate.get()[1],true);
        var pending=jdbc.sql("""
                SELECT id,workspace_id,case_id,plan_item_instance_id,task_id,run_id,child_case_id FROM case_workflow_task
                WHERE id=:id AND status='running' FOR UPDATE
                """).param("id",workflowTaskId).query((rs,n)->new Object[]{Sql.uuid(rs,"id"),Sql.uuid(rs,"workspace_id"),Sql.uuid(rs,"case_id"),
                        rs.getString("plan_item_instance_id"),rs.getString("task_id"),Sql.uuid(rs,"run_id"),Sql.uuid(rs,"child_case_id")}).list();
        boolean applied=false;
        for(var task:pending) {
            UUID taskRow=(UUID)task[0],ws=(UUID)task[1],caseId=(UUID)task[2],runId=(UUID)task[5],childId=(UUID)task[6];
            String itemId=(String)task[3];
            var row=instanceRow(ws,caseId,true);
            var state=childId!=null?childState(ws,childId):workflowRuns.state(ws,runId).orElse(null);
            if(state==null || state.active() || row.status().equals("suspended")) continue;
            var item=runtime.createPlanItemInstanceQuery().planItemInstanceId(itemId).singleResult();
            boolean itemActive=item!=null && "active".equals(item.getState());
            if(!row.status().equals("active") || !itemActive) {
                jdbc.sql("UPDATE case_workflow_task SET status='fenced',outcome=:outcome,detail='The result arrived after the case task had ended and was not applied.',ended_at=now() WHERE id=:id")
                        .param("id",taskRow).param("outcome",state.outcome()).update();
                if(row.status().equals("active")) systemEvent(ws,caseId,"workflowResultFenced",itemId,row.startedBy(),null);
                applied=true;
                continue;
            }
            if(!"completed".equals(state.lifecycle())) {
                jdbc.sql("UPDATE case_workflow_task SET status='cancelled',detail=:detail,ended_at=now() WHERE id=:id")
                        .param("id",taskRow).param("detail",childId!=null?"The child case was terminated before it finished.":"The workflow request was cancelled before it finished.").update();
                systemEvent(ws,caseId,childId!=null?"childCaseTerminated":"workflowCancelled",itemId,row.startedBy(),null);
                applied=true;
                continue;
            }
            Map<String,String> resultMapping=childId!=null
                    ?childPins(ws,row.definitionId()).stream().filter(p->p.taskId().equals(task[4])).findFirst().orElseThrow().resultMapping()
                    :pins(ws,row.definitionId()).stream().filter(p->p.taskId().equals(task[4])).findFirst().orElseThrow().resultMapping();
            var before=json.parse(jdbc.sql("SELECT data_values::text FROM case_instance WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",caseId).query(String.class).single());
            var proposed=(tools.jackson.databind.node.ObjectNode)before.deepCopy();
            resultMapping.forEach((field,source)->{
                tools.jackson.databind.JsonNode value=switch(source) {
                    case "outcome" -> state.outcome()==null?null:tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(state.outcome());
                    case "outcomeKind" -> state.outcomeKind()==null?null:tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(state.outcomeKind());
                    case "reference" -> tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(state.reference());
                    default -> state.output()==null?null:state.output().get(source);
                };
                if(value!=null && !value.isNull()) proposed.set(field,value);
            });
            tools.jackson.databind.JsonNode values;
            try { values=dataModel.values(definition(ws,row.definitionId()).dataFields(),proposed); }
            catch(ApiException ex) {
                jdbc.sql("UPDATE case_workflow_task SET status='failed',outcome=:outcome,detail=:detail,ended_at=now() WHERE id=:id")
                        .param("id",taskRow).param("outcome",state.outcome()).param("detail",truncate("The workflow result does not fit the case data: "+ex.getMessage())).update();
                systemEvent(ws,caseId,"workflowResultRejected",itemId,row.startedBy(),null);
                applied=true;
                continue;
            }
            int version=systemEvent(ws,caseId,childId!=null?"childCaseCompleted":"workflowCompleted",itemId,row.startedBy(),null);
            if(!values.equals(before)) {
                jdbc.sql("UPDATE case_instance SET data_values=CAST(:data AS jsonb) WHERE workspace_id=:ws AND id=:id")
                        .param("data",json.write(values)).param("ws",ws).param("id",caseId).update();
                appendDataEvent(ws,caseId,version,before,values);
            }
            jdbc.sql("UPDATE case_workflow_task SET status='completed',outcome=:outcome,ended_at=now() WHERE id=:id")
                    .param("id",taskRow).param("outcome",state.outcome()).update();
            if(!values.equals(before)) {
                var variables=conditionVariables(ws,row.definitionId(),values);
                if(!variables.isEmpty()) runtime.setVariables(row.engineInstanceId(),variables);
            }
            runtime.triggerPlanItemInstance(itemId);
            if(runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null) runtime.evaluateCriteria(row.engineInstanceId());
            if(!values.equals(before)) fireFileTriggers(ws,caseId,before,values,row.startedBy());
            boolean caseActive=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null;
            if(!caseActive) jdbc.sql("UPDATE case_instance SET status='completed',completed_at=now() WHERE workspace_id=:ws AND id=:id")
                    .param("ws",ws).param("id",caseId).update();
            else dispatchWorkflows(ws,caseId,row.startedBy());
            audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.SYSTEM,null,"case.workflow.completed","caseInstance",caseId.toString(),
                    AuditWriter.Outcome.SUCCESS,Map.of("planItemId",itemId,"runId",(childId!=null?childId:runId).toString(),"version",version)));
            applied=true;
        }
        return applied;
    }

    /** A child case seen like a run: active while it works; completed with its data as the result; terminated as cancelled. */
    private CaseWorkflowRuns.State childState(UUID ws,UUID childId) {
        return jdbc.sql("SELECT status,data_values::text AS data FROM case_instance WHERE workspace_id=:ws AND id=:id")
                .param("ws",ws).param("id",childId).query((rs,n)->{
                    String status=rs.getString("status");
                    boolean done="completed".equals(status);
                    return new CaseWorkflowRuns.State(childId,null,done?"completed":"terminated".equals(status)?"cancelled":status,
                            Set.of("active","suspended").contains(status),done?"Completed":null,done?"success":null,json.parse(rs.getString("data")));
                }).optional().orElse(null);
    }

    /** Plan item states that are waiting rather than working; CMMN lets a plan with autoComplete=false complete over them. */
    private static final Set<String> WAITING=Set.of("available","wait_repetition","unavailable","disabled");
    private static final Set<String> ENDED=Set.of("completed","terminated","failed","closed","occurred");

    /**
     * Whether the case (stageId null) or a stage may be completed: no required work is unresolved and nothing in it is
     * working. Waiting items do not block: the engine's own completable flag counts them, CMMN 1.1 (8.6.1) does not.
     */
    private boolean closable(InstanceRow row,UUID ws,String stageId) {
        if(unresolvedRequired(row,ws,stageId)) return false;
        if(stageId==null) {
            var engine=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult();
            if(engine!=null && engine.isCompletable()) return true;
        } else {
            var stage=runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).planItemInstanceId(stageId).singleResult();
            if(stage!=null && stage.isCompletable()) return true;
        }
        var items=runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).list();
        var parents=new java.util.HashMap<String,String>();
        for(var item:items) parents.put(item.getId(),item.getStageInstanceId());
        for(var item:items) {
            if(item.getId().equals(stageId) || WAITING.contains(item.getState()) || ENDED.contains(item.getState())) continue;
            if(stageId==null) return false;
            for(String parent=item.getStageInstanceId();parent!=null;parent=parents.get(parent)) if(parent.equals(stageId)) return false;
        }
        return true;
    }

    /** Required flags on repeated manual items are not fully reflected by the engine's completable flag. */
    private boolean unresolvedRequired(InstanceRow row,UUID ws,String stageId) {
        var items=runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).list();
        var parents=new java.util.HashMap<String,String>();
        for(var item:items) parents.put(item.getId(),item.getStageInstanceId());
        // A required discretionary item binds only once it is planned; until then it waits, available, on its planning event.
        var unplanned=discretionary(row,ws).values().stream().map(CmmnSubsetValidator.Discretionary::id).collect(java.util.stream.Collectors.toSet());
        for(var item:items) {
            if(Set.of("completed","terminated","disabled","failed").contains(item.getState()) || !required(row,item.getElementId(),ws)) continue;
            if("available".equals(item.getState()) && unplanned.contains(item.getElementId())) continue;
            if(stageId==null) return true;
            var parent=item.getStageInstanceId();
            for(int depth=0;parent!=null && depth<6;depth++) {
                if(stageId.equals(parent)) return true;
                parent=parents.get(parent);
            }
        }
        return false;
    }

    private static void requireActive(InstanceRow row) {
        if(row.status().equals("suspended")) throw new ApiException(ProblemCode.RUN_SUSPENDED,"The case is on hold. Resume it before changing its work.");
        if(!row.status().equals("active")) throw new ApiException(ProblemCode.TASK_NOT_OPEN);
    }
    private static void validateCommand(CaseCommand request) {
        if(request==null || request.expectedVersion()<1 || request.reason()==null || request.reason().isBlank() || request.reason().length()>1000)
            throw new ApiException(ProblemCode.VALIDATION_FAILED,"Supply the current case version and a reason of 1 to 1000 characters.");
    }

    /** A hold freezes every command in the admitted synchronous subset; no engine suspension API is implied. */
    @Transactional
    public Result<Instance> lifecycle(Caller caller,UUID ws,UUID id,String action,String key,CaseCommand request) {
        String operation=switch(action) {
            case "suspend" -> "suspendCaseInstance";
            case "resume" -> "resumeCaseInstance";
            case "close" -> "closeCaseInstance";
            default -> throw ApiException.notFound();
        };
        var access=access(caller,ws,Permission.RUN_OPERATE,operation); validateCommand(request);
        var row=instanceRow(ws,id,true);
        var claim=idempotency.claim(ws,caller.subjectId(),operation,IdempotencyService.requireKey(key),
                idempotency.requestHash(operation,Map.of("caseId",id.toString()),request));
        if(claim.isReplay()) return new Result<>(replayed(claim,access),true);
        if(row.version()!=request.expectedVersion()) throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT);
        String status;
        if(action.equals("resume")) {
            if(!row.status().equals("suspended")) throw new ApiException(ProblemCode.TASK_NOT_OPEN,"Only a held case can resume.");
            status="active";
        } else {
            requireActive(row);
            if(action.equals("close")) {
                var engine=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult();
                if(engine==null || !closable(row,ws,null)) throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "Required or active work still prevents closing this case.");
                // Items still waiting (unplanned discretionary work, a next repetition, an unmet entry rule) end with the case.
                if(!engine.isCompletable()) for(var item:runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).list())
                    if(WAITING.contains(item.getState()) && item.getStageInstanceId()==null) runtime.terminatePlanItemInstance(item.getId());
                // Ending the last waiting item can complete the case by itself.
                if(runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null) runtime.completeCaseInstance(row.engineInstanceId());
                status="completed";
            } else status="suspended";
        }
        jdbc.sql("UPDATE case_instance SET status=:status,version=version+1,completed_at=CASE WHEN :ended THEN now() ELSE NULL END WHERE workspace_id=:ws AND id=:id")
                .param("status",status).param("ended",status.equals("completed")).param("ws",ws).param("id",id).update();
        appendEvent(ws,id,row.version()+1,action,null,row.status(),status,caller,request.reason().strip());
        if (status.equals("active")) dispatchWorkflows(ws,id,caller.subjectId());
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"case.lifecycle.changed",
                "caseInstance",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("action",action,"version",row.version()+1)));
        Instance response=instance(ws,id,access); saveResponse(claim,id,List.of(),response);
        idempotency.complete(claim,"caseInstance",id.toString(),200); return new Result<>(response,false);
    }

    @Transactional
    public Result<Instance> planItem(Caller caller,UUID ws,UUID id,String itemId,String action,String key,CaseCommand request) {
        String operation=switch(action) {
            case "start" -> "startCasePlanItem";
            case "enable" -> "enableCasePlanItem";
            case "disable" -> "disableCasePlanItem";
            case "occur" -> "occurCaseUserEvent";
            case "activateStage" -> "activateCaseStage";
            case "completeStage" -> "completeCaseStage";
            case "terminate" -> "terminateCasePlanItem";
            case "plan" -> "planCaseDiscretionaryItem";
            case "retryWorkflow" -> "retryCaseWorkflowTask";
            default -> throw ApiException.notFound();
        };
        boolean worker=Set.of("start","enable","disable","occur","plan").contains(action);
        var access=access(caller,ws,worker?Permission.TASK_ACT:Permission.RUN_OPERATE,operation); validateCommand(request);
        var row=instanceRow(ws,id,true);
        var claim=idempotency.claim(ws,caller.subjectId(),operation,IdempotencyService.requireKey(key),
                idempotency.requestHash(operation,Map.of("caseId",id.toString(),"planItemId",itemId),request));
        if(claim.isReplay()) return new Result<>(replayed(claim,access),true);
        if(row.version()!=request.expectedVersion()) throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT);
        requireActive(row);
        var item=runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).planItemInstanceId(itemId).singleResult();
        if(item==null) throw ApiException.notFound();
        boolean listener=Set.of("occur","plan").contains(action);
        var groupMap=listener?userEventGroups(row,ws):humanGroups(row,ws);
        List<String> groups=worker?groupMap.getOrDefault(item.getPlanItemDefinitionId(),Set.of()).stream().sorted().toList():List.of();
        String expectedType=listener?org.flowable.cmmn.api.runtime.PlanItemDefinitionType.USER_EVENT_LISTENER:org.flowable.cmmn.api.runtime.PlanItemDefinitionType.HUMAN_TASK;
        if(worker && (!expectedType.equals(item.getPlanItemDefinitionType()) || groups.stream().noneMatch(access.groupKeys()::contains)))
            throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        if(!Boolean.TRUE.equals(actions(row,itemId,access).get(action)))
            throw new ApiException(ProblemCode.TASK_NOT_OPEN,"That action is not available in the current plan-item state.");
        switch(action) {
            case "start","activateStage" -> runtime.startPlanItemInstance(itemId);
            case "enable" -> runtime.enablePlanItemInstance(itemId);
            case "disable" -> runtime.disablePlanItemInstance(itemId);
            case "occur","plan" -> runtime.completeUserEventListenerInstance(itemId);
            case "completeStage" -> runtime.completeStagePlanItemInstance(itemId,!item.isCompletable());
            case "terminate" -> { runtime.terminatePlanItemInstance(itemId); cancelWorkflowRuns(ws,id,itemId,"The case task was terminated."); }
            case "retryWorkflow" -> { }
            default -> throw new IllegalStateException();
        }
        boolean active=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null;
        String status=active?"active":"completed";
        jdbc.sql("UPDATE case_instance SET status=:status,version=version+1,completed_at=CASE WHEN :active THEN NULL ELSE now() END WHERE workspace_id=:ws AND id=:id")
                .param("status",status).param("active",active).param("ws",ws).param("id",id).update();
        appendEvent(ws,id,row.version()+1,action,itemId,row.status(),status,caller,request.reason().strip());
        if (action.equals("retryWorkflow")) startWorkflowAttempt(ws,id,item.getId(),item.getPlanItemDefinitionId(),latestAttempt(ws,item.getId())+1,caller.subjectId());
        if (active) dispatchWorkflows(ws,id,caller.subjectId());
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"case.planitem.changed",
                "caseInstance",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("action",action,"planItemId",itemId,"version",row.version()+1)));
        Instance response=instance(ws,id,access); saveResponse(claim,id,groups,response);
        idempotency.complete(claim,"caseInstance",id.toString(),200); return new Result<>(response,false);
    }

    private boolean canReadData(InstanceRow row,WorkspaceAccess access) {
        return access.has(Permission.RUN_READ_DATA) || row.startedBy().equals(access.subjectId())
                || eligibleCaseWorker(row,access);
    }

    private boolean canEditData(InstanceRow row,WorkspaceAccess access) {
        if(!row.status().equals("active")) return false;
        if(access.has(Permission.RUN_OPERATE) && access.has(Permission.RUN_READ_DATA)) return true;
        return eligibleCaseWorker(row,access);
    }

    private boolean eligibleCaseWorker(InstanceRow row,WorkspaceAccess access) {
        if(!access.has(Permission.TASK_ACT) || !Set.of("active","suspended").contains(row.status())) return false;
        // Eligibility is checked independently of metadata-read grants.
        for(var task:tasks.createTaskQuery().caseInstanceId(row.engineInstanceId()).list()) {
            var groups=tasks.getIdentityLinksForTask(task.getId()).stream().filter(link->"candidate".equals(link.getType()) && link.getGroupId()!=null)
                    .map(org.flowable.identitylink.api.IdentityLink::getGroupId).toList();
            if(eligible(task.getAssignee(),groups,access)) return true;
        }
        for(var item:runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).list()) {
            if(org.flowable.cmmn.api.runtime.PlanItemDefinitionType.HUMAN_TASK.equals(item.getPlanItemDefinitionType())
                    && Set.of("enabled","disabled").contains(item.getState())
                    && humanGroups(row,access.workspaceId()).getOrDefault(item.getPlanItemDefinitionId(),Set.of()).stream().anyMatch(access.groupKeys()::contains)) return true;
        }
        return false;
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CaseData data(Caller caller,UUID ws,UUID id) {
        var access=policy.requireMember(caller,ws); var row=instanceRow(ws,id,false);
        if(!canReadData(row,access) && !canEditData(row,access)) throw ApiException.notFound();
        var definition=definition(ws,row.definitionId());
        var values=jdbc.sql("SELECT data_values::text FROM case_instance WHERE workspace_id=:ws AND id=:id")
                .param("ws",ws).param("id",id).query(String.class).single();
        var changes=access.has(Permission.RUN_READ_DATA)?jdbc.sql("SELECT version,before_values::text,after_values::text FROM case_data_event WHERE workspace_id=:ws AND case_id=:id ORDER BY version DESC LIMIT 200")
                .param("ws",ws).param("id",id).query((rs,n)->new DataChange(rs.getInt(1),json.parse(rs.getString(2)),json.parse(rs.getString(3)))).list():List.<DataChange>of();
        return new CaseData(definition.dataFields(),json.parse(values),row.version(),canEditData(row,access),changes,definition.viewBinding());
    }

    @Transactional
    public Result<Instance> updateData(Caller caller,UUID ws,UUID id,String key,UpdateData request) {
        var access=policy.requireMember(caller,ws);
        if(!access.has(Permission.TASK_ACT) && !(access.has(Permission.RUN_OPERATE) && access.has(Permission.RUN_READ_DATA))) throw ApiException.forbidden();
        if(request==null || request.values()==null) throw new ApiException(ProblemCode.VALIDATION_FAILED);
        validateCommand(new CaseCommand(request.expectedVersion(),request.reason()));
        var row=instanceRow(ws,id,true);
        var values=dataModel.values(definition(ws,row.definitionId()).dataFields(),request.values());
        String operation="updateCaseData";
        var claim=idempotency.claim(ws,caller.subjectId(),operation,IdempotencyService.requireKey(key),
                idempotency.requestHash(operation,Map.of("caseId",id.toString()),request));
        if(claim.isReplay()) {
            if(!(access.has(Permission.RUN_OPERATE) && access.has(Permission.RUN_READ_DATA)) && !eligibleCaseWorker(row,access)) throw new ApiException(ProblemCode.NOT_ELIGIBLE);
            return new Result<>(replayed(claim,access),true);
        }
        requireActive(row);
        if(!canEditData(row,access)) throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        if(row.version()!=request.expectedVersion()) throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT);
        var before=json.parse(jdbc.sql("SELECT data_values::text FROM case_instance WHERE workspace_id=:ws AND id=:id").param("ws",ws).param("id",id).query(String.class).single());
        if(before.equals(values)) throw new ApiException(ProblemCode.VALIDATION_FAILED,"Change at least one case data field.");
        jdbc.sql("UPDATE case_instance SET data_values=CAST(:data AS jsonb),version=version+1 WHERE workspace_id=:ws AND id=:id")
                .param("data",json.write(values)).param("ws",ws).param("id",id).update();
        appendEvent(ws,id,row.version()+1,"dataUpdated",null,row.status(),row.status(),caller,request.reason().strip());
        appendDataEvent(ws,id,row.version()+1,before,values);
        afterDataChange(ws,id,before,values,caller.subjectId());
        var changed=new java.util.TreeSet<String>(); before.propertyNames().forEach(changed::add);values.propertyNames().forEach(changed::add);
        changed.removeIf(field->java.util.Objects.equals(before.get(field),values.get(field)));
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"case.data.updated","caseInstance",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("version",row.version()+1,"fields",changed)));
        var response=instance(ws,id,access);saveResponse(claim,id,List.of(),response);
        idempotency.complete(claim,"caseInstance",id.toString(),200);return new Result<>(response,false);
    }

    private void appendDataEvent(UUID ws,UUID id,int version,tools.jackson.databind.JsonNode before,tools.jackson.databind.JsonNode after) {
        jdbc.sql("INSERT INTO case_data_event(workspace_id,case_id,version,before_values,after_values) VALUES(:ws,:id,:version,CAST(:before AS jsonb),CAST(:after AS jsonb))")
                .param("ws",ws).param("id",id).param("version",version).param("before",json.write(before)).param("after",json.write(after)).update();
    }

    private void appendEvent(UUID ws,UUID id,int version,String action,String itemId,String before,String after,Caller caller,String reason) {
        appendEvent(ws,id,version,action,itemId,before,after,caller.subjectId(),reason);
    }

    private void appendEvent(UUID ws,UUID id,int version,String action,String itemId,String before,String after,UUID actor,String reason) {
        jdbc.sql("""
                INSERT INTO case_event(workspace_id,case_id,version,action,plan_item_id,from_status,to_status,actor_subject_id,reason)
                VALUES(:ws,:id,:version,:action,:item,:before,:after,:actor,:reason)
                """).param("ws",ws).param("id",id).param("version",version).param("action",action).param("item",itemId)
                .param("before",before).param("after",after).param("actor",actor).param("reason",reason).update();
    }

    private Instance replayed(IdempotencyService.Claim claim, WorkspaceAccess access) {
        var stored=jdbc.sql("""
                SELECT candidate_groups::text,response::text FROM case_command_response
                WHERE workspace_id=:ws AND actor_subject_id=:actor AND operation=:operation AND idem_key=:key
                """).param("ws",claim.workspaceId()).param("actor",claim.principalId())
                .param("operation",claim.operation()).param("key",claim.key())
                .query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional()
                .orElseThrow(() -> new IllegalStateException("Case response snapshot missing"));
        if (Set.of("completeCaseTask","startCasePlanItem","enableCasePlanItem","disableCasePlanItem","occurCaseUserEvent","planCaseDiscretionaryItem").contains(claim.operation())) {
            var groups=new ArrayList<String>();
            for (var node : json.parse(stored.getKey())) groups.add(node.asString());
            if (!groups.isEmpty() && groups.stream().noneMatch(access.groupKeys()::contains))
                throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        }
        return json.convert(json.parse(stored.getValue()),Instance.class);
    }

    /**
     * The open human task of one plan item, for a caller that already found the item through {@link #plan}. This grants
     * nothing: {@link #complete} checks eligibility, version, idempotency and the case hold again.
     */
    @Transactional(readOnly=true)
    public java.util.Optional<String> openTaskId(UUID ws, UUID id, String planItemId) {
        var row=instanceRow(ws,id,false);
        return tasks.createTaskQuery().caseInstanceId(row.engineInstanceId()).list().stream()
                .filter(task -> planItemId.equals(task.getSubScopeId())).map(org.flowable.task.api.Task::getId).findFirst();
    }

    /** The newest version of one case definition that pins a view, with the problem a move to the target would have. */
    public record ViewUse(UUID definitionId, String key, String name, int version, int viewVersion, String problem) {}

    /** S114 pin migration: the newest version of every case definition that pins this view. Older versions stay as they are. */
    @Transactional(readOnly=true)
    public List<ViewUse> viewUses(UUID ws, UUID viewId, ApplicationViewQueries.Pinned target) {
        return jdbc.sql("""
                SELECT d.id,c.key,d.name,d.version,v.view_version FROM case_catalog c
                JOIN case_definition d ON d.workspace_id=c.workspace_id AND d.catalog_id=c.id AND d.version=c.current_version
                JOIN case_definition_view v ON v.workspace_id=d.workspace_id AND v.definition_id=d.id
                WHERE c.workspace_id=:ws AND v.view_id=:view ORDER BY c.key
                """).param("ws",ws).param("view",viewId).query((rs,n)->new Object[]{Sql.uuid(rs,"id"),rs.getString("key"),
                        rs.getString("name"),rs.getInt("version"),rs.getInt("view_version")}).list().stream()
                .map(row -> new ViewUse((UUID)row[0],(String)row[1],(String)row[2],(int)row[3],(int)row[4],
                        (int)row[4]==target.version() ? null : viewProblem(ws,(UUID)row[0],target))).toList();
    }

    /** Older case definition versions that pin a view (S116): listed read-only; cases started on them keep that view. */
    @Transactional(readOnly=true)
    public List<ViewUse> olderViewUses(UUID ws, UUID viewId) {
        return jdbc.sql("""
                SELECT d.id,c.key,d.name,d.version,v.view_version FROM case_catalog c
                JOIN case_definition d ON d.workspace_id=c.workspace_id AND d.catalog_id=c.id AND d.version<c.current_version
                JOIN case_definition_view v ON v.workspace_id=d.workspace_id AND v.definition_id=d.id
                WHERE c.workspace_id=:ws AND v.view_id=:view ORDER BY c.key, d.version DESC LIMIT 50
                """).param("ws",ws).param("view",viewId).query((rs,n)->new ViewUse(Sql.uuid(rs,"id"),rs.getString("key"),
                        rs.getString("name"),rs.getInt("version"),rs.getInt("view_version"),null)).list();
    }

    /**
     * Publishes the next version of a case definition that differs only in its view pin: same model, data fields and
     * workflow task mappings. Refused when the definition is no longer the newest version, when the view does not fit
     * its data fields, or when a workflow task would move to another release (that change belongs in the case designer).
     */
    @Transactional
    public Definition reviseView(Caller caller, UUID ws, UUID definitionId, int expectedVersion, ApplicationViewQueries.Pin viewRef) {
        access(caller, ws, Permission.WORKFLOW_EDIT, "migrateViewPins");
        var row = definition(ws, definitionId);
        int current = jdbc.sql("SELECT current_version FROM case_catalog WHERE workspace_id=:ws AND key=:key FOR UPDATE")
                .param("ws",ws).param("key",row.key()).query(Integer.class).single();
        if (row.version()!=expectedVersion || current!=expectedVersion)
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "Case \"" + row.name() + "\" changed since you reviewed it. Review the pins again.");
        var view = viewQueries.require(ws, viewRef);
        String problem = viewProblem(ws, definitionId, view);
        if (problem != null) throw new ApiException(ProblemCode.VALIDATION_FAILED, problem);
        var mappings = pins(ws, definitionId).stream().map(pin -> new WorkflowTaskMapping(pin.taskId(), pin.inputMapping(), pin.resultMapping())).toList();
        return createDefinition(caller, ws, new CreateDefinition(row.key(), row.name(), row.sourceXml(), row.dataFields(), viewRef, mappings));
    }

    private String viewProblem(UUID ws, UUID definitionId, ApplicationViewQueries.Pinned view) {
        var row = definition(ws, definitionId);
        try {
            CaseViewBinding.validate(row.dataFields(), view.definition());
        } catch (ApiException e) {
            return e.detail() == null ? e.code().title() : e.detail();
        }
        for (var pin : pins(ws, definitionId)) {
            UUID active = jdbc.sql("SELECT active_release_id FROM workflow WHERE workspace_id=:ws AND id=:id AND archived_at IS NULL")
                    .param("ws",ws).param("id",pin.workflowId()).query((rs,n)->Sql.uuid(rs,"active_release_id")).optional().orElse(null);
            if (!pin.releaseId().equals(active))
                return "Workflow task " + pin.taskId() + " pins release " + pin.releaseNumber() + " of \"" + pin.workflowName()
                        + "\", which is no longer its active release. Create the new case version in the case designer instead.";
        }
        return null;
    }
}
