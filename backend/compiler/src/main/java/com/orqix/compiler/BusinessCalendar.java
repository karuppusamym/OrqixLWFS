package com.orqix.compiler;

import java.time.*;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Immutable working-hours calendar. Hours are elapsed working time; DST transitions use real instants. */
public record BusinessCalendar(ZoneId zone, Set<Integer> weekdays, Set<LocalDate> holidays,
        LocalTime start, LocalTime end) {
    public BusinessCalendar {
        weekdays = Set.copyOf(weekdays);
        holidays = Set.copyOf(holidays);
        if (weekdays.isEmpty() || weekdays.stream().anyMatch(d -> d < 1 || d > 7)
                || holidays.size() > 366 || !start.isBefore(end))
            throw new IllegalArgumentException("Calendar requires working weekdays and an opening time before closing.");
    }

    public static BusinessCalendar read(JsonNode value) {
        try {
            var days = new HashSet<Integer>();
            value.path("weekdays").forEach(n -> days.add(n.asInt()));
            var holidays = new HashSet<LocalDate>();
            value.path("holidays").forEach(n -> holidays.add(LocalDate.parse(n.asString())));
            return new BusinessCalendar(ZoneId.of(value.path("zone").asString()), days, holidays,
                    LocalTime.parse(value.path("start").asString()), LocalTime.parse(value.path("end").asString()));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Calendar needs a valid time zone, dates and opening/closing times.", e);
        }
    }

    public Instant deadline(Instant from, int hours) {
        if (hours < 1 || hours > 720) throw new IllegalArgumentException("Working hours must be 1..720.");
        Duration remaining = Duration.ofHours(hours);
        LocalDate day = from.atZone(zone).toLocalDate();
        for (int i = 0; i < 3660; i++, day = day.plusDays(1)) {
            if (!weekdays.contains(day.getDayOfWeek().getValue()) || holidays.contains(day)) continue;
            Instant opens = day.atTime(start).atZone(zone).toInstant();
            Instant closes = day.atTime(end).atZone(zone).toInstant();
            Instant cursor = from.isAfter(opens) ? from : opens;
            if (!cursor.isBefore(closes)) continue;
            Duration available = Duration.between(cursor, closes);
            if (remaining.compareTo(available) <= 0) return cursor.plus(remaining);
            remaining = remaining.minus(available);
        }
        throw new IllegalArgumentException("Calendar cannot schedule this deadline within ten years.");
    }
}
