package com.orqix.runtime.internal;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.orqix.compiler.FormValidator.FieldError;
import com.orqix.runtime.engine.OrqixWait;

import tools.jackson.databind.JsonNode;

/**
 * Start-time check of "wait until a date" steps (S12 section 10): a date taken from the request form must not be more
 * than {@link OrqixWait#HORIZON_DAYS} days after today (UTC), so the requester hears about it on the form instead of
 * the request failing later. Because the wait is reached no earlier than the start, a date that passes here also
 * passes the check of {@link OrqixWait} when the wait begins. Dates in the past are allowed: the wait ends at once.
 */
final class WaitDates {

    private WaitDates() {
    }

    /** @return one error per form date field that a wait step would reject as too far away */
    static List<FieldError> check(JsonNode definition, JsonNode input, LocalDate today) {
        LocalDate latest = today.plusDays(OrqixWait.HORIZON_DAYS);
        List<FieldError> errors = new ArrayList<>();
        for (JsonNode node : definition.path("nodes")) {
            if (!"wait".equals(node.path("type").asString(""))) {
                continue;
            }
            String ref = node.path("config").path("untilField").asString("");
            if (!ref.startsWith("/input/")) {
                continue;
            }
            String key = ref.substring("/input/".length());
            JsonNode value = input.path(key);
            if (!value.isString()) {
                continue; // missing or wrongly typed: reported by the form validation
            }
            try {
                if (LocalDate.parse(value.asString()).isAfter(latest)) {
                    errors.add(new FieldError("/" + key, "formatMaximum", "Choose a date no later than " + latest
                            + ". Requests can wait for at most " + OrqixWait.HORIZON_DAYS + " days."));
                }
            } catch (DateTimeParseException e) {
                // not a date: reported by the form validation
            }
        }
        return errors;
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
