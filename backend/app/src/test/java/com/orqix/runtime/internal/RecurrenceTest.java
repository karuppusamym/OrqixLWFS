package com.orqix.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.BusinessCalendar;
import com.orqix.compiler.internal.json.StrictJson;

/** Scheduled-start timing (docs/build/S139, CG-E02): cron and the RRULE subset in time zones, with daylight saving. */
class RecurrenceTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static List<String> next(String kind, String expression, String zone, String after, int count) {
        return Recurrence.parse(kind, expression, zone, START).upcoming(Instant.parse(after), count).stream()
                .map(Instant::toString).toList();
    }

    @Test
    void cronRunsInTheZoneAcrossDaylightSaving() {
        // 07:00 London is 07:00 UTC in winter and 06:00 UTC in summer (clocks go forward on 29 March 2026).
        assertThat(next("cron", "0 7 * * *", "Europe/London", "2026-03-27T08:00:00Z", 3))
                .containsExactly("2026-03-28T07:00:00Z", "2026-03-29T06:00:00Z", "2026-03-30T06:00:00Z");
        assertThat(next("cron", "30 9 * * MON-FRI", "America/New_York", "2026-11-06T15:00:00Z", 2))
                .containsExactly("2026-11-09T14:30:00Z", "2026-11-10T14:30:00Z");
        assertThatThrownBy(() -> Recurrence.parse("cron", "0 0 7 * * *", "UTC", START))
                .hasMessageContaining("five fields");
        assertThatThrownBy(() -> Recurrence.parse("cron", "0 7 * *", "Nowhere/City", START))
                .hasMessageContaining("time zone");
    }

    @Test
    void anRruleCoversWeeklyMonthlyAndHourlyRules() {
        assertThat(next("rrule", "FREQ=WEEKLY;BYDAY=MO,WE;BYHOUR=8;BYMINUTE=30", "Europe/London",
                "2026-11-06T12:00:00Z", 3))
                .containsExactly("2026-11-09T08:30:00Z", "2026-11-11T08:30:00Z", "2026-11-16T08:30:00Z");
        // Every second week from the start week (week of 1 January 2026, a Thursday).
        assertThat(next("rrule", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TH;BYHOUR=9", "UTC", "2026-01-02T00:00:00Z", 2))
                .containsExactly("2026-01-15T09:00:00Z", "2026-01-29T09:00:00Z");
        // The last Friday of the month, and the last day of the month.
        assertThat(next("rrule", "FREQ=MONTHLY;BYDAY=-1FR;BYHOUR=17", "UTC", "2026-01-01T00:00:00Z", 2))
                .containsExactly("2026-01-30T17:00:00Z", "2026-02-27T17:00:00Z");
        assertThat(next("rrule", "FREQ=MONTHLY;BYMONTHDAY=-1;BYHOUR=6", "UTC", "2026-02-01T00:00:00Z", 2))
                .containsExactly("2026-02-28T06:00:00Z", "2026-03-31T06:00:00Z");
        // Every six hours from the start, at minute 15.
        assertThat(next("rrule", "FREQ=HOURLY;INTERVAL=6;BYMINUTE=15", "UTC", "2026-01-01T00:00:00Z", 3))
                .containsExactly("2026-01-01T00:15:00Z", "2026-01-01T06:15:00Z", "2026-01-01T12:15:00Z");
        // UNTIL ends the series.
        assertThat(next("rrule", "FREQ=DAILY;BYHOUR=8;UNTIL=20260103", "UTC", "2026-01-01T00:00:00Z", 5))
                .containsExactly("2026-01-01T08:00:00Z", "2026-01-02T08:00:00Z", "2026-01-03T08:00:00Z");
    }

    @Test
    void unsupportedRuleMembersAreRefusedInPlainLanguage() {
        assertThatThrownBy(() -> Recurrence.parse("rrule", "FREQ=DAILY;COUNT=3", "UTC", START))
                .hasMessageContaining("COUNT is not supported");
        assertThatThrownBy(() -> Recurrence.parse("rrule", "FREQ=YEARLY", "UTC", START))
                .hasMessageContaining("FREQ must be");
        assertThatThrownBy(() -> Recurrence.parse("rrule", "FREQ=WEEKLY;BYDAY=1MO", "UTC", START))
                .hasMessageContaining("FREQ=MONTHLY");
        assertThatThrownBy(() -> Recurrence.parse("rrule", "FREQ=DAILY;BYHOUR=25", "UTC", START))
                .hasMessageContaining("BYHOUR");
    }

    @Test
    void aWorkingCalendarSkipsTimesOutsideItsHours() {
        BusinessCalendar office = BusinessCalendar.read(StrictJson.parse("""
                {"zone":"Europe/London","weekdays":[1,2,3,4,5],"start":"09:00","end":"17:00",
                 "intervals":[{"start":"09:00","end":"17:00"}],"holidays":["2026-12-25"]}"""));
        Recurrence hourly = Recurrence.parse("cron", "0 * * * *", "Europe/London", START);
        // Friday 16:30 UTC (16:30 London): the next working hour is Monday 09:00.
        assertThat(Schedules.firstWorking(hourly, office, null, Instant.parse("2026-11-06T16:30:00Z")))
                .isEqualTo(Instant.parse("2026-11-09T09:00:00Z"));
        // Christmas Day is skipped.
        assertThat(Schedules.firstWorking(hourly, office, null, Instant.parse("2026-12-24T16:30:00Z")))
                .isEqualTo(Instant.parse("2026-12-28T09:00:00Z"));
        // An end date stops the schedule.
        assertThat(Schedules.firstWorking(hourly, office, Instant.parse("2026-11-07T00:00:00Z"),
                Instant.parse("2026-11-06T16:30:00Z"))).isNull();
    }

    @Test
    void workingDurationsCountDaysAsTheCalendarsWorkingDay() {
        BusinessCalendar split = BusinessCalendar.read(StrictJson.parse("""
                {"zone":"UTC","weekdays":[1,2,3,4,5],"start":"09:00","end":"17:00",
                 "intervals":[{"start":"09:00","end":"12:00"},{"start":"13:00","end":"17:00"}]}"""));
        assertThat(WorkingCalendars.workingMinutes(split, "P1D")).isEqualTo(7 * 60);
        assertThat(WorkingCalendars.workingMinutes(split, "P3DT2H30M")).isEqualTo(3 * 7 * 60 + 150);
        assertThat(WorkingCalendars.workingMinutes(split, "PT90S")).isEqualTo(2);
        // Three working days from Friday 10:00 end on Wednesday 10:00.
        assertThat(WorkingCalendars.due(split, Instant.parse("2026-11-06T10:00:00Z"), "P3D"))
                .isEqualTo(Instant.parse("2026-11-11T10:00:00Z"));
    }
}
