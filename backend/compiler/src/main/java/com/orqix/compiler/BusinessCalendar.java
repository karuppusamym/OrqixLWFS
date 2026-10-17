package com.orqix.compiler;

import java.time.*;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Immutable working-hours calendar. Hours are elapsed working time; DST transitions use real instants. */
public record BusinessCalendar(ZoneId zone, Set<Integer> weekdays, Set<LocalDate> holidays,
        LocalTime start, LocalTime end, java.util.List<Window> intervals) {
    public record Window(LocalTime start, LocalTime end) {}
    public BusinessCalendar(ZoneId zone, Set<Integer> weekdays, Set<LocalDate> holidays, LocalTime start, LocalTime end) {
        this(zone, weekdays, holidays, start, end, java.util.List.of(new Window(start, end)));
    }
    public BusinessCalendar {
        weekdays = Set.copyOf(weekdays);
        holidays = Set.copyOf(holidays);
        intervals = intervals.stream().sorted(java.util.Comparator.comparing(Window::start)).toList();
        if (intervals.isEmpty() || intervals.size() > 8) throw new IllegalArgumentException("Supply one to eight working intervals.");
        LocalTime previous = null;
        for (Window w : intervals) {
            if (!w.start().isBefore(w.end()) || previous != null && w.start().isBefore(previous))
                throw new IllegalArgumentException("Working intervals must not overlap or cross midnight.");
            previous = w.end();
        }
        start = intervals.getFirst().start(); end = intervals.getLast().end();
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
            var windows = new java.util.ArrayList<Window>();
            if (value.has("intervals")) value.path("intervals").forEach(w -> windows.add(new Window(
                    LocalTime.parse(w.path("start").asString()), LocalTime.parse(w.path("end").asString()))));
            else windows.add(new Window(LocalTime.parse(value.path("start").asString()), LocalTime.parse(value.path("end").asString())));
            return new BusinessCalendar(ZoneId.of(value.path("zone").asString()), days, holidays,
                    LocalTime.parse(value.path("start").asString()), LocalTime.parse(value.path("end").asString()), windows);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Calendar needs a valid time zone, dates and opening/closing times.", e);
        }
    }

    /** Validate a pinned ten-year scheduling horizon before Tier-1 publication. */
    public void validateBoundaries(LocalDate from) {
        for (LocalDate day = from; day.isBefore(from.plusYears(10)); day = day.plusDays(1)) {
            if (!weekdays.contains(day.getDayOfWeek().getValue()) || holidays.contains(day)) continue;
            for (Window w : intervals) { boundary(day, w.start()); boundary(day, w.end()); }
        }
    }
    private Instant boundary(LocalDate day, LocalTime time) {
        LocalDateTime local = day.atTime(time);
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw new IllegalArgumentException("Working boundary is ambiguous or nonexistent in " + zone + ": " + local);
        return local.toInstant(offsets.getFirst());
    }
    public Instant deadline(Instant from, int hours) {
        if (hours < 1 || hours > 720) throw new IllegalArgumentException("Working hours must be 1..720.");
        return deadlineMinutes(from, Math.multiplyExact(hours, 60));
    }
    public Instant deadlineMinutes(Instant from, int minutes) {
        if (minutes < 1 || minutes > 43200) throw new IllegalArgumentException("Working minutes must be 1..43200.");
        Duration remaining = Duration.ofMinutes(minutes);
        LocalDate day = from.atZone(zone).toLocalDate();
        for (int i = 0; i < 3660; i++, day = day.plusDays(1)) {
            if (!weekdays.contains(day.getDayOfWeek().getValue()) || holidays.contains(day)) continue;
            for (Window w : intervals) {
                Instant opens = boundary(day, w.start()), closes = boundary(day, w.end());
                Instant cursor = from.isAfter(opens) ? from : opens;
                if (!cursor.isBefore(closes)) continue;
                Duration available = Duration.between(cursor, closes);
                if (remaining.compareTo(available) <= 0) return cursor.plus(remaining);
                remaining = remaining.minus(available);
            }
        }
        throw new IllegalArgumentException("Calendar cannot schedule this deadline within ten years.");
    }
}
