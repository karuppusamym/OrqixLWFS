package com.orqix.compiler.internal.model;

import java.util.List;
import java.util.Map;

/**
 * Settings of the S139 waits (CG-W05, CG-W06) kept beside the steps they belong to, by step id, so the step records stay
 * as they were: working-time waits, working-time deadlines, reminders while waiting and the calendar pins of
 * {@code dependencies.assets}.
 *
 * @param working          "Wait" steps that wait in working time ({@code workingDuration} + {@code calendarRef})
 * @param timeoutCalendars message waits and check-until calls whose deadline counts working time (step id to calendar
 *                         key: {@code timeoutCalendarRef}, or {@code poll.timeoutCalendarRef} for a call)
 * @param reminders        {@code whileWaiting.remind} of message waits, callbacks, check-until calls and worker jobs
 * @param calendarPins     {@code dependencies.assets} entries of kind {@code calendar}, by calendar key
 */
public record WaitExtras(Map<String, Working> working, Map<String, String> timeoutCalendars,
        Map<String, Remind> reminders, Map<String, CalendarPin> calendarPins) {

    public static final WaitExtras NONE = new WaitExtras(Map.of(), Map.of(), Map.of(), Map.of());

    public WaitExtras {
        working = Map.copyOf(working);
        timeoutCalendars = Map.copyOf(timeoutCalendars);
        reminders = Map.copyOf(reminders);
        calendarPins = Map.copyOf(calendarPins);
    }

    /** @return the working-time setting of a Wait step, or null */
    public Working working(String stepId) {
        return working.get(stepId);
    }

    /** @return the calendar key a step's deadline counts working time in, or null for wall-clock time */
    public String timeoutCalendar(String stepId) {
        return timeoutCalendars.get(stepId);
    }

    /** @return the reminder settings of a step, or null */
    public Remind remind(String stepId) {
        return reminders.get(stepId);
    }

    /** A wait of {@code duration} working time in the shared calendar {@code calendarRef}. */
    public record Working(String duration, String calendarRef) {
    }

    /**
     * Reminders while waiting (CG-W06): every {@code every} (wall-clock, from the start of the wait), at most
     * {@code maxTimes}, a message through the adapter {@code adapterRef}. Email recipients are the literals {@code to}
     * (on the adapter's allowlist) or one request field {@code toField} (D-CG7: checked against the adapter's approved
     * domains when each reminder is sent). Texts may use the fixed placeholders and the names of {@code inputMapping}.
     */
    public record Remind(String every, int maxTimes, String adapterRef, List<String> to, String toField,
            String subject, String body, String text, Map<String, String> inputMapping, String path) {
        public Remind {
            to = List.copyOf(to);
            inputMapping = Map.copyOf(inputMapping);
        }
    }

    /** A pinned version of a shared working calendar ({@code dependencies.assets}, kind {@code calendar}). */
    public record CalendarPin(int index, String key, int version) {
        public String path() {
            return "/dependencies/assets/" + index;
        }
    }
}
