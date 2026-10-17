package com.orqix.runtime.internal;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.MailTemplates;
import com.orqix.identity.*;
import com.orqix.notification.NotificationService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;

/** Transactional stage email outbox and one durable SLA breach per task. SMTP is performed only by the worker. */
@Service
@Profile("!migrate")
public class StageMail {
    private final JdbcClient jdbc;
    private final RunStore runs;
    private final TaskStore tasks;
    private final TaskService taskService;
    private final ReleaseQueries releases;
    private final SubjectService subjects;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final RunEvents events;
    private final NotificationService notifications;
    private final StageMailTransport transport;
    private final Json json;
    private final Clock clock;
    private final String origin;

    public StageMail(JdbcClient jdbc, RunStore runs, TaskStore tasks, @Lazy TaskService taskService,
            ReleaseQueries releases, SubjectService subjects, PolicyService policy, AuditWriter audit,
            @Lazy RunEvents events, NotificationService notifications, StageMailTransport transport, Json json, Clock clock,
            @Value("${orqix.mail.application-origin:http://localhost:8080}") String origin) {
        this.jdbc=jdbc; this.runs=runs; this.tasks=tasks; this.taskService=taskService; this.releases=releases;
        this.subjects=subjects; this.policy=policy; this.audit=audit; this.events=events;
        this.notifications=notifications; this.transport=transport; this.json=json; this.clock=clock;
        var uri=java.net.URI.create(origin);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !Set.of("","/").contains(uri.getPath()))
            throw new IllegalArgumentException("Mail application-origin must be an HTTP(S) origin.");
        this.origin=origin.replaceAll("/$", "");
    }

    @EventListener
    public void onEvent(RunEvents.Appended event) {
        String kind=switch(event.type()) {
            case "TASK_CREATED", "TASK_CLAIMED", "TASK_REASSIGNED", "TASK_DISPATCHED" -> "assignment";
            case "TASK_DECIDED" -> "reject".equals(event.data().get("action")) ? "rejection" : "completion";
            case "TASK_SLA_BREACHED" -> "overdue";
            case "RUN_COMPLETED", "RUN_CANCELLED" -> "terminal";
            default -> null;
        };
        if (kind==null) return;
        var run=runs.find(event.workspaceId(),event.runId()).orElseThrow();
        var release=releases.require(event.workspaceId(),run.releaseId());
        JsonNode node=event.nodeId()==null ? null : release.node(event.nodeId());
        JsonNode template="terminal".equals(kind) ? release.definition().path("terminalNotification")
                : node==null ? null : node.path("config").path("notifications").path(kind);
        if (template==null || template.isMissingNode()) return;
        // Tier-1 is sequential. Match the event's task if given, otherwise the latest task for this node.
        var matching=tasks.forRun(event.workspaceId(),event.runId()).stream()
                .filter(t -> Objects.equals(t.nodeId(),event.nodeId())).toList();
        TaskStore.TaskRow task=matching.isEmpty() ? null : matching.getLast();
        var values=Map.of("reference",run.reference(),"workflow",release.workflowName(),
                "stage",node==null ? "Case" : node.path("label").asString(),
                "outcome",run.outcome()==null ? kind : run.outcome(),
                "dueAt",task==null || task.dueAt()==null ? "" : task.dueAt().toString(),
                "link",origin+"/w/"+event.workspaceId()+"/runs/"+run.id());
        var recipients=recipients(run,task,template);
        String key=run.id()+":"+event.sequence()+":"+kind;
        if (recipients.isEmpty()) {
            insert(run,task,event.nodeId(),kind,key+":unresolved",null,template,values,"blocked","NO_ELIGIBLE_RECIPIENT");
            attention(run,key,"No eligible email recipient is configured for this workflow event.");
        } else for (UUID recipient:recipients) {
            insert(run,task,event.nodeId(),kind,key+":"+recipient,recipient,template,values,"queued",null);
        }
    }

    private void insert(RunStore.RunRow run, TaskStore.TaskRow task, String node, String kind, String key,
            UUID recipient, JsonNode template, Map<String,String> values, String status, String failure) {
        jdbc.sql("""
                INSERT INTO stage_mail(id,workspace_id,run_id,task_id,node_id,event_kind,dedup_key,recipient_subject_id,
                    template,values_json,status,failure_code,created_at,updated_at,next_attempt_at)
                VALUES(:id,:ws,:run,:task,:node,:kind,:key,:recipient,CAST(:template AS jsonb),CAST(:values AS jsonb),:status,:failure,:now,:now,:now)
                ON CONFLICT(dedup_key) DO NOTHING
                """).param("id",Ids.newId()).param("ws",run.workspaceId()).param("run",run.id())
                .param("task","terminal".equals(kind) || task==null ? null : task.taskId()).param("node",node)
                .param("kind",kind).param("key",key).param("recipient",recipient).param("template",json.write(template))
                .param("values",json.write(values)).param("status",status).param("failure",failure)
                .param("now",Sql.ts(clock.instant())).update();
    }

    private Set<UUID> recipients(RunStore.RunRow run, TaskStore.TaskRow task, JsonNode template) {
        Set<UUID> result=new LinkedHashSet<>();
        for (JsonNode kind:template.path("recipients")) {
            if ("requester".equals(kind.asString())) {
                if (access(run.workspaceId(),run.initiator())!=null) result.add(run.initiator());
            } else if ("assignee".equals(kind.asString())) {
                if (task!=null && task.assignee()!=null && taskService.eligibleSubject(run.workspaceId(),task,task.assignee())) result.add(task.assignee());
            } else {
                // Explicit bound: do not silently fan out through a truncated directory.
                var ids=jdbc.sql("SELECT id FROM subject WHERE disabled_at IS NULL ORDER BY id LIMIT 501").query(UUID.class).list();
                if (ids.size()>500) return Set.of();
                for (UUID id:ids) {
                    var a=access(run.workspaceId(),id);
                    if (a==null) continue;
                    boolean eligible=switch(kind.asString()) {
                        case "candidateGroup" -> task!=null && taskService.eligibleSubject(run.workspaceId(),task,id);
                        case "supervisor" -> a.has(Permission.RUN_READ) && a.groupKeys().contains(template.path("supervisorGroupRef").asString());
                        case "operators" -> a.has(Permission.RUN_OPERATE);
                        default -> false;
                    };
                    if (eligible) result.add(id);
                }
            }
        }
        return result;
    }

    private WorkspaceAccess access(UUID ws, UUID id) {
        var s=subjects.find(id).orElse(null);
        if (s==null || s.disabled()) return null;
        return policy.freshAccess(new Caller(s.id(),s.displayName(),s.email(),s.issuer(),s.externalSubject(),subjects.storedGroups(id),false),ws)
                .filter(WorkspaceAccess::member).orElse(null);
    }

    private void attention(RunStore.RunRow run, String key, String message) {
        audit.write(new AuditWriter.Entry(run.workspaceId(),AuditWriter.ActorType.SYSTEM,null,"notification.attention",
                "run",run.id().toString(),AuditWriter.Outcome.FAILED,Map.of("reason",message)));
        var template=json.object(); template.putArray("recipients").add("operators");
        for (UUID id:recipients(run,null,template)) notifications.create(run.workspaceId(),id,run.id(),
                NotificationService.Kind.RUN_ATTENTION,"stage-mail-attention:"+key+":"+id,message);
    }

    @Scheduled(fixedDelayString="${orqix.mail.poll-delay:1s}",scheduler="stageMailScheduler")
    @Transactional
    public void dispatch() {
        if (!transport.enabled()) return;
        var ids=jdbc.sql("SELECT id FROM stage_mail WHERE status='queued' AND next_attempt_at<=:now ORDER BY next_attempt_at,id LIMIT 100 FOR UPDATE SKIP LOCKED")
                .param("now",Sql.ts(clock.instant())).query(UUID.class).list();
        for(UUID id:ids) {
            var row=jdbc.sql("SELECT workspace_id,run_id,task_id,event_kind,recipient_subject_id,template::text,values_json::text,attempts FROM stage_mail WHERE id=:id")
                    .param("id",id).query((rs,n)->new Pending(Sql.uuid(rs,"workspace_id"),Sql.uuid(rs,"run_id"),rs.getString("task_id"),rs.getString("event_kind"),
                            Sql.uuid(rs,"recipient_subject_id"),json.parse(rs.getString("template")),json.parse(rs.getString("values_json")),rs.getInt("attempts"))).single();
            // Try locks avoid deadlocking a user decision (task -> run) or operator closure (run -> task).
            boolean locked=jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id=:ws AND id=:run FOR UPDATE SKIP LOCKED")
                    .param("ws",row.ws()).param("run",row.run()).query(UUID.class).optional().isPresent();
            if (!locked) continue;
            var run=runs.find(row.ws(),row.run()).orElseThrow();
            TaskStore.TaskRow task=row.task()==null ? null : tasks.tryLock(row.ws(),row.task()).orElse(null);
            if(row.task()!=null && task==null) continue;
            boolean transientTask=Set.of("assignment","overdue").contains(row.kind());
            if (transientTask && (!run.isActive() || task==null || !task.isOpen())
                    || !recipients(run,task,row.template()).contains(row.recipient())) {
                finish(id,row,"suppressed",row.attempts(),"STALE_OR_UNAUTHORIZED_RECIPIENT"); continue;
            }
            var subject=subjects.find(row.recipient()).orElseThrow();
            if(subject.email()==null || subject.email().isBlank()) {
                finish(id,row,"blocked",row.attempts(),"MISSING_EMAIL"); attention(run,id.toString(),"An eligible notification recipient has no email address."); continue;
            }
            Map<String,String> values=new HashMap<>();row.values().properties().forEach(e -> values.put(e.getKey(),e.getValue().asString()));
            try {
                transport.send(id,subject.email(),MailTemplates.render(row.template().path("subject").asString(),values),
                        MailTemplates.render(row.template().path("body").asString(),values));
                finish(id,row,"providerAccepted",row.attempts()+1,null);
            } catch (org.springframework.mail.MailException | jakarta.mail.MessagingException e) {
                String status=row.attempts()+1>=3 ? "failed" : "queued";
                finish(id,row,status,row.attempts()+1,"SMTP_DELIVERY_FAILED");
                if("failed".equals(status)) attention(run,id.toString(),"Stage email failed after three delivery attempts.");
            }
        }
    }
    private record Pending(UUID ws,UUID run,String task,String kind,UUID recipient,JsonNode template,JsonNode values,int attempts) {}
    private void finish(UUID id, Pending row, String status, int attempts, String failure) {
        jdbc.sql("UPDATE stage_mail SET status=:status,attempts=:attempts,failure_code=:failure,updated_at=:now,next_attempt_at=:next WHERE id=:id")
                .param("status",status).param("attempts",attempts).param("failure",failure).param("now",Sql.ts(clock.instant()))
                .param("next",Sql.ts(clock.instant().plusSeconds(attempts==1 ? 10 : 30))).param("id",id).update();
        audit.write(new AuditWriter.Entry(row.ws(),AuditWriter.ActorType.SYSTEM,null,"notification.delivery",
                "stageMail",id.toString(),"providerAccepted".equals(status) ? AuditWriter.Outcome.SUCCESS : AuditWriter.Outcome.FAILED,
                Map.of("runId",row.run().toString(),"status",status,"attempts",attempts)));
    }

    @Scheduled(fixedDelayString="${orqix.sla.poll-delay:30s}")
    @Transactional
    public void detectBreaches() {
        var candidates=jdbc.sql("""
                SELECT t.workspace_id,t.run_id,t.engine_task_id FROM task_context t
                JOIN workflow_run r ON r.workspace_id=t.workspace_id AND r.id=t.run_id
                JOIN workflow_release rel ON rel.workspace_id=r.workspace_id AND rel.id=r.release_id
                WHERE t.status='open' AND t.due_at<:now AND rel.definition->>'acceptanceProfile'='tier1'
                AND NOT EXISTS(SELECT 1 FROM task_sla_breach b WHERE b.workspace_id=t.workspace_id AND b.task_id=t.engine_task_id)
                ORDER BY t.due_at,t.engine_task_id LIMIT 100
                """).param("now",Sql.ts(clock.instant())).query((rs,n)->List.of(Sql.uuid(rs,"workspace_id").toString(),Sql.uuid(rs,"run_id").toString(),rs.getString("engine_task_id"))).list();
        for(var item:candidates) {
            UUID ws=UUID.fromString(item.get(0)), runId=UUID.fromString(item.get(1));
            if(jdbc.sql("SELECT id FROM workflow_run WHERE workspace_id=:ws AND id=:run FOR UPDATE SKIP LOCKED")
                    .param("ws",ws).param("run",runId).query(UUID.class).optional().isEmpty()) continue;
            var run=runs.find(ws,runId).orElseThrow(); var task=tasks.tryLock(ws,item.get(2)).orElse(null);
            if(!run.isActive() || task==null || !task.isOpen() || !task.dueAt().isBefore(clock.instant())) continue;
            int inserted=jdbc.sql("INSERT INTO task_sla_breach(workspace_id,task_id,run_id,due_at,detected_at) VALUES(:ws,:task,:run,:due,:now) ON CONFLICT DO NOTHING")
                    .param("ws",ws).param("task",task.taskId()).param("run",runId).param("due",Sql.ts(task.dueAt())).param("now",Sql.ts(clock.instant())).update();
            if(inserted==0) continue;
            events.append(ws,runId,"TASK_SLA_BREACHED",task.nodeId(),null,Map.of("taskId",task.taskId(),"dueAt",task.dueAt().toString(),"releaseId",run.releaseId().toString()));
            audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.SYSTEM,null,"task.sla-breached","task",task.taskId(),AuditWriter.Outcome.SUCCESS,
                    Map.of("runId",runId.toString(),"dueAt",task.dueAt().toString(),"releaseId",run.releaseId().toString())));
        }
    }
}
