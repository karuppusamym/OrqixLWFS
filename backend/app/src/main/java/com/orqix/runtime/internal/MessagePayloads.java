package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.orqix.shared.problem.ApiException.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Message payload rules (S16 section 3). The API accepts a flat object of scalar values (at most 10, names like request
 * fields, text up to 1,000 characters). A wait then accepts only the values it declares, of the declared type, and
 * insists on those marked required.
 */
final class MessagePayloads {

    static final int MAX_KEYS = 10;
    static final int MAX_TEXT = 1000;
    static final Pattern NAME = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    /** Largest accepted correlation key. */
    static final int MAX_KEY = 200;
    static final Pattern MESSAGE_NAME = Pattern.compile("^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$");

    private MessagePayloads() {
    }

    /** Shape checks that need no wait: an object of at most {@link #MAX_KEYS} flat, bounded scalar values. */
    static List<FieldError> shape(JsonNode payload) {
        List<FieldError> errors = new ArrayList<>();
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return errors;
        }
        if (!payload.isObject()) {
            errors.add(new FieldError("/payload", "type", "The payload must be an object of values."));
            return errors;
        }
        if (payload.size() > MAX_KEYS) {
            errors.add(new FieldError("/payload", "maxProperties", "A message can carry at most " + MAX_KEYS + " values."));
        }
        for (Map.Entry<String, JsonNode> e : payload.properties()) {
            String pointer = "/payload/" + e.getKey().replace("~", "~0").replace("/", "~1");
            JsonNode v = e.getValue();
            if (!NAME.matcher(e.getKey()).matches()) {
                errors.add(new FieldError(pointer, "propertyNames",
                        "Value names start with a lowercase letter and use only letters and digits."));
            } else if (v.isString()) {
                if (v.stringValue().length() > MAX_TEXT) {
                    errors.add(new FieldError(pointer, "maxLength", "Text can be at most " + MAX_TEXT + " characters."));
                }
            } else if (!(v.isNumber() || v.isBoolean() || v.isNull())) {
                errors.add(new FieldError(pointer, "type", "A value must be text, a number or true or false."));
            }
        }
        return errors;
    }

    /**
     * Checks a payload against the values a wait declares ({@code config.payload} of the step) and returns the values
     * the step provides (only declared, non-null values).
     *
     * @param output receives the accepted values
     */
    static List<FieldError> validate(JsonNode declared, JsonNode payload, ObjectNode output) {
        List<FieldError> errors = new ArrayList<>();
        Set<String> known = new HashSet<>();
        JsonNode values = payload != null && payload.isObject() ? payload
                : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (JsonNode field : declared) {
            String name = field.path("name").asString();
            known.add(name);
            JsonNode value = values.path(name);
            String pointer = "/payload/" + name;
            if (value.isMissingNode() || value.isNull()) {
                if (field.path("required").asBoolean(false)) {
                    errors.add(new FieldError(pointer, "required", "The message must carry " + name + "."));
                }
                continue;
            }
            String problem = typeProblem(field.path("type").asString(), value);
            if (problem != null) {
                errors.add(new FieldError(pointer, "type", name + " " + problem + "."));
            } else {
                output.set(name, value);
            }
        }
        for (Map.Entry<String, JsonNode> e : values.properties()) {
            if (!known.contains(e.getKey())) {
                errors.add(new FieldError("/payload/" + e.getKey(), "unknown",
                        "This wait does not declare a value named " + e.getKey() + "."));
            }
        }
        return errors;
    }

    private static String typeProblem(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isString() ? null : "must be text";
            case "number" -> value.isNumber() ? null : "must be a number";
            case "integer" -> value.isIntegralNumber() || (value.isNumber() && integral(value.decimalValue()))
                    ? null : "must be a whole number";
            case "boolean" -> value.isBoolean() ? null : "must be true or false";
            case "date" -> date(value);
            default -> "has an unsupported type";
        };
    }

    private static boolean integral(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static String date(JsonNode value) {
        if (!value.isString()) {
            return "must be a date written YYYY-MM-DD";
        }
        try {
            LocalDate.parse(value.stringValue());
            return value.stringValue().length() == 10 ? null : "must be a date written YYYY-MM-DD";
        } catch (DateTimeParseException e) {
            return "must be a date written YYYY-MM-DD";
        }
    }
}
