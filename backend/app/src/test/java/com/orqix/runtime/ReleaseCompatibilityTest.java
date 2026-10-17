package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.orqix.runtime.internal.ReleaseCompatibility;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/** Pure graph rules of release migration (docs/build/S38); the engine behavior is covered by RunMigrationTest. */
class ReleaseCompatibilityTest {

    private final Json json = new Json(tools.jackson.databind.json.JsonMapper.builder().build());

    private JsonNode flow(String nodes, String transitions) {
        return json.parse("{\"nodes\":[" + nodes + "],\"transitions\":[" + transitions + "],\"stages\":[]}");
    }

    private static String node(String id, String type, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"label\":\"" + id + "\",\"config\":" + config + "}";
    }

    private static String t(String from, String to, String on) {
        return "{\"id\":\"" + from + to + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"on\":\"" + on + "\"}";
    }

    private static final String START = node("start", "start", "{}");
    private static final String END = node("end", "end", "{}");

    private List<String> codes(JsonNode source, JsonNode target, String... active) {
        return ReleaseCompatibility.check(source, target, List.of(active)).stream()
                .map(ReleaseCompatibility.Finding::code).distinct().toList();
    }

    private JsonNode base(String managerConfig, String afterManager) {
        return flow(START + "," + node("manager", "approval", managerConfig) + "," + afterManager + "," + END,
                t("start", "manager", "next") + "," + t("manager", "end", "approved"));
    }

    @Test
    void identicalReleasesAreCompatible() {
        JsonNode a = base("{\"candidateGroupRef\":\"managers\"}", node("notify", "notification", "{}"));
        assertThat(codes(a, a, "manager")).isEmpty();
    }

    @Test
    void changesAheadOfTheWaitingStepAreAllowed() {
        JsonNode a = flow(START + "," + node("manager", "approval", "{\"candidateGroupRef\":\"managers\"}") + ","
                + node("finance", "approval", "{\"candidateGroupRef\":\"finance\"}") + "," + END,
                t("start", "manager", "next") + "," + t("manager", "finance", "approved") + "," + t("finance", "end", "approved"));
        JsonNode b = flow(START + "," + node("manager", "approval", "{\"candidateGroupRef\":\"managers\"}") + ","
                + node("finance", "approval", "{\"candidateGroupRef\":\"directors\"}") + "," + END,
                t("start", "manager", "next") + "," + t("manager", "end", "approved"));
        assertThat(codes(a, b, "manager")).isEmpty();
    }

    @Test
    void stepSettingsKindAndPresenceMustMatch() {
        JsonNode a = base("{\"candidateGroupRef\":\"managers\",\"dueAfterHours\":24}", node("x", "notification", "{}"));
        assertThat(codes(a, base("{\"candidateGroupRef\":\"managers\",\"dueAfterHours\":48}", node("x", "notification", "{}")),
                "manager")).containsExactly("STEP_CHANGED");
        JsonNode removed = flow(START + "," + node("other", "approval", "{}") + "," + END,
                t("start", "other", "next") + "," + t("other", "end", "approved"));
        assertThat(codes(a, removed, "manager")).containsExactly("STEP_MISSING");
        JsonNode retyped = flow(START + "," + node("manager", "wait", "{\"duration\":\"PT1H\"}") + "," + END,
                t("start", "manager", "next") + "," + t("manager", "end", "next"));
        assertThat(codes(a, retyped, "manager")).contains("STEP_CHANGED");
    }

    @Test
    void relabellingAStepIsNotAChange() {
        JsonNode a = base("{\"candidateGroupRef\":\"managers\"}", node("x", "notification", "{}"));
        JsonNode b = json.parse(json.write(a).replace("\"label\":\"manager\"", "\"label\":\"Line manager review\""));
        assertThat(codes(a, b, "manager")).isEmpty();
    }

    @Test
    void theTravelledPartMustBeIdentical() {
        JsonNode a = flow(START + "," + node("review", "approval", "{\"candidateGroupRef\":\"managers\"}") + ","
                + node("finance", "approval", "{\"candidateGroupRef\":\"finance\"}") + "," + END,
                t("start", "review", "next") + "," + t("review", "finance", "approved") + "," + t("finance", "end", "approved"));
        JsonNode changedUpstream = flow(START + "," + node("review", "approval", "{\"candidateGroupRef\":\"directors\"}") + ","
                + node("finance", "approval", "{\"candidateGroupRef\":\"finance\"}") + "," + END,
                t("start", "review", "next") + "," + t("review", "finance", "approved") + "," + t("finance", "end", "approved"));
        assertThat(codes(a, changedUpstream, "finance")).containsExactly("HISTORY_CHANGED");
        JsonNode addedUpstream = flow(START + "," + node("review", "approval", "{\"candidateGroupRef\":\"managers\"}") + ","
                + node("legal", "approval", "{}") + "," + node("finance", "approval", "{\"candidateGroupRef\":\"finance\"}") + "," + END,
                t("start", "review", "next") + "," + t("review", "legal", "approved") + "," + t("legal", "finance", "approved")
                        + "," + t("finance", "end", "approved"));
        assertThat(codes(a, addedUpstream, "finance")).containsExactly("HISTORY_CHANGED");
        JsonNode rerouted = flow(START + "," + node("review", "approval", "{\"candidateGroupRef\":\"managers\"}") + ","
                + node("finance", "approval", "{\"candidateGroupRef\":\"finance\"}") + "," + END,
                t("start", "review", "next") + "," + t("review", "finance", "rejected") + "," + t("finance", "end", "approved"));
        assertThat(codes(a, rerouted, "finance")).containsExactly("HISTORY_CHANGED");
    }

    @Test
    void onlyOneWaitingApprovalWaitOrMessageStepCanMove() {
        JsonNode a = flow(START + "," + node("call", "http", "{}") + "," + END, t("start", "call", "next") + "," + t("call", "end", "next"));
        assertThat(codes(a, a, "call")).contains("STEP_KIND");
        assertThat(codes(a, a)).containsExactly("STEP_COUNT");
        assertThat(codes(a, a, "call", "start")).containsExactly("STEP_COUNT");
        assertThat(codes(a, a, "ghost")).containsExactly("STEP_UNKNOWN");
    }

    @Test
    void aStepInsideAParallelSectionCannotMove() {
        String nodes = START + "," + node("split", "parallelSplit", "{\"joinRef\":\"join\"}") + ","
                + node("one", "approval", "{}") + "," + node("two", "approval", "{}") + ","
                + node("join", "parallelJoin", "{}") + "," + END;
        String flows = t("start", "split", "next") + "," + t("split", "one", "next") + "," + t("split", "two", "next") + ","
                + t("one", "join", "approved") + "," + t("two", "join", "approved") + "," + t("join", "end", "next");
        JsonNode a = flow(nodes, flows);
        assertThat(codes(a, a, "one")).containsExactly("PARALLEL_REGION");
    }

    @Test
    void thePassedDecisionTableAndConnectionVersionMustBeUnchanged() {
        String nodes = START + "," + node("rule", "decision", "{\"decisionRef\":\"threshold\"}") + ","
                + node("call", "http", "{\"connectionRef\":\"erp\"}") + ","
                + node("review", "approval", "{\"candidateGroupRef\":\"managers\"}") + "," + END;
        String flows = t("start", "rule", "next") + "," + t("rule", "call", "next") + "," + t("call", "review", "next")
                + "," + t("review", "end", "approved");
        String tail = ",\"transitions\":[" + flows + "]";
        JsonNode a = json.parse("{\"nodes\":[" + nodes + "]" + tail + ",\"decisions\":[{\"key\":\"threshold\",\"value\":5000}],"
                + "\"dependencies\":[{\"key\":\"erp\",\"definitionVersion\":1}]}");
        JsonNode sameDefinition = json.parse(json.write(a));
        assertThat(codes(a, sameDefinition, "review")).isEmpty();
        JsonNode newThreshold = json.parse(json.write(a).replace("5000", "10000"));
        assertThat(codes(a, newThreshold, "review")).containsExactly("HISTORY_CHANGED");
        JsonNode newConnection = json.parse(json.write(a).replace("\"definitionVersion\":1", "\"definitionVersion\":2"));
        assertThat(codes(a, newConnection, "review")).containsExactly("HISTORY_CHANGED");
    }

    @Test
    void stageDefinitionsMustMatch() {
        JsonNode a = base("{}", node("x", "notification", "{}"));
        JsonNode b = json.parse(json.write(a).replace("\"stages\":[]", "\"stages\":[{\"id\":\"s1\",\"label\":\"Review\"}]"));
        assertThat(codes(a, b, "manager")).containsExactly("STAGES_CHANGED");
    }
}
