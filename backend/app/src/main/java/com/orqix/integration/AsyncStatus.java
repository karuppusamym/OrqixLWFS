package com.orqix.integration;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.ConnectionContracts;

import tools.jackson.databind.JsonNode;

/**
 * Start and follow up (CG-W02, docs/build/S133-connector-packages.md section 2): reads the status location of an
 * accepted asynchronous answer and turns it into the path parameters of the status operation ({@code /@async/<name>}
 * results). A status URL is accepted only when it has the binding's scheme, host, port and base path; anything else is
 * refused as {@code STATUS_URL_OUTSIDE_BINDING}, so a response can never point Orqix at another host (server-side
 * request forgery through a response). The rest of the path must match the status operation's path template, and every
 * value must pass that parameter's pattern, enum and safe-segment checks ({@code STATUS_URL_UNEXPECTED} otherwise).
 */
final class AsyncStatus {

    static final String OUTSIDE = "STATUS_URL_OUTSIDE_BINDING";
    static final String UNMATCHED = "STATUS_URL_UNEXPECTED";
    static final String MISSING = "STATUS_LOCATION_MISSING";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{([a-z][A-Za-z0-9]{0,63})\\}$");

    /** A refused location; the message is fixed text, never the location itself. */
    static final class Refused extends RuntimeException {
        private final String code;

        Refused(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private AsyncStatus() {
    }

    /** @return true when the status is one of the operation's accepted statuses */
    static boolean accepted(ConnectionDefinition.Operation operation, int status) {
        if (!operation.v1() || operation.source() == null || !operation.source().has("async")) {
            return false;
        }
        for (JsonNode s : operation.source().get("async").path("acceptedStatuses")) {
            if (s.asInt() == status) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param requestUri the URI the accepted request was sent to (relative locations resolve against it)
     * @return status operation path parameter values by name, typed like the parameter (text or whole number)
     */
    static Map<String, JsonNode> read(String baseUrl, URI requestUri, ConnectionDefinition definition,
            ConnectionDefinition.Operation operation, HttpHeaders headers, JsonNode body) {
        Map<String, String> raw = readText(baseUrl, requestUri, definition, operation, headers, body);
        Map<String, JsonNode> params = ConnectionContracts.asyncResults(definition.document(), operation.source());
        Map<String, JsonNode> typed = new LinkedHashMap<>();
        raw.forEach((name, value) -> typed.put(name, "integer".equals(params.get(name).path("type").asString())
                ? tools.jackson.databind.node.JsonNodeFactory.instance.numberNode(Long.parseLong(value))
                : tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(value)));
        return typed;
    }

    private static Map<String, String> readText(String baseUrl, URI requestUri, ConnectionDefinition definition,
            ConnectionDefinition.Operation operation, HttpHeaders headers, JsonNode body) {
        JsonNode async = operation.source().get("async");
        JsonNode statusOp = definition.document().path("operations").get(async.path("statusOperation").asString());
        if (statusOp == null) {
            throw new Refused(UNMATCHED, "The status operation is not part of this connection.");
        }
        String value = null;
        JsonNode from = async.path("statusFrom");
        if (from.has("header")) {
            value = headers == null ? null : headers.firstValue(from.get("header").asString()).orElse(null);
        } else if (from.has("bodyPointer") && body != null) {
            JsonNode at = body.at(from.get("bodyPointer").asString());
            if (at.isString() || at.isIntegralNumber()) {
                value = at.asString();
            }
        }
        if (value == null || value.isBlank() || value.length() > 2048) {
            throw new Refused(MISSING, "The accepted answer has no usable status location.");
        }
        value = value.strip();
        Map<String, JsonNode> params = ConnectionContracts.asyncResults(definition.document(), operation.source());
        if (!value.contains("://") && !value.startsWith("/")) {
            // A bare reference (for example a job id) fills the status operation's single path parameter.
            if (params.size() != 1) {
                throw new Refused(UNMATCHED, "A bare status reference needs a status operation with one path parameter.");
            }
            Map.Entry<String, JsonNode> only = params.entrySet().iterator().next();
            return Map.of(only.getKey(), checked(only.getKey(), only.getValue(), value));
        }
        URI location;
        try {
            location = requestUri.resolve(new URI(value)).normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new Refused(OUTSIDE, "The status location is not a valid address inside the binding.");
        }
        URI base = URI.create(baseUrl);
        String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        String path = location.getRawPath() == null ? "" : location.getRawPath();
        String lower = path.toLowerCase(Locale.ROOT);
        boolean inside = location.isAbsolute() && location.getRawUserInfo() == null && location.getRawFragment() == null
                && base.getScheme().equalsIgnoreCase(location.getScheme())
                && base.getHost() != null && base.getHost().equalsIgnoreCase(location.getHost())
                && port(base) == port(location)
                && path.startsWith(basePath + "/")
                && !path.contains("/../") && !path.endsWith("/..") && !lower.contains("%2f") && !lower.contains("%5c")
                && !lower.contains("%2e");
        if (!inside) {
            throw new Refused(OUTSIDE, "The status location is outside the binding's address.");
        }
        return match(statusOp, params, path.substring(basePath.length()));
    }

    /** Matches the path below the base against the status operation's path template, segment by segment. */
    private static Map<String, String> match(JsonNode statusOp, Map<String, JsonNode> params, String relative) {
        String[] template = statusOp.path("path").asString().split("/", -1);
        String[] actual = relative.split("/", -1);
        if (template.length != actual.length) {
            throw new Refused(UNMATCHED, "The status location does not match the status operation's address.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode declared = statusOp.path("pathParams");
        for (int i = 0; i < template.length; i++) {
            Matcher m = PLACEHOLDER.matcher(template[i]);
            if (!m.matches()) {
                if (!template[i].equals(actual[i])) {
                    throw new Refused(UNMATCHED, "The status location does not match the status operation's address.");
                }
                continue;
            }
            String name = m.group(1);
            String decoded = decode(actual[i]);
            JsonNode param = declared.get(name);
            if (param == null) {
                throw new Refused(UNMATCHED, "The status location does not match the status operation's address.");
            }
            if (param.has("const")) {
                if (!param.get("const").asString().equals(decoded)) {
                    throw new Refused(UNMATCHED, "The status location does not match the status operation's address.");
                }
                continue;
            }
            values.put(name, checked(name, params.get(name), decoded));
        }
        return values;
    }

    private static String checked(String name, JsonNode param, String value) {
        try {
            if (value == null || value.isEmpty() || !ConnectionContracts.safePathValue(value)) {
                throw new IllegalArgumentException("unsafe");
            }
            V1Requests.conforms(param, value, name);
            return value;
        } catch (IllegalArgumentException e) {
            throw new Refused(UNMATCHED, "A value in the status location does not fit the status operation.");
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    /** Percent-decoding of one path segment ('+' stays '+'); malformed escapes are refused. */
    static String decode(String segment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%') {
                int hi = i + 2 < bytes.length ? Character.digit(bytes[i + 1], 16) : -1;
                int lo = i + 2 < bytes.length ? Character.digit(bytes[i + 2], 16) : -1;
                if (hi < 0 || lo < 0) {
                    throw new Refused(UNMATCHED, "The status location is not a valid address.");
                }
                out.write(hi * 16 + lo);
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
