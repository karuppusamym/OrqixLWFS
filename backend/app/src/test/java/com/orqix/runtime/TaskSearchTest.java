package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.orqix.support.RuntimeTestBase;

class TaskSearchTest extends RuntimeTestBase {
    @Test void filtersBeforePaginationCountsOnlyMatchesAndBindsCursor() throws Exception {
        UUID workflow = publishPurchase(PURCHASING,null);
        String marker = "search-" + UUID.randomUUID();
        for (int i=0;i<4;i++) {
            UUID run = UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
            String task = openTask(PURCHASING,run,"manager").path("id").asString();
            jdbc.sql("UPDATE task_context SET label=:label, due_at=now()-interval '1 hour' WHERE engine_task_id=:task")
                .param("label",i<2 ? "Unrelated" : marker).param("task",task).update();
        }
        var first=api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&q={q}&queue=managers&due=overdue&limit=1",PURCHASING,marker);
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        assertThat(first.body().path("matchedCount").asInt()).isEqualTo(2);
        assertThat(first.body().path("items").size()).isEqualTo(1);
        String cursor=first.body().path("nextCursor").asString();
        var next=api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&q={q}&queue=managers&due=overdue&limit=1&cursor={cursor}",PURCHASING,marker,cursor);
        assertThat(next.status()).as(next.toString()).isEqualTo(200);
        assertThat(next.body().path("items").get(0).path("id").asString()).isNotEqualTo(first.body().path("items").get(0).path("id").asString());
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&q={q}&due=soon&cursor={cursor}",PURCHASING,marker,cursor).status()).isEqualTo(400);
        var outsider=api("fin.finance").get("/workspaces/{ws}/tasks?view=claimable&q={q}",PURCHASING,marker);
        assertThat(outsider.body().path("items").size()).isZero();
        assertThat(outsider.body().path("matchedCount").asInt()).isZero();
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&due=invalid",PURCHASING).status()).isEqualTo(400);
    }

    @Test void searchUsesDisplayedTitleAndTreatsWildcardsLiterally() throws Exception {
        UUID workflow=publishPurchase(PURCHASING,null);
        UUID run=UUID.fromString(start("rhea.requester",PURCHASING,workflow,BigDecimal.TEN).path("id").asString());
        String task=openTask(PURCHASING,run,"manager").path("id").asString();
        String marker="Literal_%_"+UUID.randomUUID();
        jdbc.sql("UPDATE task_context SET label=:label,due_at=NULL WHERE engine_task_id=:task").param("label",marker).param("task",task).update();
        var page=api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&q={q}&due=none",PURCHASING,marker.toUpperCase());
        assertThat(page.status()).as(page.toString()).isEqualTo(200);
        assertThat(page.body().path("items").size()).isEqualTo(1);
        String title=page.body().path("items").get(0).path("requestTitle").asString();
        var byTitle=api("mani.manager").get("/workspaces/{ws}/tasks?view=claimable&q={q}",PURCHASING,title+" "+marker);
        assertThat(byTitle.status()).as(byTitle.toString()).isEqualTo(200);
        assertThat(byTitle.body().path("items").size()).isEqualTo(1);
    }
}
