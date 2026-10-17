package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** EX04 in-flight release migration (docs/build/S38) on PostgreSQL and Flowable through the public API. */
class RunMigrationTest extends RuntimeTestBase {

    @Autowired
    private RuntimeService engine;

    /** Publishes another release of {@code workflow} from the current draft with {@code patch} applied. */
    private UUID release(UUID workflow, Consumer<ObjectNode> patch) throws Exception {
        JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body();
        int revision = draft.path("revision").asInt();
        ObjectNode definition = (ObjectNode) draft.path("definition");
        patch.accept(definition);
        Response saved = api("dana.designer").put(Map.of("definition", definition), "\"r" + revision + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        Response published = api("paul.publisher").post(
                Map.of("draftRevision", saved.body().path("revision").asInt(), "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return UUID.fromString(published.body().path("id").asString());
    }

    private UUID startRun(UUID workflow, String amount) throws Exception {
        return UUID.fromString(start("rhea.requester", PURCHASING, workflow, new BigDecimal(amount)).path("id").asString());
    }

    private Response suspend(UUID run) throws Exception {
        return api("olga.operator").post(Map.of("reason", "Moving to the new release",
                "expectedVersion", run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/suspend", PURCHASING, run);
    }

    private Response migrate(String user, UUID run, UUID target, int version, String idempotencyKey) throws Exception {
        return api(user).post(Map.of("targetReleaseId", target, "reason", "Pick up the new finance threshold",
                "expectedVersion", version), idempotencyKey, "/workspaces/{ws}/runs/{id}/migrate", PURCHASING, run);
    }

    private Response migrate(UUID run, UUID target) throws Exception {
        return migrate("olga.operator", run, target, run(PURCHASING, run).path("version").asInt(),
                UUID.randomUUID().toString());
    }

    private JsonNode options(UUID run) throws Exception {
        return api("olga.operator").get("/workspaces/{ws}/runs/{id}/migration-options", PURCHASING, run).body();
    }

    private JsonNode option(JsonNode options, UUID release) {
        for (JsonNode o : options.path("options")) {
            if (release.toString().equals(o.path("release").path("id").asString())) {
                return o;
            }
        }
        return null;
    }

    private String engineDefinition(UUID run) {
        return engine.createProcessInstanceQuery().processInstanceId(processInstance(PURCHASING, run)).singleResult()
                .getProcessDefinitionId();
    }

    private String releaseDefinition(UUID release) {
        return jdbc.sql("SELECT process_definition_id FROM workflow_release WHERE id = :id").param("id", release)
                .query(String.class).single();
    }

    private static final Consumer<ObjectNode> RAISE_THRESHOLD = d ->
            ((ObjectNode) d.path("decisions").get(0).path("rows").get(0).path("when")).put("value", 10000);

    @Test
    void aRequestMovesToAReleaseThatOnlyChangesWhatLiesAhead() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = startRun(workflow, "6000"); // finance approval is needed on release 1
        UUID r2 = release(workflow, RAISE_THRESHOLD);
        assertThat(run(PURCHASING, run).path("release").path("releaseNumber").asInt()).isEqualTo(1);

        // Not ready until suspended, but the verdict on each release is already visible.
        JsonNode before = options(run);
        assertThat(before.path("ready").asBoolean()).isFalse();
        assertThat(option(before, r2).path("compatible").asBoolean()).isTrue();
        Response early = migrate(run, r2);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().path("code").asString()).isEqualTo("RUN_NOT_SUSPENDED");

        assertThat(suspend(run).status()).isEqualTo(200);
        assertThat(options(run).path("ready").asBoolean()).isTrue();
        int version = run(PURCHASING, run).path("version").asInt();
        String idempotencyKey = UUID.randomUUID().toString();
        Response migrated = migrate("olga.operator", run, r2, version, idempotencyKey);
        assertThat(migrated.status()).as(migrated.toString()).isEqualTo(200);
        assertThat(migrated.body().path("release").path("releaseNumber").asInt()).isEqualTo(2);
        assertThat(migrated.body().path("suspension").path("since").isMissingNode()).isFalse();
        assertThat(engineDefinition(run)).isEqualTo(releaseDefinition(r2));
        assertThat(jdbc.sql("SELECT release_id FROM workflow_run WHERE id = :id").param("id", run).query(UUID.class).single())
                .isEqualTo(r2);

        Response replay = migrate("olga.operator", run, r2, version, idempotencyKey);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :id AND type = 'RUN_MIGRATED'").param("id", run)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id AND action = 'run.migrated'")
                .param("id", run.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata->>'toRelease' FROM audit_event WHERE resource_id = :id AND action = 'run.migrated'")
                .param("id", run.toString()).query(String.class).single()).isEqualTo("2");

        // Resume: the manager decides and release 2 no longer sends 6,000 to finance.
        Response resumed = api("olga.operator").post(Map.of("reason", "Moved", "expectedVersion",
                run(PURCHASING, run).path("version").asInt()), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs/{id}/resume", PURCHASING, run);
        assertThat(resumed.status()).as(resumed.toString()).isEqualTo(200);
        approve("mani.manager", PURCHASING, run, "manager");
        awaitLifecycle(PURCHASING, run, "completed");
        assertThat(openTask(PURCHASING, run, "finance")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id = :id AND node_id = 'finance'").param("id", run)
                .query(Long.class).single()).isZero();
    }

    @Test
    void changedStepSettingsAndChangedHistoryAreRefusedWithReasons() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = startRun(workflow, "6000");
        UUID stepChanged = release(workflow, d -> {
            for (JsonNode node : d.path("nodes")) {
                if ("manager".equals(node.path("id").asString())) {
                    ((ObjectNode) node.path("config")).put("dueAfterHours", 48);
                }
            }
        });

        JsonNode listed = options(run);
        JsonNode stepOption = option(listed, stepChanged);
        assertThat(stepOption.path("compatible").asBoolean()).isFalse();
        assertThat(stepOption.path("findings").get(0).path("code").asString()).isEqualTo("STEP_CHANGED");
        assertThat(stepOption.path("findings").get(0).path("nodeId").asString()).isEqualTo("manager");

        assertThat(suspend(run).status()).isEqualTo(200);
        Response refused = migrate(run, stepChanged);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().path("code").asString()).isEqualTo("RUN_NOT_MIGRATABLE");
        assertThat(refused.body().path("findings").get(0).path("code").asString()).isEqualTo("STEP_CHANGED");
        assertThat(run(PURCHASING, run).path("release").path("releaseNumber").asInt()).isEqualTo(1);
    }

    @Test
    void aRequestPastTheDecisionCannotTakeAReleaseWithADifferentDecision() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID atFinance = startRun(workflow, "6000");
        approve("mani.manager", PURCHASING, atFinance, "manager");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> openTask(PURCHASING, atFinance, "finance") != null);
        UUID r2 = release(workflow, RAISE_THRESHOLD);

        JsonNode option = option(options(atFinance), r2);
        assertThat(option.path("compatible").asBoolean()).isFalse();
        assertThat(option.path("findings").get(0).path("code").asString()).isEqualTo("HISTORY_CHANGED");
        assertThat(suspend(atFinance).status()).isEqualTo(200);
        assertThat(migrate(atFinance, r2).body().path("code").asString()).isEqualTo("RUN_NOT_MIGRATABLE");
    }

    @Test
    void renamingTheWaitingStepCarriesOverAndAccessRulesHold() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = startRun(workflow, "6000");
        UUID renamed = release(workflow, d -> {
            for (JsonNode node : d.path("nodes")) {
                if ("manager".equals(node.path("id").asString())) {
                    ((ObjectNode) node).put("label", "Line manager review");
                }
            }
        });
        assertThat(api("rhea.requester").get("/workspaces/{ws}/runs/{id}/migration-options", PURCHASING, run).status())
                .isEqualTo(403);
        assertThat(suspend(run).status()).isEqualTo(200);
        int version = run(PURCHASING, run).path("version").asInt();
        assertThat(migrate("rhea.requester", run, renamed, version, UUID.randomUUID().toString()).status()).isEqualTo(403);
        assertThat(migrate("olga.operator", run, renamed, version + 3, UUID.randomUUID().toString()).status()).isEqualTo(409);
        assertThat(migrate("olga.operator", run, UUID.randomUUID(), version, UUID.randomUUID().toString()).status())
                .isEqualTo(404);

        Response migrated = migrate("olga.operator", run, renamed, version, UUID.randomUUID().toString());
        assertThat(migrated.status()).as(migrated.toString()).isEqualTo(200);
        assertThat(migrated.body().path("currentSteps").get(0).path("label").asString()).isEqualTo("Line manager review");
        assertThat(jdbc.sql("SELECT label FROM task_context WHERE run_id = :id AND status = 'open'").param("id", run)
                .query(String.class).single()).isEqualTo("Line manager review");
    }
}
