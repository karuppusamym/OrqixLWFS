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
    public record Definition(UUID id, String key, String name, int version, Instant createdAt) {}
    public record DefinitionDetail(UUID id, String key, String name, int version, Instant createdAt, String sourceXml) {}
    public record Task(String id, String name, String status, List<String> candidateGroups, boolean eligible) {}
    public record Instance(UUID id, UUID definitionId, int definitionVersion, String status, int version,
            List<Task> tasks, Instant createdAt) {}
    public record Result<T>(T value, boolean replayed) {}
    public record CreateDefinition(String key, String name, String sourceXml) {}
    public record CompleteTask(int expectedVersion) {}
    public record TerminateCase(int expectedVersion, String reason) {}
    public record PlanItem(String id, String elementId, String name, String type, String state,
            String parentStageId, Instant createdAt, Instant endedAt) {}
    public record Milestone(String id, String elementId, String name, Instant achievedAt) {}
    public record PlanOverview(List<PlanItem> items, List<Milestone> milestones) {}

    private record DefinitionRow(UUID id, UUID workspaceId, String key, String name, int version,
            Instant createdAt, String sourceXml, String engineDefinitionId) {
        Definition summary() { return new Definition(id, key, name, version, createdAt); }
        DefinitionDetail detail() { return new DefinitionDetail(id, key, name, version, createdAt, sourceXml); }
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

    public CaseService(JdbcClient jdbc, PolicyService policy, CmmnSubsetValidator validator,
            CmmnRepositoryService repository, CmmnRuntimeService runtime, CmmnTaskService tasks,
            CmmnHistoryService history, IdempotencyService idempotency, Json json, AuditWriter audit) {
        this.jdbc=jdbc; this.policy=policy; this.validator=validator; this.repository=repository;
        this.runtime=runtime; this.tasks=tasks; this.idempotency=idempotency; this.json=json; this.audit=audit;
        this.history=history;
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
                    engine_definition_id,engine_deployment_id,created_by)
                VALUES(:id,:ws,:catalog,:version,:name,:xml,:sha,:engine,:deployment,:actor)
                """)
                .param("id",id).param("ws",ws).param("catalog",existing.getKey()).param("version",version)
                .param("name",request.name().strip()).param("xml",xml).param("sha",sha)
                .param("engine",engineDefinition.getId()).param("deployment",deployment.getId())
                .param("actor",caller.subjectId()).update();
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
                SELECT d.id,d.workspace_id,c.key,d.name,d.version,d.created_at,d.source_xml,d.engine_definition_id
                FROM case_definition d JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.id=d.catalog_id
                WHERE d.workspace_id=:ws ORDER BY d.created_at DESC,d.id DESC LIMIT 100
                """).param("ws",ws).query(CaseService::mapDefinition).list().stream().map(DefinitionRow::summary).toList();
    }

    @Transactional(readOnly=true)
    public DefinitionDetail definition(Caller caller, UUID ws, UUID id) {
        access(caller,ws,Permission.WORKFLOW_EDIT,"getCaseDefinition");
        return definition(ws,id).detail();
    }

    private DefinitionRow definition(UUID ws, UUID id) {
        return jdbc.sql("""
                SELECT d.id,d.workspace_id,c.key,d.name,d.version,d.created_at,d.source_xml,d.engine_definition_id
                FROM case_definition d JOIN case_catalog c ON c.workspace_id=d.workspace_id AND c.id=d.catalog_id
                WHERE d.workspace_id=:ws AND d.id=:id
                """).param("ws",ws).param("id",id).query(CaseService::mapDefinition).optional().orElseThrow(ApiException::notFound);
    }

    private static DefinitionRow mapDefinition(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DefinitionRow(Sql.uuid(rs,"id"),Sql.uuid(rs,"workspace_id"),rs.getString("key"),
                rs.getString("name"),rs.getInt("version"),Sql.instant(rs,"created_at"),
                rs.getString("source_xml"),rs.getString("engine_definition_id"));
    }

    @Transactional
    public Result<Instance> start(Caller caller, UUID ws, UUID definitionId, String key) {
        var access = access(caller,ws,Permission.WORKFLOW_START,"startCase");
        var definition = definition(ws,definitionId);
        String operation="startCase";
        var claim=idempotency.claim(ws,caller.subjectId(),operation,IdempotencyService.requireKey(key),
                idempotency.requestHash(operation,Map.of("definitionId",definitionId.toString()),Map.of()));
        if (claim.isReplay()) return new Result<>(replayed(claim,access),true);
        UUID id=Ids.newId();
        var engine=runtime.createCaseInstanceBuilder().caseDefinitionId(definition.engineDefinitionId())
                .tenantId(access.workspace().engineTenantId()).businessKey(id.toString()).start();
        jdbc.sql("INSERT INTO case_instance(id,workspace_id,definition_id,engine_instance_id,status,started_by) VALUES(:id,:ws,:definition,:engine,'active',:actor)")
                .param("id",id).param("ws",ws).param("definition",definitionId).param("engine",engine.getId())
                .param("actor",caller.subjectId()).update();
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
        if (!access.has(Permission.TASK_ACT) || !row.status().equals("active")) return false;
        for (var task : tasks.createTaskQuery().caseInstanceId(row.engineInstanceId()).list()) {
            var groups=tasks.getIdentityLinksForTask(task.getId()).stream()
                    .filter(link -> "candidate".equals(link.getType()) && link.getGroupId()!=null)
                    .map(org.flowable.identitylink.api.IdentityLink::getGroupId).toList();
            if (eligible(task.getAssignee(),groups,access)) return true;
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
                        instant(item.getCreateTime()), instant(item.getEndedTime())))
                .sorted(Comparator.comparing(PlanItem::elementId).thenComparing(PlanItem::id)).toList();
        var milestones = history.createHistoricMilestoneInstanceQuery().milestoneInstanceCaseInstanceId(row.engineInstanceId())
                .list().stream().map(item -> new Milestone(item.getId(), item.getElementId(), item.getName(),
                        instant(item.getTimeStamp())))
                .sorted(Comparator.comparing(Milestone::achievedAt).thenComparing(Milestone::id)).toList();
        return new PlanOverview(items, milestones);
    }

    private static Instant instant(java.util.Date date) { return date == null ? null : date.toInstant(); }

    private Instance instance(UUID ws, UUID id, WorkspaceAccess access) {
        var row=instanceRow(ws,id,false);
        List<Task> visibleTasks = new ArrayList<>();
        if (row.status().equals("active")) {
            for (var task : tasks.createTaskQuery().caseInstanceId(row.engineInstanceId()).list()) {
                var groups=tasks.getIdentityLinksForTask(task.getId()).stream()
                        .filter(link -> "candidate".equals(link.getType()) && link.getGroupId()!=null)
                        .map(org.flowable.identitylink.api.IdentityLink::getGroupId).distinct().sorted().toList();
                boolean eligible=eligible(task.getAssignee(),groups,access);
                visibleTasks.add(new Task(task.getId(),task.getName(),"available",groups,eligible));
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
        if (!row.status().equals("active")) throw new ApiException(ProblemCode.TASK_NOT_OPEN);
        var task=tasks.createTaskQuery().taskId(taskId).caseInstanceId(row.engineInstanceId()).singleResult();
        if (task == null) throw ApiException.notFound();
        var groups=tasks.getIdentityLinksForTask(taskId).stream()
                .filter(link -> "candidate".equals(link.getType()) && link.getGroupId()!=null)
                .map(org.flowable.identitylink.api.IdentityLink::getGroupId).toList();
        if (!eligible(task.getAssignee(),groups,access)) throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        tasks.complete(taskId);
        boolean active=runtime.createCaseInstanceQuery().caseInstanceId(row.engineInstanceId()).singleResult()!=null;
        jdbc.sql("UPDATE case_instance SET version=version+1,status=:status,completed_at=CASE WHEN :active THEN NULL ELSE now() END WHERE workspace_id=:ws AND id=:id")
                .param("status",active?"active":"completed").param("active",active).param("ws",ws).param("id",id).update();
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
        if (!row.status().equals("active")) throw new ApiException(ProblemCode.TASK_NOT_OPEN);
        runtime.terminateCaseInstance(row.engineInstanceId());
        jdbc.sql("UPDATE case_instance SET version=version+1,status='terminated',completed_at=now() WHERE workspace_id=:ws AND id=:id")
                .param("ws", ws).param("id", id).update();
        // The reason is retained separately under run-read-data authorization, never in audit metadata.
        jdbc.sql("INSERT INTO case_termination(workspace_id,case_id,reason,terminated_by) VALUES(:ws,:id,:reason,:actor)")
                .param("ws", ws).param("id", id).param("reason", request.reason().strip()).param("actor", caller.subjectId()).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "case.instance.terminated",
                "caseInstance", id.toString(), AuditWriter.Outcome.SUCCESS, Map.of("version", row.version() + 1)));
        Instance response = instance(ws, id, access);
        saveResponse(claim, id, List.of(), response);
        idempotency.complete(claim, "caseInstance", id.toString(), 200);
        return new Result<>(response, false);
    }

    private Instance replayed(IdempotencyService.Claim claim, WorkspaceAccess access) {
        var stored=jdbc.sql("""
                SELECT candidate_groups::text,response::text FROM case_command_response
                WHERE workspace_id=:ws AND actor_subject_id=:actor AND operation=:operation AND idem_key=:key
                """).param("ws",claim.workspaceId()).param("actor",claim.principalId())
                .param("operation",claim.operation()).param("key",claim.key())
                .query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional()
                .orElseThrow(() -> new IllegalStateException("Case response snapshot missing"));
        if (claim.operation().equals("completeCaseTask")) {
            var groups=new ArrayList<String>();
            for (var node : json.parse(stored.getKey())) groups.add(node.asString());
            if (!groups.isEmpty() && groups.stream().noneMatch(access.groupKeys()::contains))
                throw new ApiException(ProblemCode.NOT_ELIGIBLE);
        }
        return json.convert(json.parse(stored.getValue()),Instance.class);
    }
}
