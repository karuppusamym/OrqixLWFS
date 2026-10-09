package com.orqix.compiler.internal.schema;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.networknt.schema.Error;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.schema.SchemaValidation.Problem;

import tools.jackson.databind.JsonNode;

/**
 * Turns JSON Schema errors into business-English messages and stable JSON Pointers. Missing and unexpected members
 * point at the member itself (for example {@code /nodes/4/config/candidateGroupRef}) so the editor can focus it.
 */
final class SchemaMessages {

    private static final Pattern NODE = Pattern.compile("^/nodes/(\\d+)(/.*)?$");
    private static final Pattern DEF = Pattern.compile("#/\\$defs/([A-Za-z]+)/");

    /** Missing config members per node type, phrased after the node label. */
    private static final Map<String, String> REQUIRED_CONFIG = Map.ofEntries(
            Map.entry("approval/candidateGroupRef", "needs an approver group."),
            Map.entry("approval/rejectReasonRequired", "must require a reason when a request is rejected."),
            Map.entry("decision/decisionRef", "needs a rule table."),
            Map.entry("http/connectionRef", "needs a connection."),
            Map.entry("http/operation", "needs an operation of its connection."),
            Map.entry("http/inputMapping", "needs at least one request field mapping."),
            Map.entry("http/resultMapping", "needs at least one result mapping."),
            Map.entry("http/timeoutSeconds", "needs a timeout in seconds."),
            Map.entry("notification/channel", "needs a channel (inApp)."),
            Map.entry("notification/recipient", "needs a recipient (initiator)."),
            Map.entry("notification/message", "needs a message."),
            Map.entry("end/businessOutcome", "needs an outcome name, for example Fulfilled."),
            Map.entry("end/outcomeKind", "needs an outcome kind: success or rejected."));

    private static final Map<String, String> PATTERN_BY_DEF = Map.of(
            "key", "Use 2 to 64 lower-case letters, digits and hyphens, starting with a letter and not ending with a"
                    + " hyphen.",
            "id", "IDs must start with a letter and use only letters, digits, hyphens and underscores (at most 64"
                    + " characters, no double underscore).",
            "fieldKey", "Field names must start with a lower-case letter and use only letters and digits (at most 64"
                    + " characters).",
            "fieldRef", "A field reference must look like /input/<field> or /steps/<stepId>/<output>.");

    private SchemaMessages() {
    }

    static Problem describe(JsonNode doc, Error error, String instance) {
        String keyword = error.getKeyword() == null ? "" : error.getKeyword();
        String property = error.getProperty();
        Object[] args = error.getArguments() == null ? new Object[0] : error.getArguments();
        String location = error.getSchemaLocation() == null ? "" : error.getSchemaLocation().toString();
        switch (keyword) {
            case "required" -> {
                return new Problem(JsonPointers.append(instance, property), required(doc, instance, property));
            }
            case "additionalProperties" -> {
                String path = JsonPointers.append(instance, property);
                if (instance.matches("^/nodes/\\d+/config$")) {
                    return new Problem(path, subject(doc, instance) + " has an unknown setting \"" + property
                            + "\".");
                }
                return new Problem(path, "The property \"" + property + "\" is not allowed here.");
            }
            case "propertyNames" -> {
                String path = JsonPointers.append(instance, property);
                if (instance.equals("/uiLayout/positions")) {
                    return new Problem(path, "\"" + property + "\" is not a valid step ID. "
                            + PATTERN_BY_DEF.get("id"));
                }
                return new Problem(path, "The name \"" + property + "\" is not allowed here. "
                        + PATTERN_BY_DEF.get("fieldKey"));
            }
            case "type" -> {
                String found = args.length > 0 ? String.valueOf(args[0]) : "another type";
                String expected = args.length > 1 ? String.valueOf(args[1]) : "another type";
                return new Problem(instance, "Expected " + typeWords(expected) + " here, but found "
                        + typeWords(found) + ".");
            }
            case "const" -> {
                return new Problem(instance, "This value must be " + (args.length > 0 ? args[0] : "fixed") + ".");
            }
            case "enum" -> {
                return new Problem(instance, "This value is not allowed. Allowed values: "
                        + (args.length > 0 ? stripBrackets(String.valueOf(args[0])) : "see the documentation")
                        + ".");
            }
            case "pattern" -> {
                return new Problem(instance, pattern(location, instance));
            }
            case "minLength" -> {
                String n = arg(args);
                return new Problem(instance, "1".equals(n) ? "This text cannot be empty."
                        : "This text must be at least " + n + " characters long.");
            }
            case "maxLength" -> {
                return new Problem(instance, "This text is too long (at most " + arg(args) + " characters).");
            }
            case "minimum" -> {
                return new Problem(instance, "This number must be at least " + arg(args) + ".");
            }
            case "maximum" -> {
                return new Problem(instance, "This number must be at most " + arg(args) + ".");
            }
            case "exclusiveMinimum" -> {
                return new Problem(instance, "This number must be greater than " + arg(args) + ".");
            }
            case "exclusiveMaximum" -> {
                return new Problem(instance, "This number must be less than " + arg(args) + ".");
            }
            case "minItems" -> {
                return new Problem(instance, "This list needs at least " + arg(args) + " entries.");
            }
            case "maxItems" -> {
                return new Problem(instance, "This list can have at most " + arg(args) + " entries.");
            }
            case "minProperties" -> {
                String n = arg(args);
                return new Problem(instance, "1".equals(n) ? "This cannot be empty."
                        : "This needs at least " + n + " entries.");
            }
            case "maxProperties" -> {
                return new Problem(instance, "This can have at most " + arg(args) + " entries.");
            }
            case "uniqueItems" -> {
                return new Problem(instance, "This list contains the same entry more than once.");
            }
            case "not" -> {
                if (instance.matches("^/transitions/\\d+$")) {
                    return new Problem(JsonPointers.append(instance, "condition"),
                            "Only a path that continues on a condition (on: condition) can have a condition.");
                }
                return new Problem(instance, "This combination of settings is not allowed.");
            }
            default -> {
                String message = error.getMessage() == null ? "is not valid" : error.getMessage();
                return new Problem(instance, "This value is not valid: " + message + ".");
            }
        }
    }

    private static String required(JsonNode doc, String instance, String property) {
        Matcher m = NODE.matcher(instance);
        if (m.matches()) {
            JsonNode node = doc.path("nodes").path(Integer.parseInt(m.group(1)));
            String rest = m.group(2) == null ? "" : m.group(2);
            if (rest.equals("/config")) {
                String type = node.path("type").isString() ? node.get("type").stringValue() : "";
                String phrase = REQUIRED_CONFIG.get(type + "/" + property);
                if (phrase != null) {
                    return subject(doc, instance) + " " + phrase;
                }
            }
            if (rest.isEmpty()) {
                return switch (property) {
                    case "label" -> "Every step needs a name.";
                    case "id" -> "Every step needs an ID.";
                    case "config" -> subject(doc, instance) + " needs its settings (config).";
                    default -> "Every step needs \"" + property + "\".";
                };
            }
        }
        if (instance.matches("^/transitions/\\d+$")) {
            if (property.equals("condition")) {
                return "A path that continues on a condition (on: condition) needs a condition.";
            }
            return "Every path needs \"" + property + "\".";
        }
        return "The required property \"" + property + "\" is missing.";
    }

    /** @return the label of the node an instance location belongs to, or a neutral subject */
    private static String subject(JsonNode doc, String instance) {
        Matcher m = NODE.matcher(instance);
        if (m.matches()) {
            JsonNode node = doc.path("nodes").path(Integer.parseInt(m.group(1)));
            if (node.path("label").isString() && !node.get("label").stringValue().isBlank()) {
                return node.get("label").stringValue();
            }
            if (node.path("id").isString()) {
                return "Step " + node.get("id").stringValue();
            }
        }
        return "This step";
    }

    private static String pattern(String location, String instance) {
        Matcher m = DEF.matcher(location);
        if (m.find() && PATTERN_BY_DEF.containsKey(m.group(1))) {
            return PATTERN_BY_DEF.get(m.group(1));
        }
        if (instance.matches("^/nodes/\\d+/config/resultMapping/.*")) {
            return "A result pointer must be a JSON Pointer into the response, such as /id.";
        }
        if (instance.matches("^/nodes/\\d+/config/operation$")) {
            return "Operation names start with a lower-case letter and use only letters and digits.";
        }
        if (instance.matches("^/nodes/\\d+/config/businessOutcome$")) {
            return "An outcome name starts with a capital letter and uses only letters and spaces (at most 40"
                    + " characters).";
        }
        return "This value does not have the required format.";
    }

    private static String typeWords(String jsonType) {
        return switch (jsonType) {
            case "string" -> "text";
            case "integer" -> "a whole number";
            case "number" -> "a number";
            case "boolean" -> "true or false";
            case "object" -> "an object";
            case "array" -> "a list";
            case "null" -> "null";
            default -> stripBrackets(jsonType);
        };
    }

    private static String arg(Object[] args) {
        return args.length > 0 ? String.valueOf(args[0]) : "?";
    }

    private static String stripBrackets(String text) {
        if (text.startsWith("[") && text.endsWith("]")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
