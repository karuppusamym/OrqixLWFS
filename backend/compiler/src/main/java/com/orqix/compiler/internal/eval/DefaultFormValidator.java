package com.orqix.compiler.internal.eval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.orqix.compiler.FormValidator;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/**
 * Request input validation against the approved form keyword subset (type, required, enum, minLength, maxLength,
 * minimum, maximum, multipleOf, additionalProperties) with exact {@link BigDecimal} arithmetic. Types are strict: the
 * string {@code "1800"} is not a number. One error per failed keyword; a field with the wrong type reports only the
 * type error. Errors are ordered by pointer, then code.
 */
public final class DefaultFormValidator implements FormValidator {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    @Override
    public List<FieldError> validate(String inputSchemaJson, String inputJson) {
        JsonNode schema = StrictJson.parse(inputSchemaJson);
        JsonNode input = StrictJson.parse(inputJson);
        if (!schema.isObject()) {
            throw new IllegalArgumentException("The input schema must be a JSON object");
        }
        List<FieldError> errors = new ArrayList<>();
        if (!input.isObject()) {
            errors.add(new FieldError("", "type", "The request must be an object with the form fields."));
            return errors;
        }
        JsonNode properties = schema.path("properties");
        boolean additionalAllowed = !schema.has("additionalProperties")
                || !schema.get("additionalProperties").isBoolean()
                || schema.get("additionalProperties").booleanValue();
        if (!additionalAllowed) {
            for (String name : input.propertyNames()) {
                if (!properties.has(name)) {
                    errors.add(new FieldError(JsonPointers.of(name), "additionalProperties",
                            "This field is not part of the form."));
                }
            }
        }
        for (JsonNode required : schema.path("required")) {
            String name = required.stringValue("");
            if (!input.has(name)) {
                errors.add(new FieldError(JsonPointers.of(name), "required", "This field is required."));
            }
        }
        for (Map.Entry<String, JsonNode> e : properties.properties()) {
            JsonNode value = input.get(e.getKey());
            if (value != null) {
                field(JsonPointers.of(e.getKey()), e.getValue(), value, errors);
            }
        }
        errors.sort(Comparator.comparing(FieldError::pointer, JsonPointers::compare)
                .thenComparing(FieldError::code));
        return List.copyOf(errors);
    }

    private static void field(String pointer, JsonNode schema, JsonNode value, List<FieldError> errors) {
        String type = schema.path("type").stringValue("");
        boolean typeOk = switch (type) {
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && integral(value.decimalValue());
            case "boolean" -> value.isBoolean();
            default -> throw new IllegalArgumentException("Unsupported field type in input schema: " + type);
        };
        if (!typeOk) {
            String expected = switch (type) {
                case "string" -> "Enter text.";
                case "number" -> "Enter a number.";
                case "integer" -> "Enter a whole number.";
                default -> "Choose true or false.";
            };
            errors.add(new FieldError(pointer, "type", expected));
            return;
        }
        if (value.isString()) {
            String text = value.stringValue();
            if (schema.has("enum")) {
                List<String> allowed = new ArrayList<>();
                schema.get("enum").forEach(v -> allowed.add(v.stringValue("")));
                if (!allowed.contains(text)) {
                    errors.add(new FieldError(pointer, "enum", "Choose one of: " + String.join(", ", allowed) + "."));
                }
            }
            int length = text.codePointCount(0, text.length());
            if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
                int min = schema.get("minLength").asInt();
                errors.add(new FieldError(pointer, "minLength", min == 1 ? "This field cannot be empty."
                        : "Enter at least " + min + " characters."));
            }
            if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
                errors.add(new FieldError(pointer, "maxLength", "Enter at most "
                        + schema.get("maxLength").asInt() + " characters."));
            }
        }
        if (value.isNumber()) {
            BigDecimal number = value.decimalValue();
            if (schema.path("minimum").isNumber() && number.compareTo(schema.get("minimum").decimalValue()) < 0) {
                errors.add(new FieldError(pointer, "minimum", "Enter a value of at least "
                        + schema.get("minimum").decimalValue().toPlainString() + "."));
            }
            if (schema.path("maximum").isNumber() && number.compareTo(schema.get("maximum").decimalValue()) > 0) {
                errors.add(new FieldError(pointer, "maximum", "Enter a value of at most "
                        + schema.get("maximum").decimalValue().toPlainString() + "."));
            }
            if (schema.path("multipleOf").isNumber()) {
                BigDecimal step = schema.get("multipleOf").decimalValue();
                if (step.signum() > 0 && number.remainder(step).signum() != 0) {
                    String message = step.compareTo(CENT) == 0 ? "Use at most two decimal places."
                            : step.compareTo(BigDecimal.ONE) == 0 ? "Enter a whole number."
                            : "Enter a multiple of " + step.toPlainString() + ".";
                    errors.add(new FieldError(pointer, "multipleOf", message));
                }
            }
        }
    }

    private static boolean integral(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }
}
