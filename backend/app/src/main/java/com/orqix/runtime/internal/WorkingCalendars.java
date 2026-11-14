package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.compiler.BusinessCalendar;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared working calendars (docs/build/S139, CG-W05): a workspace asset with numbered, unchangeable versions, read with
 * {@code workflow.read} and changed with {@code workflow.edit} like shared decisions (S61). A workflow pins one version
 * ({@code dependencies.assets}, kind {@code calendar}); the runtime reads pinned versions only, so a new version or an
 * archive never changes a release or a running request.
 * <p>
 * Working time: one working day is the calendar's working hours of one day (the sum of its intervals), so {@code P3D}
 * in a 09:00-17:00 calendar is 24 working hours; hours and minutes are working hours and minutes; seconds are rounded
 * up to a minute. The S27 {@link BusinessCalendar} walks the windows (weekends and holidays skipped, daylight-saving
 * transitions as real instants).
 */
@Service
public class WorkingCalendars {

    /** Most calendars per workspace and versions per calendar. */
    static final int MAX_CALENDARS = 100;
    static final int MAX_VERSIONS = 100;

    private static final Pattern DURATION = Pattern.compile(
            "^P(?=\\d|T\\d)(?:(\\d{1,3})D)?(?:T(?=\\d)(?:(\\d{1,4})H)?(?:(\\d{1,5})M)?(?:(\\d{1,6})S)?)?$");
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");
    private static final Pattern TIME = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");

    public record Summary(String key, String name, String description, int latestVersion, boolean archived,
            Instant createdAt, Instant updatedAt) {
    }

    public record Version(int version, JsonNode content, String changeNote, UUID createdBy, Instant createdAt) {
    }

    public record Detail(Summary calendar, List<Version> versions) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Map<String, BusinessCalendar> cache = new ConcurrentHashMap<>();

    public WorkingCalendars(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, AuditWriter audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ working time (runtime)

    /** @return the pinned version of a calendar (versions never change, so it is cached) */
    public BusinessCalendar pinned(UUID workspaceId, String key, int version) {
        String cacheKey = workspaceId + "/" + key + "/" + version;
        BusinessCalendar cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        JsonNode content = jdbc.sql("""
                SELECT v.content::text FROM working_calendar c JOIN working_calendar_version v ON v.calendar_id = c.id
                WHERE c.workspace_id = :ws AND c.key = :key AND v.version = :version
                """).param("ws", workspaceId).param("key", key).param("version", version).query(String.class)
                .optional().map(json::parse).orElseThrow(() -> new IllegalStateException("WF_CALENDAR_MISSING: the"
                        + " working calendar " + key + " version " + version + " does not exist in this workspace"));
        BusinessCalendar calendar = BusinessCalendar.read(content);
        cache.put(cacheKey, calendar);
        return calendar;
    }

    /** @return the end of {@code duration} of working time starting at {@code from} */
    public static Instant due(BusinessCalendar calendar, Instant from, String duration) {
        long minutes = workingMinutes(calendar, duration);
        if (minutes < 1 || minutes > 43_200) {
            throw new IllegalArgumentException("WF_CALENDAR_DURATION: " + duration + " is " + minutes
                    + " working minutes; the most is 43,200 (30 days of 24 hours)");
        }
        return calendar.deadlineMinutes(from, (int) minutes);
    }

    /** @return the working minutes of an ISO duration: days count as the calendar's working minutes of one day */
    public static long workingMinutes(BusinessCalendar calendar, String duration) {
        Matcher m = duration == null ? null : DURATION.matcher(duration);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("Not a duration: " + duration);
        }
        long day = 0;
        for (BusinessCalendar.Window w : calendar.intervals()) {
            day += Duration.between(w.start(), w.end()).toMinutes();
        }
        long days = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long hours = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long minutes = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        long seconds = m.group(4) == null ? 0 : Long.parseLong(m.group(4));
        return days * day + hours * 60 + minutes + (seconds + 59) / 60;
    }

    /** @return true when the instant falls inside the calendar's working hours (not on a weekend or holiday) */
    public static boolean working(BusinessCalendar calendar, Instant at) {
        ZonedDateTime local = at.atZone(calendar.zone());
        if (!calendar.weekdays().contains(local.getDayOfWeek().getValue())
                || calendar.holidays().contains(local.toLocalDate())) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        return calendar.intervals().stream().anyMatch(w -> !time.isBefore(w.start()) && time.isBefore(w.end()));
    }

    // ------------------------------------------------------------------ API

    @Transactional(readOnly = true)
    public List<Summary> list(Caller caller, UUID ws) {
        reader(caller, ws, "listWorkingCalendars");
        return jdbc.sql("SELECT * FROM working_calendar WHERE workspace_id = :ws ORDER BY lower(name), key")
                .param("ws", ws).query(WorkingCalendars::summary).list();
    }

    @Transactional(readOnly = true)
    public Detail get(Caller caller, UUID ws, String key) {
        reader(caller, ws, "getWorkingCalendar");
        return detail(ws, key);
    }

    @Transactional
    public Detail create(Caller caller, UUID ws, String key, String name, String description, JsonNode content) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_EDIT, "createWorkingCalendar", true);
        List<ApiException.FieldError> errors = new ArrayList<>();
        if (key == null || !KEY.matcher(key).matches()) {
            errors.add(new ApiException.FieldError("/key", "pattern", "Use lowercase letters, digits and hyphens,"
                    + " 2 to 64 characters, for example office-hours."));
        }
        if (name == null || name.isBlank() || name.strip().length() > 80) {
            errors.add(new ApiException.FieldError("/name", "required", "Give the calendar a name of 1 to 80"
                    + " characters."));
        }
        if (description != null && description.length() > 300) {
            errors.add(new ApiException.FieldError("/description", "maxLength", "At most 300 characters."));
        }
        ObjectNode normalized = content(content, errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        int count = jdbc.sql("SELECT count(*) FROM working_calendar WHERE workspace_id = :ws").param("ws", ws)
                .query(Integer.class).single();
        if (count >= MAX_CALENDARS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A workspace can have at most " + MAX_CALENDARS
                    + " working calendars.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO working_calendar (id, workspace_id, key, name, description, latest_version, created_by,
                                                  created_at, updated_at)
                    VALUES (:id, :ws, :key, :name, :description, 1, :by, :now, :now)
                    """).param("id", id).param("ws", ws).param("key", key).param("name", name.strip())
                    .param("description", description).param("by", caller.subjectId()).param("now", Sql.ts(now))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "A working calendar with this key or name already exists.");
        }
        insertVersion(ws, id, 1, normalized, null, caller.subjectId(), now);
        audit(caller, ws, "calendar.created", key, Map.of("key", key, "version", 1));
        return detail(ws, key);
    }

    @Transactional
    public Detail addVersion(Caller caller, UUID ws, String key, Integer expectedVersion, JsonNode content,
            String changeNote) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_EDIT, "addWorkingCalendarVersion", true);
        Row row = lock(ws, key).orElseThrow(ApiException::notFound);
        List<ApiException.FieldError> errors = new ArrayList<>();
        if (expectedVersion == null) {
            errors.add(new ApiException.FieldError("/expectedVersion", "required", "Say which version you changed."));
        }
        if (changeNote != null && changeNote.length() > 300) {
            errors.add(new ApiException.FieldError("/changeNote", "maxLength", "At most 300 characters."));
        }
        ObjectNode normalized = content(content, errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        if (row.archived()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The calendar is archived. Restore it first.");
        }
        if (expectedVersion != row.latestVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The calendar has a newer version (" + row.latestVersion()
                    + "). Reload it and try again.");
        }
        if (row.latestVersion() >= MAX_VERSIONS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A calendar can have at most " + MAX_VERSIONS
                    + " versions.");
        }
        String latest = jdbc.sql("SELECT sha256 FROM working_calendar_version WHERE calendar_id = :id AND version = :v")
                .param("id", row.id()).param("v", row.latestVersion()).query(String.class).single();
        if (latest.equals(json.canonicalSha256(normalized))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This is the same as version " + row.latestVersion()
                    + ".");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int version = row.latestVersion() + 1;
        insertVersion(ws, row.id(), version, normalized, changeNote, caller.subjectId(), now);
        jdbc.sql("UPDATE working_calendar SET latest_version = :v, updated_at = :now WHERE id = :id")
                .param("v", version).param("now", Sql.ts(now)).param("id", row.id()).update();
        audit(caller, ws, "calendar.versioned", key, Map.of("key", key, "version", version));
        return detail(ws, key);
    }

    @Transactional
    public Detail archive(Caller caller, UUID ws, String key, boolean archived) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_EDIT, "archiveWorkingCalendar", true);
        Row row = lock(ws, key).orElseThrow(ApiException::notFound);
        if (row.archived() != archived) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            jdbc.sql("UPDATE working_calendar SET archived_at = :at, updated_at = :now WHERE id = :id")
                    .param("at", archived ? Sql.ts(now) : null).param("now", Sql.ts(now)).param("id", row.id()).update();
            audit(caller, ws, archived ? "calendar.archived" : "calendar.restored", key, Map.of("key", key));
        }
        return detail(ws, key);
    }

    /** "3 working days from now" for the Studio: the end of a working duration in one version. */
    @Transactional(readOnly = true)
    public Instant preview(Caller caller, UUID ws, String key, Integer version, Instant from, String duration) {
        reader(caller, ws, "previewWorkingCalendar");
        Detail detail = detail(ws, key);
        int pinned = version == null ? detail.calendar().latestVersion() : version;
        if (detail.versions().stream().noneMatch(v -> v.version() == pinned)) {
            throw invalid(List.of(new ApiException.FieldError("/version", "invalid", "The calendar has no version "
                    + pinned + ".")));
        }
        try {
            return due(pinned(ws, key, pinned), from == null ? clock.instant() : from, duration);
        } catch (IllegalArgumentException e) {
            throw invalid(List.of(new ApiException.FieldError("/workingDuration", "invalid", "Use a working time such"
                    + " as P3D or PT4H, at most 30 days of 24 hours of working time.")));
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Row(UUID id, int latestVersion, boolean archived) {
    }

    private Optional<Row> lock(UUID ws, String key) {
        return jdbc.sql("SELECT id, latest_version, archived_at IS NOT NULL AS archived FROM working_calendar"
                + " WHERE workspace_id = :ws AND key = :key FOR UPDATE").param("ws", ws).param("key", key)
                .query((rs, n) -> new Row(Sql.uuid(rs, "id"), rs.getInt("latest_version"), rs.getBoolean("archived")))
                .optional();
    }

    private Detail detail(UUID ws, String key) {
        Summary summary = jdbc.sql("SELECT * FROM working_calendar WHERE workspace_id = :ws AND key = :key")
                .param("ws", ws).param("key", key).query(WorkingCalendars::summary).optional()
                .orElseThrow(ApiException::notFound);
        List<Version> versions = jdbc.sql("""
                SELECT v.version, v.content::text AS content, v.change_note, v.created_by, v.created_at
                FROM working_calendar_version v JOIN working_calendar c ON c.id = v.calendar_id
                WHERE c.workspace_id = :ws AND c.key = :key ORDER BY v.version DESC
                """).param("ws", ws).param("key", key).query((rs, n) -> new Version(rs.getInt("version"),
                        json.parse(rs.getString("content")), rs.getString("change_note"), Sql.uuid(rs, "created_by"),
                        Sql.instant(rs, "created_at"))).list();
        return new Detail(summary, versions);
    }

    private void insertVersion(UUID ws, UUID id, int version, ObjectNode content, String note, UUID by, Instant now) {
        jdbc.sql("""
                INSERT INTO working_calendar_version (workspace_id, calendar_id, version, content, sha256, change_note,
                                                      created_by, created_at)
                VALUES (:ws, :id, :version, CAST(:content AS jsonb), :sha, :note, :by, :now)
                """).param("ws", ws).param("id", id).param("version", version).param("content", json.write(content))
                .param("sha", json.canonicalSha256(content)).param("note", note).param("by", by)
                .param("now", Sql.ts(now)).update();
    }

    /**
     * Checks and normalizes calendar content: {@code zone}, {@code weekdays} (1 Monday to 7 Sunday), {@code intervals}
     * (1 to 8 same-day windows, HH:mm) or {@code start}/{@code end}, and up to 366 {@code holidays}. The ten-year
     * daylight-saving check of S27 refuses window boundaries that do not exist or are ambiguous in the zone.
     */
    ObjectNode content(JsonNode content, List<ApiException.FieldError> errors) {
        if (content == null || !content.isObject()) {
            errors.add(new ApiException.FieldError("/content", "required", "Describe the working week."));
            return null;
        }
        ObjectNode out = json.object();
        String zone = content.path("zone").asString(null);
        try {
            ZoneId.of(zone == null ? "" : zone);
            out.put("zone", zone);
        } catch (DateTimeException e) {
            errors.add(new ApiException.FieldError("/content/zone", "invalid", "Use a time zone such as"
                    + " Europe/London or America/New_York."));
        }
        TreeSet<Integer> days = new TreeSet<>();
        content.path("weekdays").forEach(d -> days.add(d.asInt(0)));
        if (days.isEmpty() || days.first() < 1 || days.last() > 7 || days.size() != content.path("weekdays").size()) {
            errors.add(new ApiException.FieldError("/content/weekdays", "invalid", "Choose the working days, 1"
                    + " (Monday) to 7 (Sunday), each once."));
        }
        ArrayNode weekdays = out.putArray("weekdays");
        days.forEach(weekdays::add);
        ArrayNode intervals = out.putArray("intervals");
        JsonNode source = content.path("intervals");
        if (!source.isArray() || source.isEmpty()) {
            ArrayNode single = json.mapper().createArrayNode();
            single.addObject().put("start", content.path("start").asString("")).put("end",
                    content.path("end").asString(""));
            source = single;
        }
        if (source.size() > 8) {
            errors.add(new ApiException.FieldError("/content/intervals", "maxItems", "At most 8 working periods a day."));
        }
        for (JsonNode w : source) {
            String start = w.path("start").asString("");
            String end = w.path("end").asString("");
            if (!TIME.matcher(start).matches() || !TIME.matcher(end).matches()) {
                errors.add(new ApiException.FieldError("/content/intervals", "invalid", "Write times as HH:mm, for"
                        + " example 09:00 and 17:30."));
                break;
            }
            intervals.addObject().put("start", start).put("end", end);
        }
        if (!intervals.isEmpty()) {
            out.put("start", intervals.get(0).path("start").asString());
            out.put("end", intervals.get(intervals.size() - 1).path("end").asString());
        }
        TreeSet<String> holidays = new TreeSet<>();
        for (JsonNode h : content.path("holidays")) {
            try {
                holidays.add(LocalDate.parse(h.asString("")).toString());
            } catch (DateTimeParseException e) {
                errors.add(new ApiException.FieldError("/content/holidays", "invalid", "Write holidays as dates,"
                        + " YYYY-MM-DD."));
                break;
            }
        }
        if (holidays.size() > 366) {
            errors.add(new ApiException.FieldError("/content/holidays", "maxItems", "At most 366 holidays."));
        }
        ArrayNode holidayArray = out.putArray("holidays");
        holidays.forEach(holidayArray::add);
        if (errors.isEmpty()) {
            try {
                BusinessCalendar calendar = BusinessCalendar.read(out);
                calendar.validateBoundaries(LocalDate.now(clock.withZone(calendar.zone())));
            } catch (IllegalArgumentException e) {
                errors.add(new ApiException.FieldError("/content", "invalid", e.getMessage()));
            }
        }
        return out;
    }

    private void reader(Caller caller, UUID ws, String operation) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_READ, operation, false);
    }

    private void audit(Caller caller, UUID ws, String action, String key, Map<String, ?> metadata) {
        audit.write(new AuditWriter.Entry(ws, caller.machine() ? AuditWriter.ActorType.SERVICE
                : AuditWriter.ActorType.USER, caller.subjectId(), action, "workingCalendar", key,
                AuditWriter.Outcome.SUCCESS, new LinkedHashMap<>(metadata)));
    }

    private static ApiException invalid(List<ApiException.FieldError> errors) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "Some values are not valid.").withFieldErrors(errors);
    }

    private static Summary summary(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Summary(rs.getString("key"), rs.getString("name"), rs.getString("description"),
                rs.getInt("latest_version"), rs.getTimestamp("archived_at") != null, Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"));
    }
}
