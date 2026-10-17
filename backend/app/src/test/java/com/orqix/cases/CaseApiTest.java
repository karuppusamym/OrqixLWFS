package com.orqix.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.IntegrationTestBase;

/** Browser-session API journey; every response is checked against OpenAPI by IntegrationTestBase. */
class CaseApiTest extends IntegrationTestBase {
    @Test void designerPublishesRequesterStartsAndEligibleWorkerCompletes() throws Exception {
        String key="apicase"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        String source;
        try (var input=getClass().getResourceAsStream("/cases/cmmn-compatibility.cmmn")) {
            source=new String(input.readAllBytes(),StandardCharsets.UTF_8)
                    .replace("compatibilityCase",key).replace("case-workers","managers");
        }
        String base="/workspaces/"+SANDBOX+"/cases";
        var created=api("dana.designer").post(Map.of("key",key,"name","API case","sourceXml",source),base+"/definitions");
        assertThat(created.status()).isEqualTo(201);
        String definitionId=created.body().path("id").asString();
        assertThat(api("rhea.requester").get(base+"/definitions").body().toString()).contains(definitionId);
        var started=api("rhea.requester").post(Map.of(),"start-api-"+key,
                base+"/definitions/"+definitionId+"/instances");
        assertThat(started.status()).isEqualTo(201);
        String instanceId=started.body().path("id").asString();
        String taskId=started.body().path("tasks").get(0).path("id").asString();
        assertThat(api("mani.manager").get(base+"/instances/"+instanceId).body().path("tasks").get(0)
                .path("eligible").asBoolean()).isTrue();
        assertThat(api("otto.outsider").get(base+"/instances/"+instanceId).status()).isEqualTo(404);
        var completed=api("mani.manager").post(Map.of("expectedVersion",1),"complete-api-"+key,
                base+"/instances/"+instanceId+"/tasks/"+taskId+"/complete");
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.body().path("version").asInt()).isEqualTo(2);
        assertThat(completed.body().path("tasks").get(0).path("name").asString()).isEqualTo("Review evidence");
    }
}
