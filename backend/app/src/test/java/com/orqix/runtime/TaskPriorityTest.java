package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;

class TaskPriorityTest extends RuntimeTestBase {
    @Test void priorityRequiresEligibleOwnershipOrOperatorAndRetainsHistoryAcrossReassignment() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        int version=claim("mani.manager",PURCHASING,task).body().path("version").asInt();
        var change=Map.of("expectedVersion",version,"priority",75,"reason","Time sensitive request");
        assertThat(api("max.manager").put(change,null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task).status()).isEqualTo(403);
        var changed=api("mani.manager").put(change,null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task);
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        assertThat(changed.body().path("priority").asInt()).isEqualTo(75);
        assertThat(changed.body().path("version").asInt()).isEqualTo(version+1);
        assertThat(api("mani.manager").put(change,null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task).status()).isEqualTo(409);
        var target=api("max.manager").get("/me").body().path("subject").path("id").asString();
        var delegated=api("mani.manager").post(Map.of("expectedVersion",version+1,"assigneeSubjectId",target,"reason","Coverage"),"/workspaces/{ws}/tasks/{task}/delegate",PURCHASING,task);
        assertThat(delegated.status()).isEqualTo(200);
        assertThat(delegated.body().path("priority").asInt()).isEqualTo(75);
        var operator=api("olga.operator").put(Map.of("expectedVersion",version+2,"priority",90,"reason","Queue triage"),null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task);
        assertThat(operator.status()).as(operator.toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='task.priority-changed' AND resource_id=:task").param("task",task).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='task.priority-changed' AND resource_id=:task AND jsonb_exists(metadata,'reason')").param("task",task).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT data->>'reason' FROM run_event WHERE run_id=:run AND type='TASK_PRIORITY_CHANGED' ORDER BY seq LIMIT 1").param("run",run).query(String.class).single()).isEqualTo("Time sensitive request");
        assertThat(decide("max.manager",PURCHASING,task,"approve",null).status()).isEqualTo(201);
        assertThat(api("olga.operator").put(Map.of("expectedVersion",version+3,"priority",10,"reason","Closed task"),null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task).status()).isEqualTo(409);
    }

    @Test void priorityPaginationUsesFullSortTupleAndBindsFilters() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        String[] tasks=new String[3];
        for (int i=0;i<3;i++) {
            UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
            tasks[i]=openTask(PURCHASING,run,"manager").path("id").asString();
            claim("mani.manager",PURCHASING,tasks[i]);
            // Equal times exercise the deterministic task-id tie break.
            jdbc.sql("UPDATE task_context SET priority=:p,created_at='2026-01-01T00:00:00Z' WHERE engine_task_id=:task").param("p",100-i/2).param("task",tasks[i]).update();
        }
        String firstId=tasks[0].compareTo(tasks[1])<0?tasks[0]:tasks[1];
        String secondId=firstId.equals(tasks[0])?tasks[1]:tasks[0];
        var first=api("mani.manager").get("/workspaces/{ws}/tasks?view=assigned&sort=priority&minPriority=98&limit=1",PURCHASING);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().path("items").get(0).path("id").asString()).isEqualTo(firstId);
        String cursor=first.body().path("nextCursor").asString();
        var next=api("mani.manager").get("/workspaces/{ws}/tasks?view=assigned&sort=priority&minPriority=98&limit=1&cursor={cursor}",PURCHASING,cursor);
        assertThat(next.body().path("items").get(0).path("id").asString()).isEqualTo(secondId);
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=assigned&sort=oldest&minPriority=98&cursor={cursor}",PURCHASING,cursor).status()).isEqualTo(400);
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=assigned&minPriority=101",PURCHASING).status()).isEqualTo(400);
    }

    @Test void priorityRejectsMissingReasonAndOutOfBoundsValues() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        assertThat(api("olga.operator").put(Map.of("expectedVersion",1,"priority",101,"reason","Urgent"),null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task).status()).isEqualTo(400);
        assertThat(api("olga.operator").put(Map.of("expectedVersion",1,"priority",30,"reason","  "),null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task).status()).isEqualTo(400);
        assertThat(api("rhea.requester").put(Map.of("expectedVersion",1,"priority",30,"reason","Own request"),null,"/workspaces/{ws}/tasks/{task}/priority",PURCHASING,task).status()).isEqualTo(404);
    }
}
