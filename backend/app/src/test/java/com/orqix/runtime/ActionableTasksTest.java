package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.runtime.internal.LocalTaskActionStub;
import com.orqix.runtime.internal.TaskActionLinks;
import com.orqix.runtime.internal.TaskActionOutbox;
import com.orqix.runtime.internal.TaskActionService;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.OpenApiContract;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * CG-T01 (docs/build/S141): actionable tasks through email links and Teams/Slack cards, delivered to the local stub
 * transports and acted on as the person, on PostgreSQL and Flowable.
 */
class ActionableTasksTest extends RuntimeTestBase {

    private static final String SLACK_SECRET = "slack-signing-secret-for-tests";
    private static final String TEAMS_SECRET = Base64.getEncoder().encodeToString("teams-hmac-key-for-tests-0123456789".getBytes());

    @Autowired
    TaskActionOutbox outbox;
    @Autowired
    LocalTaskActionStub stub;
    @Autowired
    TaskActionLinks links;
    @Autowired
    OrqixProperties properties;
    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void configure() throws Exception {
        Files.createDirectories(properties.secrets().dir());
        Files.writeString(properties.secrets().dir().resolve("s141-slack"), SLACK_SECRET);
        Files.writeString(properties.secrets().dir().resolve("s141-teams"), TEAMS_SECRET);
        for (String user : List.of("mani.manager", "max.manager", "rhea.requester", "fin.finance")) {
            api(user).get("/me");
        }
        settings(true);
        link("slack", "U-MANI", "mani.manager");
        link("teams", "aad-max", "max.manager");
        stub.reset();
    }

    @AfterEach
    void switchOff() throws Exception {
        settings(false);
    }

    private void settings(boolean enabled) throws Exception {
        int version = api("wade.admin").get("/workspaces/{ws}/task-actions/settings", PURCHASING).body().path("version").asInt();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("linkHours", 24);
        body.put("expectedVersion", version);
        body.put("channels", List.of(
                Map.of("channel", "email", "enabled", true,
                        "config", Map.of("sender", "orqix@orqix.example", "recipientDomains", List.of("orqix.example"))),
                Map.of("channel", "teams", "enabled", true, "config", Map.of(), "secretRef", "s141-teams"),
                Map.of("channel", "slack", "enabled", true, "config", Map.of(), "secretRef", "s141-slack")));
        var saved = api("wade.admin").put(body, null, "/workspaces/{ws}/task-actions/settings", PURCHASING);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
    }

    private void link(String platform, String user, String person) throws Exception {
        var linked = api("wade.admin").put(Map.of("platform", platform, "platformUserId", user, "subjectId", subject(person)),
                null, "/workspaces/{ws}/task-actions/identities", PURCHASING);
        assertThat(linked.status()).as(linked.toString()).isEqualTo(200);
    }

    private UUID subject(String user) throws Exception {
        return UUID.fromString(api(user).get("/me").body().path("subject").path("id").asString());
    }

    private String newTask(String requester) throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        UUID run = UUID.fromString(start(requester, PURCHASING, workflow, BigDecimal.TEN).path("id").asString());
        String task = openTask(PURCHASING, run, "manager").path("id").asString();
        outbox.sendQueued();
        return task;
    }

    private List<LocalTaskActionStub.Delivered> delivered(String task, String channel) {
        return stub.delivered().stream().filter(d -> d.channel().equals(channel)
                && d.message().path("taskId").asString().equals(task)).toList();
    }

    private String token(String task, String recipient) {
        String url = delivered(task, "email").stream().filter(d -> d.recipient().equals(recipient)).findFirst()
                .orElseThrow().message().path("actionUrl").asString();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private MvcResult slack(String body, long timestamp, String signature) throws Exception {
        var builder = MockMvcRequestBuilders.post("/api/v1/workspaces/" + PURCHASING + "/task-actions/slack/interactions")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body)
                .header("X-Slack-Request-Timestamp", String.valueOf(timestamp));
        if (signature != null) {
            builder.header("X-Slack-Signature", signature);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        OpenApiContract.validate(result);
        return result;
    }

    private JsonNode slackPress(String user, String actionId, String value, String reason) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "block_actions");
        payload.put("user", Map.of("id", user));
        payload.put("actions", List.of(Map.of("action_id", actionId, "value", value)));
        if (reason != null) {
            payload.put("state", Map.of("values", Map.of("orqix-reason", Map.of("reason", Map.of("type", "plain_text_input", "value", reason)))));
        }
        String body = "payload=" + URLEncoder.encode(json.write(payload), StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        MvcResult result = slack(body, ts, TaskActionService.slackSignature(SLACK_SECRET, ts, body.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.parse(result.getResponse().getContentAsString());
    }

    private JsonNode teamsPress(String user, Map<String, Object> value) throws Exception {
        String body = json.write(Map.of("type", "message", "from", Map.of("id", "29:x", "aadObjectId", user), "value", value));
        var builder = MockMvcRequestBuilders.post("/api/v1/workspaces/" + PURCHASING + "/task-actions/teams/interactions")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", TaskActionService.teamsAuthorization(TEAMS_SECRET, body.getBytes(StandardCharsets.UTF_8)));
        MvcResult result = mvc.perform(builder).andReturn();
        OpenApiContract.validate(result);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.parse(result.getResponse().getContentAsString());
    }

    private String confirmationValue(JsonNode slackReply) {
        for (JsonNode block : slackReply.path("blocks")) {
            for (JsonNode element : block.path("elements")) {
                if ("orqix-confirm".equals(element.path("action_id").asString())) {
                    return element.path("value").asString();
                }
            }
        }
        throw new AssertionError("No confirm button: " + slackReply);
    }

    @Test
    void cardsGoToEligiblePeopleOnlyAndCarryNoRequestValues() throws Exception {
        String task = newTask("rhea.requester");
        // Both managers are eligible: an email each; Slack only for mani and Teams only for max (mapped identities).
        assertThat(delivered(task, "email")).extracting(LocalTaskActionStub.Delivered::recipient)
                .containsExactlyInAnyOrder("mani.manager@orqix.example", "max.manager@orqix.example");
        assertThat(delivered(task, "slack")).extracting(LocalTaskActionStub.Delivered::recipient).containsExactly("U-MANI");
        assertThat(delivered(task, "teams")).extracting(LocalTaskActionStub.Delivered::recipient).containsExactly("aad-max");
        assertThat(stub.delivered().toString()).doesNotContain("Replacement laptop").doesNotContain("Device failed");
        // The requester is never sent a card for their own request; unmapped chat users are skipped with a reason.
        assertThat(stub.delivered().stream().map(LocalTaskActionStub.Delivered::recipient)).doesNotContain("rhea.requester@orqix.example");
        assertThat(jdbc.sql("SELECT count(*) FROM task_action_message WHERE task_id = :t AND status = 'skipped' AND skip_code = 'NO_CHAT_IDENTITY'")
                .param("t", task).query(Integer.class).single()).isEqualTo(2);
        var listed = api("wade.admin").get("/workspaces/{ws}/task-actions/messages?limit=20", PURCHASING);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(api("mani.manager").get("/workspaces/{ws}/task-actions/messages", PURCHASING).status()).isEqualTo(403);
    }

    @Test
    void anEmailLinkActsAsItsPersonAfterConfirmationAndOnlyOnce() throws Exception {
        String task = newTask("rhea.requester");
        String token = token(task, "mani.manager@orqix.example");

        JsonNode view = api(null).get("/task-action-links/{token}", token).body();
        assertThat(view.path("taskId").asString()).isEqualTo(task);
        assertThat(view.path("person").path("displayName").asString()).isNotBlank();
        assertThat(view.path("actions").path("approve").path("allowed").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("Replacement laptop");
        int version = view.path("version").asInt();

        // A tampered or unknown token is just not found; an unconfirmed action is refused and changes nothing.
        assertThat(api(null).get("/task-action-links/{token}", token.substring(0, token.length() - 2) + "AA").status()).isEqualTo(404);
        assertThat(api(null).post(Map.of("action", "approve", "expectedVersion", version), null, "/task-action-links/{token}", token)
                .status()).isEqualTo(422);
        // A stale version is a conflict, as on the task API.
        assertThat(api(null).post(Map.of("action", "approve", "expectedVersion", version + 5, "confirm", true), null,
                "/task-action-links/{token}", token).status()).isEqualTo(409);

        var done = api(null).post(Map.of("action", "approve", "expectedVersion", version, "confirm", true), null,
                "/task-action-links/{token}", token);
        assertThat(done.status()).as(done.toString()).isEqualTo(200);
        assertThat(done.body().path("outcome").asString()).isEqualTo("done");
        assertThat(done.body().path("decision").asString()).isEqualTo("approved");
        var again = api(null).post(Map.of("action", "approve", "expectedVersion", version, "confirm", true), null,
                "/task-action-links/{token}", token);
        assertThat(again.body().path("outcome").asString()).isEqualTo("replayed");

        // Decided as mani, with the task API's own records.
        UUID mani = subject("mani.manager");
        assertThat(jdbc.sql("SELECT actor_subject_id FROM task_decision WHERE engine_task_id = :t").param("t", task)
                .query(UUID.class).single()).isEqualTo(mani);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'task.decided' AND resource_id = :t AND actor_subject_id = :s")
                .param("t", task).param("s", mani).query(Integer.class).single()).isEqualTo(1);
        // Max's link to the same task can no longer decide it.
        String other = token(task, "max.manager@orqix.example");
        assertThat(api(null).post(Map.of("action", "approve", "expectedVersion", version, "confirm", true), null,
                "/task-action-links/{token}", other).status()).isEqualTo(409);
    }

    @Test
    void aLinkIsCheckedLikeTheTaskApiForSelfApproval() throws Exception {
        // Mani requests; Mani gets no card. A link issued to Mani anyway is refused exactly as the API refuses it.
        String task = newTask("mani.manager");
        assertThat(stub.delivered().stream().map(LocalTaskActionStub.Delivered::recipient)).doesNotContain("mani.manager@orqix.example");
        UUID mani = subject("mani.manager");
        String token = transactions.execute(s -> links.issue(PURCHASING, task, mani, "email", Duration.ofHours(1)).token());
        int version = api(null).get("/task-action-links/{token}", token).body().path("version").asInt();
        var refused = api(null).post(Map.of("action", "approve", "expectedVersion", version, "confirm", true), null,
                "/task-action-links/{token}", token);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("SELF_APPROVAL_DENIED");
        assertThat(openTask(PURCHASING, UUID.fromString(api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task)
                .body().path("runId").asString()), "manager")).isNotNull();
    }

    @Test
    void slackPressesNeedTheSignatureAndAConfirmationAndActAsTheMappedPerson() throws Exception {
        String task = newTask("rhea.requester");
        int version = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task).body().path("version").asInt();
        String value = json.write(Map.of("taskId", task, "version", version));
        String body = "payload=" + URLEncoder.encode(json.write(Map.of("user", Map.of("id", "U-MANI"),
                "actions", List.of(Map.of("action_id", "orqix-approve", "value", value)))), StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        assertThat(slack(body, now, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(slack(body, now, "v0=" + "0".repeat(64)).getResponse().getStatus()).isEqualTo(401);
        assertThat(slack(body, now - 3600, TaskActionService.slackSignature(SLACK_SECRET, now - 3600,
                body.getBytes(StandardCharsets.UTF_8))).getResponse().getStatus()).isEqualTo(401);

        // An unmapped Slack user does nothing.
        assertThat(slackPress("U-NOBODY", "orqix-approve", value, null).path("text").asString()).contains("not linked");

        // Approve asks for a confirmation; nothing is decided yet.
        JsonNode prompt = slackPress("U-MANI", "orqix-approve", value, null);
        assertThat(prompt.path("text").asString()).startsWith("Confirm: approve");
        assertThat(openTask(PURCHASING, runOf(task), "manager")).isNotNull();
        String confirmation = confirmationValue(prompt);
        JsonNode done = slackPress("U-MANI", "orqix-confirm", confirmation, null);
        assertThat(done.path("text").asString()).startsWith("Done: you approved");
        assertThat(slackPress("U-MANI", "orqix-confirm", confirmation, null).path("text").asString()).startsWith("Already done");
        assertThat(jdbc.sql("SELECT actor_subject_id FROM task_decision WHERE engine_task_id = :t").param("t", task)
                .query(UUID.class).single()).isEqualTo(subject("mani.manager"));
        assertThat(jdbc.sql("SELECT count(*) FROM task_action_confirmation WHERE task_id = :t AND reason IS NOT NULL")
                .param("t", task).query(Integer.class).single()).isZero();
    }

    @Test
    void slackRejectNeedsAReasonAndAnotherPersonsConfirmationIsUnknown() throws Exception {
        String task = newTask("rhea.requester");
        int version = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task).body().path("version").asInt();
        String value = json.write(Map.of("taskId", task, "version", version));
        assertThat(slackPress("U-MANI", "orqix-reject", value, null).path("text").asString()).contains("reason");
        JsonNode prompt = slackPress("U-MANI", "orqix-reject", value, "Over budget");
        String confirmation = confirmationValue(prompt);
        // Max cannot use Mani's confirmation, even through his own mapped account.
        link("slack", "U-MAX", "max.manager");
        assertThat(slackPress("U-MAX", "orqix-confirm", confirmation, null).path("text").asString()).contains("not available");
        assertThat(slackPress("U-MANI", "orqix-confirm", confirmation, null).path("text").asString()).startsWith("Done: you rejected");
        assertThat(jdbc.sql("SELECT reason FROM task_decision WHERE engine_task_id = :t").param("t", task)
                .query(String.class).single()).isEqualTo("Over budget");
    }

    @Test
    void teamsClaimThenDecideWithConfirmation() throws Exception {
        String task = newTask("rhea.requester");
        int version = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task).body().path("version").asInt();
        JsonNode claimed = teamsPress("aad-max", Map.of("orqixAction", "claim", "taskId", task, "version", version));
        assertThat(claimed.path("text").asString()).startsWith("You claimed");
        JsonNode claimedTask = api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task).body();
        assertThat(claimedTask.path("assignee").path("id").asString()).isEqualTo(subject("max.manager").toString());
        // Mani's card is now stale: someone else holds the task.
        String maniValue = json.write(Map.of("taskId", task, "version", claimedTask.path("version").asInt()));
        assertThat(slackPress("U-MANI", "orqix-approve", maniValue, null).path("text").asString()).startsWith("Nothing was done");

        JsonNode prompt = teamsPress("aad-max", Map.of("orqixAction", "approve", "taskId", task,
                "version", claimedTask.path("version").asInt()));
        JsonNode data = prompt.path("attachments").path(0).path("content").path("actions").path(0).path("data");
        assertThat(data.path("orqixAction").asString()).isEqualTo("confirm");
        JsonNode done = teamsPress("aad-max", Map.of("orqixAction", "confirm", "confirmationId", data.path("confirmationId").asString()));
        assertThat(done.path("text").asString()).startsWith("Done: you approved");

        // A wrong HMAC is refused before anything is read.
        String body = json.write(Map.of("from", Map.of("aadObjectId", "aad-max"), "value", Map.of("orqixAction", "claim")));
        MvcResult forged = mvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + PURCHASING + "/task-actions/teams/interactions")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "HMAC AAAA")).andReturn();
        assertThat(forged.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void settingsAndIdentitiesNeedWorkspaceAccessManagement() throws Exception {
        assertThat(api("mani.manager").get("/workspaces/{ws}/task-actions/settings", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/task-actions/settings", PURCHASING).status()).isEqualTo(404);
        var bad = api("wade.admin").put(Map.of("enabled", true, "linkHours", 24, "expectedVersion",
                api("wade.admin").get("/workspaces/{ws}/task-actions/settings", PURCHASING).body().path("version").asInt(),
                "channels", List.of(Map.of("channel", "email", "enabled", true, "config",
                        Map.of("sender", "x@orqix.example", "recipientDomains", List.of("*.example.com"))),
                        Map.of("channel", "slack", "enabled", true, "config", Map.of()))),
                null, "/workspaces/{ws}/task-actions/settings", PURCHASING);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().toString()).contains("/channels/0/config/recipientDomains").contains("/channels/1/secretRef");
        var stale = api("wade.admin").put(Map.of("enabled", true, "linkHours", 24, "expectedVersion", 999, "channels", List.of()),
                null, "/workspaces/{ws}/task-actions/settings", PURCHASING);
        assertThat(stale.status()).isEqualTo(409);
        var identities = api("wade.admin").get("/workspaces/{ws}/task-actions/identities", PURCHASING).body();
        assertThat(identities.path("items").toString()).contains("U-MANI").contains("aad-max");
        assertThat(api("wade.admin").delete("/workspaces/{ws}/task-actions/identities/{p}/{u}", PURCHASING, "teams", "aad-max")
                .status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'task.chat-identity-unlinked'")
                .query(Integer.class).single()).isPositive();
    }

    private UUID runOf(String task) throws Exception {
        return UUID.fromString(api("olga.operator").get("/workspaces/{ws}/tasks/{id}", PURCHASING, task).body().path("runId").asString());
    }
}
