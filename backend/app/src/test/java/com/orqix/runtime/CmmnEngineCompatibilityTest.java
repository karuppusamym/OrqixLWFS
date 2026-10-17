package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.flowable.cmmn.api.CmmnRepositoryService;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.api.CmmnTaskService;
import org.flowable.cmmn.api.CmmnHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.IntegrationTestBase;

/** WM04 engine integration check on the real migrated PostgreSQL test stack; not a public case API. */
class CmmnEngineCompatibilityTest extends IntegrationTestBase {
    @Autowired private CmmnRepositoryService repository;
    @Autowired private CmmnRuntimeService runtime;
    @Autowired private CmmnTaskService tasks;
    @Autowired private CmmnHistoryService history;

    @Test void sentryAdvancesHumanTasksAndMilestoneSurvivesCaseCompletion() {
        repository.createDeployment().addClasspathResource("cases/cmmn-compatibility.cmmn").deploy();
        var instance = runtime.createCaseInstanceBuilder().caseDefinitionKey("compatibilityCase").start();
        var initial = tasks.createTaskQuery().caseInstanceId(instance.getId()).list();
        assertThat(initial).extracting(org.flowable.task.api.Task::getName).containsExactly("Collect evidence");
        tasks.complete(initial.getFirst().getId());
        var review = tasks.createTaskQuery().caseInstanceId(instance.getId()).list();
        assertThat(review).extracting(org.flowable.task.api.Task::getName).containsExactly("Review evidence");
        tasks.complete(review.getFirst().getId());
        assertThat(tasks.createTaskQuery().caseInstanceId(instance.getId()).count()).isZero();
        assertThat(history.createHistoricCaseInstanceQuery().caseInstanceId(instance.getId()).singleResult().getEndTime()).isNotNull();
        assertThat(history.createHistoricMilestoneInstanceQuery().milestoneInstanceCaseInstanceId(instance.getId()).list())
                .extracting(org.flowable.cmmn.api.history.HistoricMilestoneInstance::getName).contains("Reviewed");
    }
}
