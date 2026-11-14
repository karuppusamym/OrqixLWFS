package com.orqix.runtime.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.scheduling.support.CronExpression;

/**
 * When a schedule fires (docs/build/S139, CG-E02): a five-field cron expression (minute hour day-of-month month
 * day-of-week, as Spring evaluates it) or a bounded RRULE (RFC 5545 subset), always in an IANA time zone. Daylight-saving
 * gaps move a local time forward to the first valid instant; repeated hours fire once, at the earlier offset.
 * <p>
 * RRULE: {@code FREQ=HOURLY|DAILY|WEEKLY|MONTHLY}, {@code INTERVAL} (1-366), {@code BYDAY} (MO..SU; with MONTHLY also
 * 1MO..5SU and -1MO..-5SU), {@code BYMONTHDAY} (1..31, -1..-31), {@code BYHOUR} (0-23), {@code BYMINUTE} (0-59) and
 * {@code UNTIL} (a date or UTC date-time). The series starts at the schedule's start time, which also gives the default
 * hour and minute. COUNT, BYSETPOS, BYWEEKNO, BYYEARDAY and seconds are refused.
 */
final class Recurrence {

    /** Search horizon for the next occurrence. */
    static final int HORIZON_DAYS = 5 * 366;

    private final ZoneId zone;
    private final CronExpression cron;
    private final Rule rule;
    private final ZonedDateTime start;

    private Recurrence(ZoneId zone, CronExpression cron, Rule rule, ZonedDateTime start) {
        this.zone = zone;
        this.cron = cron;
        this.rule = rule;
        this.start = start;
    }

    /** @throws IllegalArgumentException with a plain-language reason */
    static Recurrence parse(String kind, String expression, String zoneId, Instant startsAt) {
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Use a time zone such as Europe/London or America/New_York.");
        }
        ZonedDateTime start = startsAt.atZone(zone).truncatedTo(ChronoUnit.MINUTES);
        if ("cron".equals(kind)) {
            String[] fields = expression.trim().split("\\s+");
            if (fields.length != 5) {
                throw new IllegalArgumentException("Write a cron expression with five fields: minute, hour, day of the"
                        + " month, month and day of the week, for example \"0 7 * * MON-FRI\".");
            }
            try {
                return new Recurrence(zone, CronExpression.parse("0 " + String.join(" ", fields)), null, start);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("The cron expression is not valid: " + e.getMessage());
            }
        }
        if ("rrule".equals(kind)) {
            return new Recurrence(zone, null, Rule.parse(expression, start), start);
        }
        throw new IllegalArgumentException("Choose cron or rrule.");
    }

    ZoneId zone() {
        return zone;
    }

    /** @return the first occurrence strictly after {@code after} (and not before the start), or null when none */
    Instant next(Instant after) {
        Instant from = after.isBefore(start.toInstant()) ? start.toInstant().minusSeconds(1) : after;
        if (cron != null) {
            ZonedDateTime next = cron.next(from.atZone(zone));
            return next == null ? null : next.toInstant();
        }
        return rule.next(from, zone);
    }

    /** @return up to {@code count} occurrences after {@code after} */
    List<Instant> upcoming(Instant after, int count) {
        List<Instant> out = new ArrayList<>();
        Instant cursor = after;
        while (out.size() < count) {
            Instant next = next(cursor);
            if (next == null) {
                break;
            }
            out.add(next);
            cursor = next;
        }
        return out;
    }

    /** A parsed RRULE. */
    private record Rule(String freq, int interval, List<DayRule> days, TreeSet<Integer> monthDays, TreeSet<Integer> hours,
            TreeSet<Integer> minutes, Instant until, LocalDate anchor) {

        static Rule parse(String text, ZonedDateTime start) {
            String body = text.trim();
            if (body.toUpperCase(Locale.ROOT).startsWith("RRULE:")) {
                body = body.substring("RRULE:".length());
            }
            Map<String, String> parts = new LinkedHashMap<>();
            for (String part : body.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("Write the rule as NAME=value parts separated by ;, for example"
                            + " FREQ=WEEKLY;BYDAY=MO,WE;BYHOUR=8.");
                }
                String name = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                if (parts.put(name, part.substring(eq + 1).trim().toUpperCase(Locale.ROOT)) != null) {
                    throw new IllegalArgumentException(name + " is given twice.");
                }
            }
            for (String name : parts.keySet()) {
                if (!List.of("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYHOUR", "BYMINUTE", "UNTIL", "WKST")
                        .contains(name)) {
                    throw new IllegalArgumentException(name + " is not supported. Use FREQ, INTERVAL, BYDAY, BYMONTHDAY,"
                            + " BYHOUR, BYMINUTE and UNTIL.");
                }
            }
            String freq = parts.get("FREQ");
            if (freq == null || !List.of("HOURLY", "DAILY", "WEEKLY", "MONTHLY").contains(freq)) {
                throw new IllegalArgumentException("FREQ must be HOURLY, DAILY, WEEKLY or MONTHLY.");
            }
            int interval = number(parts.getOrDefault("INTERVAL", "1"), 1, 366, "INTERVAL");
            List<DayRule> days = new ArrayList<>();
            if (parts.containsKey("BYDAY")) {
                for (String d : parts.get("BYDAY").split(",")) {
                    days.add(DayRule.parse(d, freq));
                }
            }
            TreeSet<Integer> monthDays = numbers(parts.get("BYMONTHDAY"), -31, 31, "BYMONTHDAY");
            if (monthDays.contains(0)) {
                throw new IllegalArgumentException("BYMONTHDAY cannot be 0.");
            }
            TreeSet<Integer> hours = numbers(parts.get("BYHOUR"), 0, 23, "BYHOUR");
            TreeSet<Integer> minutes = numbers(parts.get("BYMINUTE"), 0, 59, "BYMINUTE");
            if (!freq.equals("HOURLY") && hours.isEmpty()) {
                hours.add(start.getHour());
            }
            if (minutes.isEmpty()) {
                minutes.add(start.getMinute());
            }
            Instant until = parts.containsKey("UNTIL") ? until(parts.get("UNTIL"), start.getZone()) : null;
            return new Rule(freq, interval, List.copyOf(days), monthDays, hours, minutes, until, start.toLocalDate());
        }

        Instant next(Instant after, ZoneId zone) {
            LocalDate day = after.atZone(zone).toLocalDate();
            ZonedDateTime anchorStart = anchor.atStartOfDay(zone);
            for (int i = 0; i <= HORIZON_DAYS; i++, day = day.plusDays(1)) {
                if (!dayMatches(day)) {
                    continue;
                }
                for (int hour : hoursOf(day, anchorStart)) {
                    for (int minute : minutes) {
                        Instant candidate = ZonedDateTime.ofLocal(LocalDateTime.of(day, LocalTime.of(hour, minute)),
                                zone, null).toInstant();
                        if (candidate.isAfter(after)) {
                            return until != null && candidate.isAfter(until) ? null : candidate;
                        }
                    }
                }
                if (until != null && day.atStartOfDay(zone).toInstant().isAfter(until)) {
                    return null;
                }
            }
            return null;
        }

        private Iterable<Integer> hoursOf(LocalDate day, ZonedDateTime anchorStart) {
            if (!freq.equals("HOURLY")) {
                return hours;
            }
            List<Integer> out = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                long elapsed = ChronoUnit.HOURS.between(anchorStart.toLocalDateTime(), day.atTime(h, 0));
                if (elapsed >= 0 && elapsed % interval == 0 && (hours.isEmpty() || hours.contains(h))) {
                    out.add(h);
                }
            }
            return out;
        }

        private boolean dayMatches(LocalDate day) {
            if (day.isBefore(anchor)) {
                return false;
            }
            switch (freq) {
                case "DAILY" -> {
                    if (ChronoUnit.DAYS.between(anchor, day) % interval != 0) {
                        return false;
                    }
                }
                case "WEEKLY" -> {
                    long weeks = ChronoUnit.WEEKS.between(anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                            day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                    if (weeks % interval != 0) {
                        return false;
                    }
                    if (days.isEmpty()) {
                        return day.getDayOfWeek() == anchor.getDayOfWeek();
                    }
                }
                case "MONTHLY" -> {
                    long months = ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), day.withDayOfMonth(1));
                    if (months % interval != 0) {
                        return false;
                    }
                    if (days.isEmpty() && monthDays.isEmpty()) {
                        return day.getDayOfMonth() == anchor.getDayOfMonth();
                    }
                }
                default -> {
                    // HOURLY: every day; the hours are filtered by hoursOf
                }
            }
            if (!days.isEmpty() && days.stream().noneMatch(d -> d.matches(day))) {
                return false;
            }
            if (!monthDays.isEmpty()) {
                int last = day.lengthOfMonth();
                return monthDays.stream().anyMatch(m -> m > 0 ? day.getDayOfMonth() == m
                        : day.getDayOfMonth() == last + m + 1);
            }
            return true;
        }

        private static int number(String text, int min, int max, String name) {
            try {
                int v = Integer.parseInt(text);
                if (v < min || v > max) {
                    throw new NumberFormatException();
                }
                return v;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a whole number from " + min + " to " + max + ".");
            }
        }

        private static TreeSet<Integer> numbers(String text, int min, int max, String name) {
            TreeSet<Integer> out = new TreeSet<>();
            if (text != null) {
                for (String v : text.split(",")) {
                    out.add(number(v.trim(), min, max, name));
                }
            }
            return out;
        }

        private static Instant until(String text, ZoneId zone) {
            try {
                if (text.length() == 8) {
                    return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay(zone)
                            .toInstant().minusSeconds(1);
                }
                return LocalDateTime.parse(text.replace("Z", ""), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        .toInstant(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("UNTIL must be a date (20271231) or a UTC time (20271231T170000Z).");
            }
        }
    }

    /** One BYDAY entry: a weekday, with an optional position in the month (1MO, -1FR). */
    private record DayRule(DayOfWeek day, int ordinal) {

        static DayRule parse(String text, String freq) {
            String t = text.trim();
            String code = t.length() >= 2 ? t.substring(t.length() - 2) : "";
            DayOfWeek day = switch (code) {
                case "MO" -> DayOfWeek.MONDAY;
                case "TU" -> DayOfWeek.TUESDAY;
                case "WE" -> DayOfWeek.WEDNESDAY;
                case "TH" -> DayOfWeek.THURSDAY;
                case "FR" -> DayOfWeek.FRIDAY;
                case "SA" -> DayOfWeek.SATURDAY;
                case "SU" -> DayOfWeek.SUNDAY;
                default -> throw new IllegalArgumentException("BYDAY uses MO, TU, WE, TH, FR, SA and SU.");
            };
            String prefix = t.substring(0, t.length() - 2);
            int ordinal = 0;
            if (!prefix.isEmpty()) {
                if (!freq.equals("MONTHLY")) {
                    throw new IllegalArgumentException("A position such as 1MO or -1FR needs FREQ=MONTHLY.");
                }
                try {
                    ordinal = Integer.parseInt(prefix);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("BYDAY " + t + " is not valid.");
                }
                if (ordinal == 0 || ordinal < -5 || ordinal > 5) {
                    throw new IllegalArgumentException("A BYDAY position is 1 to 5 or -1 to -5.");
                }
            }
            return new DayRule(day, ordinal);
        }

        boolean matches(LocalDate date) {
            if (date.getDayOfWeek() != day) {
                return false;
            }
            if (ordinal == 0) {
                return true;
            }
            if (ordinal > 0) {
                return (date.getDayOfMonth() - 1) / 7 + 1 == ordinal;
            }
            return (date.lengthOfMonth() - date.getDayOfMonth()) / 7 + 1 == -ordinal;
        }
    }
}
