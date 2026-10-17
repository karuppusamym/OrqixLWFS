package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

class OpenApiConnectionReviewApiTest extends RuntimeTestBase {
    private static final String SOURCE="""
        {"openapi":"3.0.3","info":{"title":"Reviewed policy API","version":"1"},
         "servers":[{"url":"https://alice:never-return-this@example.test/api?token=private-query"}],
         "paths":{"/health":{"get":{"operationId":"health","responses":{"200":{"description":"OK",
          "content":{"application/json":{"schema":{"type":"object","properties":{"status":{"type":"string"}},"required":["status"]}}}}}}}}}
        """;
    private Map<String,Object> selected(String key,boolean confirmed) {
        return Map.of("source",SOURCE,"fileName","api.json","key",key,"version",1,"name","Policy API",
                "selections",List.of(Map.of("key","health","effectClass","readOnly")),"confirmEffects",confirmed);
    }
    @Test void authorizedReviewAndExplicitRegistrationAreSeparateAndNeverSaveServerCredentials() throws Exception {
        String key="import-"+UUID.randomUUID().toString().substring(0,8);
        var analyzed=api("wade.admin").post(Map.of("source",SOURCE),"/workspaces/{ws}/connections/import-openapi",PURCHASING);
        assertThat(analyzed.status()).as(analyzed.toString()).isEqualTo(200);
        assertThat(analyzed.body().path("definition").isNull()).isTrue();
        assertThat(analyzed.body().toString()).doesNotContain("never-return-this","private-query","alice:");
        assertThat(api("wade.admin").post(selected(key,false),"/workspaces/{ws}/connections/import-openapi",PURCHASING).status()).isEqualTo(422);
        var built=api("wade.admin").post(selected(key,true),"/workspaces/{ws}/connections/import-openapi",PURCHASING);
        assertThat(built.status()).as(built.toString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM connection_definition WHERE workspace_id=:ws AND key=:key")
                .param("ws",PURCHASING).param("key",key).query(Long.class).single()).isZero();
        var saved=api("wade.admin").post(Map.of("definition",built.body().path("definition")),"/workspaces/{ws}/connections/{key}/versions",PURCHASING,key);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM connection_binding WHERE workspace_id=:ws AND connection_key=:key")
                .param("ws",PURCHASING).param("key",key).query(Long.class).single()).isZero();
        assertThat(api("wade.admin").put(Map.of("baseUrl",Stub.baseUrl(),"enabled",true,"auth",Map.of("type","none")),null,
                "/workspaces/{ws}/connections/{key}/binding",PURCHASING,key).status()).isEqualTo(200);
        var tested=api("wade.admin").post(Map.of("version",1,"operation","health"),
                "/workspaces/{ws}/connections/{key}/test",PURCHASING,key);
        assertThat(tested.status()).as(tested.toString()).isEqualTo(200);
        assertThat(tested.body().path("outcome").asString()).isEqualTo("succeeded");
        var metadata=jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id=:ws AND action='connection.openapi-reviewed' ORDER BY occurred_at DESC LIMIT 1")
                .param("ws",PURCHASING).query(String.class).single();
        assertThat(json.parse(metadata).propertyNames()).containsExactlyInAnyOrder("operations","selected");
    }
    @Test void reviewRequiresFreshWorkspaceManagementAndRejectsUnsafeSourceAndSelections() throws Exception {
        var body=Map.of("source",SOURCE);
        assertThat(api("dana.designer").post(body,"/workspaces/{ws}/connections/import-openapi",PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").post(body,"/workspaces/{ws}/connections/import-openapi",PURCHASING).status()).isEqualTo(404);
        assertThat(api("wade.admin").post(Map.of("source","openapi: 3.0.3\ninfo: &anchor {title: Unsafe}\npaths: {}"),"/workspaces/{ws}/connections/import-openapi",PURCHASING).status()).isEqualTo(422);
        assertThat(api("wade.admin").post(Map.of("source",SOURCE,"selections",List.of(Map.of("key","not-in-file","effectClass","readOnly")),"confirmEffects",true),"/workspaces/{ws}/connections/import-openapi",PURCHASING).status()).isEqualTo(422);
        assertThat(api("wade.admin").post(Map.of("source",SOURCE,"selections",List.of(Map.of("key","health","effectClass","idempotentWithKey","idempotencyHeader","Authorization")),"confirmEffects",true),"/workspaces/{ws}/connections/import-openapi",PURCHASING).status()).isEqualTo(422);
    }
}
