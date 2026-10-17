package com.orqix.runtime.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.runtime.engine.DecisionTaskDelegate;
import com.orqix.shared.json.Json;
import com.orqix.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compatibility spike B01.2 (S1 section 10) against the real application context, engine configuration and a
 * PostgreSQL migrated by the {@code migrate} profile. Results are recorded in docs/adr/0001-dependency-baseline.md.
 */
class EngineSpikeTest extends IntegrationTestBase {

    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RuntimeService runtimeService;
    @Autowired
    TaskService taskService;
    @Autowired
    ManagementService managementService;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApplicationContext context;
    @Autowired
    DecisionTaskDelegate decisionTaskDelegate;
    @Autowired
    Json json;

    @Test
    void s1_bootAndFlowableStartersOnJava21WithoutRestOrIdmAndJackson3() {
        assertThat(Runtime.version().feature()).isEqualTo(21);
        assertThat(context.getBeansOfType(JsonMapper.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(org.flowable.idm.api.IdmIdentityService.class)).isEmpty();
        assertThat(classPresent("org.flowable.rest.service.api.RestResponseFactory")).isFalse();
        assertThat(classPresent("com.fasterxml.jackson.databind.ObjectMapper") && !context
                .getBeansOfType(com.fasterxml.jackson.databind.ObjectMapper.class).isEmpty()).isFalse();
    }

    @Test
    void s2_bpmnAndDmnInOneTenantDeploymentStartByDefinitionIdCandidateGroupTask() throws IOException {
        Deployment deployment = deploy("spike-s2", "tenant-s2", "spike/approval.bpmn20.xml", "spike/policy-v1.dmn");
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId())
                .singleResult();
        assertThat(definition.getTenantId()).isEqualTo("tenant-s2");
        ProcessInstance instance = runtimeService.createProcessInstanceBuilder().processDefinitionId(definition.getId())
                .businessKey(UUID.randomUUID().toString()).tenantId("tenant-s2").start();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("manager");
        assertThat(taskService.createTaskQuery().taskCandidateGroup("managers").taskTenantId("tenant-s2").list())
                .extracting(Task::getId).contains(task.getId());
        // The DMN resource of the same deployment became a child DMN deployment.
        org.flowable.common.engine.impl.interceptor.Command<Long> countPinned = ctx -> org.flowable.engine.impl.util.CommandContextUtil
                .getDmnRepositoryService().createDecisionQuery().parentDeploymentId(deployment.getId()).count();
        long pinned = managementService.executeCommand(countPinned);
        assertThat(pinned).isEqualTo(1L);
        taskService.complete(task.getId());
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
    }

    @Test
    void s3_decisionPinnedToParentDeploymentAndBigDecimalBoundaries() throws IOException {
        Deployment d1 = deploy("spike-s3-r1", "tenant-s3", "spike/approval.bpmn20.xml", "spike/policy-v1.dmn");
        Deployment d2 = deploy("spike-s3-r2", "tenant-s3", "spike/approval.bpmn20.xml", "spike/policy-v2.dmn");
        String pd1 = repositoryService.createProcessDefinitionQuery().deploymentId(d1.getId()).singleResult().getId();
        String pd2 = repositoryService.createProcessDefinitionQuery().deploymentId(d2.getId()).singleResult().getId();

        // Release 1 (threshold 5000): boundaries with BigDecimal.
        assertThat(route(pd1, "4999.99")).isEqualTo("standard");
        assertThat(route(pd1, "5000")).isEqualTo("financeReview");
        assertThat(route(pd1, "5000.00")).isEqualTo("financeReview");
        assertThat(route(pd1, "5000.01")).isEqualTo("financeReview");
        // Release 2 (threshold 10000) is the latest version of the same key; release 1 stays pinned to v1.
        assertThat(route(pd2, "5000")).isEqualTo("standard");
        assertThat(route(pd1, "9999.99")).isEqualTo("financeReview");
        assertThat(matched(pd1, "1800")).isEqualTo("default");
        assertThat(matched(pd1, "1800000")).isEqualTo("r1");
    }

    @Test
    void s4_retryCycleRnYieldsExactlyNAttempts() throws IOException {
        Deployment deployment = deploy("spike-s4", "tenant-s4", "spike/retry.bpmn20.xml");
        String pd = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult().getId();
        for (int n = 1; n <= 3; n++) {
            assertThat(attemptsUntilDeadLetter(pd, "R" + n + "/PT5S", CountingDelegate.Mode.FAIL_RETRYABLE))
                    .as("R%d attempts", n).isEqualTo(n);
        }
        // Unexpected exceptions (even with a null message) follow the same cycle.
        assertThat(attemptsUntilDeadLetter(pd, "R2/PT5S", CountingDelegate.Mode.FAIL_UNEXPECTED)).isEqualTo(2);
        // Non-retryable failures dead-letter immediately with retries 0.
        assertThat(attemptsUntilDeadLetter(pd, "R3/PT5S", CountingDelegate.Mode.FAIL_NON_RETRYABLE)).isEqualTo(1);
    }

    @Test
    void s4_deadLetterBackToExecutableResumes() throws IOException {
        Deployment deployment = deploy("spike-s4b", "tenant-s4", "spike/retry.bpmn20.xml");
        String pd = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult().getId();
        CountingDelegate.mode = CountingDelegate.Mode.FAIL_NON_RETRYABLE;
        CountingDelegate.INVOCATIONS.set(0);
        ProcessInstance instance = runtimeService.createProcessInstanceBuilder().processDefinitionId(pd)
                .tenantId("tenant-s4").variable("cycle", "R3/PT5S").start();
        Job dead = awaitDeadLetter(instance.getId());
        assertThat(dead.getRetries()).isZero();
        CountingDelegate.mode = CountingDelegate.Mode.SUCCEED;
        managementService.moveDeadLetterJobToExecutableJob(dead.getId(), 3);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                runtimeService.createProcessInstanceQuery().processInstanceId(instance.getId()).count() == 0);
        assertThat(CountingDelegate.INVOCATIONS.get()).isEqualTo(2);
    }

    @Test
    void s6_engineTablesInFlowableAppTablesInOrqixOneDataSourceAsRuntimeRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("orqix_app");
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'flowable' AND table_name = 'act_ru_task'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'flowable' AND table_name = 'act_dmn_decision'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'orqix' AND table_name LIKE 'act_%'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'orqix' AND table_name = 'workflow_run'")
                .query(Integer.class).single()).isEqualTo(1);
        // Pods run no DDL: the runtime role cannot create anything.
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE orqix.should_fail (id int)").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE flowable.should_fail (id int)").update())
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void s7_bigDecimalSurvivesParseVariableAndEvaluator() throws IOException {
        JsonNode input = json.parse("{\"amount\": 1800.07}");
        assertThat(input.get("amount").decimalValue()).isEqualByComparingTo("1800.07");
        Deployment deployment = deploy("spike-s7", "tenant-s7", "spike/approval.bpmn20.xml", "spike/policy-v1.dmn");
        String pd = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult().getId();
        ProcessInstance instance = runtimeService.createProcessInstanceBuilder().processDefinitionId(pd)
                .tenantId("tenant-s7").variable("orqixData", json.write(input)).start();
        String stored = (String) runtimeService.getVariable(instance.getId(), "orqixData");
        BigDecimal amount = json.parse(stored).get("amount").decimalValue();
        assertThat(amount.toPlainString()).isEqualTo("1800.07");
        assertThat(route(pd, amount.toPlainString())).isEqualTo("standard");
        assertThat(json.write(json.parse("{\"a\":5000.00}"))).isEqualTo("{\"a\":5000.00}");
    }

    @Test
    void s10_listenerWritesShareTheSpringTransaction() throws IOException {
        Deployment deployment = deploy("spike-s10", "tenant-s10", "spike/approval.bpmn20.xml");
        String pd = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult().getId();
        String marker = "spike-" + UUID.randomUUID();
        org.flowable.common.engine.api.delegate.event.FlowableEventListener listener = new SpikeAuditListener(jdbc, marker);
        runtimeService.addEventListener(listener, org.flowable.common.engine.api.delegate.event.FlowableEngineEventType.TASK_CREATED);
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                runtimeService.createProcessInstanceBuilder().processDefinitionId(pd).tenantId("tenant-s10")
                        .businessKey(marker).start();
                throw new IllegalStateException("forced rollback");
            })).hasMessage("forced rollback");
            assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(marker).count()).isZero();
            assertThat(auditRows(marker)).isZero();

            tx.executeWithoutResult(status -> runtimeService.createProcessInstanceBuilder().processDefinitionId(pd)
                    .tenantId("tenant-s10").businessKey(marker).start());
            assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(marker).count()).isEqualTo(1);
            assertThat(auditRows(marker)).isEqualTo(1);
        } finally {
            runtimeService.removeEventListener(listener);
        }
    }

    @Test
    void expressionBeanAllowlist_onlyOrqixBeansResolve() throws IOException {
        assertThat(probeBean("orqixRoute")).isTrue();
        // "clock", "jdbcClient" and "dataSource" are Spring beans, but not reachable from expressions.
        for (String bean : List.of("clock", "jdbcClient", "dataSource")) {
            assertThatThrownBy(() -> probeBean(bean)).as(bean).hasMessageContaining(bean);
        }
    }

    private boolean probeBean(String bean) throws IOException {
        String xml = new ClassPathResource("spike/bean-allowlist.bpmn20.xml").getContentAsString(StandardCharsets.UTF_8)
                .replace("BEAN_NAME", bean).replace("spikeBeans", "spikeBeans" + bean.replaceAll("[^A-Za-z]", ""));
        Deployment deployment = repositoryService.createDeployment().name("spike-beans-" + bean).tenantId("tenant-beans")
                .addString("beans.bpmn20.xml", xml).deploy();
        String pd = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult().getId();
        ProcessInstance instance = runtimeService.createProcessInstanceBuilder().processDefinitionId(pd)
                .tenantId("tenant-beans").start();
        return instance.isEnded();
    }

    private int auditRows(String marker) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :m").param("m", marker)
                .query(Integer.class).single();
    }

    private int attemptsUntilDeadLetter(String pd, String cycle, CountingDelegate.Mode mode) {
        CountingDelegate.mode = mode;
        CountingDelegate.INVOCATIONS.set(0);
        ProcessInstance instance = runtimeService.createProcessInstanceBuilder().processDefinitionId(pd)
                .tenantId("tenant-s4").variable("cycle", cycle).start();
        awaitDeadLetter(instance.getId());
        int attempts = CountingDelegate.INVOCATIONS.get();
        runtimeService.deleteProcessInstance(instance.getId(), "spike cleanup");
        return attempts;
    }

    /** Waits for the job to dead-letter, moving retry timers to executable instead of waiting for their due date. */
    private Job awaitDeadLetter(String processInstanceId) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            for (Job timer : managementService.createTimerJobQuery().processInstanceId(processInstanceId).list()) {
                managementService.moveTimerToExecutableJob(timer.getId());
            }
            return managementService.createDeadLetterJobQuery().processInstanceId(processInstanceId).count() == 1;
        });
        return managementService.createDeadLetterJobQuery().processInstanceId(processInstanceId).singleResult();
    }

    private String route(String processDefinitionId, String amount) {
        return evaluate(processDefinitionId, amount).outputs().get("route").asString();
    }

    private String matched(String processDefinitionId, String amount) {
        return evaluate(processDefinitionId, amount).matched();
    }

    private DecisionTaskDelegate.Result evaluate(String processDefinitionId, String amount) {
        String tenant = repositoryService.getProcessDefinition(processDefinitionId).getTenantId();
        return managementService.executeCommand(ctx -> decisionTaskDelegate.evaluatePinned(processDefinitionId, tenant,
                "spike-policy", new BigDecimal(amount), 1));
    }

    private Deployment deploy(String name, String tenant, String... resources) throws IOException {
        var builder = repositoryService.createDeployment().name(name).tenantId(tenant).category("orqix");
        for (String resource : resources) {
            try (InputStream in = new ClassPathResource(resource).getInputStream()) {
                String fileName = resource.substring(resource.lastIndexOf('/') + 1);
                builder.addBytes(fileName.endsWith(".dmn") ? "decisions/" + fileName : fileName, in.readAllBytes());
            }
        }
        return builder.deploy();
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> vars(Object... kv) {
        return Map.of();
    }
}
