package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.flowable.engine.RuntimeService;
import com.orqix.runtime.internal.RunWatchdog;
import com.orqix.support.RuntimeTestBase;

class RunWatchdogTest extends RuntimeTestBase {
    @Autowired RunWatchdog watchdog;
    @Autowired RuntimeService engine;
    @Autowired TransactionTemplate transactions;

    @Test void staleEmptyProjectionWithLiveApprovalIsNotFlagged() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        jdbc.sql("UPDATE workflow_run SET current_steps='[]'::jsonb,updated_at=now()-interval '10 minutes' WHERE id=:id")
                .param("id",run).update();
        watchdog.inspect();
        assertThat(jdbc.sql("SELECT lifecycle FROM workflow_run WHERE id=:id").param("id",run).query(String.class).single())
                .isEqualTo("waiting");
        var detail=api("olga.operator").get("/workspaces/{ws}/runs/{id}",PURCHASING,run).body();
        assertThat(api("olga.operator").post(Map.of("expectedVersion",detail.path("version").asInt(),"reason","Test cleanup"),
                UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{id}/cancel",PURCHASING,run).status()).isEqualTo(200);
    }

    @Test void missingEngineRunBecomesAttentionOnceAndOnlyOperatorCanCancelWithoutInventingCompletion() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String process=jdbc.sql("SELECT engine_process_instance_id FROM workflow_run WHERE id=:id").param("id",run).query(String.class).single();
        transactions.executeWithoutResult(status -> engine.deleteProcessInstance(process,"Simulate lost engine state"));
        jdbc.sql("UPDATE workflow_run SET lifecycle='running',current_steps='[]'::jsonb,ended_at=NULL,updated_at=now()-interval '10 minutes' WHERE id=:id")
                .param("id",run).update();
        watchdog.inspect(); watchdog.inspect();
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id=:id AND type='RUN_NEEDS_ATTENTION' AND data->>'reason'=:reason")
                .param("id",run).param("reason",RunWatchdog.REASON).query(Long.class).single()).isEqualTo(1);
        var detail=api("olga.operator").get("/workspaces/{ws}/runs/{id}",PURCHASING,run).body();
        assertThat(detail.path("lifecycle").asString()).isEqualTo("needsAttention");
        assertThat(detail.path("allowedActions").path("retry").path("allowed").asBoolean()).isFalse();
        var request=Map.of("expectedVersion",detail.path("version").asInt(),"reason","Inspected orphaned request");
        assertThat(api("rhea.requester").post(request,UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{id}/cancel",PURCHASING,run).status()).isEqualTo(403);
        assertThat(api("olga.operator").post(request,UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{id}/cancel",PURCHASING,run).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT lifecycle FROM workflow_run WHERE id=:id").param("id",run).query(String.class).single()).isEqualTo("cancelled");
    }
}
