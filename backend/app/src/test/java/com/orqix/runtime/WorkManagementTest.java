package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.identity.PolicyService;
import com.orqix.runtime.internal.TaskReminders;
import com.orqix.support.RuntimeTestBase;
import tools.jackson.databind.node.ObjectNode;

class WorkManagementTest extends RuntimeTestBase {
    @Autowired TaskReminders reminders;
    @Autowired PolicyService policy;
    private ObjectNode approval(ObjectNode definition) { return (ObjectNode)definition.path("nodes").get(1).path("config"); }

    @Test void skillIntersectionControlsQueueClaimDecisionDelegationAndRunDataAfterRevocation() throws Exception {
        UUID mani=UUID.fromString(api("mani.manager").get("/me").body().path("subject").path("id").asString());
        UUID max=UUID.fromString(api("max.manager").get("/me").body().path("subject").path("id").asString());
        UUID workflow=publishPurchase(PURCHASING,definition -> approval(definition).putArray("requiredSkills").add("finance-approvers"));
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable",PURCHASING).body().findValues("id").stream().map(n->n.asString())).doesNotContain(task);
        assertThat(claim("mani.manager",PURCHASING,task).status()).isIn(403,404);
        assertThat(api("mani.manager").get("/workspaces/{ws}/runs/{run}",PURCHASING,run).status()).isEqualTo(404);
        addSkill(mani);
        try {
            assertThat(claim("mani.manager",PURCHASING,task).status()).isEqualTo(200);
            var delegates=api("mani.manager").get("/workspaces/{ws}/tasks/{task}/delegates",PURCHASING,task);
            assertThat(delegates.body().findValues("id").stream().map(n->n.asString())).doesNotContain(max.toString());
            var current=api("mani.manager").get("/workspaces/{ws}/tasks/{task}",PURCHASING,task);
            assertThat(api("mani.manager").post(Map.of("expectedVersion",current.body().path("version").asInt(),
                    "assigneeSubjectId",max,"reason","Unqualified target"),"/workspaces/{ws}/tasks/{task}/delegate",PURCHASING,task).status()).isEqualTo(422);
            removeSkill(mani); // Deliberately no cache eviction: the next command must check fresh membership.
            assertThat(decide("mani.manager",PURCHASING,task,"approve",null).status()).isEqualTo(403);
            jdbc.sql("UPDATE task_reminder SET next_attempt_at=now()-interval '1 minute' WHERE task_id=:task").param("task",task).update();
            reminders.dispatch();
            assertThat(notices(run)).isZero();
        } finally { removeSkill(mani); policy.evictAll(); }
    }

    @Test void calendarDeadlineIsPinnedAndRecurringNoticesAreDurableBoundedAndStopWithTask() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,definition -> {
            var cfg=approval(definition); cfg.put("dueAfterHours",8);
            var calendar=cfg.putObject("calendar").put("zone","UTC").put("start","09:00").put("end","17:00");
            calendar.putArray("weekdays").add(1).add(2).add(3).add(4).add(5);
            cfg.putObject("escalation").put("intervalHours",1).put("maxNotices",2);
        });
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        var row=jdbc.sql("SELECT created_at,due_at FROM task_context WHERE engine_task_id=:task").param("task",task)
                .query((rs,n)->Map.of("created",rs.getTimestamp("created_at").toInstant(),"due",rs.getTimestamp("due_at").toInstant())).single();
        assertThat(row.get("due")).isEqualTo(new com.orqix.compiler.BusinessCalendar(java.time.ZoneOffset.UTC,java.util.Set.of(1,2,3,4,5),java.util.Set.of(),java.time.LocalTime.of(9,0),java.time.LocalTime.of(17,0)).deadline(row.get("created"),8));
        assertThat(claim("mani.manager",PURCHASING,task).status()).isEqualTo(200);
        jdbc.sql("UPDATE task_context SET due_at=now()-interval '1 hour' WHERE engine_task_id=:task").param("task",task).update();
        mature(task); reminders.dispatch(); reminders.dispatch(); assertThat(notices(run)).isEqualTo(1);
        mature(task); reminders.dispatch(); mature(task); reminders.dispatch(); assertThat(notices(run)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT delivered_at IS NOT NULL FROM task_reminder WHERE task_id=:task").param("task",task).query(Boolean.class).single()).isTrue();
        assertThat(decide("mani.manager",PURCHASING,task,"approve",null).status()).isEqualTo(201);
        mature(task); reminders.dispatch(); assertThat(notices(run)).isEqualTo(2);
    }
    private void mature(String task) { jdbc.sql("UPDATE task_reminder SET next_attempt_at=now()-interval '1 minute' WHERE task_id=:task").param("task",task).update(); }
    private long notices(UUID run) { return jdbc.sql("SELECT count(*) FROM notification WHERE run_id=:run AND dedup_key LIKE 'task-reminder:%'").param("run",run).query(Long.class).single(); }
    private void addSkill(UUID subject) {
        jdbc.sql("INSERT INTO workspace_group_member(workspace_id,group_id,principal_type,principal_value) VALUES(:ws,'00000000-0000-7000-8000-000000000502','subject',:subject) ON CONFLICT DO NOTHING")
                .param("ws",PURCHASING).param("subject",subject.toString()).update();
    }
    private void removeSkill(UUID subject) {
        jdbc.sql("DELETE FROM workspace_group_member WHERE workspace_id=:ws AND group_id='00000000-0000-7000-8000-000000000502' AND principal_type='subject' AND principal_value=:subject")
                .param("ws",PURCHASING).param("subject",subject.toString()).update();
    }
}
