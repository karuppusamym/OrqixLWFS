package com.orqix.runtime;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Temporary engine-semantics spike for EX04 (suspension and migration). */
@org.junit.jupiter.api.Disabled("Engine-semantics diagnostic, not an acceptance test. Suspend/migration implementation remains pending.")
class Ex04SpikeTest extends RuntimeTestBase {
    @Autowired RuntimeService runtime;
    @Autowired TaskService taskService;
    @Autowired ProcessMigrationService migration;
    @Autowired TransactionTemplate tx;

    private void graph(ObjectNode d, String nodes, String transitions) {
        d.set("nodes", json.parse(nodes));
        d.set("transitions", json.parse(transitions));
        d.set("decisions", json.parse("[]"));
        d.set("outputMapping", json.object());
        d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        d.set("dependencies", json.parse("{\"connections\":[]}"));
        d.remove("uiLayout");
    }
    private static final String START = "{\"id\":\"start\",\"type\":\"start\",\"label\":\"Submitted\",\"config\":{}}";
    private static final String END = "{\"id\":\"end\",\"type\":\"end\",\"label\":\"Done\",\"config\":{\"businessOutcome\":\"Completed\",\"outcomeKind\":\"success\"}}";
    private static final String REJECT = "{\"id\":\"reject\",\"type\":\"end\",\"label\":\"Rejected\",\"config\":{\"businessOutcome\":\"Rejected\",\"outcomeKind\":\"rejected\"}}";
    private String approval(String id, String label, String extra) {
        return "{\"id\":\"" + id + "\",\"type\":\"approval\",\"label\":\"" + label
                + "\",\"config\":{\"candidateGroupRef\":\"managers\",\"rejectReasonRequired\":true" + extra + "}}";
    }
    private String flow(String id, String from, String to, String on) {
        return "{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"on\":\"" + on + "\"}";
    }

    private UUID republish(UUID wf, Consumer<ObjectNode> patch) throws Exception {
        JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).body();
        System.out.println("SPIKE draft keys " + draft.propertyNames());
        int rev = draft.path("revision").asInt();
        ObjectNode def = (ObjectNode) draft.path("definition");
        patch.accept(def);
        var saved = api("dana.designer").put(Map.of("definition", def), "\"r" + rev + "\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf);
        System.out.println("SPIKE save " + saved.status() + " " + saved.body());
        int newRev = saved.body().path("revision").asInt();
        var pub = api("paul.publisher").post(Map.of("draftRevision", newRev, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, wf);
        System.out.println("SPIKE publish " + pub.status() + " " + pub.body());
        return UUID.fromString(pub.body().path("id").asString());
    }

    private String defId(UUID release) {
        return jdbc.sql("SELECT process_definition_id FROM workflow_release WHERE id=:id").param("id", release).query(String.class).single();
    }

    private void shape(String pid) {
        System.out.println("SPIKE executions of " + pid);
        runtime.createExecutionQuery().processInstanceId(pid).list().forEach(e ->
                System.out.println("   exec " + e.getId() + " act=" + e.getActivityId() + " parent=" + e.getParentId() + " susp=" + e.isSuspended()));
        taskService.createTaskQuery().processInstanceId(pid).list().forEach(t ->
                System.out.println("   task " + t.getId() + " def=" + t.getTaskDefinitionKey() + " pdef=" + t.getProcessDefinitionId() + " susp=" + t.isSuspended() + " assignee=" + t.getAssignee()));
        managementService.createTimerJobQuery().processInstanceId(pid).list().forEach(j ->
                System.out.println("   timer " + j.getId() + " due=" + j.getDuedate() + " pdef=" + j.getProcessDefinitionId() + " h=" + j.getJobHandlerType() + " cfg=" + j.getJobHandlerConfiguration()));
        managementService.createSuspendedJobQuery().processInstanceId(pid).list().forEach(j ->
                System.out.println("   SUSPENDEDJOB " + j.getId() + " due=" + j.getDuedate() + " h=" + j.getJobHandlerType()));
        runtime.createEventSubscriptionQuery().processInstanceId(pid).list().forEach(s ->
                System.out.println("   sub " + s.getId() + " type=" + s.getEventType() + " name=" + s.getEventName() + " act=" + s.getActivityId() + " exec=" + s.getExecutionId()));
    }

    @Test
    void approvalWithTimeoutAndWaitAndParallel() throws Exception {
        UUID wf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + "," + approval("review", "Review", ",\"timeoutAfter\":\"PT1H\"") + ","
                + "{\"id\":\"pause\",\"type\":\"wait\",\"label\":\"Pause\",\"config\":{\"duration\":\"PT2H\"}}," + END + "," + REJECT + "]",
                "[" + flow("t1", "start", "review", "next") + "," + flow("t2", "review", "pause", "approved") + ","
                        + flow("t3", "review", "reject", "rejected") + "," + flow("t4", "review", "reject", "timedOut") + "," + flow("t5", "pause", "end", "next") + "]"));
        UUID r1 = UUID.fromString(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, wf).body().path("activeReleaseId").asString());
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, wf, BigDecimal.TEN).path("id").asString());
        String pid = processInstance(PURCHASING, run);
        System.out.println("SPIKE --- approval with timeout, before suspend");
        shape(pid);
        tx.executeWithoutResult(s -> runtime.suspendProcessInstanceById(pid));
        System.out.println("SPIKE --- suspended");
        shape(pid);
        String taskId = taskService.createTaskQuery().processInstanceId(pid).list().get(0).getId();
        try { tx.executeWithoutResult(s -> taskService.complete(taskId)); System.out.println("SPIKE complete on suspended: NO ERROR"); }
        catch (RuntimeException e) { System.out.println("SPIKE complete on suspended: " + e.getClass().getName() + " " + e.getMessage()); }
        try { tx.executeWithoutResult(s -> taskService.claim(taskId, "x")); System.out.println("SPIKE claim on suspended: NO ERROR"); }
        catch (RuntimeException e) { System.out.println("SPIKE claim on suspended: " + e.getClass().getName() + " " + e.getMessage()); }
        try { tx.executeWithoutResult(s -> taskService.setAssignee(taskId, "x")); System.out.println("SPIKE setAssignee on suspended: NO ERROR"); }
        catch (RuntimeException e) { System.out.println("SPIKE setAssignee on suspended: " + e.getClass().getName() + " " + e.getMessage()); }
        // push the timeout due date into the past while suspended
        int n = jdbc.sql("UPDATE flowable.act_ru_suspended_job SET duedate_ = now() - interval '1 hour' WHERE proc_inst_id_ = :p").param("p", pid).update();
        System.out.println("SPIKE moved suspended due dates: " + n);
        Thread.sleep(3000);
        System.out.println("SPIKE --- after 3s still suspended: " + lifecycle(PURCHASING, run));
        shape(pid);
        tx.executeWithoutResult(s -> runtime.activateProcessInstanceById(pid));
        System.out.println("SPIKE --- activated");
        shape(pid);
        Thread.sleep(8000);
        System.out.println("SPIKE --- 8s after activation: lifecycle=" + lifecycle(PURCHASING, run));
        shape(pid);

        // migration of a fresh run
        UUID r2 = republish(wf, d -> graph(d, "[" + START + "," + approval("review", "Review renamed", ",\"timeoutAfter\":\"PT1H\"") + ","
                + "{\"id\":\"pause\",\"type\":\"wait\",\"label\":\"Pause\",\"config\":{\"duration\":\"PT2H\"}}," + END + "," + REJECT + "]",
                "[" + flow("t1", "start", "review", "next") + "," + flow("t2", "review", "pause", "approved") + ","
                        + flow("t3", "review", "reject", "rejected") + "," + flow("t4", "review", "reject", "timedOut") + "," + flow("t5", "pause", "end", "next") + "]"));
        UUID run2 = UUID.fromString(start("rhea.requester", PURCHASING, wf, BigDecimal.TEN).path("id").asString());
        // run2 started on r2 (active). start another on r1? only via migration back; use run (lifecycle?)
        String pid2 = processInstance(PURCHASING, run2);
        System.out.println("SPIKE --- run2 on r2 shape");
        shape(pid2);
        String target = defId(r1);
        System.out.println("SPIKE migrating run2 from r2 to r1 " + target);
        var builder = migration.createProcessInstanceMigrationBuilder().migrateToProcessDefinition(target);
        var validation = builder.validateMigration(pid2);
        System.out.println("SPIKE validation valid=" + validation.isMigrationValid() + " msgs=" + validation.getValidationMessages());
        tx.executeWithoutResult(s -> builder.migrate(pid2));
        System.out.println("SPIKE --- run2 migrated");
        shape(pid2);
        var instance = runtime.createProcessInstanceQuery().processInstanceId(pid2).singleResult();
        System.out.println("SPIKE instance pdef=" + instance.getProcessDefinitionId());
    }

    @Test
    void parallelAndMessageShapes() throws Exception {
        UUID wf = publishPurchase(PURCHASING, d -> graph(d, "[" + START + ","
                + "{\"id\":\"split\",\"type\":\"parallelSplit\",\"label\":\"Together\",\"config\":{\"joinRef\":\"join\"}},"
                + approval("one", "One", "") + "," + approval("two", "Two", "") + ","
                + "{\"id\":\"join\",\"type\":\"parallelJoin\",\"label\":\"All done\",\"config\":{}}," + END + "," + REJECT + "]",
                "[" + String.join(",", flow("t1", "start", "split", "next"), flow("t2", "split", "one", "next"), flow("t3", "split", "two", "next"),
                        flow("t4", "one", "join", "approved"), flow("t5", "two", "join", "approved"), flow("t6", "one", "reject", "rejected"),
                        flow("t7", "two", "reject", "rejected"), flow("t8", "join", "end", "next")) + "]"));
        UUID run = UUID.fromString(start("rhea.requester", PURCHASING, wf, BigDecimal.TEN).path("id").asString());
        String pid = processInstance(PURCHASING, run);
        System.out.println("SPIKE --- parallel both open");
        shape(pid);
        approve("mani.manager", PURCHASING, run, "one");
        System.out.println("SPIKE --- parallel one done, waiting at join");
        shape(pid);
        System.out.println("SPIKE active ids " + runtime.getActiveActivityIds(pid));
    }
}
