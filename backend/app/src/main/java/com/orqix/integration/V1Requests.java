package com.orqix.integration;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.ConnectionContracts;
import com.orqix.integration.ConnectionDefinition.Operation;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the wire request of a v1 operation (CG-C01, S130) from the flat inputs a workflow mapped: path parameters
 * (checked against their pattern or enum, percent-encoded, never '/', '..' or control characters), query parameters
 * (const, input or a template filled by a reviewed encoder), allow-listed headers and the body (json template or flat
 * fields, form, multipart, binary from authorized request files). Any value that breaks a rule throws
 * {@link IllegalArgumentException}: the request is not sent.
 */
final class V1Requests {

    /** The parts of one request; {@code body} null means no body. */
    record Built(URI uri, Map<String, String> headers, byte[] body, String contentType) {
    }

    /** Request-scoped context for files and pagination. */
    record Context(UUID workspaceId, UUID runId, RequestFiles files, Map<String, String> extraQuery, URI absolute) {
        static final Context NONE = new Context(null, null, null, Map.of(), null);
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][A-Za-z0-9]{0,63})\\}");
    private static final long MAX_BODY = RequestFiles.MAX_BYTES + 64 * 1024;

    private V1Requests() {
    }

    static Built build(String baseUrl, Operation operation, JsonNode inputs, Json json, Context context) {
        JsonNode op = operation.source();
        JsonNode values = inputs == null ? json.object() : inputs;
        JsonNode fields = op.path("requestSchema").path("properties");
        URI uri;
        if (context.absolute() != null) {
            uri = context.absolute();
        } else {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            StringBuilder url = new StringBuilder(base).append(path(op, values));
            String query = query(op, values, fields, context.extraQuery());
            if (!query.isEmpty()) {
                url.append('?').append(query);
            }
            uri = URI.create(url.toString());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode declared = op.path("headers");
        for (String name : declared.propertyNames()) {
            JsonNode h = declared.get(name);
            String value = h.has("const") ? h.get("const").asString() : text(values.get(h.get("input").asString()));
            if (value == null) {
                continue;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c == 0x7f) {
                    throw new IllegalArgumentException("Header value contains a control character");
                }
            }
            headers.put(name, value);
        }
        String method = op.path("method").asString();
        if ("GET".equals(method)) {
            return new Built(uri, headers, null, null);
        }
        String contentType = op.path("contentType").asString("json");
        return switch (contentType) {
            case "form" -> new Built(uri, headers, form(op.get("body"), values), "application/x-www-form-urlencoded");
            case "multipart" -> multipart(uri, headers, op.get("body"), values, fields, context);
            case "binary" -> {
                RequestFiles.File file = file(values.get(op.get("binaryInput").asString()), context);
                yield new Built(uri, headers, file.bytes(), file.contentType());
            }
            default -> {
                JsonNode body = jsonBody(op, values, json);
                yield body == null ? new Built(uri, headers, null, null)
                        : new Built(uri, headers, json.write(body).getBytes(StandardCharsets.UTF_8), "application/json");
            }
        };
    }

    // ------------------------------------------------------------------------------------------------- path

    private static String path(JsonNode op, JsonNode values) {
        String template = op.path("path").asString();
        JsonNode params = op.path("pathParams");
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            JsonNode param = params.get(name);
            String value = param.has("const") ? param.get("const").asString() : text(values.get(name));
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Path parameter " + name + " has no value");
            }
            if (!param.has("const")) {
                conforms(param, value, name);
            }
            if (!ConnectionContracts.safePathValue(value)) {
                throw new IllegalArgumentException("Path parameter " + name + " is not a single safe segment");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rfc3986(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ query

    private static String query(JsonNode op, JsonNode values, JsonNode fields, Map<String, String> extra) {
        List<String> parts = new ArrayList<>();
        JsonNode query = op.path("query");
        for (String name : query.propertyNames()) {
            JsonNode q = query.get(name);
            String value;
            if (q.has("const")) {
                value = text(q.get("const"));
            } else if (q.has("input")) {
                String input = q.get("input").asString();
                value = text(values.get(input));
                if (value != null) {
                    conforms(fields.get(input), value, input);
                }
            } else {
                value = template(q.get("template").asString(), q.get("encoder").asString(), values, fields);
            }
            if (value != null) {
                parts.add(rfc3986(name) + "=" + rfc3986(value));
            }
        }
        extra.forEach((name, value) -> parts.add(rfc3986(name) + "=" + rfc3986(value)));
        return String.join("&", parts);
    }

    /** @return the filled template, or null when a placeholder has no value (the parameter is left out) */
    static String template(String template, String encoder, JsonNode values, JsonNode fields) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = text(values.get(name));
            if (value == null) {
                return null;
            }
            conforms(fields.get(name), value, name);
            m.appendReplacement(out, Matcher.quoteReplacement(encode(encoder, value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Reviewed encoders (never package scripts): the value is escaped for the target syntax. */
    static String encode(String encoder, String value) {
        return switch (encoder) {
            // The whole parameter is percent-encoded afterwards; the value must stay one literal.
            case "rfc3986" -> value;
            // ServiceNow encoded queries join conditions with '^' (^OR, ^NQ, ^ORDERBY ...); a value can never carry
            // one, nor line breaks or control characters. Patterns on the field already narrow the value.
            case "servicenowEncodedQuery" -> {
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c == '^' || c < 0x20 || c == 0x7f) {
                        throw new IllegalArgumentException("Value cannot be used in an encoded query");
                    }
                }
                yield value;
            }
            default -> throw new IllegalArgumentException("Unknown encoder " + encoder);
        };
    }

    // ------------------------------------------------------------------------------------------------- body

    private static JsonNode jsonBody(JsonNode op, JsonNode values, Json json) {
        if (op.has("body")) {
            return templateObject(op.get("body"), values, json);
        }
        List<String> flat = ConnectionContracts.flatBodyFields(op);
        ObjectNode body = json.object();
        for (String field : flat) {
            JsonNode value = values.get(field);
            if (value != null && !value.isNull()) {
                body.set(field, value);
            }
        }
        // DELETE without fields sends no body; POST, PUT and PATCH always send an object (as in v0).
        return body.isEmpty() && "DELETE".equals(op.path("method").asString()) ? null : body;
    }

    private static ObjectNode templateObject(JsonNode template, JsonNode values, Json json) {
        ObjectNode out = json.object();
        for (String member : template.propertyNames()) {
            JsonNode value = templateValue(template.get(member), values, json);
            if (value != null) {
                out.set(member, value);
            }
        }
        return out;
    }

    private static JsonNode templateValue(JsonNode value, JsonNode values, Json json) {
        if (value.has("input")) {
            JsonNode v = values.get(value.get("input").asString());
            return v == null || v.isNull() ? null : v;
        }
        if (value.has("const")) {
            return value.get("const");
        }
        if (value.has("object")) {
            return templateObject(value.get("object"), values, json);
        }
        ArrayNode array = json.mapper().createArrayNode();
        for (JsonNode item : value.path("array")) {
            JsonNode v = templateValue(item, values, json);
            if (v != null) {
                array.add(v);
            }
        }
        return array;
    }

    private static byte[] form(JsonNode template, JsonNode values) {
        List<String> parts = new ArrayList<>();
        for (String member : template.propertyNames()) {
            JsonNode v = template.get(member);
            String value = v.has("const") ? text(v.get("const")) : text(values.get(v.get("input").asString()));
            if (value != null) {
                parts.add(rfc3986(member) + "=" + rfc3986(value));
            }
        }
        return String.join("&", parts).getBytes(StandardCharsets.US_ASCII);
    }

    private static Built multipart(URI uri, Map<String, String> headers, JsonNode template, JsonNode values,
            JsonNode fields, Context context) {
        String boundary = "orqix-" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String member : template.propertyNames()) {
            JsonNode v = template.get(member);
            String input = v.has("input") ? v.get("input").asString() : null;
            boolean isFile = input != null && "attachment".equals(fields.path(input).path("format").asString());
            write(out, "--" + boundary + "\r\n");
            if (isFile) {
                RequestFiles.File file = file(values.get(input), context);
                write(out, "Content-Disposition: form-data; name=\"" + quoted(member) + "\"; filename=\""
                        + quoted(file.name()) + "\"\r\nContent-Type: " + file.contentType() + "\r\n\r\n");
                out.writeBytes(file.bytes());
                write(out, "\r\n");
            } else {
                String value = v.has("const") ? text(v.get("const")) : text(values.get(input));
                write(out, "Content-Disposition: form-data; name=\"" + quoted(member) + "\"\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n\r\n" + (value == null ? "" : value) + "\r\n");
            }
            if (out.size() > MAX_BODY) {
                throw new IllegalArgumentException("Body exceeds the size limit");
            }
        }
        write(out, "--" + boundary + "--\r\n");
        return new Built(uri, headers, out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private static RequestFiles.File file(JsonNode id, Context context) {
        String text = text(id);
        if (text == null || context.files() == null || context.runId() == null) {
            throw new IllegalArgumentException("No request file for the body");
        }
        UUID attachment;
        try {
            attachment = UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The file reference is not a file of this request");
        }
        RequestFiles.File file = context.files().read(context.workspaceId(), context.runId(), attachment)
                .orElseThrow(() -> new IllegalArgumentException("The file is not an available file of this request"));
        if (file.bytes().length > RequestFiles.MAX_BYTES) {
            throw new IllegalArgumentException("The file exceeds the size limit");
        }
        return file;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** Checks a mapped value against the declared pattern, enum and maxLength of its field. */
    static void conforms(JsonNode field, String value, String name) {
        if (field == null) {
            return;
        }
        if (field.has("maxLength") && value.length() > field.get("maxLength").asInt()) {
            throw new IllegalArgumentException("Value of " + name + " is too long");
        }
        if (field.has("enum")) {
            boolean listed = false;
            for (JsonNode allowed : field.get("enum")) {
                listed |= allowed.asString().equals(value);
            }
            if (!listed) {
                throw new IllegalArgumentException("Value of " + name + " is not allowed");
            }
        }
        if (field.has("pattern") && !Pattern.compile(field.get("pattern").asString()).matcher(value).find()) {
            throw new IllegalArgumentException("Value of " + name + " does not match its pattern");
        }
        if ("integer".equals(field.path("type").asString()) && !value.matches("^-?[0-9]{1,18}$")) {
            throw new IllegalArgumentException("Value of " + name + " is not a whole number");
        }
    }

    /** @return the scalar as text (numbers in plain notation), or null when absent; containers are refused */
    static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isContainer()) {
            throw new IllegalArgumentException("A list or object cannot be sent here");
        }
        if (value.isNumber()) {
            BigDecimal decimal = value.decimalValue();
            return decimal.stripTrailingZeros().scale() <= 0 ? decimal.toBigInteger().toString()
                    : decimal.toPlainString();
        }
        return value.asString();
    }

    /** RFC 3986 percent-encoding of everything except unreserved characters. */
    static String rfc3986(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(c == '"' || c == '\\' || c < 0x20 ? '_' : c);
        }
        return out.toString();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
