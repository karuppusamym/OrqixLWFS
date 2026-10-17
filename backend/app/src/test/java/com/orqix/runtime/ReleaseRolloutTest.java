package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** PL02 (docs/build/S54): a bounded canary routes a share of new starts, with per-release analytics and boundaries. */
class ReleaseRolloutTest extends RuntimeTestBase {

    private String path(UUID wf, String suffix) {
        return "/workspaces/{ws}/workflows/" + wf + suffix;
    }

    private JsonNode releases(UUID wf) throws Exception {
        return api("vic.viewer").get(path(wf, "/releases"), PURCHASING).body().path("items");
    }

    private UUID releaseId(UUID wf, int number) throws Exception {
        for (JsonNode r : releases(wf)) {
            if (r.path("releaseNumber").asInt() == number) {
                return UUID.fromString(r.path("id").asString());
            }
        }
        throw new AssertionError("no release " + number);
    }

    /** Publishes another release of the workflow without activating it. */
    private void publishInactive(UUID wf, java.util.function.Consumer<ObjectNode> patch) throws Exception {
        JsonNode draft = api("dana.designer").get(path(wf, "/draft"), PURCHASING).body();
        int revision = draft.path("revision").asInt();
        ObjectNode definition = (ObjectNode) draft.path("definition");
        patch.accept(definition);
        var saved = api("dana.designer").put(Map.of("definition", definition), "\"r" + revision + "\"",
                path(wf, "/draft"), PURCHASING);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        var published = api("paul.publisher").post(Map.of("draftRevision", revision + 1, "acknowledgeWarnings", true,
                "activate", false), UUID.randomUUID().toString(), path(wf, "/publish"), PURCHASING);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
    }

    private JsonNode rollout(UUID wf) throws Exception {
        return api("vic.viewer").get(path(wf, "/rollout"), PURCHASING).body();
    }

    private Response canary(String user, UUID wf, UUID release, int percent, int version) throws Exception {
        return api(user).put(Map.of("releaseId", release.toString(), "percent", percent, "expectedVersion", version), null,
                path(wf, "/canary"), PURCHASING);
    }

    @Test
    void canaryRoutesAShareOfStartsAndFeedsReleaseAnalytics() throws Exception {
        UUID wf = publishPurchase(PURCHASING, null);
        publishInactive(wf, d -> ((ObjectNode) d.path("metadata")).put("description", "second release"));
        UUID first = releaseId(wf, 1);
        UUID second = releaseId(wf, 2);
        assertThat(rollout(wf).path("canary").isNull()).isTrue();
        int version = rollout(wf).path("version").asInt();

        var set = canary("paul.publisher", wf, second, 50, version);
        assertThat(set.status()).as(set.toString()).isEqualTo(200);
        assertThat(set.body().path("canary").path("percent").asInt()).isEqualTo(50);
        assertThat(set.body().path("canary").path("releaseNumber").asInt()).isEqualTo(2);
        assertThat(set.body().path("version").asInt()).isEqualTo(version + 1);

        int total = 24;
        for (int i = 0; i < total; i++) {
            start("rhea.requester", PURCHASING, wf, new BigDecimal("100"));
        }
        Map<UUID, Long> byRelease = new HashMap<>();
        jdbc.sql("SELECT release_id, count(*) AS n FROM workflow_run WHERE workflow_id = :wf GROUP BY release_id")
                .param("wf", wf).query((rs, n) -> {
                    byRelease.put(rs.getObject("release_id", UUID.class), rs.getLong("n"));
                    return null;
                }).list();
        // 24 fair coin flips all landing on one release has probability 2^-23.
        assertThat(byRelease.getOrDefault(first, 0L)).isPositive();
        assertThat(byRelease.getOrDefault(second, 0L)).isPositive();
        assertThat(byRelease.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(total);

        JsonNode figures = api("vic.viewer").get(path(wf, "/release-analytics?days=7"), PURCHASING).body();
        assertThat(figures.path("days").asInt()).isEqualTo(7);
        long started = 0;
        for (JsonNode r : figures.path("releases")) {
            UUID id = UUID.fromString(r.path("releaseId").asString());
            assertThat(r.path("started").asLong()).isEqualTo(byRelease.getOrDefault(id, 0L));
            assertThat(r.path("active").asBoolean()).isEqualTo(id.equals(first));
            assertThat(r.path("canary").asBoolean()).isEqualTo(id.equals(second));
            started += r.path("started").asLong();
        }
        assertThat(started).isEqualTo(total);
        // Aggregate counts only: nothing of the request data appears.
        assertThat(figures.toString()).doesNotContain("Replacement laptop");

        // Promoting the canary (making it active) ends it; a stale version is refused first.
        int current = rollout(wf).path("version").asInt();
        assertThat(api("paul.publisher").post(Map.of("releaseId", second.toString(), "expectedVersion", current - 1),
                path(wf, "/active-release"), PURCHASING).status()).isEqualTo(409);
        var activated = api("paul.publisher").post(Map.of("releaseId", second.toString(), "expectedVersion", current),
                path(wf, "/active-release"), PURCHASING);
        assertThat(activated.status()).as(activated.toString()).isEqualTo(200);
        assertThat(rollout(wf).path("canary").isNull()).isTrue();
        assertThat(rollout(wf).path("activeReleaseNumber").asInt()).isEqualTo(2);
    }

    @Test
    void canaryMustMatchTheFormAndIsGuardedByPublishPermissionAndVersion() throws Exception {
        UUID wf = publishPurchase(PURCHASING, null);
        publishInactive(wf, d -> ((ObjectNode) d.path("metadata")).put("description", "same form"));
        publishInactive(wf, d -> ((ObjectNode) d.path("inputSchema").path("properties")).putObject("extraNote")
                .put("type", "string").put("title", "Extra note"));
        UUID first = releaseId(wf, 1);
        UUID sameForm = releaseId(wf, 2);
        UUID otherForm = releaseId(wf, 3);
        int version = rollout(wf).path("version").asInt();

        // Only people who may publish can change it; reading needs workflow.read.
        assertThat(canary("dana.designer", wf, sameForm, 10, version).status()).isEqualTo(403);
        assertThat(canary("vic.viewer", wf, sameForm, 10, version).status()).isEqualTo(403);
        assertThat(canary("rhea.requester", wf, sameForm, 10, version).status()).isIn(403, 404);
        assertThat(canary("otto.outsider", wf, sameForm, 10, version).status()).isEqualTo(404);
        assertThat(api("rhea.requester").get(path(wf, "/rollout"), PURCHASING).status()).isIn(403, 404);
        assertThat(api("otto.outsider").get(path(wf, "/rollout"), PURCHASING).status()).isEqualTo(404);

        // The active release, a different form, a stale version and an out-of-range share are all refused.
        assertThat(canary("paul.publisher", wf, first, 10, version).status()).isEqualTo(422);
        var different = canary("paul.publisher", wf, otherForm, 10, version);
        assertThat(different.status()).isEqualTo(422);
        assertThat(different.body().path("detail").asString()).contains("request fields differ");
        assertThat(canary("paul.publisher", wf, sameForm, 10, version + 5).status()).isEqualTo(409);
        assertThat(canary("paul.publisher", wf, sameForm, 51, version).status()).isIn(400, 422);
        assertThat(canary("paul.publisher", wf, sameForm, 0, version).status()).isIn(400, 422);
        assertThat(canary("paul.publisher", wf, UUID.randomUUID(), 10, version).status()).isEqualTo(404);
        assertThat(rollout(wf).path("canary").isNull()).isTrue();

        assertThat(canary("paul.publisher", wf, sameForm, 10, version).status()).isEqualTo(200);
        int afterSet = rollout(wf).path("version").asInt();
        // Clearing is guarded the same way and leaves every new start on the active release.
        assertThat(api("dana.designer").delete(path(wf, "/canary?expectedVersion=" + afterSet), PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("paul.publisher").delete(path(wf, "/canary?expectedVersion=" + version), PURCHASING).status())
                .isEqualTo(409);
        var cleared = api("paul.publisher").delete(path(wf, "/canary?expectedVersion=" + afterSet), PURCHASING);
        assertThat(cleared.status()).as(cleared.toString()).isEqualTo(200);
        assertThat(cleared.body().path("canary").isNull()).isTrue();

        // Analytics need run.read and a sane window.
        assertThat(api("rhea.requester").get(path(wf, "/release-analytics"), PURCHASING).status()).isIn(403, 404);
        assertThat(api("vic.viewer").get(path(wf, "/release-analytics?days=0"), PURCHASING).status()).isEqualTo(400);
        assertThat(api("vic.viewer").get(path(wf, "/release-analytics?days=366"), PURCHASING).status()).isEqualTo(400);
        assertThat(api("otto.outsider").get(path(wf, "/release-analytics"), PURCHASING).status()).isEqualTo(404);
        assertThat(api("vic.viewer").get(path(wf, "/release-analytics"), PURCHASING).body().path("releases")).hasSize(3);
    }

    @Test
    void shareIsAStableRoughlyProportionalFunctionOfTheRunId() {
        for (int percent : List.of(1, 10, 25, 50)) {
            int hits = 0;
            for (int i = 0; i < 20_000; i++) {
                UUID id = UUID.randomUUID();
                boolean in = ReleaseQueries.inCanaryShare(id, percent);
                assertThat(ReleaseQueries.inCanaryShare(id, percent)).isEqualTo(in);
                if (in) {
                    hits++;
                }
            }
            assertThat(hits / 20_000.0).isBetween(percent / 100.0 - 0.02, percent / 100.0 + 0.02);
        }
    }
}
