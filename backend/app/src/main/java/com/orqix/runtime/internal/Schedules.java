package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.BusinessCalendar;
import com.orqix.compiler.FormValidator;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.Ids;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * Scheduled starts (docs/build/S139, CG-E02). A schedule starts the active release of one workflow on a cron or RRULE in
 * a time zone, with a fixed input, as the person who saved it (their start permission is checked again at every start,
 * like a Kafka trigger's run-as person). Optionally only inside a pinned working calendar's hours: an occurrence outside
 * them is skipped, not moved.
 * <p>
 * <b>Firing.</b> One pod at a time is the scheduler (lease in {@code scheduler_lease}, renewed every tick). For each due
 * schedule it locks the row (SKIP LOCKED), decides what happens to the planned time, records it in {@code schedule_fire}
 * (unique per schedule and time) and starts the request in its own transaction with the idempotency key
 * {@code schedule-<id>-<epoch>}, so a changed leader or a retry never starts a second request for the same time.
 * <p>
 * <b>Missed runs</b> (the planned time is more than {@link #GRACE} in the past, for example after an outage):
 * {@code catchUpOnce} starts one request now for all the missed times; {@code skip} records them as skipped and waits
 * for the next time. <b>Overlap</b>: {@code skip} records a planned time as skipped while the previous request of the
 * schedule is still open; {@code allow} starts anyway.
 */
@Service
public class Schedules {

    private static final Logger LOG = LoggerFactory.getLogger(Schedules.class);

    /** A planned time this far in the past counts as missed. */
    static final Duration GRACE = Duration.ofMinutes(2);
    /** Occurrences closer than this are refused (at most one start every five minutes). */
    static final Duration MIN_SPACING = Duration.ofMinutes(5);
    static final int MAX_SCHEDULES = 200;
    static final int MAX_INPUT_BYTES = 16 * 1024;
    static final String LEASE = "schedules";

    public record Fire(Instant fireAt, Instant firedAt, String outcome, String reason, int missed, UUID runId) {
    }

    public record Schedule(UUID id, UUID workflowId, String workflowName, String name, String kind, String expression,
            String zone, Instant startsAt, Instant endsAt, String calendarKey, Integer calendarVersion, JsonNode input,
            String missedRunPolicy, String overlapPolicy, boolean enabled, UUID runAs, String runAsName,
            Instant nextFireAt, Instant lastFireAt, String lastOutcome, UUID lastRunId, int version, Instant createdAt,
            Instant updatedAt, List<Instant> upcoming, List<Fire> recent) {
    }

    /** What a person sets; null members of an update keep the stored value. */
    public record Settings(UUID workflowId, String name, String kind, String expression, String zone, Instant startsAt,
            Instant endsAt, String calendarKey, JsonNode input, String missedRunPolicy, String overlapPolicy,
            Boolean enabled) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final ReleaseQueries releases;
    private final FormValidator forms;
    private final SubjectService subjects;
    private final RunService runs;
    private final RunStore runStore;
    private final WorkingCalendars calendars;
    private final AuditWriter audit;
    private final OrqixProperties properties;
    private final TransactionTemplate tx;
    private final TransactionTemplate startTx;

    public Schedules(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, ReleaseQueries releases,
            FormValidator forms, SubjectService subjects, @org.springframework.context.annotation.Lazy RunService runs,
            RunStore runStore, WorkingCalendars calendars, AuditWriter audit, OrqixProperties properties,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.releases = releases;
        this.forms = forms;
        this.subjects = subjects;
        this.runs = runs;
        this.runStore = runStore;
        this.calendars = calendars;
        this.audit = audit;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.startTx = new TransactionTemplate(transactions);
        this.startTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ API

    @Transactional(readOnly = true)
    public List<Schedule> list(Caller caller, UUID ws, UUID workflowId) {
        reader(caller, ws, "listSchedules");
        boolean data = policy.requireVisible(caller, ws).has(Permission.RUN_READ_DATA)
                || policy.requireVisible(caller, ws).has(Permission.WORKFLOW_PUBLISH);
        return jdbc.sql("SELECT s.*, w.name AS workflow_name, p.display_name AS run_as_name FROM schedule_trigger s"
                + " JOIN workflow w ON w.workspace_id = s.workspace_id AND w.id = s.workflow_id"
                + " JOIN subject p ON p.id = s.run_as WHERE s.workspace_id = :ws"
                + (workflowId == null ? "" : " AND s.workflow_id = :wf") + " ORDER BY lower(s.name), s.id")
                .param("ws", ws).param("wf", workflowId)
                .query((rs, n) -> map(rs, data, List.of(), List.of())).list();
    }

    @Transactional(readOnly = true)
    public Schedule get(Caller caller, UUID ws, UUID id) {
        reader(caller, ws, "getSchedule");
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        return view(ws, id, access.has(Permission.RUN_READ_DATA) || access.has(Permission.WORKFLOW_PUBLISH))
                .orElseThrow(ApiException::notFound);
    }

    /** The next times of an expression, before saving it. */
    @Transactional(readOnly = true)
    public List<Instant> preview(Caller caller, UUID ws, Settings s) {
        reader(caller, ws, "previewSchedule");
        List<ApiException.FieldError> errors = new ArrayList<>();
        Instant start = s.startsAt() == null ? clock.instant() : s.startsAt();
        Recurrence recurrence = recurrence(s.kind(), s.expression(), s.zone(), start, errors);
        BusinessCalendar calendar = null;
        if (s.calendarKey() != null) {
            Integer version = latestCalendar(ws, s.calendarKey(), errors);
            calendar = version == null ? null : calendars.pinned(ws, s.calendarKey(), version);
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return upcoming(recurrence, calendar, s.endsAt(), clock.instant(), 10);
    }

    @Transactional
    public Schedule create(Caller caller, UUID ws, Settings s) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_PUBLISH, "createSchedule", true);
        List<ApiException.FieldError> errors = new ArrayList<>();
        ReleaseQueries.WorkflowRef workflow = s.workflowId() == null ? null
                : releases.workflow(ws, s.workflowId()).orElse(null);
        if (workflow == null) {
            errors.add(new ApiException.FieldError("/workflowId", "invalid", "Choose a workflow of this workspace."));
        } else if (!access.canStart(workflow.id())) {
            throw policy.deny(ws, caller.subjectId(), "workflow.start", "createSchedule", true,
                    new ApiException(ProblemCode.FORBIDDEN, "A schedule starts requests as you, and you may not start"
                            + " this workflow."));
        }
        Checked c = check(ws, s, null, workflow, errors);
        int count = jdbc.sql("SELECT count(*) FROM schedule_trigger WHERE workspace_id = :ws").param("ws", ws)
                .query(Integer.class).single();
        if (count >= MAX_SCHEDULES) {
            errors.add(new ApiException.FieldError("", "maxItems", "A workspace can have at most " + MAX_SCHEDULES
                    + " schedules."));
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID id = Ids.newId();
        boolean enabled = s.enabled() == null || s.enabled();
        Instant next = enabled ? firstWorking(c.recurrence(), c.calendar(), c.endsAt(), now) : null;
        jdbc.sql("""
                INSERT INTO schedule_trigger (id, workspace_id, workflow_id, name, kind, expression, zone, starts_at,
                    ends_at, calendar_key, calendar_version, input, missed_policy, overlap_policy, enabled, run_as,
                    next_fire_at, created_by, created_at, updated_at)
                VALUES (:id, :ws, :wf, :name, :kind, :expr, :zone, :start, :end, :ckey, :cver, CAST(:input AS jsonb),
                    :missed, :overlap, :enabled, :by, :next, :by, :now, :now)
                """).param("id", id).param("ws", ws).param("wf", workflow.id()).param("name", c.name())
                .param("kind", c.kind()).param("expr", c.expression()).param("zone", c.zone())
                .param("start", Sql.ts(c.startsAt())).param("end", Sql.ts(c.endsAt())).param("ckey", c.calendarKey())
                .param("cver", c.calendarVersion()).param("input", json.write(c.input())).param("missed", c.missed())
                .param("overlap", c.overlap()).param("enabled", enabled).param("by", caller.subjectId())
                .param("next", Sql.ts(next)).param("now", Sql.ts(now)).update();
        audit(caller, ws, "schedule.created", id, Map.of("workflowId", workflow.id().toString(), "kind", c.kind(),
                "enabled", enabled));
        return view(ws, id, true).orElseThrow();
    }

    @Transactional
    public Schedule update(Caller caller, UUID ws, UUID id, Integer expectedVersion, Settings s) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_PUBLISH, "updateSchedule", true);
        Schedule current = lockView(ws, id).orElseThrow(ApiException::notFound);
        if (expectedVersion == null) {
            throw invalid(List.of(new ApiException.FieldError("/expectedVersion", "required", "Say which version you"
                    + " changed.")));
        }
        if (expectedVersion != current.version()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The schedule changed since you loaded"
                    + " it.");
        }
        if (s.workflowId() != null && !s.workflowId().equals(current.workflowId())) {
            throw invalid(List.of(new ApiException.FieldError("/workflowId", "invalid", "A schedule keeps its"
                    + " workflow; create a new schedule for another one.")));
        }
        if (!access.canStart(current.workflowId())) {
            throw policy.deny(ws, caller.subjectId(), "workflow.start", "updateSchedule", true,
                    new ApiException(ProblemCode.FORBIDDEN, "A schedule starts requests as you, and you may not start"
                            + " this workflow."));
        }
        Settings merged = new Settings(current.workflowId(), or(s.name(), current.name()), or(s.kind(), current.kind()),
                or(s.expression(), current.expression()), or(s.zone(), current.zone()),
                s.startsAt() == null ? current.startsAt() : s.startsAt(),
                s.endsAt() == null ? current.endsAt() : s.endsAt(),
                s.calendarKey() == null ? current.calendarKey() : s.calendarKey().isEmpty() ? null : s.calendarKey(),
                s.input() == null ? current.input() : s.input(), or(s.missedRunPolicy(), current.missedRunPolicy()),
                or(s.overlapPolicy(), current.overlapPolicy()), s.enabled() == null ? current.enabled() : s.enabled());
        List<ApiException.FieldError> errors = new ArrayList<>();
        Checked c = check(ws, merged, current, releases.workflow(ws, current.workflowId()).orElse(null), errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean enabled = merged.enabled();
        Instant next = enabled ? firstWorking(c.recurrence(), c.calendar(), c.endsAt(), now) : null;
        jdbc.sql("""
                UPDATE schedule_trigger SET name = :name, kind = :kind, expression = :expr, zone = :zone,
                    starts_at = :start, ends_at = :end, calendar_key = :ckey, calendar_version = :cver,
                    input = CAST(:input AS jsonb), missed_policy = :missed, overlap_policy = :overlap,
                    enabled = :enabled, run_as = :by, next_fire_at = :next, version = version + 1, updated_at = :now
                WHERE workspace_id = :ws AND id = :id
                """).param("name", c.name()).param("kind", c.kind()).param("expr", c.expression())
                .param("zone", c.zone()).param("start", Sql.ts(c.startsAt())).param("end", Sql.ts(c.endsAt()))
                .param("ckey", c.calendarKey()).param("cver", c.calendarVersion()).param("input", json.write(c.input()))
                .param("missed", c.missed()).param("overlap", c.overlap()).param("enabled", enabled)
                .param("by", caller.subjectId()).param("next", Sql.ts(next)).param("now", Sql.ts(now))
                .param("ws", ws).param("id", id).update();
        audit(caller, ws, enabled == current.enabled() ? "schedule.updated" : enabled ? "schedule.resumed"
                : "schedule.paused", id, Map.of("workflowId", current.workflowId().toString(), "kind", c.kind(),
                "enabled", enabled));
        return view(ws, id, true).orElseThrow();
    }

    @Transactional
    public void delete(Caller caller, UUID ws, UUID id) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_PUBLISH, "deleteSchedule", true);
        Schedule current = lockView(ws, id).orElseThrow(ApiException::notFound);
        jdbc.sql("DELETE FROM schedule_trigger WHERE workspace_id = :ws AND id = :id").param("ws", ws).param("id", id)
                .update();
        audit(caller, ws, "schedule.deleted", id, Map.of("workflowId", current.workflowId().toString(),
                "kind", current.kind(), "enabled", current.enabled()));
    }

    // ------------------------------------------------------------------ checks

    private record Checked(String name, String kind, String expression, String zone, Instant startsAt, Instant endsAt,
            String calendarKey, Integer calendarVersion, BusinessCalendar calendar, JsonNode input, String missed,
            String overlap, Recurrence recurrence) {
    }

    private Checked check(UUID ws, Settings s, Schedule current, ReleaseQueries.WorkflowRef workflow,
            List<ApiException.FieldError> errors) {
        String name = s.name() == null ? null : s.name().strip();
        if (name == null || name.isEmpty() || name.length() > 80) {
            errors.add(new ApiException.FieldError("/name", "required", "Give the schedule a name of 1 to 80"
                    + " characters."));
        }
        String expression = s.expression() == null ? "" : s.expression().strip();
        if (expression.isEmpty() || expression.length() > 300) {
            errors.add(new ApiException.FieldError("/expression", "required", "Say when it runs (1 to 300 characters)."));
        }
        Instant start = s.startsAt() != null ? s.startsAt().truncatedTo(ChronoUnit.MINUTES)
                : current != null ? current.startsAt() : clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Recurrence recurrence = expression.isEmpty() ? null : recurrence(s.kind(), expression, s.zone(), start, errors);
        if (s.endsAt() != null && !s.endsAt().isAfter(start)) {
            errors.add(new ApiException.FieldError("/endsAt", "invalid", "The end must be after the start."));
        }
        String missed = s.missedRunPolicy() == null ? "catchUpOnce" : s.missedRunPolicy();
        if (!List.of("catchUpOnce", "skip").contains(missed)) {
            errors.add(new ApiException.FieldError("/missedRunPolicy", "enum", "Choose catchUpOnce or skip."));
        }
        String overlap = s.overlapPolicy() == null ? "skip" : s.overlapPolicy();
        if (!List.of("allow", "skip").contains(overlap)) {
            errors.add(new ApiException.FieldError("/overlapPolicy", "enum", "Choose allow or skip."));
        }
        Integer calendarVersion = null;
        BusinessCalendar calendar = null;
        if (s.calendarKey() != null) {
            calendarVersion = current != null && s.calendarKey().equals(current.calendarKey())
                    ? current.calendarVersion() : latestCalendar(ws, s.calendarKey(), errors);
            calendar = calendarVersion == null ? null : calendars.pinned(ws, s.calendarKey(), calendarVersion);
        }
        JsonNode input = s.input() == null ? json.object() : s.input();
        if (!input.isObject()) {
            errors.add(new ApiException.FieldError("/input", "type", "The input is an object of request values."));
        } else if (json.write(input).length() > MAX_INPUT_BYTES) {
            errors.add(new ApiException.FieldError("/input", "maxLength", "The input is larger than 16 KiB."));
        } else if (workflow != null && workflow.activeReleaseId() != null) {
            ReleaseQueries.ReleaseView release = releases.require(ws, workflow.activeReleaseId());
            for (FormValidator.FieldError e : forms.validate(json.write(release.inputSchema()), json.write(input),
                    json.write(release.inputUi()))) {
                errors.add(new ApiException.FieldError("/input" + e.pointer(), e.code(), e.message()));
            }
        }
        if (recurrence != null && errors.isEmpty()) {
            List<Instant> next = upcoming(recurrence, calendar, s.endsAt(), clock.instant(), 3);
            for (int i = 1; i < next.size(); i++) {
                if (Duration.between(next.get(i - 1), next.get(i)).compareTo(MIN_SPACING) < 0) {
                    errors.add(new ApiException.FieldError("/expression", "invalid", "A schedule starts at most once"
                            + " every 5 minutes."));
                    break;
                }
            }
        }
        return new Checked(name, s.kind(), expression, s.zone(), start, s.endsAt(), s.calendarKey(), calendarVersion,
                calendar, input, missed, overlap, recurrence);
    }

    private Recurrence recurrence(String kind, String expression, String zone, Instant start,
            List<ApiException.FieldError> errors) {
        if (kind == null || !List.of("cron", "rrule").contains(kind)) {
            errors.add(new ApiException.FieldError("/kind", "enum", "Choose cron or rrule."));
            return null;
        }
        try {
            return Recurrence.parse(kind, expression == null ? "" : expression, zone == null ? "" : zone, start);
        } catch (IllegalArgumentException e) {
            errors.add(new ApiException.FieldError(e.getMessage().startsWith("Use a time zone") ? "/zone" : "/expression",
                    "invalid", e.getMessage()));
            return null;
        }
    }

    private Integer latestCalendar(UUID ws, String key, List<ApiException.FieldError> errors) {
        Optional<Integer> latest = jdbc.sql("SELECT latest_version FROM working_calendar WHERE workspace_id = :ws"
                + " AND key = :key AND archived_at IS NULL").param("ws", ws).param("key", key).query(Integer.class)
                .optional();
        if (latest.isEmpty()) {
            errors.add(new ApiException.FieldError("/calendarKey", "invalid", "There is no working calendar " + key
                    + " in this workspace (or it is archived)."));
            return null;
        }
        return latest.get();
    }

    // ------------------------------------------------------------------ timing

    /** @return the next occurrence after {@code after} inside the calendar's hours (when set) and before the end */
    static Instant firstWorking(Recurrence recurrence, BusinessCalendar calendar, Instant endsAt, Instant after) {
        Instant cursor = after;
        for (int i = 0; i < 2_000; i++) {
            Instant next = recurrence.next(cursor);
            if (next == null || endsAt != null && next.isAfter(endsAt)) {
                return null;
            }
            if (calendar == null || WorkingCalendars.working(calendar, next)) {
                return next;
            }
            cursor = next;
        }
        return null;
    }

    static List<Instant> upcoming(Recurrence recurrence, BusinessCalendar calendar, Instant endsAt, Instant after,
            int count) {
        List<Instant> out = new ArrayList<>();
        Instant cursor = after;
        while (out.size() < count) {
            Instant next = firstWorking(recurrence, calendar, endsAt, cursor);
            if (next == null) {
                break;
            }
            out.add(next);
            cursor = next;
        }
        return out;
    }

    // ------------------------------------------------------------------ scheduler

    /** @return true when this pod holds (or took) the scheduler lease */
    boolean lead(Duration lease) {
        Instant now = clock.instant();
        Integer won = tx.execute(status -> jdbc.sql("""
                UPDATE scheduler_lease SET owner = :me, lease_until = :until, fence = fence + 1
                WHERE name = :name AND (owner = :me OR lease_until IS NULL OR lease_until < :now)
                """).param("me", properties.runtime().lockOwner()).param("until", Sql.ts(now.plus(lease)))
                .param("name", LEASE).param("now", Sql.ts(now)).update());
        return won != null && won == 1;
    }

    /** @return how many due schedules were handled */
    public int runDue(int limit) {
        Instant now = clock.instant();
        List<UUID> due = jdbc.sql("SELECT id FROM schedule_trigger WHERE enabled AND next_fire_at <= :now"
                + " ORDER BY next_fire_at, id LIMIT :limit").param("now", Sql.ts(now)).param("limit", limit)
                .query(UUID.class).list();
        int handled = 0;
        for (UUID id : due) {
            try {
                Boolean done = tx.execute(status -> fire(id));
                handled += Boolean.TRUE.equals(done) ? 1 : 0;
            } catch (RuntimeException e) {
                LOG.warn("Schedule {} could not fire; it is tried again ({})", id, e.getClass().getSimpleName());
            }
        }
        return handled;
    }

    private record Row(UUID id, UUID workspaceId, UUID workflowId, String kind, String expression, String zone,
            Instant startsAt, Instant endsAt, String calendarKey, Integer calendarVersion, JsonNode input,
            String missed, String overlap, UUID runAs, Instant nextFireAt, UUID lastRunId) {
    }

    private boolean fire(UUID id) {
        Row s = jdbc.sql("SELECT * FROM schedule_trigger WHERE id = :id AND enabled AND next_fire_at <= :now"
                + " FOR UPDATE SKIP LOCKED").param("id", id).param("now", Sql.ts(clock.instant()))
                .query((rs, n) -> new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), Sql.uuid(rs, "workflow_id"),
                        rs.getString("kind"), rs.getString("expression"), rs.getString("zone"),
                        Sql.instant(rs, "starts_at"), Sql.instant(rs, "ends_at"), rs.getString("calendar_key"),
                        (Integer) rs.getObject("calendar_version"), json.parse(rs.getString("input")),
                        rs.getString("missed_policy"), rs.getString("overlap_policy"), Sql.uuid(rs, "run_as"),
                        Sql.instant(rs, "next_fire_at"), (UUID) rs.getObject("last_run_id"))).optional().orElse(null);
        if (s == null) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Recurrence recurrence = Recurrence.parse(s.kind(), s.expression(), s.zone(), s.startsAt());
        BusinessCalendar calendar = s.calendarKey() == null ? null
                : calendars.pinned(s.workspaceId(), s.calendarKey(), s.calendarVersion());
        Instant planned = s.nextFireAt();
        boolean late = Duration.between(planned, now).compareTo(GRACE) > 0;
        int missed = 0;
        if (late) {
            for (Instant t = planned; t != null && t.isBefore(now) && missed < 1_000;
                    t = firstWorking(recurrence, calendar, s.endsAt(), t)) {
                missed++;
            }
        }
        Instant next = firstWorking(recurrence, calendar, s.endsAt(), late ? now : planned);
        if (next != null && !next.isAfter(now)) {
            next = firstWorking(recurrence, calendar, s.endsAt(), now);
        }
        String outcome;
        String reason = null;
        UUID runId = null;
        if (late && "skip".equals(s.missed())) {
            outcome = "skipped";
            reason = missed == 1 ? "The planned start was missed while the scheduler was not running."
                    : missed + " planned starts were missed while the scheduler was not running.";
        } else if ("skip".equals(s.overlap()) && s.lastRunId() != null && runStore.find(s.workspaceId(), s.lastRunId())
                .map(RunStore.RunRow::isActive).orElse(false)) {
            outcome = "skipped";
            reason = "The previous request of this schedule is still open.";
        } else {
            try {
                runId = start(s, planned);
                outcome = "started";
                if (late) {
                    reason = missed == 1 ? "Started late: the planned start was missed."
                            : "Started once for " + missed + " missed planned starts.";
                }
            } catch (ApiException e) {
                outcome = "failed";
                reason = cut(e.code().name() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                outcome = "failed";
                reason = "The request could not be started (" + e.getClass().getSimpleName() + ").";
            }
        }
        int inserted = jdbc.sql("""
                INSERT INTO schedule_fire (id, workspace_id, schedule_id, fire_at, fired_at, outcome, reason, missed, run_id)
                VALUES (:id, :ws, :schedule, :at, :now, :outcome, :reason, :missed, :run)
                ON CONFLICT (schedule_id, fire_at) DO NOTHING
                """).param("id", Ids.newId()).param("ws", s.workspaceId()).param("schedule", s.id())
                .param("at", Sql.ts(planned)).param("now", Sql.ts(now)).param("outcome", outcome).param("reason", reason)
                .param("missed", Math.max(0, missed - 1)).param("run", runId).update();
        jdbc.sql("""
                UPDATE schedule_trigger SET next_fire_at = :next, last_fire_at = :at, last_outcome = :outcome,
                    last_run_id = COALESCE(:run, last_run_id) WHERE id = :id
                """).param("next", Sql.ts(next)).param("at", Sql.ts(planned)).param("outcome", outcome)
                .param("run", runId).param("id", s.id()).update();
        if ("failed".equals(outcome) && inserted == 1) {
            audit.write(new AuditWriter.Entry(s.workspaceId(), AuditWriter.ActorType.SYSTEM, null,
                    "schedule.start-failed", "schedule", s.id().toString(), AuditWriter.Outcome.FAILED,
                    Map.of("workflowId", s.workflowId().toString(), "fireAt", planned.toString())));
        }
        return true;
    }

    /** Starts the request in its own transaction, as the schedule's run-as person, once per planned time. */
    private UUID start(Row s, Instant planned) {
        SubjectService.Subject subject = subjects.find(s.runAs()).orElse(null);
        if (subject == null || subject.disabled()) {
            throw new ApiException(ProblemCode.FORBIDDEN, "The person this schedule runs as is no longer active.");
        }
        Caller caller = new Caller(subject.id(), subject.displayName(), subject.email(), subject.issuer(),
                subject.externalSubject(), subjects.storedGroups(subject.id()), false);
        String key = "schedule-" + s.id() + "-" + planned.getEpochSecond();
        String businessKey = "schedule-" + planned.truncatedTo(ChronoUnit.MINUTES).toString().replace(":", "")
                .replace("-", "");
        RunService.Started started = startTx.execute(status -> runs.start(caller, s.workspaceId(), key,
                new RuntimeDtos.StartRunRequest(s.workflowId(), null, businessKey, s.input().deepCopy())));
        return started.run().id();
    }

    // ------------------------------------------------------------------ views

    private Optional<Schedule> view(UUID ws, UUID id, boolean data) {
        List<Fire> recent = jdbc.sql("SELECT * FROM schedule_fire WHERE schedule_id = :id ORDER BY fire_at DESC LIMIT 10")
                .param("id", id).query((rs, n) -> new Fire(Sql.instant(rs, "fire_at"), Sql.instant(rs, "fired_at"),
                        rs.getString("outcome"), rs.getString("reason"), rs.getInt("missed"),
                        (UUID) rs.getObject("run_id"))).list();
        return jdbc.sql("SELECT s.*, w.name AS workflow_name, p.display_name AS run_as_name FROM schedule_trigger s"
                + " JOIN workflow w ON w.workspace_id = s.workspace_id AND w.id = s.workflow_id"
                + " JOIN subject p ON p.id = s.run_as WHERE s.workspace_id = :ws AND s.id = :id")
                .param("ws", ws).param("id", id).query((rs, n) -> {
                    List<Instant> upcoming = List.of();
                    try {
                        Recurrence r = Recurrence.parse(rs.getString("kind"), rs.getString("expression"),
                                rs.getString("zone"), Sql.instant(rs, "starts_at"));
                        String key = rs.getString("calendar_key");
                        BusinessCalendar cal = key == null ? null
                                : calendars.pinned(ws, key, rs.getInt("calendar_version"));
                        upcoming = rs.getBoolean("enabled")
                                ? upcoming(r, cal, Sql.instant(rs, "ends_at"), clock.instant(), 5) : List.of();
                    } catch (RuntimeException e) {
                        // a stored schedule always parses; nothing to show otherwise
                    }
                    return map(rs, data, upcoming, recent);
                }).optional();
    }

    private Optional<Schedule> lockView(UUID ws, UUID id) {
        boolean exists = jdbc.sql("SELECT 1 FROM schedule_trigger WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", ws).param("id", id).query(Integer.class).optional().isPresent();
        return exists ? view(ws, id, true) : Optional.empty();
    }

    private Schedule map(java.sql.ResultSet rs, boolean data, List<Instant> upcoming, List<Fire> recent)
            throws java.sql.SQLException {
        return new Schedule(Sql.uuid(rs, "id"), Sql.uuid(rs, "workflow_id"), rs.getString("workflow_name"),
                rs.getString("name"), rs.getString("kind"), rs.getString("expression"), rs.getString("zone"),
                Sql.instant(rs, "starts_at"), Sql.instant(rs, "ends_at"), rs.getString("calendar_key"),
                (Integer) rs.getObject("calendar_version"), data ? json.parse(rs.getString("input")) : null,
                rs.getString("missed_policy"), rs.getString("overlap_policy"), rs.getBoolean("enabled"),
                Sql.uuid(rs, "run_as"), rs.getString("run_as_name"), Sql.instant(rs, "next_fire_at"),
                Sql.instant(rs, "last_fire_at"), rs.getString("last_outcome"), (UUID) rs.getObject("last_run_id"),
                rs.getInt("version"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), upcoming, recent);
    }

    private void reader(Caller caller, UUID ws, String operation) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_READ, operation, false);
    }

    private void audit(Caller caller, UUID ws, String action, UUID id, Map<String, ?> metadata) {
        audit.write(new AuditWriter.Entry(ws, caller.machine() ? AuditWriter.ActorType.SERVICE
                : AuditWriter.ActorType.USER, caller.subjectId(), action, "schedule", id.toString(),
                AuditWriter.Outcome.SUCCESS, new LinkedHashMap<>(metadata)));
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String cut(String text) {
        return text == null || text.length() <= 300 ? text : text.substring(0, 300);
    }

    private static ApiException invalid(List<ApiException.FieldError> errors) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "Some values are not valid.").withFieldErrors(errors);
    }
}
