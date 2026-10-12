package com.orqix.runtime.internal;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.FormValidator;
import com.orqix.identity.*;
import com.orqix.runtime.api.IncidentCorrectionController.Request;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Conservative correction of explicitly editable inputs before external capture or prior data consumption. */
@Service
public class IncidentCorrectionService {
    public record Result(UUID runId,int version,int fields,boolean replayed) {}
    private final RunAccess access; private final PolicyService policy; private final RunStore runs;
    private final ReleaseQueries releases; private final FormValidator validator; private final EngineCommands engine;
    private final RunEvents events; private final IdempotencyService idempotency; private final AuditWriter audit;
    private final JdbcClient jdbc; private final Json json;
    public IncidentCorrectionService(RunAccess access,PolicyService policy,RunStore runs,ReleaseQueries releases,
            FormValidator validator,EngineCommands engine,RunEvents events,IdempotencyService idempotency,
            AuditWriter audit,JdbcClient jdbc,Json json) {
        this.access=access; this.policy=policy; this.runs=runs; this.releases=releases; this.validator=validator;
        this.engine=engine; this.events=events; this.idempotency=idempotency; this.audit=audit; this.jdbc=jdbc; this.json=json;
    }
    @Transactional
    public Result correct(Caller caller,UUID workspace,UUID runId,String key,Request request) {
        var visible=access.require(caller,workspace,runId);
        policy.require(visible.access(),Permission.RUN_OPERATE,"correctRunInput",true);
        if (!visible.dataVisible()) throw ApiException.forbidden();
        if (request.changes()==null || !request.changes().isObject() || request.changes().size()<1 || request.changes().size()>30)
            throw ApiException.malformed("changes must contain 1..30 scalar request fields.");
        String hash=idempotency.requestHash("correctRunInput",Map.of("workspaceId",workspace.toString(),"runId",runId.toString()),request);
        var claim=idempotency.claim(workspace,caller.subjectId(),"correctRunInput",IdempotencyService.requireKey(key),hash);
        if (claim.isReplay()) return new Result(runId,Integer.parseInt(claim.replay().resourceId()),request.changes().size(),true);
        var run=runs.lock(workspace,runId).orElseThrow(ApiException::notFound);
        if (!RunStore.NEEDS_ATTENTION.equals(run.lifecycle())) throw blocked("Only a request needing attention can be corrected.");
        if (run.version()!=request.expectedVersion()) throw new ApiException(ProblemCode.RUN_NOT_ACTIVE,"The request changed; reload it.").with("currentVersion",run.version());
        if (run.engineProcessInstanceId()==null || engine.hasExecutableOrTimerJob(run.engineProcessInstanceId()))
            throw new ApiException(ProblemCode.RUN_BUSY,"An execution or timer may still capture input; wait for it to settle.");
        boolean captured=jdbc.sql("SELECT EXISTS(SELECT 1 FROM effect_journal WHERE workspace_id=:ws AND run_id=:run)")
                .param("ws",workspace).param("run",runId).query(Boolean.class).single();
        if (captured) throw blocked("An external effect already captured this request. Correcting it could invalidate that effect.");
        boolean observed=jdbc.sql("SELECT EXISTS(SELECT 1 FROM run_event WHERE workspace_id=:ws AND run_id=:run AND type IN ('TASK_CREATED','DECISION_EVALUATED','DOCUMENT_GENERATED','WAIT_STARTED','MESSAGE_WAIT_STARTED','CHILD_STARTED','CHILD_COMPLETED','EFFECT_SUCCEEDED'))")
                .param("ws",workspace).param("run",runId).query(Boolean.class).single();
        if (observed) throw blocked("A previous task or data-consuming step already observed this input.");
        var release=releases.require(workspace,run.releaseId());
        guardAncestors(release.definition(),run.attention()==null ? null : run.attention().path("nodeId").asString());
        ObjectNode corrected=(ObjectNode)run.input().deepCopy(); var before=json.object(); var after=json.object();
        for (var change:request.changes().properties()) {
            JsonNode field=release.inputSchema().path("properties").path(change.getKey());
            JsonNode value=change.getValue();
            if (!field.path("incidentEditable").asBoolean() || "array".equals(field.path("type").asString())
                    || "attachment".equals(field.path("format").asString()) || !value.isValueNode() || value.isNull())
                throw new ApiException(ProblemCode.VALIDATION_FAILED,"Only explicitly editable scalar request fields can change.");
            before.set(change.getKey(),corrected.path(change.getKey())); after.set(change.getKey(),value);
            corrected.set(change.getKey(),value);
        }
        if (corrected.equals(run.input())) throw new ApiException(ProblemCode.VALIDATION_FAILED,"Change at least one value.");
        var errors=validator.validate(json.write(release.inputSchema()),json.write(corrected),json.write(release.inputUi()));
        if (!errors.isEmpty()) throw new ApiException(ProblemCode.VALIDATION_FAILED,"The corrected request does not match its pinned form.")
                .withFieldErrors(errors.stream().map(e->new ApiException.FieldError("/changes"+e.pointer(),e.code(),e.message())).toList());
        try { engine.correctInput(run.engineProcessInstanceId(),json.write(corrected),caller.subjectId()); }
        catch (EngineCommands.EngineConflictException e) { throw new ApiException(ProblemCode.RUN_BUSY,"The execution changed; reload the request."); }
        int version=runs.correctInput(workspace,runId,run.version(),corrected);
        events.append(workspace,runId,"INPUT_CORRECTED",run.attention().path("nodeId").asString(),caller.subjectId(),
                Map.of("before",before,"after",after,"reason",request.reason().trim(),"fields",request.changes().size(),"version",version));
        audit.write(new AuditWriter.Entry(workspace,AuditWriter.ActorType.USER,caller.subjectId(),"run.input-corrected","run",runId.toString(),AuditWriter.Outcome.SUCCESS,
                Map.of("fields",request.changes().size(),"version",version)));
        idempotency.complete(claim,"runInputCorrection",Integer.toString(version),200);
        return new Result(runId,version,request.changes().size(),false);
    }
    private static ApiException blocked(String reason) { return new ApiException(ProblemCode.RUN_NOT_RECOVERABLE,reason); }
    private static void guardAncestors(JsonNode definition,String current) {
        if (current==null || current.isBlank()) throw blocked("The incident does not identify a correctable step.");
        boolean found=false; for (JsonNode node:definition.path("nodes")) found|=current.equals(node.path("id").asString());
        if (!found) throw blocked("The incident does not identify a step in the pinned release.");
        Set<String> previous=new HashSet<>(); previous.add(current); boolean changed;
        do { changed=false; for (JsonNode transition:definition.path("transitions"))
            if (previous.contains(transition.path("to").asString())) changed|=previous.add(transition.path("from").asString());
        } while(changed);
        for (JsonNode node:definition.path("nodes")) if (previous.contains(node.path("id").asString()) && !current.equals(node.path("id").asString())
                && !Set.of("start","parallelSplit","parallelJoin").contains(node.path("type").asString()))
            throw blocked("A preceding step may have consumed this input. Start a corrected request instead.");
    }
}
