package com.orqix.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.orqix.identity.Caller;
import com.orqix.shared.problem.ApiException;
import com.orqix.support.IntegrationTestBase;

/** Exercises Orqix authorization and versioning over the migrated PostgreSQL CMMN engine. */
class CaseServiceTest extends IntegrationTestBase {
    @Autowired private CaseService cases;
    @Autowired private JdbcClient jdbc;

    private Caller caller(String group) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO subject(id,issuer,external_subject,display_name) VALUES(:id,'urn:orqix:test',:external,'Case test')")
                .param("id",id).param("external",id.toString()).update();
        return new Caller(id,"Test",null,
                "test","test",List.of(group),false);
    }

    private static String fixture() throws Exception {
        try (var input=CaseServiceTest.class.getResourceAsStream("/cases/cmmn-compatibility.cmmn")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private CaseService.Definition publish(Caller designer) throws Exception {
        String key="case"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String source=fixture().replace("compatibilityCase",key).replace("case-workers","managers");
        return cases.createDefinition(designer,SANDBOX,new CaseService.CreateDefinition(key,"Review case",source));
    }

    @Test void publishesAndRunsPinnedCaseWithSentryAndIdempotentCommands() throws Exception {
        var designer=caller("orqix-purchasing-designers");
        var requester=caller("orqix-purchasing-requesters");
        var manager=caller("orqix-purchasing-managers");
        var outsider=caller("unrelated");
        var definition=publish(designer);
        assertThat(definition.version()).isEqualTo(1);
        assertThat(cases.definitions(requester,SANDBOX)).extracting(CaseService.Definition::id).contains(definition.id());
        var started=cases.start(designer,SANDBOX,definition.id(),"start-"+definition.id());
        assertThat(started.replayed()).isFalse();
        var first=started.value();
        assertThat(first.status()).isEqualTo("active");
        assertThat(first.tasks()).extracting(CaseService.Task::name).containsExactly("Collect evidence");
        assertThat(first.tasks().getFirst().eligible()).isTrue();
        assertThat(cases.start(designer,SANDBOX,definition.id(),"start-"+definition.id()).replayed()).isTrue();
        assertThat(cases.instances(manager,SANDBOX)).extracting(CaseService.Instance::id).contains(first.id());
        assertThat(cases.instance(manager,SANDBOX,first.id()).tasks().getFirst().eligible()).isTrue();
        assertThatThrownBy(() -> cases.instance(outsider,SANDBOX,first.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cases.instance(designer,PURCHASING,first.id())).isInstanceOf(ApiException.class);

        String key="complete-"+first.id()+"-1";
        var next=cases.complete(manager,SANDBOX,first.id(),first.tasks().getFirst().id(),key,
                new CaseService.CompleteTask(first.version()));
        assertThat(next.value().tasks()).extracting(CaseService.Task::name).containsExactly("Review evidence");
        assertThat(next.value().version()).isEqualTo(first.version()+1);
        assertThat(cases.complete(manager,SANDBOX,first.id(),first.tasks().getFirst().id(),key,
                new CaseService.CompleteTask(first.version())).replayed()).isTrue();
        var changedGroup=new Caller(manager.subjectId(),"Test",null,"test","test",
                List.of("orqix-purchasing-finance"),false);
        assertThatThrownBy(() -> cases.complete(changedGroup,SANDBOX,first.id(),first.tasks().getFirst().id(),key,
                new CaseService.CompleteTask(first.version()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cases.complete(manager,SANDBOX,first.id(),next.value().tasks().getFirst().id(),
                "stale-"+first.id(),new CaseService.CompleteTask(first.version()))).isInstanceOf(ApiException.class);
        String finalKey="complete-"+first.id()+"-2";
        var completed=cases.complete(manager,SANDBOX,first.id(),next.value().tasks().getFirst().id(),
                finalKey,new CaseService.CompleteTask(next.value().version())).value();
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.tasks()).isEmpty();
        assertThat(cases.complete(manager,SANDBOX,first.id(),next.value().tasks().getFirst().id(),
                finalKey,new CaseService.CompleteTask(next.value().version())).value()).isEqualTo(completed);
        assertThatThrownBy(() -> cases.instance(manager,SANDBOX,first.id())).isInstanceOf(ApiException.class);
        assertThat(cases.instance(designer,SANDBOX,first.id()).status()).isEqualTo("completed");
    }

    @Test void rejectsUnsupportedXmlAndKeepsDefinitionsImmutable() throws Exception {
        var designer=caller("orqix-purchasing-designers");
        var definition=publish(designer);
        String source=cases.definition(designer,SANDBOX,definition.id()).sourceXml();
        var second=cases.createDefinition(designer,SANDBOX,
                new CaseService.CreateDefinition(definition.key(),"Review case v2",source));
        assertThat(second.version()).isEqualTo(2);
        assertThat(cases.definition(designer,SANDBOX,definition.id()).version()).isEqualTo(1);
        assertThatThrownBy(() -> cases.createDefinition(designer,SANDBOX,new CaseService.CreateDefinition(
                "unsafe", "Unsafe",source.replace(definition.key(),"unsafe").replace("<humanTask", "<scriptTask"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cases.createDefinition(designer,SANDBOX,new CaseService.CreateDefinition(
                "unsafe", "Unsafe", "<!DOCTYPE definitions [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+source)))
                .isInstanceOf(ApiException.class);
    }

    @Test void operatorTerminationCancelsEngineTasksAndCannotBeReplayedWithDifferentIntent() throws Exception {
        var designer = caller("orqix-purchasing-designers");
        var operator = caller("orqix-purchasing-operators");
        var manager = caller("orqix-purchasing-managers");
        var definition = publish(designer);
        var active = cases.start(designer, SANDBOX, definition.id(), "start-" + definition.id()).value();
        var request = new CaseService.TerminateCase(active.version(), "Duplicate request");
        String key = "terminate-" + active.id();
        assertThatThrownBy(() -> cases.terminate(manager, SANDBOX, active.id(), key, request)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cases.terminate(operator, PURCHASING, active.id(), key, request)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cases.terminate(operator, SANDBOX, active.id(), key, new CaseService.TerminateCase(2, "Stale")))
                .isInstanceOf(ApiException.class);
        var result = cases.terminate(operator, SANDBOX, active.id(), key, request);
        assertThat(result.value().status()).isEqualTo("terminated");
        assertThat(result.value().version()).isEqualTo(2);
        assertThat(result.value().tasks()).isEmpty();
        assertThat(cases.terminate(operator, SANDBOX, active.id(), key, request).replayed()).isTrue();
        assertThatThrownBy(() -> cases.terminate(operator, SANDBOX, active.id(), key, new CaseService.TerminateCase(1, "Changed")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cases.complete(manager, SANDBOX, active.id(), active.tasks().getFirst().id(),
                "late-" + active.id(), new CaseService.CompleteTask(2))).isInstanceOf(ApiException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM flowable.act_ru_task WHERE scope_id_=(SELECT engine_instance_id FROM case_instance WHERE id=:id)")
                .param("id", active.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM case_termination WHERE workspace_id=:ws AND case_id=:id")
                .param("ws", SANDBOX).param("id", active.id()).query(Long.class).single()).isEqualTo(1);
    }
}
