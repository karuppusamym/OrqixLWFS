package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orqix.support.BankingStub;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;
import com.orqix.support.TestUsers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Banking workflows (KYC onboarding, credit approval, card dispute) published from /examples and executed against
 * PostgreSQL and Flowable through the public API. External effects are stubbed (BankingStub); money is asserted at
 * decimal boundaries; every approval is checked against requester/approver separation. Tests named {@code gap_*}
 * record behavior that a bank would need to be different; they document the current limit, see
 * docs/banking-capability-matrix.md. The workflows are illustrative templates, not regulatory-compliant products.
 */
class BankingWorkflowsTest extends RuntimeTestBase {

    private static final String SCREEN = "/banking/sanctions/screen";
    private static final String BOOKINGS = "/banking/bookings";
    private static final String CHARGEBACKS = "/banking/chargebacks";

    private static boolean configured;
    private static UUID kycChild, kyc, creditChild, credit, disputeChild, claim;

    @Autowired
    private org.flowable.engine.ProcessEngine processEngine;

    // ------------------------------------------------------------------ setup: groups, connections, bindings

    @BeforeEach
    void bankingSetup() throws Exception {
        BankingStub.reset();
        if (!configured) {
            configure();
            configured = true;
        }
    }

    private UUID subject(String user) throws Exception {
        api(user).get("/me");
        return jdbc.sql("SELECT id FROM subject WHERE issuer = :i AND external_subject = :s")
                .param("i", TestUsers.ISSUER).param("s", "kc-" + user).query(UUID.class).single();
    }

    private void group(String key, String name, Object... members) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        for (Object member : members) {
            list.add(member instanceof UUID id ? Map.of("type", "subject", "value", id.toString())
                    : Map.of("type", "idpGroup", "value", member.toString()));
        }
        Response saved = api("wade.admin").put(Map.of("name", name, "members", list), null,
                "/workspaces/{ws}/groups/{key}", PURCHASING, key);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
    }

    private void configure() throws Exception {
        UUID mani = subject("mani.manager"), max = subject("max.manager"), fin = subject("fin.finance");
        subject("rhea.requester");
        // KYC onboarding
        group("kyc-analysts", "KYC analysts", mani);
        group("document-controllers", "Document controllers", max);
        group("kyc-seniors", "Senior KYC analysts", max);
        group("compliance-officers", "Compliance officers", fin);
        group("head-of-compliance", "Head of compliance", mani);
        // Credit approval. The committee deliberately includes mani, who is also a credit manager: see gap_sameApprover...
        group("credit-managers", "Credit managers", "orqix-purchasing-managers");
        group("credit-committee", "Credit committee", fin, mani);
        group("credit-directors", "Credit directors", fin);
        group("credit-operations", "Credit operations", max);
        // Card disputes
        group("dispute-analysts", "Dispute analysts", "orqix-purchasing-managers");
        group("fraud-investigators", "Fraud investigators", fin);
        for (String file : List.of("sanctions-screening", "core-banking")) {
            JsonNode definition = json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                    "examples", "connections", file + ".connection.json")));
            Response registered = api("wade.admin").post(Map.of("definition", definition),
                    "/workspaces/{ws}/connections/{key}/versions", PURCHASING, file);
            assertThat(registered.status()).as(registered.toString()).isIn(201, 409);
            Response bound = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/banking",
                    "auth", Map.of("type", "none"), "enabled", true), null,
                    "/workspaces/{ws}/connections/{key}/binding", PURCHASING, file);
            assertThat(bound.status()).as(bound.toString()).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ publishing the examples

    private UUID publishBanking(String file, String childKey, Consumer<ObjectNode> tweak) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            try {
                ObjectNode source = (ObjectNode) json.parse(Files.readString(Path.of(System.getProperty("orqix.repoRoot"),
                        "examples", file)));
                source.put("workflowKey", d.path("workflowKey").asString());
                ((ObjectNode) source.path("execution")).put("retryDelaySeconds", 5);
                for (JsonNode node : source.path("nodes")) {
                    ObjectNode config = (ObjectNode) node.path("config");
                    if ("startWorkflow".equals(node.path("type").asString()) && childKey != null) {
                        config.put("workflowRef", childKey);
                    }
                    if ("http".equals(node.path("type").asString())) {
                        config.put("timeoutSeconds", 3);
                    }
                }
                if (tweak != null) {
                    tweak.accept(source);
                }
                d.removeAll();
                d.setAll(source);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private String keyOf(UUID workflow) throws Exception {
        return api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, workflow).body().path("key").asString();
    }

    private UUID kyc() throws Exception {
        if (kyc == null) {
            kycChild = publishBanking("banking-kyc-periodic-review.json", null, null);
            kyc = publishBanking("banking-kyc-onboarding.json", keyOf(kycChild), null);
        }
        return kyc;
    }

    private UUID credit() throws Exception {
        if (credit == null) {
            creditChild = publishBanking("banking-credit-review-round.json", null, null);
            credit = publishBanking("banking-credit-approval.json", keyOf(creditChild), null);
        }
        return credit;
    }

    private UUID claim() throws Exception {
        if (claim == null) {
            disputeChild = publishBanking("banking-card-dispute.json", null, null);
            claim = publishBanking("banking-card-claim.json", keyOf(disputeChild), null);
        }
        return claim;
    }

    // ------------------------------------------------------------------ run helpers

    private Response startRaw(String user, UUID workflow, Map<String, Object> input) throws Exception {
        return api(user).post(Map.of("workflowId", workflow, "input", input), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
    }

    private UUID startRun(String user, UUID workflow, Map<String, Object> input) throws Exception {
        Response started = startRaw(user, workflow, input);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    /** Request body with the exact JSON text of the numbers, for notations a Java map cannot carry. */
    private Response startJson(String user, UUID workflow, String inputJson) throws Exception {
        return api(user).post("{\"workflowId\":\"" + workflow + "\",\"input\":" + inputJson + "}",
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
    }

    private void until(BooleanSupplier condition) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(condition::getAsBoolean);
    }

    private boolean taskOpen(UUID run, String nodeId) {
        try {
            return openTask(PURCHASING, run, nodeId) != null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitOpenTask(UUID run, String nodeId) {
        until(() -> taskOpen(run, nodeId));
    }

    /** Moves the run's timers forward (without touching a timer that a later step creates) until the task opens. */
    private void awaitOpenTaskAfterTimeout(UUID run, String nodeId) {
        until(() -> {
            if (taskOpen(run, nodeId)) {
                return true;
            }
            fastForward(PURCHASING, run);
            return false;
        });
    }

    private void awaitCompleted(UUID run) {
        until(() -> "completed".equals(lifecycle(PURCHASING, run)));
    }

    private UUID child(UUID parent, int occurrence) {
        until(() -> jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id AND parent_occurrence = :occ")
                .param("id", parent).param("occ", occurrence).query(Long.class).single() == 1);
        return jdbc.sql("SELECT id FROM workflow_run WHERE parent_run_id = :id AND parent_occurrence = :occ")
                .param("id", parent).param("occ", occurrence).query(UUID.class).single();
    }

    private void restartExecutor() {
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().shutdown();
        processEngine.getProcessEngineConfiguration().getAsyncExecutor().start();
    }

    private List<String> events(UUID run) {
        return jdbc.sql("SELECT type FROM run_event WHERE workspace_id = :ws AND run_id = :id ORDER BY seq")
                .param("ws", PURCHASING).param("id", run).query(String.class).list();
    }

    private Response claimTask(String user, UUID run, String nodeId) throws Exception {
        JsonNode task = openTask(PURCHASING, run, nodeId);
        assertThat(task).as("open task " + nodeId).isNotNull();
        return claim(user, PURCHASING, task.path("id").asString());
    }

    /**
     * Somebody outside the candidate group (and without run.read) cannot even see the task: the API answers 404, not
     * 403, so a bank does not leak which cases exist. A candidate who is the requester gets 403 SELF_APPROVAL_DENIED.
     */
    private void assertInvisible(Response response) {
        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("NOT_FOUND");
    }

    /** Claims and rejects with a reason; asserts both succeed. */
    private void reject(String user, UUID run, String nodeId, String reason) throws Exception {
        Response claimed = claimTask(user, run, nodeId);
        assertThat(claimed.status()).as(claimed.toString()).isEqualTo(200);
        Response decided = decide(user, PURCHASING, openTask(PURCHASING, run, nodeId).path("id").asString(), "reject", reason);
        assertThat(decided.status()).as(decided.toString()).isEqualTo(201);
    }

    private JsonNode runDetail(UUID run) throws Exception {
        return run(PURCHASING, run);
    }

    private void cancel(UUID run) throws Exception {
        Response cancelled = api("olga.operator").post(Map.of("expectedVersion", runDetail(run).path("version").asInt(),
                "reason", "Test clean-up"), UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/cancel", PURCHASING, run);
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(200);
    }

    private long effects(UUID run, String state) {
        return jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :id AND state = :s")
                .param("id", run).param("s", state).query(Long.class).single();
    }

    private String candidateGroup(UUID run, String nodeId) throws Exception {
        JsonNode task = openTask(PURCHASING, run, nodeId);
        assertThat(task).as("open task " + nodeId).isNotNull();
        return api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task.path("id").asString())
                .body().path("candidateGroup").path("key").asString();
    }

    // ================================================================== examples publish cleanly

    @Test
    void everyBankingExampleValidatesAndPublishesWithoutBlockersOrUnexpectedWarnings() throws Exception {
        for (UUID workflow : List.of(kyc(), credit(), claim(), kycChild, creditChild, disputeChild)) {
            int revision = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow)
                    .body().path("revision").asInt();
            Response report = api("dana.designer").post(Map.of("revision", revision),
                    "/workspaces/{ws}/workflows/{id}/validate", PURCHASING, workflow);
            assertThat(report.status()).as(report.toString()).isEqualTo(200);
            List<String> codes = new ArrayList<>();
            report.body().path("issues").forEach(issue -> codes.add(issue.path("severity").asString() + " " + issue.path("code").asString()));
            assertThat(codes).as(report.toString()).isEmpty();
        }
    }

    // ================================================================== 1. KYC / customer onboarding

    private Map<String, Object> kycInput(String name, String type, String country, String product, String volume, boolean pep) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerName", name);
        input.put("customerType", type);
        input.put("countryGroup", country);
        input.put("product", product);
        input.put("expectedMonthlyVolume", new BigDecimal(volume));
        input.put("politicallyExposed", pep);
        return input;
    }

    private Map<String, Object> lowRiskCustomer() {
        return kycInput("Alice Example", "individual", "domestic", "currentAccount", "1000.00", false);
    }

    /** Runs identity and document checks to the end; both parallel tasks must be open together first. */
    private void passParallelChecks(UUID run) throws Exception {
        awaitOpenTask(run, "identity");
        awaitOpenTask(run, "documents");
        approve("mani.manager", PURCHASING, run, "identity");
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        approve("max.manager", PURCHASING, run, "documents");
    }

    @Test
    void kycLowRiskRunsChecksInParallelScreensOnceAndCompletesAfterThePinnedPeriodicReview() throws Exception {
        UUID run = startRun("rhea.requester", kyc(), lowRiskCustomer());
        awaitOpenTask(run, "identity");
        awaitOpenTask(run, "documents");
        // sanctions screening is the third parallel check, already done by the stub: one call, one effect
        until(() -> BankingStub.created(SCREEN) == 1);
        until(() -> effects(run, "succeeded") == 1);
        assertThat(candidateGroup(run, "identity")).isEqualTo("kyc-analysts");
        assertThat(candidateGroup(run, "documents")).isEqualTo("document-controllers");
        String screened = BankingStub.createdBodies(SCREEN).get(0);
        assertThat(screened).contains("\"partyName\":\"Alice Example\"").contains("\"partyType\":\"individual\"");

        passParallelChecks(run);
        UUID periodic = child(run, 1);
        assertThat(openTask(PURCHASING, run, "compliance")).isNull();
        assertThat(openTask(PURCHASING, run, "seniorReview")).isNull();
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        // the periodic review waits for its due date (a durable 90 day timer); the parent stays open meanwhile
        assertThat(runDetail(periodic).path("currentSteps").get(0).path("kind").asString()).isEqualTo("wait");
        awaitOpenTaskAfterTimeout(periodic, "review");
        approve("mani.manager", PURCHASING, periodic, "review");
        awaitCompleted(run);
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Onboarded");
        assertThat(detail.path("output").path("screeningReference").asString()).startsWith("SCR-");
        assertThat(detail.path("output").path("periodicReviewer").asString()).isNotBlank();
        assertThat(BankingStub.created(SCREEN)).isEqualTo(1);
        assertThat(events(run)).contains("CHILD_STARTED", "CHILD_ENDED", "RUN_COMPLETED");
    }

    private record Case(String name, String type, String country, String product, String volume, boolean pep, String path) {
    }

    @Test
    void kycJurisdictionProductAndVolumeMatrixSelectsTheExpectedReviewPath() throws Exception {
        List<Case> cases = List.of(
                new Case("Case 01", "individual", "domestic", "currentAccount", "1000.00", false, "low"),
                new Case("Case 02", "individual", "partner", "savingsAccount", "5000.00", false, "low"),
                new Case("Case 03", "individual", "domestic", "savingsAccount", "999999.99", false, "low"),
                new Case("Case 04", "individual", "domestic", "savingsAccount", "1000000.00", false, "high"),
                new Case("Case 05", "individual", "domestic", "savingsAccount", "1000000.01", false, "high"),
                new Case("Case 06", "corporate", "domestic", "currentAccount", "1000.00", false, "medium"),
                new Case("Case 07", "individual", "elevated", "currentAccount", "1000.00", false, "medium"),
                new Case("Case 08", "individual", "domestic", "tradeFinance", "1000.00", false, "medium"),
                new Case("Case 09", "individual", "domestic", "businessLoan", "1000.00", false, "medium"),
                new Case("Case 10", "individual", "restricted", "currentAccount", "1000.00", false, "high"),
                new Case("Case 11", "individual", "domestic", "currentAccount", "1000.00", true, "high"),
                new Case("Case 12", "corporate", "restricted", "tradeFinance", "1000.00", false, "high"),
                new Case("Sanctioned Trading Ltd", "corporate", "domestic", "currentAccount", "1000.00", false, "high"));
        for (Case c : cases) {
            UUID run = startRun("rhea.requester", kyc(), kycInput(c.name(), c.type(), c.country(), c.product(), c.volume(), c.pep()));
            passParallelChecks(run);
            until(() -> taskOpen(run, "compliance") || taskOpen(run, "seniorReview") || jdbc.sql(
                    "SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single() == 1);
            String path = taskOpen(run, "compliance") ? "high" : taskOpen(run, "seniorReview") ? "medium" : "low";
            assertThat(path).as(c.name() + " " + c.type() + "/" + c.country() + "/" + c.product() + "/" + c.volume() + "/pep=" + c.pep())
                    .isEqualTo(c.path());
            cancel(run);
        }
        // the same customer data would be screened once per request, never more
        assertThat(BankingStub.calls(SCREEN)).hasSize(cases.size());
    }

    @Test
    void kycVolumeRuleTableBoundariesUseExactDecimals() throws Exception {
        UUID workflow = kyc();
        int revision = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body().path("revision").asInt();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("0.00", "normal");
        expected.put("999999.99", "normal");
        expected.put("1000000", "large");
        expected.put("1000000.00", "large");
        expected.put("1000000.01", "large");
        for (var e : expected.entrySet()) {
            Response result = api("dana.designer").post(Map.of("revision", revision, "values",
                    Map.of("/input/expectedMonthlyVolume", new BigDecimal(e.getKey()))),
                    "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, workflow, "volume-policy");
            assertThat(result.status()).as(result.toString()).isEqualTo(200);
            assertThat(result.body().path("output").path("volumeBand").asString()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(result.body().path("usedDefault").asBoolean()).isEqualTo("normal".equals(e.getValue()));
        }
    }

    @Test
    void kycHighRiskNeedsComplianceThenEscalatesAfterTheTimeLimitAndTheEscalationCanApprove() throws Exception {
        UUID run = startRun("rhea.requester", kyc(), kycInput("Bob Example", "individual", "restricted", "currentAccount", "1000.00", false));
        passParallelChecks(run);
        awaitOpenTask(run, "compliance");
        assertThat(candidateGroup(run, "compliance")).isEqualTo("compliance-officers");
        String complianceTask = openTask(PURCHASING, run, "compliance").path("id").asString();
        // nobody decides: the time limit cancels the task and the request escalates
        awaitOpenTaskAfterTimeout(run, "escalation");
        assertThat(events(run)).contains("TASK_TIMED_OUT");
        assertThat(jdbc.sql("SELECT status FROM task_context WHERE engine_task_id = :t").param("t", complianceTask)
                .query(String.class).single()).isEqualTo("cancelled");
        assertThat(openTask(PURCHASING, run, "compliance")).isNull();
        assertThat(candidateGroup(run, "escalation")).isEqualTo("head-of-compliance");
        // the compliance officer is not the head of compliance
        assertInvisible(claimTask("fin.finance", run, "escalation"));
        approve("mani.manager", PURCHASING, run, "escalation");
        UUID periodic = child(run, 1);
        awaitOpenTaskAfterTimeout(periodic, "review");
        approve("mani.manager", PURCHASING, periodic, "review");
        awaitCompleted(run);
        assertThat(runDetail(run).path("outcome").asString()).isEqualTo("Onboarded");
        assertThat(BankingStub.created(SCREEN)).isEqualTo(1);
    }

    @Test
    void kycEscalationThatIsNotDecidedEitherExpiresTheRequestWithoutAPeriodicReview() throws Exception {
        UUID run = startRun("rhea.requester", kyc(), kycInput("Carol Example", "individual", "restricted", "currentAccount", "1000.00", false));
        passParallelChecks(run);
        awaitOpenTask(run, "compliance");
        awaitOpenTaskAfterTimeout(run, "escalation");
        until(() -> "completed".equals(lifecycle(PURCHASING, run)) || fastForwardReturningFalse(run));
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Expired");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single()).isZero();
        assertThat(events(run).stream().filter("TASK_TIMED_OUT"::equals).count()).isEqualTo(2);
    }

    private boolean fastForwardReturningFalse(UUID run) {
        fastForward(PURCHASING, run);
        return false;
    }

    @Test
    void kycRejectionNeedsAReasonEndsTheRequestAndCancelsTheOtherChecks() throws Exception {
        UUID run = startRun("rhea.requester", kyc(), lowRiskCustomer());
        awaitOpenTask(run, "identity");
        awaitOpenTask(run, "documents");
        Response claimed = claimTask("mani.manager", run, "identity");
        assertThat(claimed.status()).isEqualTo(200);
        String task = openTask(PURCHASING, run, "identity").path("id").asString();
        Response noReason = decide("mani.manager", PURCHASING, task, "reject", null);
        assertThat(noReason.status()).isEqualTo(422);
        assertThat(noReason.code()).isEqualTo("REASON_REQUIRED");
        assertThat(decide("mani.manager", PURCHASING, task, "reject", "   ").status()).isEqualTo(422);
        assertThat(openTask(PURCHASING, run, "identity")).isNotNull();
        assertThat(decide("mani.manager", PURCHASING, task, "reject", "Passport does not match the application").status()).isEqualTo(201);
        until(() -> "completed".equals(lifecycle(PURCHASING, run)));
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Rejected");
        assertThat(detail.path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(openTask(PURCHASING, run, "documents")).isNull();
        assertThat(jdbc.sql("SELECT reason FROM task_decision WHERE run_id = :id").param("id", run).query(String.class).single())
                .isEqualTo("Passport does not match the application");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single()).isZero();
        // screening ran once even though the request was rejected
        assertThat(BankingStub.created(SCREEN)).isEqualTo(1);
    }

    @Test
    void kycRequesterCannotTakeTheirOwnTaskAndOutsidersCannotTakeItEither() throws Exception {
        // mani submits: the analysts group contains mani, so the task is eligible but self-approval is denied
        UUID run = startRun("mani.manager", kyc(), kycInput("Dave Example", "individual", "restricted", "currentAccount", "1000.00", false));
        awaitOpenTask(run, "identity");
        Response own = claimTask("mani.manager", run, "identity");
        assertThat(own.status()).isEqualTo(403);
        assertThat(own.code()).isEqualTo("SELF_APPROVAL_DENIED");
        // somebody outside the group cannot take it either
        assertInvisible(claimTask("fin.finance", run, "identity"));
        assertInvisible(claimTask("rhea.requester", run, "identity"));
        assertThat(openTask(PURCHASING, run, "identity").path("assignee").isNull()).isTrue();
        cancel(run);
    }

    @Test
    void kycComplianceRejectionAfterTheChecksRecordsTheReasonAndNeverStartsAPeriodicReview() throws Exception {
        UUID run = startRun("rhea.requester", kyc(), kycInput("Eve Sanctioned", "individual", "domestic", "currentAccount", "1000.00", false));
        passParallelChecks(run);
        awaitOpenTask(run, "compliance");
        reject("fin.finance", run, "compliance", "Possible sanctions match confirmed");
        until(() -> "completed".equals(lifecycle(PURCHASING, run)));
        assertThat(runDetail(run).path("outcome").asString()).isEqualTo("Rejected");
        assertThat(jdbc.sql("SELECT reason FROM task_decision WHERE run_id = :id AND node_id = 'compliance'").param("id", run)
                .query(String.class).single()).isEqualTo("Possible sanctions match confirmed");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single()).isZero();
        assertThat(BankingStub.created(SCREEN)).isEqualTo(1);
    }

    @Test
    void kycParallelChecksSurviveAnExecutorRestart() throws Exception {
        UUID run = startRun("rhea.requester", kyc(), lowRiskCustomer());
        awaitOpenTask(run, "identity");
        awaitOpenTask(run, "documents");
        approve("mani.manager", PURCHASING, run, "identity");
        restartExecutor();
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        approve("max.manager", PURCHASING, run, "documents");
        UUID periodic = child(run, 1);
        restartExecutor();
        awaitOpenTaskAfterTimeout(periodic, "review");
        approve("mani.manager", PURCHASING, periodic, "review");
        awaitCompleted(run);
        assertThat(BankingStub.created(SCREEN)).isEqualTo(1);
        assertThat(events(run).stream().filter("RUN_COMPLETED"::equals).count()).isEqualTo(1);
    }

    @Test
    void kycScreeningFailureIsRetriedAndStillScreensOnce() throws Exception {
        BankingStub.fail(SCREEN, 2, 503);
        UUID run = startRun("rhea.requester", kyc(), lowRiskCustomer());
        awaitOpenTask(run, "identity");
        until(() -> {
            fastForward(PURCHASING, run);
            return effects(run, "succeeded") == 1;
        });
        assertThat(BankingStub.calls(SCREEN)).hasSize(3);
        assertThat(BankingStub.created(SCREEN)).isEqualTo(1);
        assertThat(BankingStub.calls(SCREEN).stream().map(BankingStub.Call::idempotencyKey).distinct().count()).isEqualTo(1);
        cancel(run);
    }

    @Test
    void kycPeriodicReviewIsPinnedToTheReleasePublishedWithTheParent() throws Exception {
        UUID childWorkflow = publishBanking("banking-kyc-periodic-review.json", null, null);
        UUID parent = publishBanking("banking-kyc-onboarding.json", keyOf(childWorkflow), null);
        String pinned = api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, childWorkflow)
                .body().path("activeRelease").path("id").asString();
        // republish the child with a changed review; the parent release must keep running the old one
        var draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, childWorkflow).body();
        ObjectNode changed = (ObjectNode) draft.path("definition");
        changed.put("name", "KYC periodic review (changed)");
        int revision = draft.path("revision").asInt();
        assertThat(api("dana.designer").put(Map.of("definition", changed), "\"r" + revision + "\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, childWorkflow).status()).isEqualTo(200);
        assertThat(api("paul.publisher").post(Map.of("draftRevision", revision + 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, childWorkflow).status()).isEqualTo(201);
        assertThat(api("dana.designer").get("/workspaces/{ws}/workflows/{id}", PURCHASING, childWorkflow)
                .body().path("activeRelease").path("id").asString()).isNotEqualTo(pinned);
        UUID run = startRun("rhea.requester", parent, lowRiskCustomer());
        passParallelChecks(run);
        UUID periodic = child(run, 1);
        assertThat(jdbc.sql("SELECT release_id::text FROM workflow_run WHERE id = :id").param("id", periodic)
                .query(String.class).single()).isEqualTo(pinned);
        cancel(run);
    }

    @Test
    void kycCustomerDataIsHiddenFromViewersButNotFieldByField() throws Exception {
        UUID run = startRun("rhea.requester", kyc(), lowRiskCustomer());
        awaitOpenTask(run, "identity");
        // role based, all or nothing: a viewer sees that the request exists but none of its data or effects
        JsonNode viewer = api("vic.viewer").get("/workspaces/{ws}/runs/{id}", PURCHASING, run).body();
        assertThat(viewer.path("input").isNull()).isTrue();
        assertThat(viewer.has("effects")).isFalse();
        // regression: the task summaries of a run detail used to carry the first text field of the request (the
        // customer name) as requestTitle, next to the input that was withheld
        assertThat(viewer.toString()).doesNotContain("Alice Example");
        String taskId = openTask(PURCHASING, run, "identity").path("id").asString();
        JsonNode viewerTask = api("vic.viewer").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body();
        assertThat(viewerTask.path("requestTitle").isNull()).isTrue();
        assertThat(viewerTask.toString()).doesNotContain("Alice Example");
        // the assigned group and an operator do see it; there is no per-field masking or audited reveal
        assertThat(api("mani.manager").get("/workspaces/{ws}/tasks/{id}", PURCHASING, taskId).body().path("requestTitle").asString())
                .isEqualTo("Alice Example");
        assertThat(runDetail(run).path("input").path("customerName").asString()).isEqualTo("Alice Example");
        assertThat(runDetail(run).path("tasks").get(0).path("requestTitle").asString()).isEqualTo("Alice Example");
        cancel(run);
    }

    @Test
    void kycRequestFormRejectsValuesOutsideTheDeclaredDomainsAndPrecision() throws Exception {
        Map<String, Object> badCountry = lowRiskCustomer();
        badCountry.put("countryGroup", "somewhere");
        assertThat(startRaw("rhea.requester", kyc(), badCountry).status()).isEqualTo(422);
        Map<String, Object> negative = lowRiskCustomer();
        negative.put("expectedMonthlyVolume", new BigDecimal("-0.01"));
        assertThat(startRaw("rhea.requester", kyc(), negative).status()).isEqualTo(422);
        Map<String, Object> mills = lowRiskCustomer();
        mills.put("expectedMonthlyVolume", new BigDecimal("100.001"));
        assertThat(startRaw("rhea.requester", kyc(), mills).status()).isEqualTo(422);
        Map<String, Object> missing = lowRiskCustomer();
        missing.remove("politicallyExposed");
        assertThat(startRaw("rhea.requester", kyc(), missing).status()).isEqualTo(422);
        Map<String, Object> blank = lowRiskCustomer();
        blank.put("customerName", "");
        assertThat(startRaw("rhea.requester", kyc(), blank).status()).isEqualTo(422);
        // a viewer who may not start requests
        assertThat(startRaw("vic.viewer", kyc(), lowRiskCustomer()).status()).isEqualTo(403);
        assertThat(BankingStub.created(SCREEN)).isZero();
    }

    // ================================================================== 2. Credit / loan approval

    private Map<String, Object> creditInput(String amount) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("applicantReference", "CUST-1001");
        input.put("product", "personalLoan");
        input.put("amount", new BigDecimal(amount));
        input.put("termMonths", 24);
        return input;
    }

    private static final String CREDIT_INPUT_WITHOUT_AMOUNT = "\"applicantReference\":\"CUST-1001\",\"product\":\"personalLoan\",\"termMonths\":24";

    private void assertOneBooking(String amount) {
        assertThat(BankingStub.created(BOOKINGS)).isEqualTo(1);
        String sent = BankingStub.rawNumber(BankingStub.createdBodies(BOOKINGS).get(0), "amount");
        assertThat(new BigDecimal(sent)).as("amount sent to the core system: " + sent).isEqualByComparingTo(amount);
        assertThat(sent).doesNotContain("E").doesNotContain("e");
        assertThat(sent.contains(".") ? sent.substring(sent.indexOf('.') + 1).length() : 0).isLessThanOrEqualTo(2);
    }

    /** Boundaries of the credit rule table; the value 4999.9999999999999999 is 5000 as a double but below 5000 as a decimal. */
    private static Map<String, String> creditTiers() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("0.01", "auto");
        expected.put("4999.99", "auto");
        expected.put("4999.9999999999999999", "auto");
        expected.put("5000", "manager");
        expected.put("5000.00", "manager");
        expected.put("5000.01", "manager");
        expected.put("49999.99", "manager");
        expected.put("50000.00", "committee");
        expected.put("50000.01", "committee");
        expected.put("10000000.00", "committee");
        return expected;
    }

    private void assertCreditTiers(UUID workflow, Map<String, String> expected) throws Exception {
        int revision = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body().path("revision").asInt();
        for (var e : expected.entrySet()) {
            Response result = api("dana.designer").post(Map.of("revision", revision, "values", Map.of("/input/amount", new BigDecimal(e.getKey()))),
                    "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, workflow, "credit-limits");
            assertThat(result.status()).as(result.toString()).isEqualTo(200);
            assertThat(result.body().path("output").path("tier").asString()).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void creditRuleTableBoundariesAreExact() throws Exception {
        UUID workflow = credit();
        assertCreditTiers(workflow, creditTiers());
        // the draft tester also reports which row matched: row 0 under 5,000, row 1 from 50,000, null = default
        int revision = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body().path("revision").asInt();
        Response matched = api("dana.designer").post(Map.of("revision", revision, "values", Map.of("/input/amount", new BigDecimal("50000.00"))),
                "/workspaces/{ws}/workflows/{id}/decisions/{key}/evaluate", PURCHASING, workflow, "credit-limits");
        assertThat(matched.body().path("matchedRow").asInt()).isEqualTo(1);
    }

    @Test
    void operatorsAtMostAndAboveGiveTheSameTiersAsBelowAndAtLeast() throws Exception {
        credit();
        UUID inclusive = publishBanking("banking-credit-approval.json", keyOf(creditChild), root ->
                ((ObjectNode) root.path("decisions").get(0)).set("rows", json.parse("["
                        + "{\"when\":{\"fieldRef\":\"/input/amount\",\"operator\":\"lte\",\"value\":4999.99},\"then\":{\"tier\":\"auto\"}},"
                        + "{\"when\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":49999.99},\"then\":{\"tier\":\"committee\"}}]")));
        Map<String, String> expected = creditTiers();
        // "at most 4,999.99" is a different rule from "below 5,000" for values between the two cents
        expected.put("4999.9999999999999999", "manager");
        assertCreditTiers(inclusive, expected);
    }

    private JsonNode decisionEvent(UUID run) throws Exception {
        for (JsonNode event : api("olga.operator").get("/workspaces/{ws}/runs/{id}/events", PURCHASING, run).body().path("items")) {
            if ("DECISION_EVALUATED".equals(event.path("type").asString())) {
                return event.path("data");
            }
        }
        return null;
    }

    @Test
    void creditEveryRunRecordsWhichRuleRowMatchedAndWhatItDecided() throws Exception {
        String[][] cases = {{"4999.99", "r1", "auto"}, {"5000.00", "default", "manager"}, {"50000.00", "r2", "committee"}};
        for (String[] c : cases) {
            UUID run = startRun("rhea.requester", credit(), creditInput(c[0]));
            until(() -> {
                try {
                    return decisionEvent(run) != null;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            JsonNode data = decisionEvent(run);
            assertThat(data.path("decisionKey").asString()).isEqualTo("credit-limits");
            assertThat(data.path("version").asInt()).isEqualTo(1);
            assertThat(data.path("matched").asString()).as(c[0]).isEqualTo(c[1]);
            assertThat(data.path("outputs").path("tier").asString()).as(c[0]).isEqualTo(c[2]);
            if ("auto".equals(c[2])) {
                awaitCompleted(run);
            } else {
                cancel(run);
            }
        }
    }

    @Test
    void gap_decisionEventDoesNotRecordTheInputValue() throws Exception {
        UUID run = startRun("rhea.requester", credit(), creditInput("5000.00"));
        until(() -> {
            try {
                return decisionEvent(run) != null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        JsonNode data = decisionEvent(run);
        List<String> names = new ArrayList<>();
        data.properties().forEach(e -> names.add(e.getKey()));
        // key, version, matched row and outputs are recorded; the amount that was tested, the rows not chosen and the
        // actor are not, and there is no decision-record API
        assertThat(names).containsExactlyInAnyOrder("decisionKey", "version", "matched", "outputs");
        assertThat(data.toString()).doesNotContain("5000");
        cancel(run);
    }

    @Test
    void creditUnderTheThresholdIsBookedAutomaticallyExactlyOnceWithoutAnyHumanTask() throws Exception {
        UUID run = startRun("rhea.requester", credit(), creditInput("4999.99"));
        awaitCompleted(run);
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Approved");
        assertThat(detail.path("output").path("bookingId").asString()).startsWith("BK-");
        assertThat(detail.path("tasks")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single()).isZero();
        assertOneBooking("4999.99");
        assertThat(effects(run, "succeeded")).isEqualTo(1);
    }

    @Test
    void creditAtTheThresholdNeedsAManagerAndBooksExactlyOnceAfterApproval() throws Exception {
        for (String amount : List.of("5000.00", "5000.01", "49999.99")) {
            BankingStub.reset();
            UUID run = startRun("rhea.requester", credit(), creditInput(amount));
            UUID round = child(run, 1);
            awaitOpenTask(round, "manager");
            assertThat(candidateGroup(round, "manager")).isEqualTo("credit-managers");
            assertThat(BankingStub.created(BOOKINGS)).as("nothing is booked before approval").isZero();
            approve("mani.manager", PURCHASING, round, "manager");
            awaitCompleted(run);
            JsonNode detail = runDetail(run);
            assertThat(detail.path("outcome").asString()).as(amount).isEqualTo("Approved");
            assertThat(detail.path("output").path("reviewRounds").asInt()).isEqualTo(1);
            assertThat(openTask(PURCHASING, run, "committee")).as(amount + " needs no committee").isNull();
            assertOneBooking(amount);
        }
    }

    @Test
    void creditFromTheHigherThresholdAlsoNeedsTheCommitteeAndOnlyItsMembersCanDecide() throws Exception {
        for (String amount : List.of("50000.00", "50000.01")) {
            BankingStub.reset();
            UUID run = startRun("rhea.requester", credit(), creditInput(amount));
            approve("mani.manager", PURCHASING, child(run, 1), "manager");
            awaitOpenTask(run, "committee");
            assertThat(BankingStub.created(BOOKINGS)).as("manager approval alone books nothing").isZero();
            assertThat(candidateGroup(run, "committee")).isEqualTo("credit-committee");
            // a credit manager who is not on the committee cannot decide; neither can the requester
            assertInvisible(claimTask("max.manager", run, "committee"));
            assertInvisible(claimTask("rhea.requester", run, "committee"));
            approve("fin.finance", PURCHASING, run, "committee");
            awaitCompleted(run);
            assertThat(runDetail(run).path("outcome").asString()).isEqualTo("Approved");
            assertOneBooking(amount);
        }
    }

    @Test
    void creditRequesterCanNeverApproveTheirOwnRequestInTheRoundOrTheCommittee() throws Exception {
        // mani is a credit manager and on the committee, and submits a 60,000 application
        UUID run = startRun("mani.manager", credit(), creditInput("60000.00"));
        UUID round = child(run, 1);
        awaitOpenTask(round, "manager");
        Response own = claimTask("mani.manager", round, "manager");
        assertThat(own.status()).isEqualTo(403);
        assertThat(own.code()).isEqualTo("SELF_APPROVAL_DENIED");
        assertThat(openTask(PURCHASING, round, "manager").path("assignee").isNull()).isTrue();
        approve("max.manager", PURCHASING, round, "manager");
        awaitOpenTask(run, "committee");
        Response ownCommittee = claimTask("mani.manager", run, "committee");
        assertThat(ownCommittee.status()).isEqualTo(403);
        assertThat(ownCommittee.code()).isEqualTo("SELF_APPROVAL_DENIED");
        assertThat(BankingStub.created(BOOKINGS)).isZero();
        approve("fin.finance", PURCHASING, run, "committee");
        awaitCompleted(run);
        assertOneBooking("60000.00");
        // nobody who is not the requester is blocked, and the decisions belong to max and fin
        assertThat(jdbc.sql("SELECT count(DISTINCT actor_subject_id) FROM task_decision WHERE run_id = :id OR run_id = :child")
                .param("id", run).param("child", round).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void creditReworkRoundsKeepEveryReturnReasonAndStopWhenApproved() throws Exception {
        UUID run = startRun("rhea.requester", credit(), creditInput("12000.00"));
        UUID first = child(run, 1);
        awaitOpenTask(first, "manager");
        reject("mani.manager", first, "manager", "Payslips are missing");
        UUID second = child(run, 2);
        awaitOpenTask(second, "manager");
        reject("max.manager", second, "manager", "Employer is not confirmed");
        UUID third = child(run, 3);
        awaitOpenTask(third, "manager");
        approve("mani.manager", PURCHASING, third, "manager");
        awaitCompleted(run);
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Approved");
        assertThat(detail.path("output").path("reviewRounds").asInt()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT d.reason FROM task_decision d JOIN workflow_run r ON r.id = d.run_id
                WHERE r.parent_run_id = :id AND d.action = 'reject' ORDER BY r.parent_occurrence
                """).param("id", run).query(String.class).list()).containsExactly("Payslips are missing", "Employer is not confirmed");
        assertThat(events(run).stream().filter("CHILD_ENDED"::equals).count()).isEqualTo(3);
        assertOneBooking("12000.00");
    }

    @Test
    void creditReworkExhaustionReferredToOperationsWhichMayOverrideAndTheBookingHappensOnce() throws Exception {
        UUID run = startRun("rhea.requester", credit(), creditInput("12000.00"));
        for (int round = 1; round <= 3; round++) {
            UUID child = child(run, round);
            awaitOpenTask(child, "manager");
            reject(round % 2 == 0 ? "max.manager" : "mani.manager", child, "manager", "Still incomplete, round " + round);
        }
        awaitOpenTask(run, "referred");
        assertThat(candidateGroup(run, "referred")).isEqualTo("credit-operations");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single()).isEqualTo(3);
        assertThat(BankingStub.created(BOOKINGS)).isZero();
        // the managers cannot take the referral; operations can
        assertInvisible(claimTask("mani.manager", run, "referred"));
        approve("max.manager", PURCHASING, run, "referred");
        awaitCompleted(run);
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Approved");
        assertThat(detail.path("output").path("reviewRounds").asInt()).isEqualTo(3);
        assertOneBooking("12000.00");
    }

    @Test
    void creditDeclineByOperationsNeedsAReasonAndBooksNothing() throws Exception {
        UUID run = startRun("rhea.requester", credit(), creditInput("12000.00"));
        for (int round = 1; round <= 3; round++) {
            UUID child = child(run, round);
            awaitOpenTask(child, "manager");
            reject("mani.manager", child, "manager", "Cannot verify income");
        }
        awaitOpenTask(run, "referred");
        assertThat(claimTask("max.manager", run, "referred").status()).isEqualTo(200);
        String task = openTask(PURCHASING, run, "referred").path("id").asString();
        Response noReason = decide("max.manager", PURCHASING, task, "reject", null);
        assertThat(noReason.status()).isEqualTo(422);
        assertThat(noReason.code()).isEqualTo("REASON_REQUIRED");
        assertThat(decide("max.manager", PURCHASING, task, "reject", "Affordability not met after three reviews").status()).isEqualTo(201);
        awaitCompleted(run);
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Declined");
        assertThat(detail.path("outcomeKind").asString()).isEqualTo("rejected");
        assertThat(BankingStub.created(BOOKINGS)).isZero();
        assertThat(BankingStub.calls(BOOKINGS)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id = :id").param("id", run).query(Long.class).single()).isZero();
    }

    @Test
    void creditManagerRoundThatNobodyDecidesEscalatesToADirectorAndStillBooksOnce() throws Exception {
        UUID run = startRun("rhea.requester", credit(), creditInput("7500.00"));
        UUID round = child(run, 1);
        awaitOpenTask(round, "manager");
        awaitOpenTaskAfterTimeout(round, "director");
        assertThat(events(round)).contains("TASK_TIMED_OUT");
        assertThat(candidateGroup(round, "director")).isEqualTo("credit-directors");
        assertInvisible(claimTask("mani.manager", round, "director"));
        approve("fin.finance", PURCHASING, round, "director");
        awaitCompleted(run);
        assertThat(runDetail(run).path("outcome").asString()).isEqualTo("Approved");
        assertOneBooking("7500.00");
    }

    @Test
    void creditBookingRetriesAfterTransientFailuresWithOneIdempotencyKeyAndOneBooking() throws Exception {
        BankingStub.fail(BOOKINGS, 2, 500);
        UUID run = startRun("rhea.requester", credit(), creditInput("1250.50"));
        until(() -> {
            fastForward(PURCHASING, run);
            return "completed".equals(lifecycle(PURCHASING, run));
        });
        assertThat(BankingStub.calls(BOOKINGS)).hasSize(3);
        assertThat(BankingStub.calls(BOOKINGS).stream().map(BankingStub.Call::idempotencyKey).distinct()).hasSize(1);
        assertOneBooking("1250.50");
        assertThat(jdbc.sql("SELECT attempts FROM effect_journal WHERE run_id = :id").param("id", run).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void creditPermanentBookingRejectionNeedsAttentionAndNeverBooks() throws Exception {
        BankingStub.fail(BOOKINGS, 1, 400);
        UUID run = startRun("rhea.requester", credit(), creditInput("1250.50"));
        until(() -> "needsAttention".equals(lifecycle(PURCHASING, run)));
        assertThat(BankingStub.created(BOOKINGS)).isZero();
        assertThat(effects(run, "permanentFailure")).isEqualTo(1);
        // an operator can retry once the remote is fixed: one booking, same key
        JsonNode detail = runDetail(run);
        Response retried = api("olga.operator").post(Map.of("reason", "Core system fixed", "expectedVersion", detail.path("version").asInt()),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/retry", PURCHASING, run);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        awaitCompleted(run);
        assertOneBooking("1250.50");
    }

    @Test
    void creditCaseSurvivesAnExecutorRestartBetweenRoundsAndBooksOnce() throws Exception {
        UUID run = startRun("rhea.requester", credit(), creditInput("20000.00"));
        UUID first = child(run, 1);
        awaitOpenTask(first, "manager");
        restartExecutor();
        reject("mani.manager", first, "manager", "Please add the employer letter");
        UUID second = child(run, 2);
        restartExecutor();
        awaitOpenTask(second, "manager");
        approve("max.manager", PURCHASING, second, "manager");
        awaitCompleted(run);
        assertThat(runDetail(run).path("output").path("reviewRounds").asInt()).isEqualTo(2);
        assertOneBooking("20000.00");
        assertThat(events(run).stream().filter("RUN_COMPLETED"::equals).count()).isEqualTo(1);
    }

    @Test
    void creditMoneyInputsAreDecimalExactAndFloatNoiseIsRefusedNotRounded() throws Exception {
        UUID workflow = credit();
        // 0.1 + 0.2 in binary floating point: refused because it is not a whole number of cents
        Response noise = startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":0.30000000000000004}");
        assertThat(noise.status()).as(noise.toString()).isEqualTo(422);
        assertThat(startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":4999.995}").status()).isEqualTo(422);
        assertThat(startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":5000.0000000000001}").status()).isEqualTo(422);
        assertThat(startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":0}").status()).isEqualTo(422);
        assertThat(startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":-5000}").status()).isEqualTo(422);
        assertThat(startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":10000000.01}").status()).isEqualTo(422);
        assertThat(startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":\"5000.00\"}").status()).isEqualTo(422);
        assertThat(BankingStub.calls(BOOKINGS)).isEmpty();
        // exact decimals in any notation reach the same rule row and the same cents
        for (String notation : List.of("5E3", "5000.0", "5.0e3", "5000.00")) {
            BankingStub.reset();
            Response started = startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":" + notation + "}");
            assertThat(started.status()).as(notation + " " + started).isEqualTo(201);
            UUID run = UUID.fromString(started.body().path("id").asString());
            assertThat(runDetail(run).path("input").path("amount").decimalValue()).as(notation).isEqualByComparingTo("5000");
            UUID round = child(run, 1);
            approve("mani.manager", PURCHASING, round, "manager");
            awaitCompleted(run);
            assertOneBooking("5000.00");
        }
        // 4.99999E3 is exactly 4,999.99 (automatic); 4.999995E3 is half a cent too many
        BankingStub.reset();
        Response cent = startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":4.99999E3}");
        assertThat(cent.status()).as(cent.toString()).isEqualTo(201);
        awaitCompleted(UUID.fromString(cent.body().path("id").asString()));
        assertOneBooking("4999.99");
        assertThat(startJson("rhea.requester", workflow, "{" + CREDIT_INPUT_WITHOUT_AMOUNT + ",\"amount\":4.999995E3}").status()).isEqualTo(422);
    }

    @Test
    void creditApplicationFormRejectsUnsupportedValues() throws Exception {
        Map<String, Object> badProduct = creditInput("1000.00");
        badProduct.put("product", "yacht");
        assertThat(startRaw("rhea.requester", credit(), badProduct).status()).isEqualTo(422);
        Map<String, Object> badTerm = creditInput("1000.00");
        badTerm.put("termMonths", 361);
        assertThat(startRaw("rhea.requester", credit(), badTerm).status()).isEqualTo(422);
        Map<String, Object> fractionalTerm = creditInput("1000.00");
        fractionalTerm.put("termMonths", new BigDecimal("12.5"));
        assertThat(startRaw("rhea.requester", credit(), fractionalTerm).status()).isEqualTo(422);
        assertThat(BankingStub.calls(BOOKINGS)).isEmpty();
    }

    @Test
    void gap_overrideApprovalByOperationsDoesNotRequireAReason() throws Exception {
        // A bank would require a recorded reason for every override. Only a rejection requires one today.
        UUID run = startRun("rhea.requester", credit(), creditInput("12000.00"));
        for (int round = 1; round <= 3; round++) {
            UUID child = child(run, round);
            awaitOpenTask(child, "manager");
            reject("mani.manager", child, "manager", "Incomplete");
        }
        awaitOpenTask(run, "referred");
        approve("max.manager", PURCHASING, run, "referred");
        awaitCompleted(run);
        assertThat(jdbc.sql("SELECT reason FROM task_decision WHERE run_id = :id AND node_id = 'referred'").param("id", run)
                .query(String.class).optional()).isEmpty();
        assertOneBooking("12000.00");
    }

    @Test
    void gap_sameApproverMayDecideEveryStageOfOneRequest() throws Exception {
        // mani is both a credit manager and a committee member. Separation covers the requester only; a stage-2
        // approver may be the stage-1 approver (no "different person than the previous decision" rule).
        UUID run = startRun("rhea.requester", credit(), creditInput("60000.00"));
        UUID round = child(run, 1);
        approve("mani.manager", PURCHASING, round, "manager");
        awaitOpenTask(run, "committee");
        approve("mani.manager", PURCHASING, run, "committee");
        awaitCompleted(run);
        assertThat(jdbc.sql("SELECT count(DISTINCT actor_subject_id) FROM task_decision WHERE run_id = :id OR run_id = :child")
                .param("id", run).param("child", round).query(Long.class).single()).isEqualTo(1);
        assertOneBooking("60000.00");
    }

    // ================================================================== 3. Card dispute

    private Map<String, Object> claimInput(String reason, String... transactions) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("claimReference", "CLM-2001");
        input.put("cardToken", "tok_4411");
        input.put("reasonCode", reason);
        input.put("transactions", List.of(transactions));
        return input;
    }

    @Test
    void claimStartsOnePinnedDisputePerTransactionAndRoutesFraudToTheFraudGroup() throws Exception {
        UUID fraud = startRun("rhea.requester", claim(), claimInput("fraud", "TX-1", "TX-2", "TX-3"));
        UUID other = startRun("rhea.requester", claim(), claimInput("merchantDispute", "TX-4", "TX-5"));
        for (int i = 1; i <= 3; i++) {
            UUID dispute = child(fraud, i);
            awaitOpenTask(dispute, "fraudReview");
            assertThat(candidateGroup(dispute, "fraudReview")).isEqualTo("fraud-investigators");
            assertThat(openTask(PURCHASING, dispute, "analystReview")).isNull();
            assertThat(jdbc.sql("SELECT input->>'transactionReference' FROM workflow_run WHERE id = :id").param("id", dispute)
                    .query(String.class).single()).isEqualTo("TX-" + i);
            assertThat(jdbc.sql("SELECT release_id FROM workflow_run WHERE id = :id").param("id", dispute).query(UUID.class).single())
                    .isEqualTo(jdbc.sql("SELECT release_id FROM workflow_run WHERE id = :id").param("id", child(fraud, 1)).query(UUID.class).single());
        }
        for (int i = 1; i <= 2; i++) {
            UUID dispute = child(other, i);
            awaitOpenTask(dispute, "analystReview");
            assertThat(candidateGroup(dispute, "analystReview")).isEqualTo("dispute-analysts");
            assertThat(openTask(PURCHASING, dispute, "fraudReview")).isNull();
        }
        // a dispute analyst cannot take a fraud investigation
        assertInvisible(claimTask("mani.manager", child(fraud, 1), "fraudReview"));
        assertThat(BankingStub.created(CHARGEBACKS)).isZero();
        cancel(fraud);
        cancel(other);
    }

    @Test
    void claimCompletesOnlyWhenEveryDisputeHasFinishedAndCollectsEachOutcomeInTransactionOrder() throws Exception {
        UUID run = startRun("rhea.requester", claim(), claimInput("merchantDispute", "TX-1", "TX-2", "TX-3"));
        UUID d1 = child(run, 1), d2 = child(run, 2), d3 = child(run, 3);
        awaitOpenTask(d1, "analystReview");
        awaitOpenTask(d2, "analystReview");
        awaitOpenTask(d3, "analystReview");
        // finish out of order: third declined, second accepted; the parent keeps waiting for the first
        reject("mani.manager", d3, "analystReview", "Merchant provided proof of delivery");
        approve("max.manager", PURCHASING, d2, "analystReview");
        until(() -> events(run).stream().filter("CHILD_ENDED"::equals).count() == 2);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(BankingStub.created(CHARGEBACKS)).isEqualTo(1);
        restartExecutor();
        approve("mani.manager", PURCHASING, d1, "analystReview");
        awaitCompleted(run);
        JsonNode detail = runDetail(run);
        assertThat(detail.path("output").path("outcomeItems").toString()).isEqualTo("[\"Chargeback raised\",\"Chargeback raised\",\"Dispute declined\"]");
        assertThat(detail.path("output").path("referenceItems")).hasSize(3);
        assertThat(detail.path("output").path("allSucceeded").asBoolean()).isFalse();
        assertThat(detail.path("outcome").asString()).isEqualTo("Claim has declined items");
        assertThat(detail.path("outcomeKind").asString()).isEqualTo("rejected");
        // exactly one chargeback for each accepted dispute and none for the declined one
        assertThat(BankingStub.created(CHARGEBACKS)).isEqualTo(2);
        assertThat(BankingStub.createdBodies(CHARGEBACKS)).anyMatch(b -> b.contains("\"transactionReference\":\"TX-1\""))
                .anyMatch(b -> b.contains("\"transactionReference\":\"TX-2\"")).noneMatch(b -> b.contains("TX-3"));
        assertThat(events(run).stream().filter("RUN_COMPLETED"::equals).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM effect_journal WHERE run_id IN (SELECT id FROM workflow_run WHERE parent_run_id = :id) AND state = 'succeeded'")
                .param("id", run).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void claimWithEveryDisputeAcceptedIsResolvedWithOneChargebackEach() throws Exception {
        UUID run = startRun("rhea.requester", claim(), claimInput("fraud", "TX-1", "TX-2"));
        approve("fin.finance", PURCHASING, child(run, 1), "fraudReview");
        approve("fin.finance", PURCHASING, child(run, 2), "fraudReview");
        awaitCompleted(run);
        JsonNode detail = runDetail(run);
        assertThat(detail.path("outcome").asString()).isEqualTo("Claim resolved");
        assertThat(detail.path("output").path("allSucceeded").asBoolean()).isTrue();
        assertThat(detail.path("output").path("outcomeItems").toString()).isEqualTo("[\"Chargeback raised\",\"Chargeback raised\"]");
        assertThat(BankingStub.created(CHARGEBACKS)).isEqualTo(2);
        assertThat(BankingStub.calls(CHARGEBACKS)).hasSize(2);
        assertThat(BankingStub.calls(CHARGEBACKS).stream().map(BankingStub.Call::idempotencyKey).distinct()).hasSize(2);
    }

    @Test
    void claimDisputeThatNobodyDecidesLapsesWithoutAChargebackAndTheClaimStillFinishes() throws Exception {
        UUID run = startRun("rhea.requester", claim(), claimInput("goodsNotReceived", "TX-1", "TX-2"));
        UUID d1 = child(run, 1), d2 = child(run, 2);
        awaitOpenTask(d1, "analystReview");
        awaitOpenTask(d2, "analystReview");
        approve("mani.manager", PURCHASING, d1, "analystReview");
        until(() -> {
            fastForward(PURCHASING, d2);
            return "completed".equals(lifecycle(PURCHASING, d2));
        });
        awaitCompleted(run);
        assertThat(events(d2)).contains("TASK_TIMED_OUT");
        JsonNode detail = runDetail(run);
        assertThat(detail.path("output").path("outcomeItems").toString()).isEqualTo("[\"Chargeback raised\",\"Dispute lapsed\"]");
        assertThat(detail.path("output").path("allSucceeded").asBoolean()).isFalse();
        assertThat(BankingStub.created(CHARGEBACKS)).isEqualTo(1);
    }

    @Test
    void claimRequesterCannotDecideTheirOwnDisputesAndOnlyEligibleGroupsCan() throws Exception {
        UUID run = startRun("mani.manager", claim(), claimInput("duplicateCharge", "TX-1"));
        UUID dispute = child(run, 1);
        awaitOpenTask(dispute, "analystReview");
        Response own = claimTask("mani.manager", dispute, "analystReview");
        assertThat(own.status()).isEqualTo(403);
        assertThat(own.code()).isEqualTo("SELF_APPROVAL_DENIED");
        assertInvisible(claimTask("fin.finance", dispute, "analystReview"));
        approve("max.manager", PURCHASING, dispute, "analystReview");
        awaitCompleted(run);
        assertThat(BankingStub.created(CHARGEBACKS)).isEqualTo(1);
    }

    @Test
    void claimDisputesCanRunOneAfterAnotherWhenTheTemplateIsSetToSequential() throws Exception {
        UUID sequential = publishBanking("banking-card-claim.json", keyOf(disputeChildPublished()), root -> {
            for (JsonNode node : root.path("nodes")) {
                if ("startWorkflow".equals(node.path("type").asString())) {
                    ((ObjectNode) node.path("config").path("forEach")).put("mode", "sequential");
                }
            }
        });
        UUID run = startRun("rhea.requester", sequential, claimInput("merchantDispute", "TX-1", "TX-2"));
        UUID first = child(run, 1);
        awaitOpenTask(first, "analystReview");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(Long.class).single()).isEqualTo(1);
        approve("mani.manager", PURCHASING, first, "analystReview");
        UUID second = child(run, 2);
        awaitOpenTask(second, "analystReview");
        approve("max.manager", PURCHASING, second, "analystReview");
        awaitCompleted(run);
        assertThat(runDetail(run).path("output").path("allSucceeded").asBoolean()).isTrue();
        assertThat(BankingStub.created(CHARGEBACKS)).isEqualTo(2);
    }

    private UUID disputeChildPublished() throws Exception {
        claim();
        return disputeChild;
    }

    @Test
    void claimCancelledByAnOperatorCancelsEveryOpenDisputeAndRaisesNoChargeback() throws Exception {
        UUID run = startRun("rhea.requester", claim(), claimInput("fraud", "TX-1", "TX-2", "TX-3"));
        for (int i = 1; i <= 3; i++) {
            awaitOpenTask(child(run, i), "fraudReview");
        }
        cancel(run);
        assertThat(jdbc.sql("SELECT lifecycle FROM workflow_run WHERE parent_run_id = :id").param("id", run).query(String.class).list())
                .containsExactly("cancelled", "cancelled", "cancelled");
        assertThat(jdbc.sql("SELECT count(*) FROM task_context WHERE run_id IN (SELECT id FROM workflow_run WHERE parent_run_id = :id) AND status = 'open'")
                .param("id", run).query(Long.class).single()).isZero();
        assertThat(BankingStub.calls(CHARGEBACKS)).isEmpty();
    }

    @Test
    void claimTransactionListIsBoundedAndRequired() throws Exception {
        Map<String, Object> none = claimInput("fraud");
        assertThat(startRaw("rhea.requester", claim(), none).status()).isEqualTo(422);
        Map<String, Object> tooMany = claimInput("fraud", "T1", "T2", "T3", "T4", "T5", "T6");
        assertThat(startRaw("rhea.requester", claim(), tooMany).status()).isEqualTo(422);
        Map<String, Object> blankItem = claimInput("fraud", "T1", "");
        assertThat(startRaw("rhea.requester", claim(), blankItem).status()).isEqualTo(422);
        Map<String, Object> missing = claimInput("fraud", "T1");
        missing.remove("transactions");
        assertThat(startRaw("rhea.requester", claim(), missing).status()).isEqualTo(422);
    }

    @Test
    void gap_theSameTransactionListedTwiceCreatesTwoDisputesAndTwoChargebacks() throws Exception {
        // The form subset has no uniqueItems and the idempotency key is the platform's effect key, not the
        // transaction reference, so the remote cannot deduplicate by business key either.
        UUID run = startRun("rhea.requester", claim(), claimInput("fraud", "TX-1", "TX-1"));
        approve("fin.finance", PURCHASING, child(run, 1), "fraudReview");
        approve("fin.finance", PURCHASING, child(run, 2), "fraudReview");
        awaitCompleted(run);
        assertThat(BankingStub.created(CHARGEBACKS)).isEqualTo(2);
        assertThat(BankingStub.createdBodies(CHARGEBACKS)).allMatch(b -> b.contains("\"transactionReference\":\"TX-1\""));
    }
}
