package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.orqix.runtime.internal.StageMail;
import com.orqix.runtime.internal.StageMailTransport;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.node.ObjectNode;

class HackathonAcceptanceTest extends RuntimeTestBase {
    @Autowired StageMail mail;
    @Autowired com.orqix.identity.PolicyService policy;
    @MockitoBean StageMailTransport transport;
    private UUID draft(String closure, boolean provenance) throws Exception {
        for(String user:List.of("rhea.requester","mani.manager","olga.operator")) api(user).get("/me");
        String key="tier1-"+UUID.randomUUID().toString().substring(0,8);
        var created=api("dana.designer").post(Map.of("key",key,"name","Tier-1 acceptance"),"/workspaces/{ws}/workflows",PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id=UUID.fromString(created.body().path("id").asString());
        var d=(ObjectNode)json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),"examples/hackathon/tier1-approval.json")));
        d.put("workflowKey",key).put("closurePolicy",closure);
        if (provenance) d.putObject("provenance").put("gitCommit","a".repeat(40)).put("reviewReference","synthetic-test-only");
        assertThat(api("dana.designer").put(Map.of("definition",d),"\"r1\"","/workspaces/{ws}/workflows/{wf}/draft",PURCHASING,id).status()).isEqualTo(200);
        return id;
    }
    private UUID publish(String closure) throws Exception {
        UUID id=draft(closure,true);
        var published=api("paul.publisher").post(Map.of("draftRevision",2,"acknowledgeWarnings",true),UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{wf}/publish",PURCHASING,id);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return id;
    }
    private UUID start(UUID workflow) throws Exception {
        var result=api("rhea.requester").post(Map.of("workflowId",workflow,"input",Map.of("title","Private title excluded from evidence","amount",10)),UUID.randomUUID().toString(),"/workspaces/{ws}/runs",PURCHASING);
        assertThat(result.status()).as(result.toString()).isEqualTo(201);return UUID.fromString(result.body().path("id").asString());
    }
    private Response cancel(UUID run,String key) throws Exception {
        var detail=api("olga.operator").get("/workspaces/{ws}/runs/{run}",PURCHASING,run).body();
        return api("olga.operator").post(Map.of("reason","Close reviewed case","expectedVersion",detail.path("version").asInt()),key,"/workspaces/{ws}/runs/{run}/cancel",PURCHASING,run);
    }
    private long count(UUID run,String status) {
        return jdbc.sql("SELECT count(*) FROM stage_mail WHERE run_id=:run AND status=:status").param("run",run).param("status",status).query(Long.class).single();
    }
    @Test void publicationRequiresGitProvenanceAndAnIndependentPublisher() throws Exception {
        UUID missing=draft("CANCEL_OPEN_TASKS",false);
        assertThat(api("paul.publisher").post(Map.of("draftRevision",2,"acknowledgeWarnings",true),UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{wf}/publish",PURCHASING,missing).code()).isEqualTo("WORKFLOW_INVALID");
        UUID wf=draft("CANCEL_OPEN_TASKS",true);
        UUID author=UUID.fromString(api("dana.designer").get("/me").body().path("subject").path("id").asString());
        UUID binding=UUID.randomUUID();
        jdbc.sql("INSERT INTO role_binding(id,scope_type,workspace_id,role,principal_type,principal_value,source) VALUES(:id,'workspace',:ws,'PUBLISHER','subject',:subject,'manual')")
                .param("id",binding).param("ws",PURCHASING).param("subject",author.toString()).update();policy.evictAll();
        try {
            var response=api("dana.designer").post(Map.of("draftRevision",2,"acknowledgeWarnings",true),UUID.randomUUID().toString(),"/workspaces/{ws}/workflows/{wf}/publish",PURCHASING,wf);
            assertThat(response.status()).as(response.toString()).isEqualTo(403);
            assertThat(jdbc.sql("SELECT count(*) FROM workflow_release WHERE workflow_id=:wf").param("wf",wf).query(Long.class).single()).isZero();
        } finally {jdbc.sql("DELETE FROM role_binding WHERE id=:id").param("id",binding).update();policy.evictAll();}
    }
    @Test void defaultClosureRefusesOpenTasksWithoutChangingCaseOrMail() throws Exception {
        UUID run=start(publish("REQUIRE_NO_OPEN_TASKS"));
        var result=cancel(run,UUID.randomUUID().toString());
        assertThat(result.status()).as(result.toString()).isEqualTo(409);assertThat(result.code()).isEqualTo("RUN_HAS_OPEN_TASKS");
        assertThat(result.body().path("openTaskIds").size()).isEqualTo(1);
        assertThat(openTask(PURCHASING,run,"manager")).isNotNull();assertThat(count(run,"queued")).isPositive();
    }
    @Test void concurrentClosureAndDecisionHaveOneWinnerAndNoOpenWork() throws Exception {
        UUID run=start(publish("CANCEL_OPEN_TASKS"));String task=openTask(PURCHASING,run,"manager").path("id").asString();
        assertThat(claim("mani.manager",PURCHASING,task).status()).isEqualTo(200);
        int runVersion=api("olga.operator").get("/workspaces/{ws}/runs/{run}",PURCHASING,run).body().path("version").asInt();
        int taskVersion=api("mani.manager").get("/workspaces/{ws}/tasks/{task}",PURCHASING,task).body().path("version").asInt();
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var close=executor.submit(()-> {gate.await();return api("olga.operator").post(Map.of("reason","Concurrent close","expectedVersion",runVersion),UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/cancel",PURCHASING,run);});
            var decide=executor.submit(()-> {gate.await();return api("mani.manager").post(Map.of("action","approve","expectedVersion",taskVersion),UUID.randomUUID().toString(),"/workspaces/{ws}/tasks/{task}/decision",PURCHASING,task);});
            gate.countDown();var a=close.get(15,java.util.concurrent.TimeUnit.SECONDS);var b=decide.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.status()==200 && b.status()==409 || a.status()==409 && b.status()==201).as(a+" / "+b).isTrue();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id=:run AND status='open'").param("run",run).query(Long.class).single()).isZero();
    }
    @Test void cancellationSuppressesTaskMailAndRejectsLateDecisionsAndForeignStatusReads() throws Exception {
        UUID run=start(publish("CANCEL_OPEN_TASKS"));String task=openTask(PURCHASING,run,"manager").path("id").asString();
        assertThat(claim("mani.manager",PURCHASING,task).status()).isEqualTo(200);
        assertThat(cancel(run,UUID.randomUUID().toString()).status()).isEqualTo(200);
        when(transport.enabled()).thenReturn(true);mail.dispatch();
        assertThat(count(run,"suppressed")).isPositive();assertThat(count(run,"providerAccepted")).isEqualTo(1);
        assertThat(decide("mani.manager",PURCHASING,task,"approve",null).status()).isIn(403,409);
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{run}/mail",PURCHASING,run).status()).isEqualTo(200);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{run}/mail",PURCHASING,run).status()).isEqualTo(403);
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{run}/mail",FACILITIES,run).status()).isEqualTo(404);
    }
    @Test void breachIsDurableForUnassignedTasksAndDeliveryRetriesAreBounded() throws Exception {
        UUID run=start(publish("CANCEL_OPEN_TASKS"));String task=openTask(PURCHASING,run,"manager").path("id").asString();
        jdbc.sql("UPDATE task_context SET due_at=now()-interval '1 minute' WHERE engine_task_id=:task").param("task",task).update();
        mail.detectBreaches();mail.detectBreaches();
        assertThat(jdbc.sql("SELECT count(*) FROM task_sla_breach WHERE task_id=:task").param("task",task).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:run AND type='TASK_SLA_BREACHED'").param("run",run).query(Long.class).single()).isEqualTo(1);
        when(transport.enabled()).thenReturn(true);doThrow(new org.springframework.mail.MailSendException("Test mail outage")).when(transport).send(any(),any(),any(),any());
        for(int i=0;i<4;i++) {jdbc.sql("UPDATE stage_mail SET next_attempt_at=now()-interval '1 minute' WHERE run_id=:run").param("run",run).update();mail.dispatch();}
        assertThat(count(run,"queued")).isZero();assertThat(count(run,"failed")).isPositive();
        assertThat(jdbc.sql("SELECT max(attempts) FROM stage_mail WHERE run_id=:run").param("run",run).query(Integer.class).single()).isEqualTo(3);
    }
    @Test void decisionAndTerminalEmailAreQueuedAndAuthorizedEvidenceExcludesRequestPayload() throws Exception {
        UUID run=start(publish("CANCEL_OPEN_TASKS"));String task=openTask(PURCHASING,run,"manager").path("id").asString();
        assertThat(claim("mani.manager",PURCHASING,task).status()).isEqualTo(200);
        assertThat(decide("mani.manager",PURCHASING,task,"reject","Policy refusal").status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT event_kind FROM stage_mail WHERE run_id=:run").param("run",run).query(String.class).list()).contains("rejection","terminal");
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{run}/evidence",PURCHASING,run).status()).isEqualTo(403);
        UUID actor=UUID.fromString(api("olga.operator").get("/me").body().path("subject").path("id").asString());
        var granted=api("wade.admin").post(Map.of("permission","audit.read",
                "principal",Map.of("type","subject","value",actor.toString())),
                "/workspaces/{ws}/access/grants",PURCHASING);
        assertThat(granted.status()).as(granted.toString()).isEqualTo(201);
        UUID grant=UUID.fromString(granted.body().path("id").asString());
        try {
            var response=api("olga.operator").get("/workspaces/{ws}/runs/{run}/evidence",PURCHASING,run);
            assertThat(response.status()).as(response.toString()).isEqualTo(200);
            assertThat(response.body().path("manifest").path("eventCount").asInt()).isEqualTo(response.body().path("events").size());
            assertThat(new String(response.bytes(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Private title excluded from evidence","emailPassword");
            assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{run}/evidence?format=csv",PURCHASING,run).header("Content-Type")).startsWith("text/csv");
        } finally {
            assertThat(api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}",PURCHASING,grant).status()).isEqualTo(204);
        }
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{run}/evidence",PURCHASING,run).status()).isEqualTo(403);
    }
}
