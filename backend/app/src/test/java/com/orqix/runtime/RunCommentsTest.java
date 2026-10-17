package com.orqix.runtime;
import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;

class RunCommentsTest extends RuntimeTestBase {
    @Test void participantCommentsAreImmutableAuditedAndIdempotent() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,d -> {});
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String key=UUID.randomUUID().toString();
        var created=api("rhea.requester").post(Map.of("text","Please review the updated quote."),key,"/workspaces/{ws}/runs/{run}/comments",PURCHASING,run);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        var replay=api("rhea.requester").post(Map.of("text","Please review the updated quote."),key,"/workspaces/{ws}/runs/{run}/comments",PURCHASING,run);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("id").asString()).isEqualTo(created.body().path("id").asString());
        assertThat(api("rhea.requester").post(Map.of("text","Different"),key,"/workspaces/{ws}/runs/{run}/comments",PURCHASING,run).status()).isEqualTo(409);
        assertThat(api("mani.manager").get("/workspaces/{ws}/runs/{run}/comments",PURCHASING,run).body().path("comments")).hasSize(1);
        assertThat(api("mani.manager").post(Map.of("text","Reviewing now."),UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/comments",PURCHASING,run).status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id=:ws AND action='comment.created' AND resource_id=:id")
            .param("ws",PURCHASING).param("id",created.body().path("id").asString()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id=:ws AND action='comment.created' AND resource_id=:id")
            .param("ws",PURCHASING).param("id",created.body().path("id").asString()).query(String.class).single()).doesNotContain("quote");
        assertThat(jdbc.sql("SELECT has_table_privilege(current_user,'orqix.run_comment','UPDATE')").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT has_table_privilege(current_user,'orqix.run_comment','DELETE')").query(Boolean.class).single()).isFalse();
    }
    @Test void commentDataCannotLeakThroughSummaryVisibilityOrAnotherWorkspace() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,d -> {});
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        assertThat(api("otto.outsider").get("/workspaces/{ws}/runs/{run}/comments",PURCHASING,run).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{run}/comments",SANDBOX,run).status()).isEqualTo(404);
        assertThat(api("vic.viewer").post(Map.of("text","Unauthorized"),UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/comments",PURCHASING,run).status()).isEqualTo(403);
        assertThat(api("rhea.requester").post(Map.of("text"," "),UUID.randomUUID().toString(),"/workspaces/{ws}/runs/{run}/comments",PURCHASING,run).status()).isEqualTo(400);
    }
}
