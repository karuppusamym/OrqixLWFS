package com.orqix.catalog.internal;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ApplicationViewQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/** Workspace presentation over the existing authorized task query; never changes its rows, filters or actions. */
@Service
public class WorklistViews {
    public record State(int revision, ApplicationViewQueries.Pinned binding) {}
    public record Assign(int expectedRevision, ApplicationViewQueries.Pin viewRef) {}
    static final Set<String> TEXT = Set.of("task.requestTitle","task.reference","task.label","task.workflowName",
            "task.requester","task.createdAt","task.dueAt","task.status","task.candidateGroup");
    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final ApplicationViewQueries views;
    private final Json json;
    private final AuditWriter audit;
    public WorklistViews(JdbcClient jdbc, PolicyService policy, ApplicationViewQueries views, Json json, AuditWriter audit) {
        this.jdbc=jdbc; this.policy=policy; this.views=views; this.json=json; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public State get(Caller caller, UUID ws) {
        var access=policy.requireMember(caller,ws);
        if (!access.has(Permission.TASK_ACT) && !access.has(Permission.WORKFLOW_READ)) throw ApiException.forbidden();
        return current(ws,false);
    }
    @Transactional
    public State assign(Caller caller, UUID ws, Assign request) {
        policy.require(policy.requireMember(caller,ws),Permission.WORKFLOW_PUBLISH,"assignWorklistView",true);
        if (request==null || request.expectedRevision()<0) throw new ApiException(ProblemCode.VALIDATION_FAILED);
        var pin=request.viewRef()==null?null:views.require(ws,request.viewRef());
        if (pin!=null) validate(pin.definition());
        jdbc.sql("INSERT INTO workspace_worklist_view(workspace_id,revision) VALUES(:ws,0) ON CONFLICT DO NOTHING").param("ws",ws).update();
        var current=current(ws,true);
        if (current.revision()!=request.expectedRevision()) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,"The worklist presentation changed. Refresh before applying another version.");
        int next=Math.addExact(current.revision(),1);
        jdbc.sql("UPDATE workspace_worklist_view SET revision=:revision,view_id=:id,view_version=:version,definition=CAST(:definition AS jsonb) WHERE workspace_id=:ws")
                .param("ws",ws).param("revision",next).param("id",pin==null?null:pin.id()).param("version",pin==null?null:pin.version())
                .param("definition",pin==null?null:json.write(pin.definition())).update();
        jdbc.sql("INSERT INTO worklist_view_history(workspace_id,revision,binding,changed_by) VALUES(:ws,:revision,CAST(:binding AS jsonb),:actor)")
                .param("ws",ws).param("revision",next).param("binding",pin==null?null:json.write(pin)).param("actor",caller.subjectId()).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"view.worklist-assigned","workspace",ws.toString(),AuditWriter.Outcome.SUCCESS,Map.of("revision",next)));
        return new State(next,pin);
    }
    private State current(UUID ws,boolean lock) {
        return jdbc.sql("SELECT revision,view_id,view_version,definition::text FROM workspace_worklist_view WHERE workspace_id=:ws"+(lock?" FOR UPDATE":""))
                .param("ws",ws).query((rs,n)->new State(rs.getInt(1),rs.getObject(2)==null?null:new ApplicationViewQueries.Pinned(rs.getObject(2,UUID.class),rs.getInt(3),json.parse(rs.getString(4)))))
                .optional().orElse(new State(0,null));
    }
    public static void validate(tools.jackson.databind.JsonNode view) {
        if (!"worklist".equals(view.path("kind").asString()) || view.path("sections").size()!=1 || view.path("sections").get(0).path("fields").size()>10)
            throw new ApiException(ProblemCode.VALIDATION_FAILED,"A worklist view needs one section with at most ten columns.");
        boolean title=false;
        for (var field:view.path("sections").get(0).path("fields")) {
            String binding=field.path("binding").asString(), type=field.path("type").asString();
            if (!(TEXT.contains(binding)&&type.equals("text") || binding.equals("task.priority")&&type.equals("number")) || field.path("required").asBoolean(false))
                throw new ApiException(ProblemCode.VALIDATION_FAILED,"Use supported read-only task summary columns with matching types.");
            title|=binding.equals("task.requestTitle");
        }
        var actions=view.path("actions");
        if (!title || actions.size()!=1 || !"openTask".equals(actions.get(0).path("type").asString()))
            throw new ApiException(ProblemCode.VALIDATION_FAILED,"A worklist needs its request title column and one Open task action.");
    }
}
