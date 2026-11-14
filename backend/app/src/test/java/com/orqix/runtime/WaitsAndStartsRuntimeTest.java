package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.compiler.BusinessCalendar;
import com.orqix.integration.adapters.LocalStubTransport;
import com.orqix.runtime.internal.MessageSweeper;
import com.orqix.runtime.internal.Schedules;
import com.orqix.runtime.internal.WaitReminders;
import com.orqix.runtime.internal.WorkingCalendars;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.IntegrationTestBase.Response;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * docs/build/S139 on PostgreSQL and Flowable through the public API: shared working calendars and working-time waits
 * (CG-W05), reminders while waiting (CG-W06), "Wait for the first" (CG-W07), message handlers (CG-E08) and scheduled
 * starts (CG-E02).
 */
class WaitsAndStartsRuntimeTest extends RuntimeTestBase {

    @Autowired
    private WaitReminders reminders;

    @Autowired
    private Schedules schedules;

    @Autowired
    private MessageSweeper sweeper;

    @Autowired
    private LocalStubTransport transport;

    private static final Map<String, Object> OFFICE = Map.of("zone", "Europe/London", "weekdays", List.of(1, 2, 3, 4, 5),
            "start", "09:00", "end", "17:00", "intervals", List.of(Map.of("start", "09:00", "end", "12:00"), Map.of("start", "13:00", "end", "17:00")),
            "holidays", List.of("2026-12-25"));

    // ------------------------------------------------------------------ helpers

    private ObjectNode example(String file) throws Exception {
        return (ObjectNode) json.parse(Files.readString(Path.of("../../examples/" + file)));
    }

    private UUID publishExample(String file, Consumer<ObjectNode> patch) throws Exception {
        ObjectNode example = example(file);
        if (patch != null) {
            patch.accept(example);
        }
        return publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
    }

    private UUID startOrder(UUID workflow, String orderNumber, Map<String, Object> extra) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("orderNumber", orderNumber, "customerName", "Acme Ltd"));
        input.putAll(extra);
        var started = api("rhea.requester").post(Map.of("workflowId", workflow, "input", input),
                UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING);
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        return UUID.fromString(started.body().path("id").asString());
    }

    private static String order() {
        return "SO-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Response message(String name, String key, Object payload) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("correlationKey", key);
        if (payload != null) {
            body.put("payload", payload);
        }
        return api("olga.operator").post(body, UUID.randomUUID().toString(), "/workspaces/{ws}/messages", PURCHASING);
    }

    private List<String> events(UUID run) {
        return jdbc.sql("SELECT type FROM run_event WHERE workspace_id = :ws AND run_id = :id ORDER BY seq")
                .param("ws", PURCHASING).param("id", run).query(String.class).list();
    }

    private void awaitWaiting(UUID run, String nodeId) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).until(() -> {
            JsonNode steps = run(PURCHASING, run).path("currentSteps");
            for (JsonNode s : steps) {
                if (nodeId.equals(s.path("nodeId").asString())) {
                    return true;
                }
            }
            return false;
        });
    }

    private void awaitCompleted(UUID run) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(300)).until(() -> {
            sweeper.run();
            return "completed".equals(lifecycle(PURCHASING, run));
        });
    }

    private String calendar() throws Exception {
        String key = "office-" + UUID.randomUUID().toString().substring(0, 8);
        var created = api("dana.designer").post(Map.of("key", key, "name", "Office " + key, "content", OFFICE),
                "/workspaces/{ws}/calendars", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return key;
    }

    private static void pin(ObjectNode d, String key, int version) {
        ObjectNode deps = (ObjectNode) d.get("dependencies");
        ArrayNode assets = deps.has("assets") ? (ArrayNode) deps.get("assets") : deps.putArray("assets");
        ObjectNode pin = assets.addObject();
        pin.put("kind", "calendar").put("key", key).put("version", version).put("target", "workflow");
    }

    private String emailAdapter() throws Exception {
        String key = "vendor-mail-" + UUID.randomUUID().toString().substring(0, 8);
        var created = api("wade.admin").post(Map.of("key", key, "kind", "email", "name", "Vendor mail " + key,
                "secretRef", "vendor-mail", "config", Map.of("from", "noreply@example.com",
                        "allowedRecipientDomains", List.of("supplier.example"), "maxRecipients", 3)),
                "/workspaces/{ws}/adapters", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return key;
    }

    private int sends(String adapter) {
        return (int) transport.sent().stream().filter(s -> s.adapterKey().equals(adapter)).count();
    }

    private void due(UUID run) {
        jdbc.sql("UPDATE wait_reminder SET next_at = now() - interval '1 second' WHERE run_id = :run AND status = 'waiting'")
                .param("run", run).update();
    }

    // ------------------------------------------------------------------ CG-W05

    @Test
    void calendarsAreVersionedSharedAssetsWithPermissions() throws Exception {
        String key = calendar();
        // Viewers read, requesters and outsiders do not change; outsiders see nothing.
        assertThat(api("vic.viewer").get("/workspaces/{ws}/calendars/{key}", PURCHASING, key).status()).isEqualTo(200);
        assertThat(api("vic.viewer").post(Map.of("key", "x-" + key, "name", "X", "content", OFFICE),
                "/workspaces/{ws}/calendars", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/calendars/{key}", PURCHASING, key).status()).isEqualTo(404);
        // Invalid content is refused with field errors.
        var invalid = api("dana.designer").post(Map.of("key", "bad-" + key.substring(7), "name", "Bad " + key,
                "content", Map.of("zone", "Mars/Olympus", "weekdays", List.of(), "start", "17:00", "end", "09:00")),
                "/workspaces/{ws}/calendars", PURCHASING);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().toString()).contains("/content/zone", "/content/weekdays");
        // A duplicate key is a conflict.
        assertThat(api("dana.designer").post(Map.of("key", key, "name", "Other " + key, "content", OFFICE),
                "/workspaces/{ws}/calendars", PURCHASING).status()).isEqualTo(409);
        // A new version needs the version it was based on.
        Map<String, Object> christmasOff = new LinkedHashMap<>(OFFICE);
        christmasOff.put("holidays", List.of("2026-12-25", "2026-12-26"));
        var stale = api("dana.designer").post(Map.of("expectedVersion", 7, "content", christmasOff),
                "/workspaces/{ws}/calendars/{key}/versions", PURCHASING, key);
        assertThat(stale.status()).isEqualTo(409);
        var v2 = api("dana.designer").post(Map.of("expectedVersion", 1, "content", christmasOff, "changeNote", "Boxing day"),
                "/workspaces/{ws}/calendars/{key}/versions", PURCHASING, key);
        assertThat(v2.status()).as(v2.toString()).isEqualTo(200);
        assertThat(v2.body().path("calendar").path("latestVersion").asInt()).isEqualTo(2);
        assertThat(v2.body().path("versions")).hasSize(2);
        assertThat(api("dana.designer").post(Map.of("expectedVersion", 2, "content", christmasOff),
                "/workspaces/{ws}/calendars/{key}/versions", PURCHASING, key).status()).isEqualTo(422);
        // Preview: Friday 16:00 plus four working hours ends Monday 12:00 (lunch 12:00-13:00 is not working time).
        var preview = api("vic.viewer").post(Map.of("version", 1, "from", "2026-11-06T16:00:00Z",
                "workingDuration", "PT4H"), "/workspaces/{ws}/calendars/{key}/preview", PURCHASING, key);
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat(preview.body().path("dueAt").asString()).isEqualTo("2026-11-09T12:00:00Z");
        // P1D is one working day: seven hours here.
        assertThat(api("vic.viewer").post(Map.of("version", 1, "from", "2026-11-09T09:00:00Z", "workingDuration", "P1D"),
                "/workspaces/{ws}/calendars/{key}/preview", PURCHASING, key).body().path("dueAt").asString())
                .isEqualTo("2026-11-09T17:00:00Z");
        // Audit names the key and version, never the content.
        String audit = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'calendar.versioned'"
                + " AND resource_id = :key").param("key", key).query(String.class).single();
        assertThat(audit).contains("\"version\": 2").doesNotContain("Europe/London");
    }

    @Test
    void aWorkingTimeWaitEndsAtTheWorkingTimeOfThePinnedVersion() throws Exception {
        String key = calendar();
        UUID workflow = publishExample("order-payment-wait.json", d -> {
            ObjectNode wait = (ObjectNode) d.get("nodes").get(1);
            ((ObjectNode) wait.get("config")).put("timeoutAfter", "P1D").put("timeoutCalendarRef", key);
            pin(d, key, 1);
        });
        UUID run = startOrder(workflow, order(), Map.of());
        awaitWaiting(run, "waitPayment");
        JsonNode step = run(PURCHASING, run).path("currentSteps").get(0);
        Instant since = Instant.parse(step.path("since").asString());
        Instant due = Instant.parse(step.path("dueAt").asString());
        Instant expected = WorkingCalendars.due(BusinessCalendar.read(json.mapper().valueToTree(OFFICE)), since, "P1D");
        assertThat(Duration.between(expected, due).abs()).isLessThan(Duration.ofSeconds(5));
        // The deadline counts working time: a wall-clock day would end earlier (or on a weekend).
        assertThat(WorkingCalendars.working(BusinessCalendar.read(json.mapper().valueToTree(OFFICE)), due.minusSeconds(1)))
                .isTrue();
        // A newer calendar version does not change the release; publishing with an archived calendar is refused.
        api("dana.designer").post(Map.of("archived", true), "/workspaces/{ws}/calendars/{key}/archive", PURCHASING, key);
        var draft = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body();
        var validation = api("dana.designer").post(Map.of("revision", 2),
                "/workspaces/{ws}/workflows/{id}/validate", PURCHASING, workflow);
        assertThat(validation.body().toString()).contains("WF_ASSET_ARCHIVED");
        // The running request keeps its pinned version; the time limit still works.
        fastForward(PURCHASING, run);
        awaitCompleted(run);
        assertThat(events(run)).contains("WAIT_ENDED");
    }

    // ------------------------------------------------------------------ CG-W06

    private UUID remindingOrder(String adapter, String recipients, int maxTimes) throws Exception {
        return publishExample("order-payment-wait.json", d -> {
            ((ObjectNode) d.get("inputSchema").get("properties")).set("vendorEmail", json.parse(
                    "{\"type\":\"string\",\"title\":\"Vendor email\",\"maxLength\":200}"));
            ObjectNode config = (ObjectNode) d.get("nodes").get(1).get("config");
            config.set("whileWaiting", json.parse("""
                    {"remind":{"every":"PT1H","maxTimes":%d,"adapter":"%s",%s,
                     "template":{"subject":"Reminder {{count}} of {{maxTimes}}: {{reference}}",
                                 "body":"We still wait for the payment of {{customer}} since {{since}}."},
                     "inputMapping":{"customer":"/input/customerName"}}}""".formatted(maxTimes, adapter, recipients)));
        });
    }

    @Test
    void remindersAreSentOnceEachThroughTheJournalAndStopWithTheWait() throws Exception {
        String adapter = emailAdapter();
        UUID workflow = remindingOrder(adapter, "\"to\":[\"ap@supplier.example\"]", 2);
        String key = order();
        UUID run = startOrder(workflow, key, Map.of());
        awaitWaiting(run, "waitPayment");
        JsonNode list = api("olga.operator").get("/workspaces/{ws}/runs/{id}/reminders", PURCHASING, run).body();
        assertThat(list.path("items")).hasSize(1);
        JsonNode first = list.path("items").get(0);
        assertThat(first.path("status").asString()).isEqualTo("waiting");
        Instant since = Instant.parse(run(PURCHASING, run).path("currentSteps").get(0).path("since").asString());
        assertThat(Instant.parse(first.path("nextAt").asString())).isCloseTo(since.plus(1, ChronoUnit.HOURS),
                org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        // Not due yet: nothing is sent.
        reminders.runDue();
        assertThat(sends(adapter)).isZero();

        due(run);
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(1);
        assertThat(events(run)).contains("REMINDER_SENT");
        assertThat(jdbc.sql("SELECT occurrence FROM effect_journal WHERE run_id = :run AND node_id = 'waitPayment__remind'")
                .param("run", run).query(Integer.class).list()).containsExactly(1);

        // A pod died after sending but before recording it: the lease expires and the journal answers, nothing is resent.
        jdbc.sql("UPDATE wait_reminder SET sent = 0, status = 'sending', lease_until = now() - interval '1 second'"
                + " WHERE run_id = :run").param("run", run).update();
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT sent FROM wait_reminder WHERE run_id = :run").param("run", run)
                .query(Integer.class).single()).isEqualTo(1);

        // The second (last) reminder is a new message with its own stable id; then the row is done.
        due(run);
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT occurrence FROM effect_journal WHERE run_id = :run AND node_id = 'waitPayment__remind'"
                + " ORDER BY occurrence").param("run", run).query(Integer.class).list()).containsExactly(1, 2);
        assertThat(jdbc.sql("SELECT status FROM wait_reminder WHERE run_id = :run").param("run", run)
                .query(String.class).single()).isEqualTo("done");
        due(run);
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(2);

        // A second request: the message arrives after one reminder, so reminding stops with the wait.
        String otherKey = order();
        UUID other = startOrder(workflow, otherKey, Map.of());
        awaitWaiting(other, "waitPayment");
        due(other);
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(3);
        assertThat(message("payment-received", otherKey, Map.of("paymentReference", "PAY-1")).status()).isEqualTo(202);
        awaitCompleted(other);
        assertThat(jdbc.sql("SELECT status FROM wait_reminder WHERE run_id = :run").param("run", other)
                .query(String.class).single()).isEqualTo("stopped");
        due(other);
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(3);
        // The reminder list never shows texts or recipients; outsiders see nothing.
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}/reminders", PURCHASING, other).body().toString())
                .doesNotContain("supplier.example", "Acme");
        assertThat(api("otto.outsider").get("/workspaces/{ws}/runs/{id}/reminders", PURCHASING, other).status())
                .isEqualTo(404);
    }

    @Test
    void aRecipientFromTheRequestMustBeOnTheApprovedDomainsWhenTheReminderIsSent() throws Exception {
        String adapter = emailAdapter();
        UUID workflow = remindingOrder(adapter, "\"toField\":\"/input/vendorEmail\"", 3);
        UUID approved = startOrder(workflow, order(), Map.of("vendorEmail", "ap@supplier.example"));
        UUID elsewhere = startOrder(workflow, order(), Map.of("vendorEmail", "someone@elsewhere.example"));
        awaitWaiting(approved, "waitPayment");
        awaitWaiting(elsewhere, "waitPayment");
        due(approved);
        due(elsewhere);
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(1);
        assertThat(events(approved)).contains("REMINDER_SENT");
        assertThat(events(elsewhere)).contains("REMINDER_REFUSED").doesNotContain("REMINDER_SENT");
        assertThat(jdbc.sql("SELECT status FROM wait_reminder WHERE run_id = :run").param("run", elsewhere)
                .query(String.class).single()).isEqualTo("refused");
        // The domain is checked at send time: narrowing the adapter stops the next reminder of the approved request.
        jdbc.sql("UPDATE adapter_config SET config = jsonb_set(config, '{allowedRecipientDomains}', '[\"other.example\"]')"
                + " WHERE workspace_id = :ws AND key = :key").param("ws", PURCHASING).param("key", adapter).update();
        due(approved);
        reminders.runDue();
        assertThat(sends(adapter)).isEqualTo(1);
        assertThat(events(approved)).contains("REMINDER_REFUSED");
    }

    // ------------------------------------------------------------------ CG-W07

    @Test
    void waitForTheFirstTakesTheBranchThatHappensFirstAndClosesTheOthers() throws Exception {
        UUID workflow = publishExample("order-payment-first.json", null);
        // 1. The payment message wins.
        String key = order();
        UUID paid = startOrder(workflow, key, Map.of());
        awaitWaiting(paid, "waitFirst__paid");
        awaitWaiting(paid, "waitFirst__tooOld");
        awaitWaiting(paid, "waitFirst__withdrawn");
        JsonNode waits = api("olga.operator").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, paid).body();
        assertThat(waits.path("items")).extracting(w -> w.path("kind").asString())
                .containsExactlyInAnyOrder("message", "signal", "timer");
        assertThat(message("payment-received", key, Map.of("paymentReference", "PAY-9", "amountPaid", 10)).status())
                .isEqualTo(202);
        awaitCompleted(paid);
        JsonNode done = run(PURCHASING, paid);
        assertThat(done.path("outcome").asString()).isEqualTo("Order paid");
        assertThat(done.path("output").path("paymentReference").asString()).isEqualTo("PAY-9");
        assertThat(events(paid)).contains("WAIT_FIRST_WON");
        assertThat(jdbc.sql("SELECT count(*) FROM wait_state WHERE run_id = :run").param("run", paid)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM message_subscription WHERE run_id = :run").param("run", paid)
                .query(String.class).single()).isEqualTo("received");
        // A late duplicate message does not reach the ended request.
        assertThat(message("payment-received", key, Map.of("paymentReference", "PAY-10")).body()
                .path("status").asString()).isEqualTo("buffered");

        // 2. The cancellation signal wins; the payment subscription closes.
        String key2 = order();
        UUID withdrawn = startOrder(workflow, key2, Map.of());
        awaitWaiting(withdrawn, "waitFirst__withdrawn");
        Map<String, Object> signal = Map.of("signal", "customer-cancelled", "reason", "The customer called",
                "expectedVersion", run(PURCHASING, withdrawn).path("version").asInt());
        var sent = api("olga.operator").post(signal, UUID.randomUUID().toString(), "/workspaces/{ws}/runs/{id}/signals",
                PURCHASING, withdrawn);
        assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
        awaitCompleted(withdrawn);
        assertThat(run(PURCHASING, withdrawn).path("outcome").asString()).isEqualTo("Cancelled no payment");
        assertThat(jdbc.sql("SELECT status FROM message_subscription WHERE run_id = :run").param("run", withdrawn)
                .query(String.class).single()).isEqualTo("cancelled");
        assertThat(message("payment-received", key2, Map.of("paymentReference", "PAY-11")).body()
                .path("status").asString()).isEqualTo("buffered");

        // 3. Seven days pass.
        UUID old = startOrder(workflow, order(), Map.of());
        awaitWaiting(old, "waitFirst__tooOld");
        fastForward(PURCHASING, old);
        awaitCompleted(old);
        assertThat(run(PURCHASING, old).path("outcome").asString()).isEqualTo("Cancelled no payment");
        assertThat(jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = 'WAIT_FIRST_WON'")
                .param("run", old).query(String.class).single()).contains("tooOld");
    }

    @Test
    void aCallbackBranchIsAnsweredThroughItsTokenAndOutsidersCannotSendItsMessage() throws Exception {
        UUID workflow = publishExample("order-payment-first.json", d -> {
            ArrayNode branches = (ArrayNode) d.get("nodes").get(1).get("config").get("branches");
            branches.set(1, json.parse("""
                    {"id":"withdrawn","label":"Vendor confirmed","kind":"callback","tokenValidFor":"P2D",
                     "payload":[{"name":"confirmation","type":"string","required":true}]}"""));
        });
        UUID run = startOrder(workflow, order(), Map.of());
        awaitWaiting(run, "waitFirst__withdrawn");
        JsonNode waits = api("olga.operator").get("/workspaces/{ws}/callback-waits?runId=" + run, PURCHASING).body();
        assertThat(waits.path("items")).hasSize(1);
        String waitId = waits.path("items").get(0).path("id").asString();
        var issued = api("olga.operator").post(null, "/workspaces/{ws}/callback-waits/{id}/token", PURCHASING, waitId);
        assertThat(issued.status()).as(issued.toString()).isEqualTo(201);
        String token = issued.body().path("token").asString();
        // The internal message name cannot be sent through the message API.
        String internal = com.orqix.catalog.WaitsAndStartsNodes.internalMessage("waitFirst", "withdrawn");
        var forged = message(internal, run.toString(), Map.of("confirmation", "forged"));
        assertThat(forged.status()).isEqualTo(422);
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders
                .post("/api/v1/workspaces/{ws}/callbacks/wait/resume", PURCHASING)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("result",
                        Map.of("confirmation", "VC-1"))))
                .header("X-Orqix-Callback-Token", token).header("Idempotency-Key", UUID.randomUUID().toString());
        var resumed = api(null).send(post);
        assertThat(resumed.status()).as(resumed.toString()).isEqualTo(200);
        awaitCompleted(run);
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Cancelled no payment");
        assertThat(jdbc.sql("SELECT data::text FROM run_event WHERE run_id = :run AND type = 'WAIT_FIRST_WON'")
                .param("run", run).query(String.class).single()).contains("withdrawn");
    }

    // ------------------------------------------------------------------ CG-E08

    private Consumer<ObjectNode> handler(boolean interrupting) {
        return d -> {
            d.set("subprocesses", json.parse("""
                    [{"id":"onCancel","label":"Customer cancelled","type":"event","nodes":["noteCancel"%s],
                      "interrupting":%s,"trigger":{"type":"message","message":"customer-cancelled",
                      "correlationField":"/input/orderNumber","payload":[{"name":"reason","type":"string"}]}}]"""
                    .formatted(interrupting ? ",\"endCancel\"" : "", interrupting)));
            ((ArrayNode) d.get("nodes")).add(json.parse("""
                    {"id":"noteCancel","type":"notification","label":"Tell requester","config":{"channel":"inApp",
                     "recipient":"initiator","message":"The customer cancelled the order."}}"""));
            if (interrupting) {
                ((ArrayNode) d.get("nodes")).add(json.parse("""
                        {"id":"endCancel","type":"end","label":"Cancelled by customer","config":{
                         "businessOutcome":"Cancelled by customer","outcomeKind":"rejected"}}"""));
                ((ArrayNode) d.get("transitions")).add(json.parse(
                        "{\"id\":\"h1\",\"from\":\"noteCancel\",\"to\":\"endCancel\",\"on\":\"next\"}"));
                ((ObjectNode) d.get("uiLayout").get("positions")).set("endCancel", json.parse("{\"x\":860,\"y\":640}"));
            }
            ((ObjectNode) d.get("uiLayout").get("positions")).set("noteCancel", json.parse("{\"x\":600,\"y\":640}"));
        };
    }

    @Test
    void anInterruptingMessageHandlerStopsTheRequestAndClosesItsMessageWait() throws Exception {
        UUID workflow = publishExample("order-payment-wait.json", handler(true));
        String key = order();
        UUID run = startOrder(workflow, key, Map.of());
        awaitWaiting(run, "waitPayment");
        // The handler is armed but is not a waiting step.
        assertThat(api("olga.operator").get("/workspaces/{ws}/runs/{id}/waits", PURCHASING, run).body().path("items"))
                .hasSize(1);
        var cancelled = message("customer-cancelled", key, Map.of("reason", "Changed mind"));
        assertThat(cancelled.status()).as(cancelled.toString()).isEqualTo(202);
        assertThat(cancelled.body().path("status").asString()).isEqualTo("delivered");
        awaitCompleted(run);
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Cancelled by customer");
        assertThat(events(run)).contains("HANDLER_MESSAGE_RECEIVED", "HANDLER_STARTED", "STEP_INTERRUPTED");
        assertThat(jdbc.sql("SELECT node_id || ':' || status FROM message_subscription WHERE run_id = :run ORDER BY node_id")
                .param("run", run).query(String.class).list())
                .containsExactly("onCancel:received", "waitPayment:cancelled");
        // The payment that arrives afterwards is kept like any message nobody waits for.
        assertThat(message("payment-received", key, Map.of("paymentReference", "PAY-2")).body().path("status")
                .asString()).isEqualTo("buffered");
        // A cancellation sent before a request exists never starts its handler later.
        String early = order();
        assertThat(message("customer-cancelled", early, Map.of()).body().path("status").asString()).isEqualTo("buffered");
        UUID later = startOrder(workflow, early, Map.of());
        awaitWaiting(later, "waitPayment");
        sweeper.run();
        assertThat(lifecycle(PURCHASING, later)).isEqualTo("waiting");
    }

    @Test
    void aSideMessageHandlerRunsForEveryMessageWhileTheRequestWaits() throws Exception {
        UUID workflow = publishExample("order-payment-wait.json", handler(false));
        String key = order();
        UUID run = startOrder(workflow, key, Map.of());
        awaitWaiting(run, "waitPayment");
        assertThat(message("customer-cancelled", key, Map.of("reason", "One")).body().path("status").asString())
                .isEqualTo("delivered");
        assertThat(message("customer-cancelled", key, Map.of("reason", "Two")).body().path("status").asString())
                .isEqualTo("delivered");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> events(run).stream()
                .filter("HANDLER_STARTED"::equals).count() == 2);
        assertThat(lifecycle(PURCHASING, run)).isEqualTo("waiting");
        assertThat(message("payment-received", key, Map.of("paymentReference", "PAY-3")).status()).isEqualTo(202);
        awaitCompleted(run);
        assertThat(run(PURCHASING, run).path("outcome").asString()).isEqualTo("Order paid");
        assertThat(jdbc.sql("SELECT status FROM message_subscription WHERE run_id = :run AND node_id = 'onCancel'")
                .param("run", run).query(String.class).single()).isEqualTo("cancelled");
    }

    // ------------------------------------------------------------------ CG-E02

    private void grantStart(UUID workflow) throws Exception {
        var grant = api("wade.admin").post(Map.of("permission", "workflow.start", "workflowId", workflow.toString(),
                "principal", Map.of("type", "idpGroup", "value", "orqix-purchasing-publishers")),
                "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(grant.status()).as(grant.toString()).isEqualTo(201);
    }

    private Map<String, Object> schedule(UUID workflow, String expression, Map<String, Object> more) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", workflow.toString());
        body.put("name", "Nightly " + UUID.randomUUID().toString().substring(0, 6));
        body.put("kind", "cron");
        body.put("expression", expression);
        body.put("zone", "Europe/London");
        body.put("input", input(java.math.BigDecimal.TEN));
        body.putAll(more);
        return body;
    }

    private UUID scheduleId(Response created) {
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    private void dueIn(UUID schedule, Duration ago) {
        jdbc.sql("UPDATE schedule_trigger SET next_fire_at = now() - CAST(:ago AS interval) WHERE id = :id")
                .param("ago", ago.toSeconds() + " seconds").param("id", schedule).update();
    }

    private List<String> fires(UUID schedule) {
        return jdbc.sql("SELECT outcome FROM schedule_fire WHERE schedule_id = :id ORDER BY fired_at, id").param("id", schedule)
                .query(String.class).list();
    }

    @Test
    void schedulesStartRequestsOnceAsTheirOwnerWithMissedRunAndOverlapPolicies() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        // Permissions: a designer may not schedule; a publisher who may not start the workflow neither.
        assertThat(api("dana.designer").post(schedule(workflow, "0 7 * * MON-FRI", Map.of()), "/workspaces/{ws}/schedules",
                PURCHASING).status()).isEqualTo(403);
        assertThat(api("paul.publisher").post(schedule(workflow, "0 7 * * MON-FRI", Map.of()),
                "/workspaces/{ws}/schedules", PURCHASING).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get("/workspaces/{ws}/schedules", PURCHASING).status()).isEqualTo(404);
        grantStart(workflow);
        // Validation: too often, a bad zone, an input that does not fit the request form.
        assertThat(api("paul.publisher").post(schedule(workflow, "* * * * *", Map.of()), "/workspaces/{ws}/schedules",
                PURCHASING).status()).isEqualTo(422);
        assertThat(api("paul.publisher").post(schedule(workflow, "0 7 * * *", Map.of("zone", "Nowhere/City")),
                "/workspaces/{ws}/schedules", PURCHASING).body().toString()).contains("/zone");
        assertThat(api("paul.publisher").post(schedule(workflow, "0 7 * * *", Map.of("input", Map.of("title", "x"))),
                "/workspaces/{ws}/schedules", PURCHASING).body().toString()).contains("/input/");
        // Preview: the next times of an RRULE.
        var preview = api("vic.viewer").post(Map.of("kind", "rrule", "expression", "FREQ=WEEKLY;BYDAY=MO;BYHOUR=8;BYMINUTE=30",
                "zone", "Europe/London", "startsAt", "2026-11-06T00:00:00Z"), "/workspaces/{ws}/schedules/preview", PURCHASING);
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);

        UUID id = scheduleId(api("paul.publisher").post(schedule(workflow, "0 7 * * *", Map.of("overlapPolicy", "allow")),
                "/workspaces/{ws}/schedules", PURCHASING));
        JsonNode created = api("vic.viewer").get("/workspaces/{ws}/schedules/{id}", PURCHASING, id).body();
        assertThat(created.path("upcoming")).hasSize(5);
        assertThat(created.path("input").isNull()).as("viewers do not see the fixed input").isTrue();
        assertThat(created.path("runAsName").asString()).isNotBlank();

        // Due now: one request, started as the publisher; a second scheduler pass starts nothing more.
        dueIn(id, Duration.ofSeconds(10));
        assertThat(schedules.runDue(20)).isPositive();
        schedules.runDue(20);
        assertThat(fires(id)).containsExactly("started");
        UUID runId = jdbc.sql("SELECT run_id FROM schedule_fire WHERE schedule_id = :id").param("id", id)
                .query(UUID.class).single();
        UUID initiator = jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :id").param("id", runId)
                .query(UUID.class).single();
        assertThat(initiator.toString()).isEqualTo(created.path("runAs").asString());
        assertThat(jdbc.sql("SELECT next_fire_at > now() FROM schedule_trigger WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();

        // Missed while the scheduler was down, catch up once: one request for all missed times.
        dueIn(id, Duration.ofHours(49));
        schedules.runDue(20);
        assertThat(fires(id)).containsExactly("started", "started");
        assertThat(jdbc.sql("SELECT reason FROM schedule_fire WHERE schedule_id = :id ORDER BY fire_at LIMIT 1")
                .param("id", id).query(String.class).single()).contains("missed");

        // Skip missed runs; and skip while the previous request is still open.
        JsonNode current = api("paul.publisher").get("/workspaces/{ws}/schedules/{id}", PURCHASING, id).body();
        var update = api("paul.publisher").send(MockMvcRequestBuilders.patch("/api/v1/workspaces/{ws}/schedules/{id}",
                PURCHASING, id).contentType(MediaType.APPLICATION_JSON).with(IntegrationTestBase::xsrf)
                .content(json.write(Map.of("expectedVersion", current.path("version").asInt(), "missedRunPolicy", "skip",
                        "overlapPolicy", "skip"))));
        assertThat(update.status()).as(update.toString()).isEqualTo(200);
        dueIn(id, Duration.ofHours(100));
        schedules.runDue(20);
        assertThat(fires(id)).last().isEqualTo("skipped");
        dueIn(id, Duration.ofSeconds(5));
        schedules.runDue(20);
        assertThat(jdbc.sql("SELECT reason FROM schedule_fire WHERE schedule_id = :id ORDER BY fired_at DESC LIMIT 1")
                .param("id", id).query(String.class).single()).contains("still open");

        // Pause: nothing is due; a stale update is refused; delete removes it with an audit entry.
        current = api("paul.publisher").get("/workspaces/{ws}/schedules/{id}", PURCHASING, id).body();
        var stale = api("paul.publisher").send(MockMvcRequestBuilders.patch("/api/v1/workspaces/{ws}/schedules/{id}",
                PURCHASING, id).contentType(MediaType.APPLICATION_JSON).with(IntegrationTestBase::xsrf)
                .content(json.write(Map.of("expectedVersion", 1, "enabled", false))));
        assertThat(stale.status()).isEqualTo(409);
        var paused = api("paul.publisher").send(MockMvcRequestBuilders.patch("/api/v1/workspaces/{ws}/schedules/{id}",
                PURCHASING, id).contentType(MediaType.APPLICATION_JSON).with(IntegrationTestBase::xsrf)
                .content(json.write(Map.of("expectedVersion", current.path("version").asInt(), "enabled", false))));
        assertThat(paused.status()).isEqualTo(200);
        assertThat(paused.body().path("nextFireAt").isNull()).isTrue();
        assertThat(api("paul.publisher").delete("/workspaces/{ws}/schedules/{id}", PURCHASING, id).status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT action FROM audit_event WHERE resource_id = :id ORDER BY occurred_at")
                .param("id", id.toString()).query(String.class).list())
                .contains("schedule.created", "schedule.updated", "schedule.paused", "schedule.deleted");
    }

    @Test
    void aScheduleStartsOnlyInsideItsWorkingCalendarAndFailsVisiblyWhenItsOwnerMayNoLongerStart() throws Exception {
        UUID workflow = publishPurchase(PURCHASING, null);
        grantStart(workflow);
        String key = calendar();
        // Every hour, but only inside office hours (Mon-Fri 09-12, 13-17 London).
        UUID id = scheduleId(api("paul.publisher").post(schedule(workflow, "0 * * * *", Map.of("calendarKey", key)),
                "/workspaces/{ws}/schedules", PURCHASING));
        JsonNode view = api("paul.publisher").get("/workspaces/{ws}/schedules/{id}", PURCHASING, id).body();
        assertThat(view.path("calendarVersion").asInt()).isEqualTo(1);
        BusinessCalendar office = BusinessCalendar.read(json.mapper().valueToTree(OFFICE));
        for (JsonNode t : view.path("upcoming")) {
            assertThat(WorkingCalendars.working(office, Instant.parse(t.asString()))).as(t.asString()).isTrue();
        }
        // The owner loses the right to start: the start fails, is recorded and audited, and the schedule goes on.
        jdbc.sql("DELETE FROM permission_grant WHERE workspace_id = :ws AND workflow_id = :wf AND permission = 'workflow.start'")
                .param("ws", PURCHASING).param("wf", workflow).update();
        dueIn(id, Duration.ofSeconds(10));
        schedules.runDue(20);
        assertThat(fires(id)).containsExactly("failed");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'schedule.start-failed' AND resource_id = :id")
                .param("id", id.toString()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT next_fire_at IS NOT NULL FROM schedule_trigger WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void onlyTheLeaderRunsTheScheduler() {
        jdbc.sql("UPDATE scheduler_lease SET owner = 'other-pod', lease_until = now() + interval '1 minute'"
                + " WHERE name = 'schedules'").update();
        assertThat(org.springframework.test.util.ReflectionTestUtils.<Boolean>invokeMethod(schedules, "lead",
                Duration.ofSeconds(30))).isFalse();
        jdbc.sql("UPDATE scheduler_lease SET lease_until = now() - interval '1 second' WHERE name = 'schedules'").update();
        assertThat(org.springframework.test.util.ReflectionTestUtils.<Boolean>invokeMethod(schedules, "lead",
                Duration.ofSeconds(30))).isTrue();
        assertThat(org.springframework.test.util.ReflectionTestUtils.<Boolean>invokeMethod(schedules, "lead",
                Duration.ofSeconds(30))).as("the leader renews its own lease").isTrue();
    }
}
