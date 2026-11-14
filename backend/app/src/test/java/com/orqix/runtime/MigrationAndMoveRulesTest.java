package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.orqix.runtime.internal.MigrationRules;
import com.orqix.runtime.internal.MoveRules;
import com.orqix.runtime.internal.ReleaseCompatibility;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Pure rules of mapped migration (CG-O02) and guarded moves (CG-O03), docs/build/S143. The engine behavior is covered
 * by RunMigrationWithMappingTest and RunMoveTest.
 */
class MigrationAndMoveRulesTest {

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

    private static String cond(String from, String to, String ref) {
        return "{\"id\":\"" + from + to + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"on\":\"condition\","
                + "\"condition\":{\"fieldRef\":\"" + ref + "\",\"operator\":\"eq\",\"value\":\"x\"}}";
    }

    private static final String START = node("start", "start", "{}");
    private static final String END = node("end", "end", "{}");
    private static final String MANAGERS = "{\"candidateGroupRef\":\"managers\"}";

    private static MigrationRules.Facts at(String... active) {
        return new MigrationRules.Facts(List.of(active), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static List<String> codes(MigrationRules.Plan plan) {
        return plan.findings().stream().map(ReleaseCompatibility.Finding::code).distinct().toList();
    }

    /** start -> {approval id} -> end. */
    private JsonNode single(String approval, String config) {
        return flow(START + "," + node(approval, "approval", config) + "," + END,
                t("start", approval, "next") + "," + t(approval, "end", "approved"));
    }

    @Test
    void anApprovalContinuesUnderAnotherIdOnlyThroughAMapping() {
        JsonNode source = single("manager", MANAGERS);
        JsonNode target = single("lineManager", MANAGERS);
        MigrationRules.Plan unmapped = MigrationRules.plan(source, target, at("manager"), List.of());
        assertThat(codes(unmapped)).containsExactly("STEP_MISSING");
        // The target's only new approval with the same settings is suggested.
        assertThat(unmapped.suggestedMappings()).containsExactly(new MigrationRules.Mapping("manager", "lineManager"));

        MigrationRules.Plan mapped = MigrationRules.plan(source, target, at("manager"),
                List.of(new MigrationRules.Mapping("manager", "lineManager")));
        assertThat(mapped.allowed()).isTrue();
        assertThat(mapped.steps()).singleElement().satisfies(s -> {
            assertThat(s.action()).isEqualTo("rename");
            assertThat(s.to()).isEqualTo("lineManager");
        });
        assertThat(mapped.activityMappings()).containsEntry("manager", "lineManager");

        // A changed group cannot be carried over, mapped or not.
        MigrationRules.Plan changed = MigrationRules.plan(source, single("lineManager", "{\"candidateGroupRef\":\"x\"}"),
                at("manager"), List.of(new MigrationRules.Mapping("manager", "lineManager")));
        assertThat(codes(changed)).containsExactly("STEP_CHANGED");
    }

    @Test
    void waitsKeepTheirIdsAndMappingsMustBeMeaningful() {
        JsonNode source = flow(START + "," + node("pause", "wait", "{\"duration\":\"P1D\"}") + "," + END,
                t("start", "pause", "next") + "," + t("pause", "end", "next"));
        JsonNode target = flow(START + "," + node("hold", "wait", "{\"duration\":\"P1D\"}") + "," + END,
                t("start", "hold", "next") + "," + t("hold", "end", "next"));
        assertThat(codes(MigrationRules.plan(source, target, at("pause"), List.of(new MigrationRules.Mapping("pause",
                "hold"))))).containsExactly("RENAME_UNSUPPORTED");
        assertThat(codes(MigrationRules.plan(source, source, at("pause"), List.of(new MigrationRules.Mapping("nope",
                "pause"))))).contains("MAPPING_INVALID");
        assertThat(codes(MigrationRules.plan(source, source, at(), List.of()))).containsExactly("STEP_COUNT");
        JsonNode http = flow(START + "," + node("call", "http", "{}") + "," + END,
                t("start", "call", "next") + "," + t("call", "end", "next"));
        assertThat(codes(MigrationRules.plan(http, http, at("call"), List.of()))).containsExactly("STEP_KIND");
    }

    @Test
    void historyMayDifferWhenTheStepsAheadFindTheirData() {
        // Release 2 adds a scoring step before the approval; the route after the approval reads its output.
        JsonNode source = flow(START + "," + node("manager", "approval", MANAGERS) + "," + node("route", "branch", "{}")
                + "," + END, t("start", "manager", "next") + "," + t("manager", "route", "approved") + ","
                + cond("route", "end", "/steps/score/band"));
        JsonNode readsScore = flow(START + "," + node("score", "calculate", "{}") + ","
                + node("manager", "approval", MANAGERS) + "," + node("route", "branch", "{}") + "," + END,
                t("start", "score", "next") + "," + t("score", "manager", "next") + ","
                + t("manager", "route", "approved") + "," + cond("route", "end", "/steps/score/band"));
        assertThat(codes(MigrationRules.plan(source, readsScore, at("manager"), List.of())))
                .containsExactly("DATA_MISSING");
        // A request that already has the data (produced under another id) maps it across.
        MigrationRules.Facts produced = new MigrationRules.Facts(List.of("manager"), Set.of("rating"), Set.of(),
                Set.of(), Set.of());
        JsonNode renamed = flow(START + "," + node("rating", "calculate", "{}") + "," + node("manager", "approval",
                MANAGERS) + "," + node("route", "branch", "{}") + "," + END, t("start", "rating", "next") + ","
                + t("rating", "manager", "next") + "," + t("manager", "route", "approved") + ","
                + cond("route", "end", "/steps/rating/band"));
        MigrationRules.Plan copy = MigrationRules.plan(renamed, readsScore, produced,
                List.of(new MigrationRules.Mapping("rating", "score")));
        assertThat(copy.allowed()).as(copy.findings().toString()).isTrue();
        assertThat(copy.dataCopies()).containsExactly(new MigrationRules.DataCopy("rating", "score"));
        // Data produced ahead of the reader is fine: the request will pass the producer first.
        JsonNode producedAhead = flow(START + "," + node("manager", "approval", MANAGERS) + ","
                + node("score", "calculate", "{}") + "," + node("route", "branch", "{}") + "," + END,
                t("start", "manager", "next") + "," + t("manager", "score", "approved") + ","
                + t("score", "route", "next") + "," + cond("route", "end", "/steps/score/band"));
        assertThat(MigrationRules.plan(source, producedAhead, at("manager"), List.of()).allowed()).isTrue();
    }

    private JsonNode parallel(String bConfig, String after) {
        return flow(START + "," + node("split", "parallelSplit", "{\"joinRef\":\"join\"}") + ","
                + node("a", "approval", MANAGERS) + "," + node("b", "approval", bConfig) + ","
                + node("join", "parallelJoin", "{}") + "," + node("after", "approval", after) + "," + END,
                t("start", "split", "next") + "," + t("split", "a", "next") + "," + t("split", "b", "next") + ","
                + t("a", "join", "approved") + "," + t("b", "join", "approved") + "," + t("join", "after", "next") + ","
                + t("after", "end", "approved"));
    }

    @Test
    void parallelBranchesMoveTogetherWhenTheirSectionIsIdentical() {
        JsonNode source = parallel("{\"candidateGroupRef\":\"finance\"}", MANAGERS);
        // What lies after the join may change.
        JsonNode laterChanged = parallel("{\"candidateGroupRef\":\"finance\"}", "{\"candidateGroupRef\":\"other\"}");
        MigrationRules.Plan plan = MigrationRules.plan(source, laterChanged, at("a", "b"), List.of());
        assertThat(plan.allowed()).as(plan.findings().toString()).isTrue();
        assertThat(plan.steps()).hasSize(2);
        // A changed branch inside the section is refused (the open branch b keeps its settings too).
        JsonNode inside = parallel("{\"candidateGroupRef\":\"auditors\"}", MANAGERS);
        assertThat(codes(MigrationRules.plan(source, inside, at("a"), List.of()))).contains("PARALLEL_REGION");
        // Two waiting branches cannot collapse into one step.
        assertThat(codes(MigrationRules.plan(source, source, at("a", "b"), List.of(new MigrationRules.Mapping("a",
                "b"))))).contains("MAPPING_MERGE");
    }

    @Test
    void aJoinWhoseSplitWasNeverPassedIsRefused() {
        JsonNode source = single("manager", MANAGERS);
        // Release 2 puts the approval inside a parallel section: the request would reach the join alone.
        JsonNode target = flow(START + "," + node("split", "parallelSplit", "{\"joinRef\":\"join\"}") + ","
                + node("manager", "approval", MANAGERS) + "," + node("other", "approval", MANAGERS) + ","
                + node("join", "parallelJoin", "{}") + "," + END, t("start", "split", "next") + ","
                + t("split", "manager", "next") + "," + t("split", "other", "next") + ","
                + t("manager", "join", "approved") + "," + t("other", "join", "approved") + "," + t("join", "end", "next"));
        assertThat(codes(MigrationRules.plan(source, target, at("manager"), List.of()))).contains("JOIN_UNREACHABLE");
    }

    @Test
    void effectsInFlightAndLoopsInProgressAreRefused() {
        JsonNode source = single("manager", MANAGERS);
        MigrationRules.Facts busy = new MigrationRules.Facts(List.of("manager"), Set.of(), Set.of(), Set.of("manager"),
                Set.of());
        assertThat(codes(MigrationRules.plan(source, source, busy, List.of()))).containsExactly("EFFECT_IN_FLIGHT");
        JsonNode loop = flow(START + "," + node("manager", "approval", MANAGERS) + "," + node("review", "approval",
                MANAGERS) + "," + END, t("start", "manager", "next") + "," + t("manager", "review", "approved") + ","
                + t("review", "end", "approved") + ",{\"id\":\"back\",\"from\":\"review\",\"to\":\"manager\","
                + "\"on\":\"rejected\",\"maxTraversals\":2},{\"id\":\"limit\",\"from\":\"review\",\"to\":\"end\","
                + "\"on\":\"limitReached\"}");
        JsonNode looser = json.parse(loop.toString().replace("\"maxTraversals\":2", "\"maxTraversals\":3"));
        MigrationRules.Facts looping = new MigrationRules.Facts(List.of("manager"), Set.of(), Set.of("back"), Set.of(),
                Set.of());
        assertThat(codes(MigrationRules.plan(loop, looser, looping, List.of()))).contains("LOOP_IN_PROGRESS");
        assertThat(MigrationRules.plan(loop, loop, looping, List.of()).allowed()).isTrue();
    }

    // ---------------------------------------------------------------- guarded moves

    /** start -> manager -> call (http) -> notice (notification) -> receipt (approval) -> end. */
    private JsonNode purchase() {
        return flow(START + "," + node("manager", "approval", MANAGERS) + ","
                + node("call", "http", "{\"inputMapping\":{\"a\":\"/input/a\"}}") + ","
                + node("notice", "notification", "{}") + ","
                + node("receipt", "approval", "{\"candidateGroupRef\":\"x\",\"note\":\"/steps/call/id\"}") + "," + END,
                t("start", "manager", "next") + "," + t("manager", "call", "approved") + "," + t("call", "notice", "next")
                + "," + t("notice", "receipt", "next") + "," + t("receipt", "end", "approved"));
    }

    private static MoveRules.Facts moveAt(String step, Set<String> produced, Set<String> visited) {
        return new MoveRules.Facts(List.of(step), produced, visited, Set.of(), null);
    }

    private static List<String> codes(MoveRules.Plan plan) {
        return plan.findings().stream().map(ReleaseCompatibility.Finding::code).distinct().toList();
    }

    @Test
    void theKindOfMoveFollowsFromTheGraph() {
        JsonNode d = purchase();
        MoveRules.Facts atManager = moveAt("manager", Set.of(), Set.of("manager"));
        // Skipping the manager would run the call, whose data the receipt reads later: produced ahead, so allowed.
        MoveRules.Plan skip = MoveRules.plan(d, atManager, "skip", "manager", "call");
        assertThat(skip.allowed()).as(skip.findings().toString()).isTrue();
        assertThat(skip.skipped()).extracting(MoveRules.Affected::nodeId).containsExactly("manager");
        assertThat(codes(MoveRules.plan(d, atManager, "move", "manager", "call"))).containsExactly("MOVE_KIND_MISMATCH");
        // Jumping straight to the receipt leaves it without the call's data.
        MoveRules.Plan jump = MoveRules.plan(d, atManager, "move", "manager", "receipt");
        assertThat(codes(jump)).containsExactly("DATA_MISSING");
        assertThat(jump.skipped()).extracting(MoveRules.Affected::nodeId).containsExactly("manager", "call", "notice");
    }

    @Test
    void aReRunReusesRecordedCallsAndRefusesToRepeatOtherEffects() {
        JsonNode d = purchase();
        Set<String> visited = Set.of("manager", "call", "notice", "receipt");
        MoveRules.Facts atReceipt = moveAt("receipt", Set.of("manager", "call"), visited);
        MoveRules.Plan again = MoveRules.plan(d, atReceipt, "rerun", "receipt", "receipt");
        assertThat(again.allowed()).isTrue();
        assertThat(again.rerun()).extracting(MoveRules.Affected::nodeId).containsExactly("receipt");
        // Back to the call: the call is answered from the journal, but the notice would be sent twice.
        MoveRules.Plan toCall = MoveRules.plan(d, atReceipt, "rerun", "receipt", "call");
        assertThat(codes(toCall)).containsExactly("EFFECT_WOULD_REPEAT");
        assertThat(toCall.rerun()).extracting(MoveRules.Affected::handling)
                .containsExactly("reuseRecorded", "wouldRepeat", "runsAgain");
        // Calls in doubt block a move away from them.
        MoveRules.Facts failed = new MoveRules.Facts(List.of("call"), Set.of("manager"), Set.of("manager", "call"),
                Set.of("call"), "call");
        assertThat(codes(MoveRules.plan(d, failed, "skip", "call", "notice"))).contains("EFFECT_IN_FLIGHT");
        MoveRules.Facts failedClean = new MoveRules.Facts(List.of("call"), Set.of("manager"), Set.of("manager", "call"),
                Set.of(), "call");
        // Skipping a failed call leaves the receipt without the call's data.
        assertThat(codes(MoveRules.plan(d, failedClean, "skip", "call", "notice"))).containsExactly("DATA_MISSING");
        // A call that did not fail is not moved away from (its effect may be under way).
        assertThat(codes(MoveRules.plan(d, moveAt("call", Set.of(), Set.of()), "skip", "call", "notice")))
                .contains("MOVE_FROM_KIND");
    }

    @Test
    void movesStayOutOfParallelSectionsAndLoops() {
        JsonNode p = parallel("{\"candidateGroupRef\":\"finance\"}", MANAGERS);
        assertThat(codes(MoveRules.plan(p, moveAt("after", Set.of(), Set.of()), "rerun", "after", "a")))
                .contains("PARALLEL_REGION");
        assertThat(codes(MoveRules.plan(p, new MoveRules.Facts(List.of("a", "b"), Set.of(), Set.of(), Set.of(), null),
                "skip", "a", "join"))).contains("STEP_COUNT");
    }
}
