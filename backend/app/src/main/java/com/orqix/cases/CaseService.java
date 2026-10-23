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
    public record DefinitionDetail(UUID id, String key, String name, int version, Instant createdAt, String sourceXml, List<CaseDataModel.Field> dataFields, ApplicationViewQueries.Pinned viewBinding) {}
    public record Task(String id, String name, String status, List<String> candidateGroups, boolean eligible) {}
    public record Instance(UUID id, UUID definitionId, int definitionVersion, String status, int version,
            List<Task> tasks, Instant createdAt) {}
    public record Result<T>(T value, boolean replayed) {}
    public record CreateDefinition(String key, String name, String sourceXml, List<CaseDataModel.Field> dataFields, ApplicationViewQueries.Pin viewRef) {
        public CreateDefinition(String key,String name,String sourceXml,List<CaseDataModel.Field> dataFields) { this(key,name,sourceXml,dataFields,null); }
        public CreateDefinition(String key,String name,String sourceXml) { this(key,name,sourceXml,null,null); }
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
            String parentStageId, Instant createdAt, Instant endedAt, boolean required, Map<String,Boolean> allowedActions) {}
    public record Milestone(String id, String elementId, String name, Instant achievedAt) {}
    public record CaseEvent(int version, String action, String planItemId, String fromStatus, String toStatus,
            UUID actorId, String actorName, String reason, Instant occurredAt) {
        @Override public String toString() { return "Case event " + version + " " + action + " [reason redacted]"; }
    }
    public record PlanOverview(List<PlanItem> items, List<Milestone> milestones, List<CaseEvent> events, boolean canClose) {}

    private record DefinitionRow(UUID id, UUID workspaceId, String key, String name, int version,
            Instant createdAt, String sourceXml, String engineDefinitionId, List<CaseDataModel.Field> dataFields, ApplicationViewQueries.Pinned viewBinding) {
        Definition summary() { return new Definition(id, key, name, version, createdAt, dataFields, viewBinding); }
        DefinitionDetail detail() { return new DefinitionDetail(id, key, name, version, createdAt, sourceXml, dataFields, viewBinding); }
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
    private final Map<UUID,Set<String>> requiredByDefinition = new java.util.concurrent.ConcurrentHashMap<>();

    public CaseService(JdbcClient jdbc, PolicyService policy, CmmnSubsetValidator validator,
            CmmnRepositoryService repository, CmmnRuntimeService runtime, CmmnTaskService tasks,
            CmmnHistoryService history, IdempotencyService idempotency, Json json, AuditWriter audit, CaseDataModel dataModel, ApplicationViewQueries viewQueries) {
        this.jdbc=jdbc; this.policy=policy; this.validator=validator; this.repository=repository;
        this.runtime=runtime; this.tasks=tasks; this.idempotency=idempotency; this.json=json; this.audit=audit;
        this.history=history; this.dataModel=dataModel; this.viewQueries=viewQueries;
    }

    private WorkspaceAccess access(Caller caller, UUID workspaceId, Permission permission, String operation) {
        var access = policy.requireMember(caller, workspaceId);
        policy.require(access, permission, operation, true);
        return access;
    }

    @Transactional
    public Definition createDefinition(Caller caller, UUID ws, CreateDefinition request) {
        var access = access(caller, ws, Permission.WORKFLOW_EDIT, "createCaseDefinition");
        if (request == null || request.key() == null || !request.key().matches("[a-z][a-z0-9-]{1,62}")
                || request.name() == null || request.name().isBlank() || request.name().length() > 200)
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Case key or name is invalid.");
        String xml = request.sourceXml();
        var dataFields=dataModel.fields(request.dataFields());
        var view = request.viewRef() == null ? null : viewQueries.require(ws,request.viewRef());
        if (view != null) CaseViewBinding.validate(dataFields,view.definition());
        Set<String> groups = validator.validate(xml, request.key());
        long found = jdbc.sql("SELECT count(*) FROM workspace_group WHERE workspace_id=:ws AND key IN (:groups)")
                .param("ws",ws).param("groups",groups).query(Long.class).single();
        if (found != groups.size())
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Every candidate group must exist in this workspace.");
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
                    .tenantId(access.workspace().engineTenantId()).addString(request.key() + ".cmmn",xml).deploy();
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
                    engine_definition_id,engine_deployment_id,created_by,data_fields)
                VALUES(:id,:ws,:catalog,:version,:name,:xml,:sha,:engine,:deployment,:actor,CAST(:fields AS jsonb))
                """)
                .param("id",id).param("ws",ws).param("catalog",existing.getKey()).param("version",version)
                .param("name",request.name().strip()).param("xml",xml).param("sha",sha)
                .param("engine",engineDefinition.getId()).param("deployment",deployment.getId())
                .param("actor",caller.subjectId()).param("fields",json.write(dataFields)).update();
        if (view != null) jdbc.sql("INSERT INTO case_definition_view(definition_id,workspace_id,view_id,view_version,definition) VALUES(:id,:ws,:view,:version,CAST(:definition AS jsonb))")
                .param("id",id).param("ws",ws).param("view",view.id()).param("version",view.version()).param("definition",json.write(view.definition())).update();
        jdbc.sql("UPDATE case_catalog SET current_version=:v WHERE id=:id")
                .param("v",version).param("id",existing.getKey()).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"case.definition.created",
                "caseDefinition",id.toString(),AuditWriter.Outcome.SUCCESS,Map.of("version",version)));
        return definition(ws,id).summary();
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
        return definition(ws,id).detail();
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
        UUID id=Ids.newId();
        var engine=runtime.createCaseInstanceBuilder().caseDefinitionId(definition.engineDefinitionId())
                .tenantId(access.workspace().engineTenantId()).businessKey(id.toString()).start();
        boolean engineActive = runtime.createCaseInstanceQuery().caseInstanceId(engine.getId()).singleResult()!=null;
        jdbc.sql("INSERT INTO case_instance(id,workspace_id,definition_id,engine_instance_id,status,started_by,completed_at,data_values) VALUES(:id,:ws,:definition,:engine,:status,:actor,CASE WHEN :active THEN NULL ELSE now() END,CAST(:data AS jsonb))")
                .param("id",id).param("ws",ws).param("definition",definitionId).param("engine",engine.getId())
                .param("actor",caller.subjectId()).param("status",engineActive?"active":"completed").param("active",engineActive).param("data",json.write(initialData)).update();
        appendEvent(ws,id,1,"started",null,null,engineActive?"active":"completed",caller,null);
        appendDataEvent(ws,id,1,json.object(),initialData);
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
        return false;
    }

    /** Read only engine history, including completed/terminated stages and milestones after the case ends. */
    @Transactional(readOnly=true)
    public PlanOverview plan(Caller caller, UUID ws, UUID id) {
        var access = policy.requireMember(caller, ws);
        var row = instanceRow(ws, id, false);
        if (!canRead(row, access)) throw ApiException.notFound();
        var items = history.createHistoricPlanItemInstanceQuery().planItemInstanceCaseInstanceId(row.engineInstanceId())
                .list().stream().map(item -> new PlanItem(item.getId(), item.getElementId(), item.getName(),
                        item.getPlanItemDefinitionType(), item.getState(), item.getStageInstanceId(),
                        instant(item.getCreateTime()), instant(item.getEndedTime()), required(row,item.getElementId(),access.workspaceId()), actions(row,item.getId(),access)))
                .sorted(Comparator.comparing(PlanItem::elementId).thenComparing(PlanItem::id)).toList();
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
                && engine!=null && engine.isCompletable());
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
                List.copyOf(visibleTasks),row.createdAt());
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
        boolean operator=access.has(Permission.RUN_OPERATE);
        return Map.of("start",worker && "enabled".equals(item.getState()),
                "enable",worker && "disabled".equals(item.getState()),
                "disable",worker && "enabled".equals(item.getState()) && !required(row,item.getElementId(),access.workspaceId()),
                "activateStage",operator && item.isStage() && "enabled".equals(item.getState()),
                "completeStage",operator && item.isStage() && "active".equals(item.getState()) && item.isCompletable(),
                "terminate",operator && (human || item.isStage()) && Set.of("active","enabled","disabled","available").contains(item.getState()));
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
                if(engine==null || !engine.isCompletable()) throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "Required or active work still prevents closing this case.");
                runtime.completeCaseInstance(row.engineInstanceId()); status="completed";
            } else status="suspended";
        }
        jdbc.sql("UPDATE case_instance SET status=:status,version=version+1,completed_at=CASE WHEN :ended THEN now() ELSE NULL END WHERE workspace_id=:ws AND id=:id")
                .param("status",status).param("ended",status.equals("completed")).param("ws",ws).param("id",id).update();
        appendEvent(ws,id,row.version()+1,action,null,row.status(),status,caller,request.reason().strip());
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
            case "activateStage" -> "activateCaseStage";
            case "completeStage" -> "completeCaseStage";
            case "terminate" -> "terminateCasePlanItem";
            default -> throw ApiException.notFound();
        };
        boolean worker=Set.of("start","enable","disable").contains(action);
        var access=access(caller,ws,worker?Permission.TASK_ACT:Permission.RUN_OPERATE,operation); validateCommand(request);
        var row=instanceRow(ws,id,true);
        var claim=idempotency.claim(ws,caller.subjectId(),operation,IdempotencyService.requireKey(key),
                idempotency.requestHash(operation,Map.of("caseId",id.toString(),"planItemId",itemId),request));
        if(claim.isReplay()) return new Result<>(replayed(claim,access),true);
        if(row.version()!=request.expectedVersion()) throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT);
        requireActive(row);
        var item=runtime.createPlanItemInstanceQuery().caseInstanceId(row.engineInstanceId()).planItemInstanceId(itemId).singleResult();
        if(item==null) throw ApiException.notFound();
        List<String> groups=worker?humanGroups(row,access.workspaceId()).getOrDefault(item.getPlanItemDefinitionId(),Set.of()).stream().sorted().toList():List.of();
        if(worker && (!org.flowable.cmmn.api.runtime.PlanItemDefinitionType.HUMAN_TASK.equals(item.getPlanItemDefinitionType()) || groups.stream().noneMatch(access.groupKeys()::contains)))
            throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        if(!Boolean.TRUE.equals(actions(row,itemId,access).get(action)))
            throw new ApiException(ProblemCode.TASK_NOT_OPEN,"That action is not available in the current plan-item state.");
        switch(action) {
            case "start","activateStage" -> runtime.startPlanItemInstance(itemId);
            case "enable" -> runtime.enablePlanItemInstance(itemId);
            case "disable" -> runtime.disablePlanItemInstance(itemId);
            case "completeStage" -> runtime.completeStagePlanItemInstance(itemId);
            case "terminate" -> runtime.terminatePlanItemInstance(itemId);
            default -> throw new IllegalStateException();
        }
        boolean active=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null;
        String status=active?"active":"completed";
        jdbc.sql("UPDATE case_instance SET status=:status,version=version+1,completed_at=CASE WHEN :active THEN NULL ELSE now() END WHERE workspace_id=:ws AND id=:id")
                .param("status",status).param("active",active).param("ws",ws).param("id",id).update();
        appendEvent(ws,id,row.version()+1,action,itemId,row.status(),status,caller,request.reason().strip());
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
        jdbc.sql("""
                INSERT INTO case_event(workspace_id,case_id,version,action,plan_item_id,from_status,to_status,actor_subject_id,reason)
                VALUES(:ws,:id,:version,:action,:item,:before,:after,:actor,:reason)
                """).param("ws",ws).param("id",id).param("version",version).param("action",action).param("item",itemId)
                .param("before",before).param("after",after).param("actor",caller.subjectId()).param("reason",reason).update();
    }

    private Instance replayed(IdempotencyService.Claim claim, WorkspaceAccess access) {
        var stored=jdbc.sql("""
                SELECT candidate_groups::text,response::text FROM case_command_response
                WHERE workspace_id=:ws AND actor_subject_id=:actor AND operation=:operation AND idem_key=:key
                """).param("ws",claim.workspaceId()).param("actor",claim.principalId())
                .param("operation",claim.operation()).param("key",claim.key())
                .query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional()
                .orElseThrow(() -> new IllegalStateException("Case response snapshot missing"));
        if (Set.of("completeCaseTask","startCasePlanItem","enableCasePlanItem","disableCasePlanItem").contains(claim.operation())) {
            var groups=new ArrayList<String>();
            for (var node : json.parse(stored.getKey())) groups.add(node.asString());
            if (!groups.isEmpty() && groups.stream().noneMatch(access.groupKeys()::contains))
                throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        }
        return json.convert(json.parse(stored.getValue()),Instance.class);
    }
}
