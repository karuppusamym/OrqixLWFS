package com.orqix.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.integration.ConnectionDefinition.WriteTool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The closed argument schema of a write tool (IN04, S75) and its validation. The model can only write message text:
 * email {@code subject} and {@code body}; Teams {@code text} and an optional {@code title}. Recipients, topics and the
 * adapter are never arguments. Every member is a bounded string, unknown members are refused, nothing is repaired
 * silently, and problems describe the member, never its content.
 */
public final class WriteToolArguments {

    /** Upper bound on the raw argument text the model may send for one write. */
    public static final int MAX_RAW_CHARS = 5000;
    static final int MAX_LINE = 200;
    static final int MAX_TEXT = 4000;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Member(boolean required, int maxLength, boolean singleLine) {
    }

    private WriteToolArguments() {
    }

    private static Map<String, Member> members(String kind) {
        return switch (kind) {
            case "email" -> Map.of("subject", new Member(true, MAX_LINE, true), "body", new Member(true, MAX_TEXT, false));
            case "teams" -> Map.of("title", new Member(false, MAX_LINE, true), "text", new Member(true, MAX_TEXT, false));
            default -> throw new IllegalArgumentException("Unsupported write tool kind " + kind);
        };
    }

    /** The JSON Schema offered to the model: closed, string members only, with length limits. */
    public static ObjectNode schema(WriteTool tool) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        for (String name : order(tool.kind())) {
            Member member = members(tool.kind()).get(name);
            ObjectNode property = properties.putObject(name);
            property.put("type", "string");
            property.put("minLength", 1);
            property.put("maxLength", member.maxLength());
            property.put("description", describe(tool.kind(), name, member));
            if (member.required()) {
                required.add(name);
            }
        }
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static List<String> order(String kind) {
        return "email".equals(kind) ? List.of("subject", "body") : List.of("title", "text");
    }

    private static String describe(String kind, String name, Member member) {
        String what = switch (name) {
            case "subject" -> "Subject line of the email";
            case "body" -> "Plain-text body of the email";
            case "title" -> "Optional title of the Teams message";
            default -> "Plain text of the Teams message";
        };
        return what + (member.singleLine() ? ", one line" : "") + ", at most " + member.maxLength() + " characters."
                + ("email".equals(kind) && "body".equals(name) ? " The recipients are fixed; you cannot choose them." : "");
    }

    /**
     * @return the problems (member names and rules only, never values); empty when the arguments are valid
     */
    public static List<String> problems(WriteTool tool, JsonNode arguments) {
        List<String> problems = new ArrayList<>();
        if (arguments == null || !arguments.isObject()) {
            problems.add("the arguments must be one JSON object");
            return problems;
        }
        Map<String, Member> members = members(tool.kind());
        Set<String> allowed = members.keySet();
        for (String name : arguments.propertyNames()) {
            if (!allowed.contains(name)) {
                problems.add("'" + safe(name) + "' is not allowed; use only " + String.join(", ", order(tool.kind())));
            }
        }
        for (String name : order(tool.kind())) {
            Member member = members.get(name);
            JsonNode value = arguments.get(name);
            if (value == null || value.isNull()) {
                if (member.required()) {
                    problems.add("'" + name + "' is required");
                }
                continue;
            }
            if (!value.isString()) {
                problems.add("'" + name + "' must be text");
                continue;
            }
            String text = value.asString();
            if (text.isBlank()) {
                problems.add("'" + name + "' must not be empty");
            } else if (text.length() > member.maxLength()) {
                problems.add("'" + name + "' must be at most " + member.maxLength() + " characters");
            } else if (member.singleLine() && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)) {
                problems.add("'" + name + "' must be a single line");
            } else if (hasControl(text)) {
                problems.add("'" + name + "' must not contain control characters");
            }
        }
        return problems;
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r') {
                return true;
            }
        }
        return false;
    }

    private static String safe(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9_.-]", "?");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) + "..." : cleaned;
    }
}
