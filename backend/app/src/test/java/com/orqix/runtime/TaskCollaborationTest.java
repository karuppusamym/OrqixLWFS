package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.support.RuntimeTestBase;
import com.orqix.runtime.internal.TaskReminders;
import com.orqix.identity.PolicyService;

class TaskCollaborationTest extends RuntimeTestBase {
    @Autowired TaskReminders reminders;
    @Autowired PolicyService policy;

    @Test void onlyTheCurrentEligibleAssigneeCanDelegateWithVersionAndReason() throws Exception {
        var target=api("max.manager").get("/me").body().path("subject").path("id").asString();
        var wrong=api("fin.finance").get("/me").body().path("subject").path("id").asString();
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        var claimed=claim("mani.manager",PURCHASING,task);
        assertThat(claimed.status()).isEqualTo(200);
        int version=claimed.body().path("version").asInt();
        var eligible=api("mani.manager").get("/workspaces/{ws}/tasks/{task}/delegates",PURCHASING,task);
        assertThat(eligible.status()).as(eligible.toString()).isEqualTo(200);
        assertThat(eligible.body().findValues("id").stream().map(n->n.asString())).contains(target).doesNotContain(wrong);
        var body=Map.of("expectedVersion",version,"assigneeSubjectId",target,"reason","Cover during leave");
        assertThat(api("max.manager").post(body,"/workspaces/{ws}/tasks/{task}/delegate",PURCHASING,task).status()).isEqualTo(403);
        assertThat(api("mani.manager").post(Map.of("expectedVersion",version,"assigneeSubjectId",wrong,"reason","Wrong group"),"/workspaces/{ws}/tasks/{task}/delegate",PURCHASING,task).status()).isEqualTo(422);
        var delegated=api("mani.manager").post(body,"/workspaces/{ws}/tasks/{task}/delegate",PURCHASING,task);
        assertThat(delegated.status()).as(delegated.toString()).isEqualTo(200);
        assertThat(delegated.body().path("assignee").path("id").asString()).isEqualTo(target);
        assertThat(api("mani.manager").post(body,"/workspaces/{ws}/tasks/{task}/delegate",PURCHASING,task).status()).isEqualTo(403);
        assertThat(decide("mani.manager",PURCHASING,task,"approve",null).status()).isEqualTo(409);
        assertThat(decide("max.manager",PURCHASING,task,"approve",null).status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='task.delegated' AND resource_id=:task")
                .param("task",task).query(Long.class).single()).isEqualTo(1);
    }

    @Test void remindersAreDurableOneShotAndDoNotNotifyUnassignedOrRevokedUsers() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        mature(task); reminders.dispatch();
        assertThat(reminderCount(run)).isZero();
        assertThat(claim("mani.manager",PURCHASING,task).status()).isEqualTo(200);
        mature(task); reminders.dispatch(); reminders.dispatch();
        assertThat(reminderCount(run)).isEqualTo(1);
        UUID otherRun=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String other=openTask(PURCHASING,otherRun,"manager").path("id").asString();
        assertThat(claim("max.manager",PURCHASING,other).status()).isEqualTo(200);
        jdbc.sql("UPDATE subject SET disabled_at=now() WHERE id=(SELECT assignee_subject_id FROM task_context WHERE engine_task_id=:task)").param("task",other).update();
        try {
            policy.evictAll(); mature(other); reminders.dispatch();
            assertThat(reminderCount(otherRun)).isZero();
        } finally {
            jdbc.sql("UPDATE subject SET disabled_at=NULL WHERE id=(SELECT assignee_subject_id FROM task_context WHERE engine_task_id=:task)").param("task",other).update();
            policy.evictAll();
        }
    }

    @Test void reminderPermissionChecksBypassAWarmedSessionCacheAfterRoleRevocation() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        // Claim warms the assignee's effective-access cache with task.act permission.
        assertThat(claim("mani.manager",PURCHASING,task).status()).isEqualTo(200);
        UUID binding=UUID.fromString("00000000-0000-7000-8000-000000000306");
        try {
            jdbc.sql("UPDATE role_binding SET role='VIEWER' WHERE id=:id").param("id",binding).update();
            // Intentionally no eviction: another worker may still hold a cached session policy.
            mature(task); reminders.dispatch();
            assertThat(reminderCount(run)).isZero();
        } finally {
            jdbc.sql("UPDATE role_binding SET role='APPROVER' WHERE id=:id").param("id",binding).update();
            policy.evictAll();
        }
    }
    private void mature(String task) {
        jdbc.sql("UPDATE task_reminder SET next_attempt_at=now()-interval '1 minute' WHERE task_id=:task").param("task",task).update();
    }
    private long reminderCount(UUID run) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE run_id=:run AND dedup_key LIKE 'task-reminder:%'").param("run",run).query(Long.class).single();
    }
}
