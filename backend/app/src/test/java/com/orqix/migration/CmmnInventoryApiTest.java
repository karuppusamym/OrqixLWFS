package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.support.RuntimeTestBase;

class CmmnInventoryApiTest extends RuntimeTestBase {
    @Test void workspaceUploadPersistsReportButNeverCreatesWorkflowOrCase() throws Exception {
        String content = Files.readString(Path.of("src/test/resources/cases/cmmn-compatibility.cmmn"));
        var source = Map.of("fileName", "review.cmmn", "content", content, "sourceFormat", "cmmn");
        assertThat(api("rhea.requester").post(source, "/workspaces/{ws}/migrations", PURCHASING).status()).isEqualTo(403);
        var uploaded = api("dana.designer").post(source, "/workspaces/{ws}/migrations", PURCHASING);
        assertThat(uploaded.status()).as(uploaded.toString()).isEqualTo(201);
        var job = uploaded.body();
        String id = job.path("id").asString();
        assertThat(job.path("report").path("sourceSystem").asString()).isEqualTo("cmmn");
        assertThat(job.path("report").path("publishable").asBoolean()).isFalse();
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}", PURCHASING, id).body()
                .path("report").path("sourceHash").asString()).isEqualTo(job.path("report").path("sourceHash").asString());
        assertThat(api("otto.outsider").get("/workspaces/{ws}/migrations/{id}", PURCHASING, id).status()).isEqualTo(404);
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}", SANDBOX, id).status()).isEqualTo(404);
        var blocked = api("dana.designer").post(Map.of("workflowKey", "do-not-create", "name", "CMMN"),
                "/workspaces/{ws}/migrations/{id}/convert", PURCHASING, id);
        assertThat(blocked.status()).as(blocked.toString()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow WHERE workspace_id=:ws AND key='do-not-create'")
                .param("ws", PURCHASING).query(Integer.class).single()).isZero();
        assertThat(api("dana.designer").get("/workspaces/{ws}/migrations/{id}", PURCHASING, id).body()
                .path("status").asString()).isEqualTo("analyzed");
    }
}
