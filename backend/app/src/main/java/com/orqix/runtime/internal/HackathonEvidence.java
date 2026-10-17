package com.orqix.runtime.internal;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.*;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.*;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Bounded evidence exports refuse truncation; only allowlisted control metadata leaves the service. */
@Service
public class HackathonEvidence {
    private final JdbcClient jdbc;
    private final RunAccess access;
    private final PolicyService policy;
    private final ReleaseQueries releases;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;
    public HackathonEvidence(JdbcClient jdbc,RunAccess access,PolicyService policy,ReleaseQueries releases,AuditWriter audit,Json json,Clock clock) {
        this.jdbc=jdbc;this.access=access;this.policy=policy;this.releases=releases;this.audit=audit;this.json=json;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public ObjectNode mail(Caller caller,UUID ws,UUID id) {
        var visible=access.require(caller,ws,id);
        policy.require(visible.access(),Permission.RUN_OPERATE,"getStageMail",false);
        var result=json.object();
        result.set("items",json.mapper().valueToTree(jdbc.sql("""
                SELECT id,event_kind,status,attempts,created_at,updated_at,failure_code FROM stage_mail
                WHERE workspace_id=:ws AND run_id=:run ORDER BY created_at,id LIMIT 1001
                """).param("ws",ws).param("run",id).query((rs,n)-> {
                    var row=json.object().put("id",Sql.uuid(rs,"id").toString()).put("event",rs.getString("event_kind"))
                            .put("status",rs.getString("status")).put("attempts",rs.getInt("attempts"))
                            .put("createdAt",Sql.instant(rs,"created_at").toString()).put("updatedAt",Sql.instant(rs,"updated_at").toString());
                    if(rs.getString("failure_code")!=null) row.put("failureCode",rs.getString("failure_code"));
                    return row;
                }).list()));
        if(result.path("items").size()>1000) throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,"More than 1000 notification records; use the controlled audit extraction process.");
        return result;
    }
    @Transactional
    public ObjectNode export(Caller caller,UUID ws,UUID id) {
        var visible=access.require(caller,ws,id);
        policy.require(visible.access(),Permission.AUDIT_READ,"exportCaseEvidence",true);
        var run=visible.run();var release=releases.require(ws,run.releaseId());
        // Stabilize the event sequence for this export, without changing business state.
        jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id=:ws AND id=:run FOR SHARE").param("ws",ws).param("run",id).query(UUID.class).single();
        var taskIds=jdbc.sql("SELECT engine_task_id FROM task_context WHERE workspace_id=:ws AND run_id=:run").param("ws",ws).param("run",id).query(String.class).list();
        var records=jdbc.sql("""
                SELECT id,occurred_at,actor_subject_id,actor_type,action,resource_type,resource_id,outcome,correlation_id,metadata::text
                FROM audit_event WHERE workspace_id=:ws AND (
                    (resource_type='run' AND resource_id=:run) OR
                    (resource_type='task' AND resource_id IN (SELECT engine_task_id FROM task_context WHERE workspace_id=:ws AND run_id=CAST(:run AS uuid))) OR
                    (resource_type='stageMail' AND resource_id IN (SELECT id::text FROM stage_mail WHERE workspace_id=:ws AND run_id=CAST(:run AS uuid))))
                ORDER BY occurred_at,id LIMIT 10001
                """).param("ws",ws).param("run",id.toString()).query((rs,n)-> {
                    var item=json.object().put("id",Sql.uuid(rs,"id").toString()).put("at",Sql.instant(rs,"occurred_at").toString())
                            .put("actorType",rs.getString("actor_type")).put("action",rs.getString("action"))
                            .put("resourceType",rs.getString("resource_type")).put("resourceId",rs.getString("resource_id"))
                            .put("outcome",rs.getString("outcome")).put("correlationId",rs.getString("correlation_id"));
                    UUID actor=Sql.uuid(rs,"actor_subject_id");if(actor!=null)item.put("actor",actor.toString());
                    var safe=json.object();JsonNode metadata=json.parse(rs.getString("metadata"));
                    for(String key:List.of("taskId","nodeId","action","runId","dueAt","releaseId","toSubjectId","fromSubjectId","status","attempts","closurePolicy","cancelledTaskIds"))
                        if(metadata.has(key))safe.set(key,metadata.path(key));
                    item.set("metadata",safe);return item;
                }).list();
        var timeline=jdbc.sql("SELECT seq,type,node_id,actor_subject_id,occurred_at,data::text FROM run_event WHERE workspace_id=:ws AND run_id=:run ORDER BY seq LIMIT 10001")
                .param("ws",ws).param("run",id).query((rs,n)-> {
                    var item=json.object().put("sequence",rs.getLong("seq")).put("type",rs.getString("type"))
                            .put("at",Sql.instant(rs,"occurred_at").toString());
                    if(rs.getString("node_id")!=null)item.put("nodeId",rs.getString("node_id"));
                    UUID actor=Sql.uuid(rs,"actor_subject_id");if(actor!=null)item.put("actor",actor.toString());
                    var safe=json.object();var data=json.parse(rs.getString("data"));
                    for(String key:List.of("action","dueAt","releaseId","outcome","outcomeKind","closurePolicy","cancelledTaskIds","taskId","reason","from","to")) if(data.has(key))safe.set(key,data.path(key));
                    item.set("data",safe);return item;
                }).list();
        if(records.size()>10000 || timeline.size()>10000) throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,"Evidence exceeds 10000 events. Use the controlled audit extraction process.");
        var result=json.object();var manifest=result.putObject("manifest");
        manifest.put("formatVersion","1").put("workspaceId",ws.toString()).put("runId",id.toString()).put("reference",run.reference())
                .put("releaseId",run.releaseId().toString()).put("generatedAt",clock.instant().toString())
                .put("auditCount",records.size()).put("eventCount",timeline.size()).put("taskCount",taskIds.size())
                .put("from",run.createdAt().toString()).put("through",clock.instant().toString());
        manifest.set("release",release.manifest());manifest.put("payloadPolicy","Control metadata only; no request values, credentials or template bodies.");
        result.set("audit",json.mapper().valueToTree(records));result.set("events",json.mapper().valueToTree(timeline));
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"run.evidence-exported","run",id.toString(),AuditWriter.Outcome.SUCCESS,
                Map.of("auditCount",records.size(),"eventCount",timeline.size())));
        return result;
    }
}
