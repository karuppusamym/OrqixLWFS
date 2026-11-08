package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.AdapterMessages;
import com.orqix.identity.Caller;
import com.orqix.integration.AdapterSteps;
import com.orqix.integration.EffectDispatcher;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reminders while waiting (docs/build/S139, CG-W06). A message wait, a callback, a check-until call or an outside
 * worker's job with {@code whileWaiting.remind} gets one {@code wait_reminder} row per visit, created and closed with
 * the "Waiting for" projection ({@link WaitStates#resync}, same transaction as the step change). The scheduler sends
 * each due reminder as one journaled adapter send: node {@code <step>__remind}, occurrence = the reminder's number, so
 * the effect id is stable and a retry, a restarted pod or a second scheduler never sends a second copy.
 * <p>
 * Recipients: the step's fixed addresses (checked against the adapter's allowlist at publish and again at every send),
 * or one request field (D-CG7) whose address must be on the adapter's approved domains <i>when each reminder is sent</i>;
 * an address outside them is refused and recorded, never sent. Texts are rendered at send time from the pinned release
 * and the request; nothing is kept in the row.
 */
@Service
public class WaitReminders {

    /** Wait kinds of the projection that can remind. */
    static final Set<String> KINDS = Set.of("message", "callback", "poll", "externalJob");
    static final int BATCH = 20;
    /** A send that ended without a recorded outcome is re-claimed after this lease; the journal then decides. */
    static final Duration LEASE = Duration.ofMinutes(2);
    /** Retryable failures (adapter busy) are retried after this, at most this many times in a row. */
    static final Duration RETRY = Duration.ofMinutes(15);
    static final int MAX_FAILURES = 5;

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})+)$");
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    public record Reminder(UUID id, UUID runId, String nodeId, Instant waitSince, String adapterKey, String status,
            int maxTimes, int sent, Instant nextAt, Instant lastSentAt, String lastOutcome, long everySeconds,
            int firstOrdinal, long fence, Instant endedAt, int failures) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final ReleaseQueries releases;
    private final RunStore runs;
    private final RunEvents events;
    private final AdapterSteps adapters;
    private final RunSuspensions suspensions;
    private final RunAccess runAccess;
    private final OrqixProperties properties;
    private final TransactionTemplate tx;

    public WaitReminders(JdbcClient jdbc, Json json, Clock clock, ReleaseQueries releases,
            @org.springframework.context.annotation.Lazy RunStore runs, RunEvents events, AdapterSteps adapters,
            RunSuspensions suspensions, @org.springframework.context.annotation.Lazy RunAccess runAccess,
            OrqixProperties properties, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.releases = releases;
        this.runs = runs;
        this.events = events;
        this.adapters = adapters;
        this.suspensions = suspensions;
        this.runAccess = runAccess;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ projection (same transaction as the step)

    /**
     * The open waits of a request changed: reminders start for new waits whose step reminds, and stop for waits that
     * ended (the step moved on, the request ended or was cancelled).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void sync(UUID workspaceId, UUID runId, Map<String, WaitStates.WaitRow> waits) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Reminder> open = jdbc.sql("SELECT * FROM wait_reminder WHERE workspace_id = :ws AND run_id = :run"
                + " AND status IN ('waiting', 'sending')").param("ws", workspaceId).param("run", runId)
                .query(WaitReminders::row).list();
        for (Reminder r : open) {
            WaitStates.WaitRow w = waits.get(r.nodeId());
            if (w == null || !w.since().truncatedTo(ChronoUnit.MICROS).equals(r.waitSince())) {
                jdbc.sql("UPDATE wait_reminder SET status = 'stopped', ended_at = :now, lease_owner = NULL,"
                        + " lease_until = NULL WHERE id = :id").param("now", Sql.ts(now)).param("id", r.id()).update();
            }
        }
        List<WaitStates.WaitRow> candidates = waits.values().stream().filter(w -> KINDS.contains(w.kind())).toList();
        if (candidates.isEmpty()) {
            return;
        }
        UUID releaseId = jdbc.sql("SELECT release_id FROM workflow_run WHERE workspace_id = :ws AND id = :run")
                .param("ws", workspaceId).param("run", runId).query(UUID.class).optional().orElse(null);
        if (releaseId == null) {
            return;
        }
        ReleaseQueries.ReleaseView release = releases.require(workspaceId, releaseId);
        for (WaitStates.WaitRow w : candidates) {
            JsonNode node = release.node(w.nodeId());
            JsonNode remind = node == null ? null : node.path("config").path("whileWaiting").path("remind");
            if (remind == null || !remind.isObject()) {
                continue;
            }
            long every = Duration.parse(remind.path("every").asString()).getSeconds();
            int max = remind.path("maxTimes").asInt(3);
            Instant since = w.since().truncatedTo(ChronoUnit.MICROS);
            int used = jdbc.sql("SELECT COALESCE(MAX(first_ordinal + max_times), 1) FROM wait_reminder"
                    + " WHERE run_id = :run AND node_id = :node").param("run", runId).param("node", w.nodeId())
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO wait_reminder (id, workspace_id, run_id, node_id, wait_since, adapter_key, status,
                                               every_seconds, max_times, first_ordinal, next_at, created_at)
                    VALUES (:id, :ws, :run, :node, :since, :adapter, 'waiting', :every, :max, :first, :next, :now)
                    ON CONFLICT (run_id, node_id, wait_since) DO NOTHING
                    """).param("id", Ids.newId()).param("ws", workspaceId).param("run", runId)
                    .param("node", w.nodeId()).param("since", Sql.ts(since))
                    .param("adapter", remind.path("adapter").asString()).param("every", every).param("max", max)
                    .param("first", used).param("next", Sql.ts(since.plusSeconds(every))).param("now", Sql.ts(now))
                    .update();
        }
    }

    // ------------------------------------------------------------------ scheduler

    private record Claim(Reminder reminder, long fence) {
    }

    /** @return how many due reminders were handled */
    public int runDue() {
        List<Claim> claims = tx.execute(status -> claim());
        if (claims == null) {
            return 0;
        }
        for (Claim c : claims) {
            try {
                send(c);
            } catch (RuntimeException e) {
                tx.executeWithoutResult(status -> release(c, "The reminder could not be sent: "
                        + e.getClass().getSimpleName()));
            }
        }
        return claims.size();
    }

    private List<Claim> claim() {
        Instant now = clock.instant();
        List<Reminder> due = jdbc.sql("""
                SELECT w.* FROM wait_reminder w
                JOIN workflow_run r ON r.workspace_id = w.workspace_id AND r.id = w.run_id
                WHERE ((w.status = 'waiting' AND w.next_at <= :now) OR (w.status = 'sending' AND w.lease_until < :now))
                  AND r.lifecycle IN ('running', 'waiting', 'needsAttention')
                  AND NOT EXISTS (SELECT 1 FROM run_suspension s WHERE s.workspace_id = w.workspace_id
                                  AND s.run_id = w.run_id)
                ORDER BY w.next_at
                LIMIT :limit
                FOR UPDATE OF w SKIP LOCKED
                """).param("now", Sql.ts(now)).param("limit", BATCH).query(WaitReminders::row).list();
        List<Claim> claims = new ArrayList<>();
        for (Reminder r : due) {
            jdbc.sql("UPDATE wait_reminder SET status = 'sending', lease_owner = :owner, lease_until = :until,"
                    + " fence = fence + 1 WHERE id = :id").param("owner", properties.runtime().lockOwner())
                    .param("until", Sql.ts(now.plus(LEASE))).param("id", r.id()).update();
            claims.add(new Claim(r, r.fence() + 1));
        }
        return claims;
    }

    private void send(Claim c) {
        Reminder r = c.reminder();
        RunStore.RunRow run = runs.find(workspaceOf(r), r.runId()).orElse(null);
        WaitStates.WaitRow wait = run == null ? null : WaitStates.fromStep(run.id(), stepOf(run, r.nodeId()));
        if (run == null || !run.isActive() || wait == null
                || !wait.since().truncatedTo(ChronoUnit.MICROS).equals(r.waitSince())) {
            tx.executeWithoutResult(status -> end(c, "stopped", "The wait has ended.", null));
            return;
        }
        ReleaseQueries.ReleaseView release = releases.require(run.workspaceId(), run.releaseId());
        JsonNode remind = release.node(r.nodeId()).path("config").path("whileWaiting").path("remind");
        AdapterSteps.Fact adapter = adapters.facts(run.workspaceId()).stream()
                .filter(f -> f.key().equals(r.adapterKey())).findFirst().orElse(null);
        int count = r.sent() + 1;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("reference", run.reference());
        values.put("workflow", release.workflowName());
        values.put("step", wait.label());
        values.put("since", SINCE.format(r.waitSince()));
        values.put("count", Integer.toString(count));
        values.put("maxTimes", Integer.toString(r.maxTimes()));
        remind.path("inputMapping").properties().forEach(e -> {
            String key = e.getValue().asString().substring("/input/".length());
            JsonNode v = run.input().path(key);
            values.put(e.getKey(), v.isMissingNode() || v.isNull() ? "" : v.isString() ? v.asString() : v.toString());
        });
        String kind = adapter == null ? "email" : adapter.kind();
        ObjectNode payload = json.object();
        Map<String, String> line = new LinkedHashMap<>();
        values.forEach((k, v) -> line.put(k, v.replace('\r', ' ').replace('\n', ' ')));
        JsonNode template = remind.path("template");
        if ("teams".equals(kind)) {
            payload.put("text", AdapterMessages.render(template.path("text").asString(""), values));
        } else {
            var to = payload.putArray("to");
            remind.path("to").forEach(a -> to.add(a.asString()));
            if (remind.hasNonNull("toField")) {
                // D-CG7: the address comes from the request and must be on the adapter's approved domains now.
                String address = run.input().path(remind.path("toField").asString().substring("/input/".length()))
                        .asString("").strip();
                String refusal = refusal(address, adapter);
                if (refusal != null) {
                    tx.executeWithoutResult(status -> end(c, "refused", refusal, run));
                    return;
                }
                to.add(address);
            }
            payload.put("subject", AdapterMessages.render(template.path("subject").asString(""), line));
            payload.put("body", AdapterMessages.render(template.path("body").asString(""), values));
        }
        int occurrence = r.firstOrdinal() + r.sent();
        EffectDispatcher.DispatchResult result = adapters.send(new AdapterSteps.Send(run.workspaceId(), run.id(),
                r.nodeId() + "__remind", occurrence, r.adapterKey(), kind, payload, properties.runtime().lockOwner()));
        tx.executeWithoutResult(status -> outcome(c, run, wait.label(), count, occurrence, result));
    }

    /** Reads adapter configuration (already validated when the adapter was saved). */
    private static final tools.jackson.databind.json.JsonMapper CONFIG_READER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** @return why an address from the request may not be used, or null when the adapter approves its domain */
    static String refusal(String address, AdapterSteps.Fact adapter) {
        Matcher m = EMAIL.matcher(address == null ? "" : address);
        if (!m.matches()) {
            return "The request has no usable email address for the reminder.";
        }
        if (adapter == null) {
            return "The adapter no longer exists.";
        }
        Set<String> approved = new java.util.HashSet<>();
        String config = adapter.configJson() == null || adapter.configJson().isBlank() ? "{}" : adapter.configJson();
        CONFIG_READER.readTree(config).path("allowedRecipientDomains")
                .forEach(d -> approved.add(d.asString().toLowerCase(Locale.ROOT)));
        String domain = m.group(1).toLowerCase(Locale.ROOT);
        return approved.contains(domain) ? null : "The address's domain " + domain + " is not approved by the adapter.";
    }

    private void outcome(Claim c, RunStore.RunRow run, String label, int count, int occurrence,
            EffectDispatcher.DispatchResult result) {
        Reminder r = c.reminder();
        if (!fenced(c)) {
            return; // another scheduler took it over; the journal kept the send single
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("count", count);
        data.put("maxTimes", r.maxTimes());
        data.put("adapterKey", r.adapterKey());
        if (result instanceof EffectDispatcher.Succeeded success) {
            boolean last = count >= r.maxTimes();
            jdbc.sql("""
                    UPDATE wait_reminder SET sent = sent + 1, failures = 0, last_sent_at = :now, next_at = :next,
                        status = :status, last_outcome = :outcome, lease_owner = NULL, lease_until = NULL,
                        ended_at = CASE WHEN :last THEN CAST(:now AS timestamptz) ELSE NULL END
                    WHERE id = :id
                    """).param("now", Sql.ts(now)).param("next", Sql.ts(now.plusSeconds(r.everySeconds())))
                    .param("status", last ? "done" : "waiting").param("outcome", "sent")
                    .param("last", last).param("id", r.id()).update();
            data.put("occurrence", occurrence);
            if (success.reused()) {
                data.put("reusedRecordedResult", true);
            }
            events.append(run.workspaceId(), run.id(), "REMINDER_SENT", r.nodeId(), null, data);
            return;
        }
        EffectDispatcher.Failed failed = (EffectDispatcher.Failed) result;
        if (failed.retryable() && r.failures() + 1 < MAX_FAILURES) {
            jdbc.sql("""
                    UPDATE wait_reminder SET failures = failures + 1, next_at = :next, status = 'waiting',
                        last_outcome = :outcome, lease_owner = NULL, lease_until = NULL WHERE id = :id
                    """).param("next", Sql.ts(now.plus(RETRY))).param("outcome", cut(failed.businessMessage()))
                    .param("id", r.id()).update();
            return;
        }
        end(c, "failed", failed.businessMessage(), run);
    }

    private void end(Claim c, String status, String why, RunStore.RunRow run) {
        if (!fenced(c)) {
            return;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("UPDATE wait_reminder SET status = :status, last_outcome = :why, ended_at = :now, lease_owner = NULL,"
                + " lease_until = NULL WHERE id = :id").param("status", status).param("why", cut(why))
                .param("now", Sql.ts(now)).param("id", c.reminder().id()).update();
        if (run != null && !"stopped".equals(status)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("adapterKey", c.reminder().adapterKey());
            data.put("reason", cut(why));
            events.append(run.workspaceId(), run.id(), "refused".equals(status) ? "REMINDER_REFUSED" : "REMINDER_FAILED",
                    c.reminder().nodeId(), null, data);
        }
    }

    private void release(Claim c, String why) {
        jdbc.sql("UPDATE wait_reminder SET status = 'waiting', next_at = :next, last_outcome = :why, lease_owner = NULL,"
                + " lease_until = NULL WHERE id = :id AND fence = :fence AND status = 'sending'")
                .param("next", Sql.ts(clock.instant().plus(RETRY))).param("why", cut(why)).param("id", c.reminder().id())
                .param("fence", c.fence()).update();
    }

    private boolean fenced(Claim c) {
        return jdbc.sql("SELECT 1 FROM wait_reminder WHERE id = :id AND fence = :fence AND status = 'sending' FOR UPDATE")
                .param("id", c.reminder().id()).param("fence", c.fence()).query(Integer.class).optional().isPresent();
    }

    private UUID workspaceOf(Reminder r) {
        return jdbc.sql("SELECT workspace_id FROM wait_reminder WHERE id = :id").param("id", r.id())
                .query(UUID.class).single();
    }

    private static JsonNode stepOf(RunStore.RunRow run, String nodeId) {
        for (JsonNode step : run.currentSteps()) {
            if (nodeId.equals(step.path("nodeId").asString(null))) {
                return step;
            }
        }
        return tools.jackson.databind.node.MissingNode.getInstance();
    }

    private static String cut(String text) {
        return text == null || text.length() <= 300 ? text : text.substring(0, 300);
    }

    // ------------------------------------------------------------------ reads

    /** The reminders of a request, readable by whoever may read the request (S139 run page). */
    @Transactional(readOnly = true)
    public List<Reminder> forRun(Caller caller, UUID workspaceId, UUID runId) {
        runAccess.require(caller, workspaceId, runId);
        return jdbc.sql("SELECT * FROM wait_reminder WHERE workspace_id = :ws AND run_id = :run ORDER BY created_at, id")
                .param("ws", workspaceId).param("run", runId).query(WaitReminders::row).list();
    }

    private static Reminder row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Reminder(Sql.uuid(rs, "id"), Sql.uuid(rs, "run_id"), rs.getString("node_id"),
                Sql.instant(rs, "wait_since"), rs.getString("adapter_key"), rs.getString("status"),
                rs.getInt("max_times"), rs.getInt("sent"), Sql.instant(rs, "next_at"), Sql.instant(rs, "last_sent_at"),
                rs.getString("last_outcome"), rs.getLong("every_seconds"), rs.getInt("first_ordinal"),
                rs.getLong("fence"), Sql.instant(rs, "ended_at"), rs.getInt("failures"));
    }
}
