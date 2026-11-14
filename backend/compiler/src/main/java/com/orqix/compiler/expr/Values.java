package com.orqix.compiler.expr;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.orqix.compiler.BusinessCalendar;

import tools.jackson.databind.JsonNode;

/**
 * The values an expression reads while it is evaluated (docs/build/S137). Values are JSON nodes or Java values
 * ({@link java.math.BigDecimal}, {@link String}, {@link Boolean}, {@link java.time.LocalDate}, lists, maps); the
 * evaluator converts them by the checked type, so a date field held as {@code "2026-03-31"} is a date.
 */
public interface Values {

    Object input(String key);

    Object step(String stepId, String key);

    default Object local(String name) {
        return null;
    }

    /** @return the evaluation instant {@code today()} uses: the submission or step time, never a live clock read */
    Instant now();

    /** @return the working calendar of working-day functions and {@code today()}; null for the default */
    default BusinessCalendar calendar() {
        return null;
    }

    /** Monday to Friday in UTC without holidays: the calendar when none is configured. */
    BusinessCalendar DEFAULT_CALENDAR = new BusinessCalendar(ZoneOffset.UTC, Set.of(1, 2, 3, 4, 5), Set.of(),
            LocalTime.of(9, 0), LocalTime.of(17, 0));

    /**
     * Reads a working calendar in the S27 shape (zone, weekdays, holidays; opening hours optional and unused by
     * day arithmetic).
     *
     * @return the calendar, or null for a null or missing node
     * @throws IllegalArgumentException for an invalid zone, weekday or date
     */
    static BusinessCalendar calendar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.has("start") && node.has("end")) return BusinessCalendar.read(node);
        try {
            var days = new java.util.HashSet<Integer>();
            node.path("weekdays").forEach(n -> days.add(n.asInt()));
            var holidays = new java.util.HashSet<java.time.LocalDate>();
            node.path("holidays").forEach(n -> holidays.add(java.time.LocalDate.parse(n.asString())));
            return new BusinessCalendar(java.time.ZoneId.of(node.path("zone").asString()), days, holidays,
                    LocalTime.of(9, 0), LocalTime.of(17, 0));
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("Calendar needs a valid time zone and dates.", e);
        }
    }

    /**
     * @param input    the request ({@code orqix_input}), may be null
     * @param steps    step ID to the step's stored outputs (may return null)
     * @param locals   earlier outputs of the same step
     * @param calendar null for {@link #DEFAULT_CALENDAR}
     */
    static Values of(JsonNode input, Function<String, JsonNode> steps, Map<String, ?> locals, Instant now,
            BusinessCalendar calendar) {
        return of(key -> input == null ? null : input.get(key), (id, key) -> {
            JsonNode step = steps.apply(id);
            return step == null ? null : step.get(key);
        }, locals, now, calendar);
    }

    static Values of(Function<String, Object> input, BiFunction<String, String, Object> steps, Map<String, ?> locals,
            Instant now, BusinessCalendar calendar) {
        return new Values() {
            @Override
            public Object input(String key) {
                return input.apply(key);
            }

            @Override
            public Object step(String stepId, String key) {
                return steps.apply(stepId, key);
            }

            @Override
            public Object local(String name) {
                return locals.get(name);
            }

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public BusinessCalendar calendar() {
                return calendar;
            }
        };
    }
}
