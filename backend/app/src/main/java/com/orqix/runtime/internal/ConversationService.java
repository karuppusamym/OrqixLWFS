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
        for (String k:List.of("stage","message","choices","field","review","runId","taskId","taskAction")) {
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
            state.removeAll();state.put("stage","idle").put("message","Nothing was changed and the draft is cleared. Say Start a request, My tasks or My requests when you are ready.");return;
        }
        if (lower.equals("confirm") || lower.equals("submit")) { confirm(caller,ws,state,key);return; }
        if (lower.equals("my tasks")) {
            state.removeAll();
            var choices=state.putArray("choices");
            Set<String> seen=new HashSet<>();
            for(String tab:List.of("assigned","claimable")) for(var task:tasks.listTasks(caller,ws,tab,null,25).items())
                if(seen.add(task.id())) choices.addObject().put("label",taskLabel(task.runReference(),task.requestTitle(),task.label())).put("command","review "+task.id());
            state.put("stage","idle").put("message",choices.isEmpty()?"You have no tasks waiting for you right now."
                    :"These tasks are waiting for you. Choose one to see the request before you decide.");
            return;
        }
        if (lower.equals("my requests")) {
            state.removeAll();var choices=state.putArray("choices");
            for(var run:runs.listMyRequests(caller,ws,null,null,25).items())
                choices.addObject().put("label",run.reference()+" - "+(run.requestTitle()==null||run.requestTitle().isBlank()?"":run.requestTitle()+" - ")+lifecycleWords(run.lifecycle())).put("command","status "+run.reference());
            state.put("stage","idle").put("message",choices.isEmpty()?"You have not made any requests yet. Choose Start a request to begin one."
                    :"Your latest requests. Choose one to see where it stands.");
            return;
        }
        if (lower.startsWith("status ")) {
            String ref=text.substring(7).trim();UUID runId;
            if (Pattern.matches("(?i)ORQ-[0-9]+",ref))
                runId=jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id=:ws AND reference=:ref")
                        .param("ws",ws).param("ref",ref.toUpperCase(Locale.ROOT)).query(UUID.class).optional().orElseThrow(ApiException::notFound);
            else runId=uuid(ref);
            var run=runs.getRun(caller,ws,runId);
            state.removeAll();state.put("stage","completed").put("runId",run.id().toString()).put("message",run.reference()+(run.requestTitle()==null||run.requestTitle().isBlank()?"":" ("+run.requestTitle()+")")+" is "+lifecycleWords(run.lifecycle())
                    +(run.outcome()==null?"":" ("+run.outcome()+")")+"."+(run.attention()==null?"":" "+run.attention().message()));return;
        }
        if (lower.startsWith("review ")) { reviewTask(caller,ws,state,text.substring(7).trim());return; }
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
            state.removeAll();state.put("stage","idle").put("message",candidates.isEmpty()?"I could not find a request type you can start with that name. Choose Start a request to see what is available.":(query==null?"What would you like to request?":"Which of these did you mean?"));
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
        if (state.path("stage").asString().equals("confirmingTask")) {
            state.put("message","Choose Approve or Reject below. Nothing is recorded until you confirm, and Discard leaves the task untouched.");
            return;
        }
        state.removeAll();state.put("stage","idle").put("message","I can help you start a request, decide a task that is waiting for you, or check on a request you made. Choose one below, or type what you want, for example Start Purchase approval.");
        var menu=state.putArray("choices");
        menu.addObject().put("label","Start a request").put("command","workflows");
        menu.addObject().put("label","My tasks").put("command","my tasks");
        menu.addObject().put("label","My requests").put("command","my requests");
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
        if(name==null) { state.put("message","To change a value, use the Change button beside it. To submit the request as shown, choose Submit request or say confirm.");return; }
        JsonNode schema=state.path("schema").path("properties").path(name);
        boolean required=false;for(JsonNode n:state.path("schema").path("required"))if(name.equals(n.asString()))required=true;
        if(raw.equalsIgnoreCase("skip") && !required) { ((ObjectNode)state.path("input")).remove(name); ((tools.jackson.databind.node.ArrayNode)state.path("skipped")).add(name);ask(state);return; }
        JsonNode value;
        for(JsonNode option:schema.path("enum")) if(option.isString() && option.asString().equalsIgnoreCase(raw)) raw=option.asString();
        try { value=scalar(schema,raw); }
        catch(RuntimeException ex) { state.put("message",invalid(schema,name));return; }
        ObjectNode one=json.object().put("type","object").put("additionalProperties",false);
        one.putObject("properties").set(name,schema);one.putArray("required").add(name);
        ObjectNode answer=json.object();answer.set(name,value);
        var errors=forms.validate(json.write(one),json.write(answer));
        if(!errors.isEmpty()) {
            state.put("message","format".equals(errors.getFirst().code())&&"date".equals(schema.path("format").asString(""))
                    ?invalid(schema,name):errors.getFirst().message());return;
        }
        ((ObjectNode)state.path("input")).set(name,value);ask(state);
    }
    private static final String DATE_HINT="Enter the date as year-month-day, for example 2026-10-14.";
    private static final java.time.format.DateTimeFormatter READABLE_DATE=java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy",Locale.ENGLISH);
    private String invalid(JsonNode schema,String name) {
        String title=schema.path("title").asString(name);
        if("date".equals(schema.path("format").asString("")))return "Please enter "+title+" as year-month-day, for example 2026-10-14.";
        return switch(schema.path("type").asString()) {
            case "number","integer" -> "Please enter "+title+" as a number, for example 1500 or 1,500.50.";
            case "boolean" -> "Please answer yes or no for "+title+".";
            case "array" -> "Please list the items for "+title+" separated by commas, for example Laptop, Monitor.";
            default -> "That answer is not valid for "+title+". Please try again.";
        };
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
            case "array" -> list(schema,raw);
            default -> json.mapper().valueToTree(raw);
        };
    }
    /** A list is a JSON array or, more naturally, comma-separated items such as Laptop, Monitor. */
    private JsonNode list(JsonNode schema,String raw) {
        if(raw.startsWith("[")) return json.parse(raw);
        String itemType=schema.path("items").path("type").asString("string");
        if(itemType.equals("object")||itemType.equals("array")) throw new IllegalArgumentException();
        var array=json.mapper().createArrayNode();
        for(String part:raw.split("[,\n]")) {
            String item=part.trim();
            if(item.isEmpty())continue;
            array.add(scalar(schema.path("items"),item));
        }
        return array;
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
            String title=schema.path("title").asString(name);
            String question=title.endsWith("?")?title:"What is "+title+"?";
            if("date".equals(schema.path("format").asString("")))question+=" "+DATE_HINT;
            state.put("stage","collecting").put("message",question);return;
        }
        state.remove("field");state.put("stage","review").put("message","Review "+state.path("workflowName").asString()+". Check the details below. Nothing is submitted until you confirm; you can change a value first or discard the draft.");
        ObjectNode review=state.putObject("review");review.put("workflowName",state.path("workflowName").asString());review.set("input",state.path("input"));
        review.set("fields",fields(state.path("schema"),state.path("order"),state.path("input")));
    }
    /** Labelled values in form order, so a person never sees internal field names. */
    private tools.jackson.databind.node.ArrayNode fields(JsonNode schema,JsonNode order,JsonNode input) {
        var result=json.mapper().createArrayNode();
        Set<String> names=new LinkedHashSet<>();
        for(JsonNode n:order)names.add(n.asString());
        input.properties().forEach(e->names.add(e.getKey()));
        for(String name:names) {
            if(!input.has(name))continue;
            JsonNode property=schema.path("properties").path(name);
            ObjectNode row=result.addObject();
            row.put("name",name).put("label",property.path("title").asString(name)).set("value",input.get(name));
            if(input.get(name).isString()&&"date".equals(property.path("format").asString(""))) {
                try { row.put("display",java.time.LocalDate.parse(input.get(name).asString()).format(READABLE_DATE)); }
                catch(java.time.format.DateTimeParseException ignored) { /* shown as typed */ }
            }
        }
        return result;
    }
    private static String lifecycleWords(String lifecycle) {
        return switch(lifecycle) {
            case "running"->"in progress";
            case "waiting"->"waiting for a decision";
            case "needsAttention"->"needs attention";
            case "completed"->"finished";
            case "cancelled"->"cancelled";
            default->lifecycle;
        };
    }
    private static String taskLabel(String reference,String requestTitle,String label) {
        return reference+" - "+(requestTitle==null||requestTitle.isBlank()?label:requestTitle+" ("+label+")");
    }
    private void reviewTask(Caller caller,UUID ws,ObjectNode state,String taskId) {
        if(!Pattern.matches("[A-Za-z0-9_-]{1,128}",taskId))throw ApiException.malformed("Choose a task from My tasks.");
        var detail=tasks.getTask(caller,ws,taskId);
        if(!detail.allowedActions().claim().allowed() && !detail.allowedActions().decide().allowed())throw ApiException.forbidden();
        state.removeAll();state.put("stage","confirmingTask").put("taskId",taskId).put("taskVersion",detail.version());
        taskReview(state,detail);
        state.put("message","Review "+detail.runReference()+(detail.requestTitle()==null?"":" - "+detail.requestTitle())
                +". Nothing is recorded until you approve or reject and then confirm.");
        state.putArray("choices").addObject().put("label","Approve").put("command","approve "+taskId);
    }
    private void taskReview(ObjectNode state,RuntimeDtos.TaskDetail detail) {
        ObjectNode review=state.putObject("review");
        review.put("workflowName",detail.runReference()+(detail.requestTitle()==null?"":" - "+detail.requestTitle()));
        JsonNode input=detail.request()==null||detail.request().input()==null||!detail.request().input().isObject()?json.object():detail.request().input();
        review.set("input",input);
        JsonNode schema=detail.request()==null||detail.request().inputSchema()==null?json.object():detail.request().inputSchema();
        JsonNode order=detail.request()==null||detail.request().inputUi()==null?json.mapper().createArrayNode():detail.request().inputUi().path("order");
        review.set("fields",fields(schema,order,input));
    }
    private void task(Caller caller,UUID ws,ObjectNode state,String text) {
        int space=text.indexOf(' ');String action=text.substring(0,space).toLowerCase(Locale.ROOT),rest=text.substring(space+1).trim();
        int colon=rest.indexOf(':');String taskId=colon<0?rest:rest.substring(0,colon).trim(),reason=colon<0?null:rest.substring(colon+1).trim();
        if(!Pattern.matches("[A-Za-z0-9_-]{1,128}",taskId))throw ApiException.malformed("Choose a task from My tasks.");
        var detail=tasks.getTask(caller,ws,taskId);
        if(!detail.allowedActions().claim().allowed() && !detail.allowedActions().decide().allowed())throw ApiException.forbidden();
        state.removeAll();state.put("stage","confirmingTask").put("taskId",taskId).put("taskAction",action)
                .put("taskVersion",detail.version());if(reason!=null&&!reason.isBlank())state.put("reason",reason);
        taskReview(state,detail);
        String what=detail.runReference()+(detail.requestTitle()==null?"":" - "+detail.requestTitle());
        String verb=switch(action){case "approve"->"approve";case "reject"->"reject";default->"take";};
        state.put("message",action.equals("reject")&&reason==null
                ?"A reason is required to reject "+what+". Tell the requester what to change."
                :"You are about to "+verb+" "+what+(reason==null?"":" because: "+reason)+". Nothing is recorded until you confirm.");
    }
    private void confirm(Caller caller,UUID ws,ObjectNode state,String key) {
        if(state.path("stage").asString().equals("review")) {
            var result=runs.start(caller,ws,key,new RuntimeDtos.StartRunRequest(uuid(state.path("workflowId").asString()),
                    uuid(state.path("releaseId").asString()),null,state.path("input")));
            state.removeAll();state.put("stage","completed").put("runId",result.run().id().toString()).put("message","Submitted "+result.run().reference()+(result.run().requestTitle()==null||result.run().requestTitle().isBlank()?"":" ("+result.run().requestTitle()+")")+". You can follow its progress or start another request.");return;
        }
        if(state.path("stage").asString().equals("confirmingTask")) {
            String id=state.path("taskId").asString(),action=state.path("taskAction").asString("");
            String reason=state.path("reason").asString(null);
            if(action.isEmpty()) {state.put("message","Choose Approve or Reject first, then confirm.");return;}
            if(action.equals("reject") && (reason==null||reason.isBlank())) {state.put("message","A reason is required to reject. Tell the requester what to change.");return;}
            var task=tasks.getTask(caller,ws,id);
            if(task.version()!=state.path("taskVersion").asInt())throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT).with("currentVersion",task.version());
            if(task.allowedActions().claim().allowed())task=tasks.claim(caller,ws,id,task.version());
            if(!action.equals("claim"))tasks.decide(caller,ws,id,key,new RuntimeDtos.DecisionRequest(task.version(),action,reason,null));
            state.removeAll();state.put("stage","completed").put("taskId",id).put("message",action.equals("claim")?"You took "+task.runReference()+".":
                    "You "+(action.equals("approve")?"approved ":"rejected ")+task.runReference()+".");return;
        }
        state.put("message","There is nothing to confirm yet. Start a request or choose a task first.");
    }
    private static UUID uuid(String value) {try{return UUID.fromString(value);}catch(RuntimeException ex){throw ApiException.malformed("Invalid identifier.");}}
}
