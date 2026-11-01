package com.orqix.resilience;

import static com.orqix.resilience.Flows.PURCHASING;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.cmmn.engine.CmmnEngine;
import org.flowable.engine.ProcessEngine;
import org.flowable.job.service.impl.asyncexecutor.AbstractAsyncExecutor;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import com.orqix.shared.config.NodeRoleGuard;
import com.orqix.support.Stub;

/**
 * ADR 0004 step 1: an API-role and a worker-role instance share one database. Every request goes to the API node;
 * the API node runs no engine job and no scheduled work, and every asynchronous job (the purchase-order call after
 * approval) is executed by the worker, exactly once per run.
 */
@Tag("resilience")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitRolesTest {

    private static final int RUNS = 10;

    private Node api;
    private Node worker;

    @BeforeAll
    void startBothRoles() {
        api = Node.start(Nodes.ApiNode.class);
        worker = Node.start(Nodes.WorkerNode.class);
        Flows.bindStub(api, PURCHASING, Stub.baseUrl());
    }

    @BeforeEach
    void resetStub() {
        Stub.reset();
        JobExecutionRecorder.clear();
    }

    @AfterAll
    void stopBothRoles() {
        if (worker != null) {
            worker.stop();
        }
        if (api != null) {
            api.stop();
        }
    }

    @Test
    void apiRoleRunsNoJobsAndNoScheduledWork() {
        assertThat(NodeRoleGuard.role(api.bean(org.springframework.core.env.Environment.class)))
                .isEqualTo(NodeRoleGuard.Role.API);
        assertThat(processExecutor(api).isActive()).as("BPMN job executor on the API node").isFalse();
        assertThat(caseExecutor(api).isActive()).as("CMMN job executor on the API node").isFalse();
        assertThat(api.hasBean(ScheduledAnnotationBeanPostProcessor.class)).as("scheduling on the API node").isFalse();
    }

    @Test
    void workerRoleRunsJobsAndScheduledWorkAndPollsEverySecond() {
        assertThat(NodeRoleGuard.role(worker.bean(org.springframework.core.env.Environment.class)))
                .isEqualTo(NodeRoleGuard.Role.WORKER);
        assertThat(processExecutor(worker).isActive()).as("BPMN job executor on the worker").isTrue();
        assertThat(caseExecutor(worker).isActive()).as("CMMN job executor on the worker").isTrue();
        assertThat(worker.hasBean(ScheduledAnnotationBeanPostProcessor.class)).as("scheduling on the worker").isTrue();
        assertThat(((AbstractAsyncExecutor) processExecutor(worker)).getConfiguration().getDefaultAsyncJobAcquireWaitTime())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(((AbstractAsyncExecutor) caseExecutor(worker)).getConfiguration().getDefaultAsyncJobAcquireWaitTime())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void requestsOnTheApiNodeCompleteThroughTheWorkerWithOneEffectEach() throws Exception {
        UUID workflowId = Flows.publishPurchase(api, PURCHASING, 10, null);
        JobExecutionRecorder.clear();

        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            runs.add(Flows.start(api, "rhea.requester", PURCHASING, workflowId, new BigDecimal("1800").add(BigDecimal.valueOf(i))));
        }
        for (UUID run : runs) {
            Flows.approve(api, "mani.manager", PURCHASING, run, "manager");
        }
        for (UUID run : runs) {
            Flows.awaitLifecycle(api, PURCHASING, run, Duration.ofSeconds(60), "completed", "needsAttention");
        }

        assertThat(runs).allSatisfy(run -> assertThat(Flows.lifecycle(api, PURCHASING, run)).isEqualTo("completed"));
        assertThat(Stub.orderCount()).as("one order per run").isEqualTo(RUNS);
        Map<String, Long> byOwner = JobExecutionRecorder.successesByOwner();
        assertThat(byOwner).as("every job ran on the worker").containsOnlyKeys(worker.lockOwner());
        assertThat(byOwner.get(worker.lockOwner())).isEqualTo(RUNS);
        List<Map<String, Object>> journal = api.jdbc().sql("""
                SELECT j.state, j.attempts, count(*) AS n FROM effect_journal j
                JOIN workflow_run r ON r.workspace_id = j.workspace_id AND r.id = j.run_id
                WHERE r.workflow_id = :wf GROUP BY j.state, j.attempts
                """).param("wf", workflowId).query().listOfRows();
        assertThat(journal).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("state", "succeeded").containsEntry("attempts", 1).containsEntry("n", (long) RUNS));
    }

    private static AsyncExecutor processExecutor(Node node) {
        return node.bean(ProcessEngine.class).getProcessEngineConfiguration().getAsyncExecutor();
    }

    private static AsyncExecutor caseExecutor(Node node) {
        return node.bean(CmmnEngine.class).getCmmnEngineConfiguration().getAsyncExecutor();
    }
}
