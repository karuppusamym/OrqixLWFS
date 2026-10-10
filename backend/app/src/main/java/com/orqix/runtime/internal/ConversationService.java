package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.StartableWorkflows;


import com.orqix.compiler.FormValidator;
import com.orqix.identity.Caller;
import com.orqix.identity.PolicyService;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.internal.RunService;
import com.orqix.runtime.internal.TaskService;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Resumable guided execution with deterministic commands and optional natural-language workflow matching. */
@Service
public class ConversationService {
    public record Reply(JsonNode body, boolean replayed) {}
    private record Session(UUID id, ObjectNode state, int version) {}
    private final JdbcClient jdbc;
    private final Json json;
    private final PolicyService policy;
    private final StartableWorkflows workflows;


    private final RunService runs;
    private final TaskService tasks;
    private final FormValidator forms;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final Clock clock;
    private final OrqixProperties properties;
    public ConversationService(JdbcClient jdbc, Json json, PolicyService policy, StartableWorkflows workflows,
            RunService runs, TaskService tasks,
            FormValidator forms, IdempotencyService idempotency, AuditWriter audit, Clock clock,
            OrqixProperties properties) {
        this.jdbc=jdbc; this.json=json; this.policy=policy; this.workflows=workflows;
        this.runs=runs; this.tasks=tasks; this.forms=forms;
        this.idempotency=idempotency; this.audit=audit; this.clock=clock; this.properties=properties;
    }

    public void validateMessage(Caller caller, UUID ws, String key, JsonNode body) {
        policy.requireMember(caller, ws);
        IdempotencyService.requireKey(key);
        if (body==null || !body.isObject() || !body.path("message").isString()
                || body.path("message").asString().isBlank() || body.path("message").asString().length()>4000)
            throw ApiException.malformed("message must be between 1 and 4000 characters.");
        body.properties().forEach(e -> {
            if (!Set.of("message", "conversationId", "expectedVersion").contains(e.getKey()))
                throw ApiException.malformed("Unknown conversation request property.");
        });
    }

    public boolean isCompletedReplay(Caller caller, UUID ws, String key, JsonNode body) {
        return idempotency.hasCompletedReplay(ws, caller.subjectId(), "sendConversationMessage", key,
                idempotency.requestHash("sendConversationMessage", Map.of("workspaceId", ws.toString()), body));
    }

    @Transactional(readOnly=true)
    public JsonNode get(Caller caller, UUID ws, UUID id) {
        policy.requireMember(caller, ws);
        Session session = load(caller, ws, id, false);
        return view(session);
    }

    @Transactional
    public Reply message(Caller caller, UUID ws, String key, JsonNode body,
            ConversationIntentService.Interpretation interpretation) {
        validateMessage(caller, ws, key, body);
        key=IdempotencyService.requireKey(key);
        UUID existing=null;
        if (body.hasNonNull("conversationId")) existing=uuid(body.path("conversationId").asString());
        Session current=existing==null ? null : load(caller, ws, existing, true);
        var claim=idempotency.claim(ws, caller.subjectId(), "sendConversationMessage", key,
                idempotency.requestHash("sendConversationMessage", Map.of("workspaceId", ws.toString()), body));
        if (claim.isReplay()) {
            JsonNode response=jdbc.sql("SELECT response::text FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id "
                    + "WHERE t.id=:id AND c.workspace_id=:ws AND c.subject_id=:subject AND c.expires_at>:now")
                    .param("id", uuid(claim.replay().resourceId())).param("ws",ws).param("subject",caller.subjectId())
                    .param("now",Sql.ts(clock.instant())).query((rs,n)->json.parse(rs.getString(1))).optional()
                    .orElseThrow(ApiException::notFound);
            return new Reply(response,true);
        }
        if (current!=null && (!body.path("expectedVersion").isIntegralNumber()
                || body.path("expectedVersion").asInt()!=current.version()))
            throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT,"The conversation changed; reload it before sending.")
                    .with("currentVersion",current.version());
        if (current != null && current.version() >= 200)
            throw new ApiException(ProblemCode.RATE_LIMITED,"Start a new conversation after 200 messages.");
        if (current==null) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope,0))")
                    .param("scope","conversation:"+ws+":"+caller.subjectId())
                    .query((rs,n)->true).single();
            long count=jdbc.sql("SELECT count(*) FROM conversation WHERE workspace_id=:ws AND subject_id=:subject AND expires_at>:now")
                    .param("ws",ws).param("subject",caller.subjectId()).param("now",Sql.ts(clock.instant())).query(Long.class).single();
            if (count>=50) throw new ApiException(ProblemCode.RATE_LIMITED,"You have too many active conversations.");
            current=new Session(Ids.newId(),json.object().put("stage","idle"),0);
        }
        ObjectNode state=current.state();
        state.remove("choices");
        handle(caller,ws,state,body.path("message").asString().trim(),key,interpretation);
        Session saved=new Session(current.id(),state,current.version()+1);
        jdbc.sql("INSERT INTO conversation(id,workspace_id,subject_id,state,version,expires_at,updated_at) "
                + "VALUES(:id,:ws,:subject,CAST(:state AS jsonb),:version,:expiry,:now) "
                + "ON CONFLICT(id) DO UPDATE SET state=EXCLUDED.state,version=EXCLUDED.version,updated_at=EXCLUDED.updated_at,expires_at=EXCLUDED.expires_at")
                .param("id",saved.id()).param("ws",ws).param("subject",caller.subjectId()).param("state",json.write(state))
                .param("version",saved.version()).param("expiry",Sql.ts(clock.instant().plus(properties.idempotency().retention())))
                .param("now",Sql.ts(clock.instant())).update();
        JsonNode response=view(saved);
        UUID turn=Ids.newId();
        jdbc.sql("INSERT INTO conversation_turn(id,conversation_id,response) VALUES(:id,:conversation,CAST(:response AS jsonb))")
                .param("id",turn).param("conversation",saved.id()).param("response",json.write(response)).update();
        idempotency.complete(claim,"conversationTurn",turn.toString(),200);
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"conversation.message",
                "conversation",saved.id().toString(),AuditWriter.Outcome.SUCCESS,
                Map.of("stage",state.path("stage").asString(),"version",saved.version())));
        // Replay retention protects responses until the key expires. Raw chat text is never stored.
        jdbc.sql("DELETE FROM conversation c WHERE c.expires_at<:now AND NOT EXISTS (SELECT 1 FROM conversation_turn t "
                + "JOIN idempotency_record i ON i.resource_type='conversationTurn' AND i.resource_id=t.id::text "
                + "WHERE t.conversation_id=c.id AND i.expires_at>:now)")
                .param("now",Sql.ts(clock.instant())).update();
        return new Reply(response,false);
    }

    private Session load(Caller caller,UUID ws,UUID id,boolean lock) {
        return jdbc.sql("SELECT id,state::text,version FROM conversation WHERE id=:id AND workspace_id=:ws "
                + "AND subject_id=:subject AND expires_at>:now"+(lock?" FOR UPDATE":""))
                .param("id",id).param("ws",ws).param("subject",caller.subjectId()).param("now",Sql.ts(clock.instant()))
                .query((rs,n)->new Session(UUID.fromString(rs.getString(1)),(ObjectNode)json.parse(rs.getString(2)),rs.getInt(3)))
                .optional().orElseThrow(ApiException::notFound);
    }
    private ObjectNode view(Session s) {
        ObjectNode result=json.object();
        result.put("id",s.id().toString()).put("version",s.version());
        for (String k:List.of("stage","message","choices","field","review","runId","taskId")) {
            JsonNode value=s.state().get(k);
            if (value!=null) result.set(k,value);
        }
        if (!result.has("choices")) result.putArray("choices");
        return result;
    }
    private void handle(Caller caller,UUID ws,ObjectNode state,String text,String key,
            ConversationIntentService.Interpretation interpretation) {
        String lower=text.toLowerCase(Locale.ROOT);
        if (lower.equals("cancel") || lower.equals("reset")) {
            state.removeAll();state.put("stage","idle").put("message","Draft cleared. Say start followed by a workflow name, my tasks, my requests, or status followed by a request reference.");return;
        }
        if (lower.equals("confirm") || lower.equals("submit")) { confirm(caller,ws,state,key);return; }
        if (lower.equals("my tasks")) {
            state.removeAll();state.put("stage","idle").put("message","Choose an available task to review. Approving or claiming asks for confirmation.");
            var choices=state.putArray("choices");
            for(String tab:List.of("assigned","claimable")) for(var task:tasks.listTasks(caller,ws,tab,null,25).items())
                choices.addObject().put("label",task.runReference()+" - "+task.label()).put("command","approve "+task.id());
            return;
        }
        if (lower.equals("my requests")) {
            state.removeAll();state.put("stage","idle").put("message","Your latest requests:");var choices=state.putArray("choices");
            for(var run:runs.listMyRequests(caller,ws,null,null,25).items())
                choices.addObject().put("label",run.reference()+" - "+run.lifecycle()).put("command","status "+run.reference());
            return;
        }
        if (lower.startsWith("status ")) {
            String ref=text.substring(7).trim();UUID runId;
            if (Pattern.matches("(?i)ORQ-[0-9]+",ref))
                runId=jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id=:ws AND reference=:ref")
                        .param("ws",ws).param("ref",ref.toUpperCase(Locale.ROOT)).query(UUID.class).optional().orElseThrow(ApiException::notFound);
            else runId=uuid(ref);
            var run=runs.getRun(caller,ws,runId);
            state.removeAll();state.put("stage","completed").put("runId",run.id().toString()).put("message",run.reference()+" is "+run.lifecycle()
                    +(run.outcome()==null?"":" - "+run.outcome())+(run.attention()==null?"":". "+run.attention().message()));return;
        }
        if (lower.startsWith("approve ") || lower.startsWith("reject ") || lower.startsWith("claim ")) {
            task(caller,ws,state,text);return;
        }
        if (lower.equals("workflows") || lower.startsWith("start ")) {
            String query=lower.equals("workflows")?null:text.substring(6).trim();
            if(query!=null && query.length()>100) throw ApiException.malformed("Use a workflow name of at most 100 characters.");
            var page=workflows.find(caller,ws,query);
            var exact=query==null?List.<StartableWorkflows.Choice>of():page.stream()
                    .filter(w->w.key().equalsIgnoreCase(query)||w.name().equalsIgnoreCase(query)).toList();
            var candidates=exact.isEmpty()?page:exact;
            if(query!=null && candidates.size()==1) { begin(caller,ws,state,candidates.getFirst().id());return; }
            state.removeAll();state.put("stage","idle").put("message",candidates.isEmpty()?"No published workflow you can start matched. Try workflows to see available choices.":"Choose a published workflow:");
            var choices=state.putArray("choices");for(var w:candidates) choices.addObject().put("label",w.name()).put("command","start "+w.key());
            return;
        }
        if (state.path("stage").asString().equals("collecting") || state.path("stage").asString().equals("review")) {
            collect(state,text);return;
        }
        if (interpretation != null) {
            if (!interpretation.message().isBlank()) {
                state.removeAll();
                state.put("stage", "idle").put("message", interpretation.message());
                return;
            }
            if (interpretation.choices().size() == 1) {
                begin(caller, ws, state, interpretation.choices().getFirst().id(), interpretation.input());
                return;
            }
            state.removeAll().put("stage", "idle").put("message",
                    "These workflows may fit your request. Choose one to continue:");
            var choices = state.putArray("choices");
            for (var choice : interpretation.choices()) {
                choices.addObject().put("label", choice.name()).put("command", "start " + choice.key());
            }
            return;
        }
        state.put("stage","idle").put("message","Say start followed by a workflow name, workflows, my tasks, my requests, or status followed by a request reference. Commands are guided and do not need an AI provider.");
    }
    private void begin(Caller caller,UUID ws,ObjectNode state,UUID workflow) {
        begin(caller, ws, state, workflow, null);
    }
    private void begin(Caller caller,UUID ws,ObjectNode state,UUID workflow,ObjectNode extracted) {
        var form=workflows.form(caller,ws,workflow);
        state.removeAll();state.put("stage","collecting").put("workflowId",workflow.toString())
                .put("workflowName",form.workflowName()).put("releaseId",form.releaseId().toString());
        state.set("schema",form.inputSchema());state.putObject("input");state.putArray("skipped");
        List<String> filled = new ArrayList<>();
        if (extracted != null) {
            for (var entry : form.inputSchema().path("properties").properties()) {
                if (!extracted.has(entry.getKey())) {
                    continue;
                }
                ObjectNode one = json.object().put("type", "object").put("additionalProperties", false);
                one.putObject("properties").set(entry.getKey(), entry.getValue().deepCopy());
                one.putArray("required").add(entry.getKey());
                ObjectNode candidate = json.object();
                candidate.set(entry.getKey(), extracted.get(entry.getKey()).deepCopy());
                if (forms.validate(json.write(one), json.write(candidate)).isEmpty()) {
                    ((ObjectNode) state.path("input")).set(entry.getKey(), extracted.get(entry.getKey()).deepCopy());
                    filled.add(entry.getValue().path("title").asString(entry.getKey()));
                }
            }
        }
        var order=state.putArray("order");Set<String> ordered=new LinkedHashSet<>();
        if(form.inputUi()!=null)for(JsonNode name:form.inputUi().path("order"))ordered.add(name.asString());
        form.inputSchema().path("properties").properties().forEach(e->ordered.add(e.getKey()));
        ordered.forEach(order::add);ask(state);
        if (!filled.isEmpty()) {
            state.put("message", "I matched " + form.workflowName() + " and picked up " + String.join(", ", filled)
                    + " from your message. " + state.path("message").asString());
        }
    }
    private void collect(ObjectNode state,String text) {
        String name=state.path("field").path("name").asString(null),raw=text;
        int equal=text.indexOf('=');
        if(equal>0 && state.path("schema").path("properties").has(text.substring(0,equal).trim())) {
            name=text.substring(0,equal).trim();raw=text.substring(equal+1).trim();
        }
        if(name==null) { state.put("message","To edit a value, send fieldName=value. Send confirm to submit the reviewed request.");return; }
        JsonNode schema=state.path("schema").path("properties").path(name);
        boolean required=false;for(JsonNode n:state.path("schema").path("required"))if(name.equals(n.asString()))required=true;
        if(raw.equalsIgnoreCase("skip") && !required) { ((ObjectNode)state.path("input")).remove(name); ((tools.jackson.databind.node.ArrayNode)state.path("skipped")).add(name);ask(state);return; }
        JsonNode value;
        try { value=scalar(schema,raw); }
        catch(IllegalArgumentException ex) { state.put("message","Enter a valid "+schema.path("type").asString()+" for "+schema.path("title").asString(name)+".");return; }
        ObjectNode one=json.object().put("type","object").put("additionalProperties",false);
        one.putObject("properties").set(name,schema);one.putArray("required").add(name);
        ObjectNode answer=json.object();answer.set(name,value);
        var errors=forms.validate(json.write(one),json.write(answer));
        if(!errors.isEmpty()) { state.put("message",errors.getFirst().message());return; }
        ((ObjectNode)state.path("input")).set(name,value);ask(state);
    }
    private JsonNode scalar(JsonNode schema,String raw) {
        return switch(schema.path("type").asString()) {
            case "number","integer" -> {
                if (!raw.matches("[-+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) throw new IllegalArgumentException();
                yield json.parse(new BigDecimal(raw.replace(",","")).toPlainString());
            }
            case "boolean" -> {
                if(!Set.of("true","false","yes","no").contains(raw.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
                yield json.parse(raw.equalsIgnoreCase("true")||raw.equalsIgnoreCase("yes")?"true":"false");
            }
            case "array" -> json.parse(raw);
            default -> json.mapper().valueToTree(raw);
        };
    }
    private void ask(ObjectNode state) {
        Set<String> skipped=new HashSet<>();for(JsonNode n:state.path("skipped"))skipped.add(n.asString());
        for(JsonNode entry:state.path("order")) {
            String name=entry.asString();if(state.path("input").has(name)||skipped.contains(name))continue;
            JsonNode schema=state.path("schema").path("properties").path(name);
            if(schema.isMissingNode())continue;
            ObjectNode field=json.object().put("name",name);schema.properties().forEach(e->field.set(e.getKey(),e.getValue()));
            boolean required=false;for(JsonNode n:state.path("schema").path("required"))if(name.equals(n.asString()))required=true;
            field.put("required",required);state.set("field",field);state.remove("review");
            state.put("stage","collecting").put("message","What is "+schema.path("title").asString(name)+"?"+(required?"":" You can say skip."));return;
        }
        state.remove("field");state.put("stage","review").put("message","Review "+state.path("workflowName").asString()+". Send confirm to submit, fieldName=value to edit, or cancel to discard.");
        ObjectNode review=state.putObject("review");review.put("workflowName",state.path("workflowName").asString());review.set("input",state.path("input"));
    }
    private void task(Caller caller,UUID ws,ObjectNode state,String text) {
        int space=text.indexOf(' ');String action=text.substring(0,space).toLowerCase(Locale.ROOT),rest=text.substring(space+1).trim();
        int colon=rest.indexOf(':');String taskId=colon<0?rest:rest.substring(0,colon).trim(),reason=colon<0?null:rest.substring(colon+1).trim();
        if(!Pattern.matches("[A-Za-z0-9_-]{1,128}",taskId))throw ApiException.malformed("Use the task identifier shown in My tasks.");
        var detail=tasks.getTask(caller,ws,taskId);
        if(!detail.allowedActions().claim().allowed() && !detail.allowedActions().decide().allowed())throw ApiException.forbidden();
        state.removeAll();state.put("stage","confirmingTask").put("taskId",taskId).put("taskAction",action)
                .put("taskVersion",detail.version());if(reason!=null)state.put("reason",reason);
        state.put("message","Confirm "+action+" for "+detail.runReference()+" - "+detail.label()+"."
                +(action.equals("reject")&&reason==null?" Supply a reason with reject taskId: reason before confirming.":" Send confirm to proceed or cancel to discard."));
    }
    private void confirm(Caller caller,UUID ws,ObjectNode state,String key) {
        if(state.path("stage").asString().equals("review")) {
            var result=runs.start(caller,ws,key,new RuntimeDtos.StartRunRequest(uuid(state.path("workflowId").asString()),
                    uuid(state.path("releaseId").asString()),null,state.path("input")));
            state.removeAll();state.put("stage","completed").put("runId",result.run().id().toString()).put("message","Submitted "+result.run().reference()+". You can follow its progress or start another request.");return;
        }
        if(state.path("stage").asString().equals("confirmingTask")) {
            String id=state.path("taskId").asString(),action=state.path("taskAction").asString();
            String reason=state.path("reason").asString(null);
            if(action.equals("reject") && (reason==null||reason.isBlank())) {state.put("message","A rejection requires a reason. Send reject "+id+": your reason.");return;}
            var task=tasks.getTask(caller,ws,id);
            if(task.version()!=state.path("taskVersion").asInt())throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion",task.version());
            if(task.allowedActions().claim().allowed())task=tasks.claim(caller,ws,id,task.version());
            if(!action.equals("claim"))tasks.decide(caller,ws,id,key,new RuntimeDtos.DecisionRequest(task.version(),action,reason,null));
            state.removeAll();state.put("stage","completed").put("taskId",id).put("message",action.equals("claim")?"Task claimed.":"Decision recorded.");return;
        }
        state.put("message","There is no reviewed action to confirm. Start a request or choose a task first.");
    }
    private static UUID uuid(String value) {try{return UUID.fromString(value);}catch(RuntimeException ex){throw ApiException.malformed("Invalid identifier.");}}
}
