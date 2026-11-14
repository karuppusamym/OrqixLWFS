package com.orqix.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.Schedules;
import com.orqix.runtime.internal.WaitReminders;
import com.orqix.runtime.internal.WorkingCalendars;

import tools.jackson.databind.JsonNode;

/**
 * docs/build/S139 (OpenAPI tag WaitsAndStarts): shared working calendars (CG-W05), the reminders of a request (CG-W06)
 * and scheduled starts (CG-E02). Bodies are optional at the HTTP level so that access is decided first: a caller
 * outside the workspace always gets 404.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class WaitsAndStartsController {

    private final CallerResolver callers;
    private final WorkingCalendars calendars;
    private final Schedules schedules;
    private final WaitReminders reminders;

    public WaitsAndStartsController(CallerResolver callers, WorkingCalendars calendars, Schedules schedules,
            WaitReminders reminders) {
        this.callers = callers;
        this.calendars = calendars;
        this.schedules = schedules;
        this.reminders = reminders;
    }

    // ------------------------------------------------------------------ working calendars

    public record CalendarList(List<WorkingCalendars.Summary> items) {
    }

    public record CreateCalendar(String key, String name, String description, JsonNode content) {
    }

    public record AddCalendarVersion(Integer expectedVersion, JsonNode content, String changeNote) {
    }

    public record ArchiveCalendar(Boolean archived) {
    }

    public record CalendarPreview(Integer version, Instant from, String workingDuration) {
    }

    public record CalendarDue(Instant dueAt) {
    }

    @GetMapping("/calendars")
    public CalendarList listWorkingCalendars(@PathVariable UUID workspaceId) {
        return new CalendarList(calendars.list(callers.current(), workspaceId));
    }

    @PostMapping("/calendars")
    public ResponseEntity<WorkingCalendars.Detail> createWorkingCalendar(@PathVariable UUID workspaceId,
            @RequestBody(required = false) CreateCalendar body) {
        CreateCalendar b = body == null ? new CreateCalendar(null, null, null, null) : body;
        return ResponseEntity.status(HttpStatus.CREATED).body(calendars.create(callers.current(), workspaceId, b.key(),
                b.name(), b.description(), b.content()));
    }

    @GetMapping("/calendars/{calendarKey}")
    public WorkingCalendars.Detail getWorkingCalendar(@PathVariable UUID workspaceId, @PathVariable String calendarKey) {
        return calendars.get(callers.current(), workspaceId, calendarKey);
    }

    @PostMapping("/calendars/{calendarKey}/versions")
    public WorkingCalendars.Detail addWorkingCalendarVersion(@PathVariable UUID workspaceId,
            @PathVariable String calendarKey, @RequestBody(required = false) AddCalendarVersion body) {
        AddCalendarVersion b = body == null ? new AddCalendarVersion(null, null, null) : body;
        return calendars.addVersion(callers.current(), workspaceId, calendarKey, b.expectedVersion(), b.content(),
                b.changeNote());
    }

    @PostMapping("/calendars/{calendarKey}/archive")
    public WorkingCalendars.Detail archiveWorkingCalendar(@PathVariable UUID workspaceId,
            @PathVariable String calendarKey, @RequestBody(required = false) ArchiveCalendar body) {
        return calendars.archive(callers.current(), workspaceId, calendarKey,
                body == null || body.archived() == null || body.archived());
    }

    @PostMapping("/calendars/{calendarKey}/preview")
    public CalendarDue previewWorkingCalendar(@PathVariable UUID workspaceId, @PathVariable String calendarKey,
            @RequestBody(required = false) CalendarPreview body) {
        CalendarPreview b = body == null ? new CalendarPreview(null, null, null) : body;
        return new CalendarDue(calendars.preview(callers.current(), workspaceId, calendarKey, b.version(), b.from(),
                b.workingDuration()));
    }

    // ------------------------------------------------------------------ reminders

    public record RunReminder(UUID id, String nodeId, Instant waitSince, String adapterKey, String status, int sent,
            int maxTimes, Instant nextAt, Instant lastSentAt, String lastOutcome, Instant endedAt) {
    }

    public record ReminderList(List<RunReminder> items) {
    }

    @GetMapping("/runs/{runId}/reminders")
    public ReminderList listRunReminders(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        return new ReminderList(reminders.forRun(callers.current(), workspaceId, runId).stream()
                .map(r -> new RunReminder(r.id(), r.nodeId(), r.waitSince(), r.adapterKey(), r.status(), r.sent(),
                        r.maxTimes(), "waiting".equals(r.status()) || "sending".equals(r.status()) ? r.nextAt() : null,
                        r.lastSentAt(), r.lastOutcome(), r.endedAt()))
                .toList());
    }

    // ------------------------------------------------------------------ schedules

    public record ScheduleList(List<Schedules.Schedule> items) {
    }

    public record ScheduleBody(UUID workflowId, String name, String kind, String expression, String zone,
            Instant startsAt, Instant endsAt, String calendarKey, JsonNode input, String missedRunPolicy,
            String overlapPolicy, Boolean enabled, Integer expectedVersion) {

        Schedules.Settings settings() {
            return new Schedules.Settings(workflowId, name, kind, expression, zone, startsAt, endsAt, calendarKey, input,
                    missedRunPolicy, overlapPolicy, enabled);
        }
    }

    public record SchedulePreview(List<Instant> next) {
    }

    private static ScheduleBody body(ScheduleBody body) {
        return body == null ? new ScheduleBody(null, null, null, null, null, null, null, null, null, null, null, null,
                null) : body;
    }

    @GetMapping("/schedules")
    public ScheduleList listSchedules(@PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID workflowId) {
        return new ScheduleList(schedules.list(callers.current(), workspaceId, workflowId));
    }

    @PostMapping("/schedules")
    public ResponseEntity<Schedules.Schedule> createSchedule(@PathVariable UUID workspaceId,
            @RequestBody(required = false) ScheduleBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(schedules.create(callers.current(), workspaceId,
                body(body).settings()));
    }

    @PostMapping("/schedules/preview")
    public SchedulePreview previewSchedule(@PathVariable UUID workspaceId,
            @RequestBody(required = false) ScheduleBody body) {
        return new SchedulePreview(schedules.preview(callers.current(), workspaceId, body(body).settings()));
    }

    @GetMapping("/schedules/{scheduleId}")
    public Schedules.Schedule getSchedule(@PathVariable UUID workspaceId, @PathVariable UUID scheduleId) {
        return schedules.get(callers.current(), workspaceId, scheduleId);
    }

    @PatchMapping("/schedules/{scheduleId}")
    public Schedules.Schedule updateSchedule(@PathVariable UUID workspaceId, @PathVariable UUID scheduleId,
            @RequestBody(required = false) ScheduleBody body) {
        ScheduleBody b = body(body);
        return schedules.update(callers.current(), workspaceId, scheduleId, b.expectedVersion(), b.settings());
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable UUID workspaceId, @PathVariable UUID scheduleId) {
        schedules.delete(callers.current(), workspaceId, scheduleId);
        return ResponseEntity.noContent().build();
    }
}
