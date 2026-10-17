package com.orqix.compiler.internal.eval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.orqix.compiler.FormValidator;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/**
 * Request input validation against the approved form keyword subset (type, required, enum, minLength, maxLength,
 * minimum, maximum, multipleOf, additionalProperties, and for {@code format: date} the calendar date with
 * formatMinimum and formatMaximum) with exact {@link BigDecimal} arithmetic. Types are strict: the
 * string {@code "1800"} is not a number. One error per failed keyword; a field with the wrong type reports only the
 * type error. Errors are ordered by pointer, then code.
 */
public final class DefaultFormValidator implements FormValidator {

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final Pattern UPLOAD_ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    @Override
    public List<FieldError> validate(String inputSchemaJson, String inputJson) {
        return validate(inputSchemaJson,inputJson,null);
    }

    @Override
    public List<FieldError> validate(String inputSchemaJson,String inputJson,String inputUiJson) {
        JsonNode schema = StrictJson.parse(inputSchemaJson);
        JsonNode input = StrictJson.parse(inputJson);
        JsonNode ui=StrictJson.parse(inputUiJson==null ? "{}" : inputUiJson);
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
        var requiredNames=new java.util.HashSet<String>();
        schema.path("required").forEach(n->requiredNames.add(n.asString()));
        ui.path("requiredWhen").propertyNames().forEach(requiredNames::add);
        for (String name:requiredNames) {
            if (com.orqix.compiler.FormVisibility.required(schema,ui,input,name) && !input.has(name)) {
                errors.add(new FieldError(JsonPointers.of(name), "required", "This field is required."));
            }
        }
        for (Map.Entry<String, JsonNode> e : properties.properties()) {
            JsonNode value = input.get(e.getKey());
            if (value != null) {
                if (!com.orqix.compiler.FormVisibility.visible(ui,input,e.getKey()))
                    errors.add(new FieldError(JsonPointers.of(e.getKey()),"hidden","This field is not visible for this request; omit it."));
                else field(JsonPointers.of(e.getKey()), e.getValue(), value, errors);
            }
        }
        errors.sort(Comparator.comparing(FieldError::pointer, JsonPointers::compare)
                .thenComparing(FieldError::code));
        return List.copyOf(errors);
    }

    private static void field(String pointer, JsonNode schema, JsonNode value, List<FieldError> errors) {
        String type = schema.path("type").stringValue("");
        if ("array".equals(type) && (!schema.path("items").isObject() || !schema.has("minItems") || !schema.has("maxItems")))
            throw new IllegalArgumentException("Collections require a scalar item schema and size bounds");
        boolean typeOk = switch (type) {
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && integral(value.decimalValue());
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> throw new IllegalArgumentException("Unsupported field type in input schema: " + type);
        };
        if (!typeOk) {
            String expected = switch (type) {
                case "string" -> "Enter text.";
                case "number" -> "Enter a number.";
                case "integer" -> "Enter a whole number.";
                case "array" -> "Enter a list of values.";
                case "object" -> "Enter a row of field values.";
                default -> "Choose true or false.";
            };
            errors.add(new FieldError(pointer, "type", expected));
            return;
        }
        if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt(1))
                errors.add(new FieldError(pointer, "minItems", "Enter at least " + schema.path("minItems").asInt(1) + " items."));
            if (value.size() > schema.path("maxItems").asInt(25))
                errors.add(new FieldError(pointer, "maxItems", "Enter at most " + schema.path("maxItems").asInt(25) + " items."));
            for (int i = 0; i < value.size(); i++) field(pointer + "/" + i, schema.path("items"), value.get(i), errors);
        }
        if (value.isObject()) {
            JsonNode properties=schema.path("properties");
            for(String key:value.propertyNames())if(!properties.has(key))
                errors.add(new FieldError(pointer+"/"+key,"additionalProperties","This column is not part of the row."));
            for(JsonNode key:schema.path("required"))if(!value.has(key.asString()))
                errors.add(new FieldError(pointer+"/"+key.asString(),"required","This column is required."));
            for(var entry:properties.properties())if(value.has(entry.getKey()))
                field(pointer+"/"+entry.getKey(),entry.getValue(),value.path(entry.getKey()),errors);
            return;
        }
        if (value.isString() && "attachment".equals(schema.path("format").stringValue(""))) {
            // The value is the ID of an uploaded file; the runtime checks that the file exists, is the caller's and
            // fits the field's file types and size (S17).
            if (!UPLOAD_ID.matcher(value.stringValue()).matches()) {
                errors.add(new FieldError(pointer, "format", "Choose a file."));
            }
            return;
        }
        if (value.isString() && "date".equals(schema.path("format").stringValue(""))) {
            date(pointer, schema, value.stringValue(), errors);
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

    /** Calendar dates: YYYY-MM-DD, a day that exists, within formatMinimum and formatMaximum (inclusive). */
    private static void date(String pointer, JsonNode schema, String text, List<FieldError> errors) {
        LocalDate date = parseDate(text);
        if (date == null) {
            errors.add(new FieldError(pointer, "format", "Enter a date in the form YYYY-MM-DD."));
            return;
        }
        LocalDate min = parseDate(schema.path("formatMinimum").stringValue(null));
        LocalDate max = parseDate(schema.path("formatMaximum").stringValue(null));
        if (min != null && date.isBefore(min)) {
            errors.add(new FieldError(pointer, "formatMinimum", "Choose a date on or after " + show(min) + "."));
        }
        if (max != null && date.isAfter(max)) {
            errors.add(new FieldError(pointer, "formatMaximum", "Choose a date on or before " + show(max) + "."));
        }
    }

    private static LocalDate parseDate(String text) {
        if (text == null || !DATE.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String show(LocalDate date) {
        return date.getDayOfMonth() + " " + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
                + date.getYear();
    }

    private static boolean integral(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }
}
