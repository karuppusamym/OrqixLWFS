package com.orqix.integration.adapters;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pure, bounded rules for the IN02 adapters (S47). Nothing here performs I/O. Every adapter and trigger shape is a closed
 * whitelist: unknown fields are refused so a secret can never be smuggled into stored configuration, recipients and
 * topics must be on an explicit allowlist, and the JDBC adapter is limited to one parameterized read-only statement.
 */
public final class AdapterRules {

    public enum Kind {
        EMAIL("email"), TEAMS("teams"), JDBC("jdbc"), KAFKA("kafka");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Kind of(String value) {
            for (Kind kind : values()) {
                if (kind.value.equals(value)) {
                    return kind;
                }
            }
            throw new Invalid("/kind", "Choose email, teams, jdbc or kafka.");
        }
    }

    /** A rule violation; {@code pointer} names the offending field. */
    public static final class Invalid extends RuntimeException {

        private final transient String pointer;

        public Invalid(String pointer, String message) {
            super(message, null, false, false);
            this.pointer = pointer;
        }

        public String pointer() {
            return pointer;
        }
    }

    /** Applies the egress policy to a base URL; throws {@link Invalid} when it is refused. */
    @FunctionalInterface
    public interface EgressCheck {
        void check(String baseUrl);
    }

    static final int MAX_ROWS = 500;
    static final int MAX_TOPICS = 10;
    static final int MAX_DOMAINS = 20;
    static final int MAX_MAPPINGS = 20;
    static final int MAX_KAFKA_VALUE_BYTES = 32 * 1024;
    static final int MAX_BODY = 20_000;
    static final int MAX_TEAMS_TEXT = 4_000;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})+)$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9-]{1,63}(?:\\.[a-z0-9-]{1,63})+$");
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
    private static final Pattern PARAM = Pattern.compile("^[a-z][A-Za-z0-9_]{0,39}$");
    private static final Pattern FIELD = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern NAMED = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FORBIDDEN_SQL = Pattern.compile("\\b(insert|update|delete|drop|alter|create|grant|revoke|truncate|"
            + "merge|call|exec|execute|into|copy|set|commit|rollback|lock|vacuum|pg_sleep|pg_read_file|lo_import|dblink|"
            + "information_schema|pg_catalog)\\b", Pattern.CASE_INSENSITIVE);

    private AdapterRules() {
    }

    // ---------------------------------------------------------------- adapter configuration

    /** Validates and normalizes the stored configuration of an outbound adapter. */
    public static ObjectNode validateConfig(Kind kind, JsonNode config, EgressCheck egress) {
        requireObject(config, "/config");
        ObjectNode out = MAPPER.createObjectNode();
        switch (kind) {
            case EMAIL -> {
                only(config, "/config", Set.of("from", "allowedRecipientDomains", "maxRecipients"));
                out.put("from", address(text(config, "from", "/config/from", 254)).toLowerCase(Locale.ROOT));
                out.set("allowedRecipientDomains", domains(config.path("allowedRecipientDomains"), "/config/allowedRecipientDomains"));
                int max = config.has("maxRecipients") ? integer(config, "maxRecipients", "/config/maxRecipients", 1, 10) : 5;
                out.put("maxRecipients", max);
            }
            case TEAMS -> {
                only(config, "/config", Set.of("baseUrl"));
                String base = text(config, "baseUrl", "/config/baseUrl", 300);
                try {
                    egress.check(base);
                } catch (Invalid e) {
                    throw new Invalid("/config/baseUrl", e.getMessage());
                }
                out.put("baseUrl", base);
            }
            case JDBC -> {
                only(config, "/config", Set.of("statement", "parameters", "maxRows", "timeoutSeconds"));
                String statement = text(config, "statement", "/config/statement", 2000).strip();
                List<String> declared = new ArrayList<>();
                JsonNode params = config.path("parameters");
                if (!params.isMissingNode() && !params.isNull()) {
                    if (!params.isArray() || params.size() > 10) {
                        throw new Invalid("/config/parameters", "List at most 10 parameter names.");
                    }
                    for (JsonNode name : params) {
                        if (!name.isString() || !PARAM.matcher(name.asString()).matches() || declared.contains(name.asString())) {
                            throw new Invalid("/config/parameters", "Parameter names must be unique and look like customerId.");
                        }
                        declared.add(name.asString());
                    }
                }
                validateStatement(statement, declared);
                out.put("statement", statement);
                ArrayNode names = out.putArray("parameters");
                declared.forEach(names::add);
                out.put("maxRows", config.has("maxRows") ? integer(config, "maxRows", "/config/maxRows", 1, MAX_ROWS) : 100);
                out.put("timeoutSeconds", config.has("timeoutSeconds") ? integer(config, "timeoutSeconds", "/config/timeoutSeconds", 1, 30) : 10);
            }
            case KAFKA -> {
                only(config, "/config", Set.of("allowedTopics"));
                out.set("allowedTopics", topics(config.path("allowedTopics"), "/config/allowedTopics"));
            }
            default -> throw new IllegalStateException();
        }
        return out;
    }

    /** Statement rules: one read-only SELECT/WITH, no literals, comments or casts; values only through declared parameters. */
    static void validateStatement(String statement, List<String> declared) {
        String lower = statement.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select ") || lower.startsWith("with "))) {
            throw new Invalid("/config/statement", "Only a single SELECT statement is allowed.");
        }
        if (statement.contains(";") || statement.contains("--") || statement.contains("/*") || statement.contains("*/")
                || statement.contains("'") || statement.contains("\"") || statement.contains("\\") || statement.contains("::")
                || statement.contains("$") || statement.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t' && c != '\r')) {
            throw new Invalid("/config/statement", "Remove semicolons, comments, quotes, casts and control characters; pass values as named parameters.");
        }
        Matcher forbidden = FORBIDDEN_SQL.matcher(statement);
        if (forbidden.find()) {
            throw new Invalid("/config/statement", "The word '" + forbidden.group().toLowerCase(Locale.ROOT) + "' is not allowed in a read-only statement.");
        }
        Set<String> used = new TreeSet<>();
        Matcher named = NAMED.matcher(statement);
        while (named.find()) {
            used.add(named.group(1));
        }
        if (!used.equals(new TreeSet<>(declared))) {
            throw new Invalid("/config/parameters", "Declared parameters must match the :names used in the statement exactly.");
        }
    }

    // ---------------------------------------------------------------- outbound payloads

    /** Validates one outbound request against the adapter configuration; returns the normalized payload. */
    public static ObjectNode validateSend(Kind kind, JsonNode config, JsonNode payload) {
        requireObject(payload, "/payload");
        ObjectNode out = MAPPER.createObjectNode();
        switch (kind) {
            case EMAIL -> {
                only(payload, "/payload", Set.of("to", "subject", "body"));
                JsonNode to = payload.path("to");
                int max = config.path("maxRecipients").asInt(5);
                if (!to.isArray() || to.isEmpty() || to.size() > max) {
                    throw new Invalid("/payload/to", "Provide between 1 and " + max + " recipients.");
                }
                Set<String> allowed = stringSet(config.path("allowedRecipientDomains"));
                Set<String> unique = new LinkedHashSet<>();
                for (JsonNode recipient : to) {
                    String value = recipient.isString() ? recipient.asString() : "";
                    String domain = domainOf(address(value));
                    if (!allowed.contains(domain)) {
                        throw new Invalid("/payload/to", "Recipient domain " + domain + " is not approved for this adapter.");
                    }
                    unique.add(value.toLowerCase(Locale.ROOT));
                }
                ArrayNode list = out.putArray("to");
                unique.forEach(list::add);
                out.put("subject", singleLine(text(payload, "subject", "/payload/subject", 200), "/payload/subject"));
                out.put("body", text(payload, "body", "/payload/body", MAX_BODY));
            }
            case TEAMS -> {
                only(payload, "/payload", Set.of("title", "text"));
                if (payload.has("title")) {
                    out.put("title", singleLine(text(payload, "title", "/payload/title", 200), "/payload/title"));
                }
                out.put("text", text(payload, "text", "/payload/text", MAX_TEAMS_TEXT));
            }
            case JDBC -> {
                only(payload, "/payload", Set.of("parameters"));
                JsonNode values = payload.path("parameters");
                ObjectNode bound = out.putObject("parameters");
                Set<String> declared = stringSet(config.path("parameters"));
                if (values.isMissingNode() || values.isNull()) {
                    values = MAPPER.createObjectNode();
                }
                requireObject(values, "/payload/parameters");
                for (String name : values.propertyNames()) {
                    if (!declared.contains(name)) {
                        throw new Invalid("/payload/parameters/" + name, "This parameter is not declared by the adapter.");
                    }
                }
                for (String name : declared) {
                    JsonNode value = values.path(name);
                    if (!isScalar(value) || (value.isString() && value.asString().length() > 500)) {
                        throw new Invalid("/payload/parameters/" + name, "Provide a short text, number or true/false value.");
                    }
                    bound.set(name, value);
                }
            }
            case KAFKA -> {
                only(payload, "/payload", Set.of("topic", "key", "value"));
                String topic = text(payload, "topic", "/payload/topic", 120);
                if (!stringSet(config.path("allowedTopics")).contains(topic)) {
                    throw new Invalid("/payload/topic", "This topic is not approved for this adapter.");
                }
                out.put("topic", topic);
                if (payload.has("key")) {
                    out.put("key", text(payload, "key", "/payload/key", 256));
                }
                JsonNode value = payload.path("value");
                requireObject(value, "/payload/value");
                if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_KAFKA_VALUE_BYTES) {
                    throw new Invalid("/payload/value", "The message value is larger than 32 KiB.");
                }
                out.set("value", value);
            }
            default -> throw new IllegalStateException();
        }
        return out;
    }

    // ---------------------------------------------------------------- inbound triggers

    /** Validates and normalizes the sender filter of an inbound trigger. */
    public static ObjectNode validateFilter(Kind kind, JsonNode filter) {
        JsonNode value = filter == null || filter.isNull() || filter.isMissingNode() ? MAPPER.createObjectNode() : filter;
        requireObject(value, "/filter");
        ObjectNode out = MAPPER.createObjectNode();
        switch (kind) {
            case EMAIL -> {
                only(value, "/filter", Set.of("allowedSenderDomains"));
                out.set("allowedSenderDomains", domains(value.path("allowedSenderDomains"), "/filter/allowedSenderDomains"));
            }
            case KAFKA -> {
                only(value, "/filter", Set.of("allowedTopics"));
                out.set("allowedTopics", topics(value.path("allowedTopics"), "/filter/allowedTopics"));
            }
            case TEAMS, JDBC -> only(value, "/filter", Set.of());
            default -> throw new IllegalStateException();
        }
        return out;
    }

    /** Mapping of workflow input field to a JSON Pointer inside the inbound payload. */
    public static ObjectNode validateMapping(JsonNode mapping) {
        requireObject(mapping, "/mapping");
        if (mapping.isEmpty() || mapping.size() > MAX_MAPPINGS) {
            throw new Invalid("/mapping", "Map between 1 and " + MAX_MAPPINGS + " input fields.");
        }
        ObjectNode out = MAPPER.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : mapping.properties()) {
            String pointer = entry.getValue().isString() ? entry.getValue().asString() : "";
            if (!FIELD.matcher(entry.getKey()).matches()) {
                throw new Invalid("/mapping/" + entry.getKey(), "Input field names start with a letter and use letters, digits or _.");
            }
            if (!pointer.startsWith("/") || pointer.length() > 200 || pointer.contains("~2")) {
                throw new Invalid("/mapping/" + entry.getKey(), "Use a JSON Pointer into the payload such as /value/amount.");
            }
            out.put(entry.getKey(), pointer);
        }
        return out;
    }

    /** Checks an inbound payload against its source shape and the trigger filter; throws {@link Invalid}. */
    public static void acceptInbound(Kind kind, JsonNode filter, JsonNode payload) {
        requireObject(payload, "");
        switch (kind) {
            case EMAIL -> {
                String from = payload.path("from").isString() ? payload.path("from").asString() : "";
                String domain = domainOf(address(from));
                if (!stringSet(filter.path("allowedSenderDomains")).contains(domain)) {
                    throw new Invalid("/from", "The sender domain is not approved for this trigger.");
                }
            }
            case TEAMS -> {
                if (!payload.path("text").isString() || payload.path("text").asString().length() > MAX_TEAMS_TEXT) {
                    throw new Invalid("/text", "A Teams event needs a text of at most 4000 characters.");
                }
            }
            case KAFKA -> {
                if (!payload.path("topic").isString() || !stringSet(filter.path("allowedTopics")).contains(payload.path("topic").asString())) {
                    throw new Invalid("/topic", "The topic is not approved for this trigger.");
                }
                if (!payload.path("value").isObject()) {
                    throw new Invalid("/value", "A Kafka event needs an object value.");
                }
            }
            case JDBC -> {
                if (!payload.path("row").isObject()) {
                    throw new Invalid("/row", "A database event needs a row object.");
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    /** Extracts the mapped scalar values; the raw payload is not kept anywhere. */
    public static ObjectNode mapInput(JsonNode mapping, JsonNode payload) {
        ObjectNode out = MAPPER.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : mapping.properties()) {
            JsonNode value;
            try {
                value = payload.at(entry.getValue().asString());
            } catch (RuntimeException e) {
                throw new Invalid("/mapping/" + entry.getKey(), "The pointer is not valid.");
            }
            if (!isScalar(value) || (value.isString() && value.asString().length() > 1000)) {
                throw new Invalid("/mapping/" + entry.getKey(), "The payload has no short text, number or true/false value at "
                        + entry.getValue().asString() + ".");
            }
            out.set(entry.getKey(), value);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    static boolean isScalar(JsonNode node) {
        return node != null && (node.isString() || node.isNumber() || node.isBoolean());
    }

    private static void requireObject(JsonNode node, String pointer) {
        if (node == null || !node.isObject()) {
            throw new Invalid(pointer, "Provide a JSON object.");
        }
    }

    private static void only(JsonNode node, String pointer, Set<String> allowed) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                String hint = name.toLowerCase(Locale.ROOT).matches(".*(pass|secret|token|credential|apikey|authorization).*")
                        ? " Secrets are never accepted here; reference them with secretRef." : "";
                throw new Invalid(pointer + "/" + name, "This field is not supported." + hint);
            }
        }
    }

    private static String text(JsonNode node, String field, String pointer, int max) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > max) {
            throw new Invalid(pointer, "Provide text of 1 to " + max + " characters.");
        }
        return value.asString();
    }

    private static int integer(JsonNode node, String field, String pointer, int min, int max) {
        JsonNode value = node.path(field);
        if (!value.isInt() || value.asInt() < min || value.asInt() > max) {
            throw new Invalid(pointer, "Provide a whole number from " + min + " to " + max + ".");
        }
        return value.asInt();
    }

    private static String singleLine(String value, String pointer) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new Invalid(pointer, "Line breaks are not allowed here.");
        }
        return value;
    }

    private static String address(String value) {
        Matcher m = EMAIL.matcher(value == null ? "" : value);
        if (!m.matches()) {
            throw new Invalid("/address", "Provide a plain email address such as name@example.com.");
        }
        return value;
    }

    private static String domainOf(String address) {
        return address.substring(address.indexOf('@') + 1).toLowerCase(Locale.ROOT);
    }

    private static ArrayNode domains(JsonNode node, String pointer) {
        if (!node.isArray() || node.isEmpty() || node.size() > MAX_DOMAINS) {
            throw new Invalid(pointer, "List between 1 and " + MAX_DOMAINS + " exact domains.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode domain : node) {
            String value = domain.isString() ? domain.asString().toLowerCase(Locale.ROOT) : "";
            if (!DOMAIN.matcher(value).matches()) {
                throw new Invalid(pointer, "Use exact domains such as example.com; wildcards are not allowed.");
            }
            unique.add(value);
        }
        ArrayNode out = MAPPER.createArrayNode();
        unique.forEach(out::add);
        return out;
    }

    private static ArrayNode topics(JsonNode node, String pointer) {
        if (!node.isArray() || node.isEmpty() || node.size() > MAX_TOPICS) {
            throw new Invalid(pointer, "List between 1 and " + MAX_TOPICS + " exact topic names.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode topic : node) {
            String value = topic.isString() ? topic.asString() : "";
            if (!TOPIC.matcher(value).matches() || value.equals(".") || value.equals("..")) {
                throw new Invalid(pointer, "Use exact topic names; wildcards and patterns are not allowed.");
            }
            unique.add(value);
        }
        ArrayNode out = MAPPER.createArrayNode();
        unique.forEach(out::add);
        return out;
    }

    private static Set<String> stringSet(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asString()));
        }
        return out;
    }
}
