package com.orqix.integration.openapi;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.orqix.integration.openapi.ImportReport.Auth;
import com.orqix.integration.openapi.ImportReport.Issue;
import com.orqix.integration.openapi.ImportReport.Operation;
import com.orqix.integration.openapi.ImportReport.Scheme;
import com.orqix.integration.openapi.ImportReport.Server;
import com.orqix.integration.openapi.ImportReport.Source;
import com.orqix.integration.openapi.ImportReport.Summary;
import com.orqix.integration.openapi.SpecReader.SpecException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns an OpenAPI 3.x document into a draft connection definition and a review report (S36). Pure and side-effect
 * free: no network, no file access, no code execution. Only internal {@code #/components/...} references are followed
 * (with cycle detection and a work budget); every other reference is reported, never fetched. {@code servers} are
 * reported as provenance and are never copied into the definition (the binding owns the address).
 *
 * <p>The connection definition format (contracts/connection-definition-v0.schema.json) is deliberately narrow: fixed
 * paths, GET/POST/PUT/PATCH, a flat scalar request body and a response of scalars and up to three object levels. The
 * converter maps what fits, says what it simplified, and blocks what cannot be expressed.
 */
public final class OpenApiConverter {

    public static final int MAX_OPERATIONS = 200;
    /** Operations per connection definition version (schema maxProperties). */
    public static final int MAX_SELECTABLE = 20;
    static final int STEPS_PER_OPERATION = 4_000;
    static final int REFERENCE_BUDGET = 50_000;
    static final int MAX_REFERENCE_CHAIN = 12;
    static final int MAX_RESPONSE_DEPTH = 3;
    static final int MAX_REQUEST_FIELDS = 30;
    static final int MAX_RESPONSE_FIELDS = 50;
    static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern REQUEST_FIELD = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Pattern RESPONSE_FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern STATIC_PATH = Pattern.compile("^/[A-Za-z0-9._~/-]{0,200}$");
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z][A-Za-z0-9-]{0,63}$");
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*][^)]*\\)\\s*[+*{]");
    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "PATCH");
    private static final Set<String> IGNORED_HEADERS = Set.of("accept", "content-type", "authorization");
    private static final Set<String> PAGING = Set.of("page", "pagenumber", "pagesize", "page_size", "per_page", "perpage",
            "limit", "offset", "cursor", "pagetoken", "page_token", "nexttoken", "next_token", "skip", "top", "after");
    private static final Set<String> UNENFORCED = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minLength", "multipleOf");

    /** The analysis result. {@code templates} holds, per operation key, the converted operation without effect class. */
    public record Analysis(ImportReport report, Map<String, ObjectNode> templates) {
    }

    private OpenApiConverter() {
    }

    // ------------------------------------------------------------------ entry point

    public static Analysis analyze(JsonNode root) {
        return new Run(root).run();
    }

    // ------------------------------------------------------------------ definition building

    /** Completes a converted operation with the effect class a person confirmed. */
    public static ObjectNode finish(ObjectNode template, String effectClass, String idempotencyHeader) {
        ObjectNode op = MAPPER.createObjectNode();
        op.put("label", template.path("label").asString());
        if (template.has("description")) {
            op.put("description", template.path("description").asString());
        }
        op.put("method", template.path("method").asString());
        op.put("path", template.path("path").asString());
        if (template.has("requestSchema")) {
            op.set("requestSchema", template.get("requestSchema").deepCopy());
        }
        op.set("responseSchema", template.get("responseSchema").deepCopy());
        op.set("successStatuses", template.get("successStatuses").deepCopy());
        op.put("effectClass", effectClass);
        if ("idempotentWithKey".equals(effectClass)) {
            op.put("idempotencyHeader", idempotencyHeader);
        }
        op.put("maxTimeoutSeconds", template.path("maxTimeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS));
        op.put("testable", "GET".equals(template.path("method").asString()) && "readOnly".equals(effectClass));
        return op;
    }

    public static ObjectNode definition(String key, int version, String name, String description,
            Map<String, ObjectNode> operations) {
        ObjectNode definition = MAPPER.createObjectNode();
        definition.put("schemaVersion", "0.1");
        definition.put("key", key);
        definition.put("version", version);
        definition.put("name", name);
        if (description != null && !description.isBlank()) {
            definition.put("description", truncate(description, 2000));
        }
        ObjectNode ops = definition.putObject("operations");
        operations.forEach(ops::set);
        return definition;
    }

    /** A connection key from a document title: lower case words joined by hyphens. */
    public static String suggestKey(String title) {
        String slug = (title == null ? "" : title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 63) {
            slug = slug.substring(0, 63).replaceAll("-+$", "");
        }
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            slug = ("api-" + slug).replaceAll("-+$", "");
        }
        return slug.length() < 2 ? "imported-api" : slug;
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() > max ? trimmed.substring(0, max - 3).stripTrailing() + "..." : trimmed;
    }

    // ------------------------------------------------------------------ the run

    private static final class ComplexityException extends RuntimeException {
        ComplexityException(String message) {
            super(message, null, false, false);
        }
    }

    /** A resolved schema or component: the node, the references followed, or why it could not be resolved. */
    private record Resolved(JsonNode node, List<String> refs, String error) {
        boolean failed() {
            return error != null;
        }
    }

    /** The object fields of a schema after merging allOf. */
    private static final class Shape {
        final Map<String, JsonNode> properties = new LinkedHashMap<>();
        final Set<String> required = new LinkedHashSet<>();
        final List<String> refs = new ArrayList<>();
        String error;

        static Shape fail(String error) {
            Shape shape = new Shape();
            shape.error = error;
            return shape;
        }
    }

    private enum Kind { SCALAR, OBJECT, ARRAY, ALTERNATIVES, FILE, UNKNOWN, ERROR }

    private static final class Classified {
        Kind kind;
        JsonNode node;
        List<String> refs = List.of();
        String error;
        // scalar details
        String type;
        boolean nullable;
        ObjectNode field;
        Set<String> unenforced = new TreeSet<>();
    }

    private static final class Run {

        final JsonNode root;
        final List<Issue> issues = new ArrayList<>();
        final Map<String, Integer> blockedReferences = new LinkedHashMap<>();
        final Map<String, String> usedKeys = new HashMap<>();
        int references;
        int steps;

        Run(JsonNode root) {
            this.root = root;
        }

        Analysis run() {
            String version = root.path("openapi").isString() ? root.path("openapi").asString() : null;
            if (version == null || !version.startsWith("3.")) {
                String swagger = root.path("swagger").isString() ? root.path("swagger").asString() : null;
                throw new SpecException(swagger != null
                        ? "This is a Swagger " + swagger + " document. Convert it to OpenAPI 3 first; only OpenAPI 3.x is supported."
                        : "This does not look like an OpenAPI document: the openapi version (3.x) is missing.", false);
            }
            JsonNode paths = root.path("paths");
            if (!paths.isObject()) {
                throw new SpecException("The document has no paths to import.", false);
            }
            Source source = new Source(truncate(text(root.path("info").path("title"), "Imported API"), 200),
                    truncate(text(root.path("info").path("version"), null), 64), version,
                    truncate(text(root.path("info").path("description"), null), 500));
            Map<String, Scheme> schemes = schemes();
            List<Server> servers = servers();
            if (root.path("webhooks").isObject() && root.path("webhooks").size() > 0) {
                issues.add(new Issue("info", "webhooks-ignored", root.path("webhooks").size()
                        + " webhook(s) described in the file are not imported. Orqix connections call out; they do not receive calls."));
            }

            List<Operation> operations = new ArrayList<>();
            Map<String, ObjectNode> templates = new LinkedHashMap<>();
            int seen = 0;
            boolean truncated = false;
            outer:
            for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
                JsonNode item = pathEntry.getValue();
                if (!item.isObject() || pathEntry.getKey().startsWith("x-")) {
                    continue;
                }
                if (item.has("$ref")) {
                    issues.add(new Issue("warning", "path-reference", "The address " + truncate(pathEntry.getKey(), 100)
                            + " is defined by a reference and was skipped."));
                    continue;
                }
                for (String method : METHODS) {
                    JsonNode op = item.get(method);
                    if (op == null || !op.isObject()) {
                        continue;
                    }
                    if (seen == MAX_OPERATIONS) {
                        truncated = true;
                        break outer;
                    }
                    seen++;
                    steps = 0;
                    OperationRun result = new OperationRun(this, pathEntry.getKey(), method.toUpperCase(Locale.ROOT),
                            item, op, schemes);
                    try {
                        result.convert();
                    } catch (ComplexityException e) {
                        result.blockers.add("It is too large or too deeply linked to analyse safely (" + e.getMessage() + ").");
                        result.template = null;
                    }
                    operations.add(result.toOperation());
                    if (result.template != null) {
                        templates.put(result.key, result.template);
                    }
                }
            }
            if (truncated) {
                issues.add(new Issue("warning", "operations-truncated", "Only the first " + MAX_OPERATIONS
                        + " operations were analysed. Split the file to import the rest."));
            }
            if (!blockedReferences.isEmpty()) {
                int total = blockedReferences.values().stream().mapToInt(Integer::intValue).sum();
                String example = blockedReferences.keySet().iterator().next();
                issues.add(new Issue("warning", "references-not-followed", total
                        + " reference(s) to files, web addresses or locations outside #/components were not followed "
                        + "(nothing is downloaded during an import), for example " + truncate(example, 120)
                        + ". Operations that need them are blocked or simplified."));
            }
            // default selection: usable, not deprecated, first MAX_SELECTABLE
            int selected = 0;
            List<Operation> finalOps = new ArrayList<>();
            for (Operation o : operations) {
                boolean pick = !ImportReport.BLOCKED.equals(o.disposition()) && !o.deprecated() && selected < MAX_SELECTABLE;
                if (pick) {
                    selected++;
                }
                finalOps.add(new Operation(o.key(), o.method(), o.path(), o.summary(), o.disposition(), o.blockers(),
                        o.notes(), o.suggestedEffectClass(), o.effectReason(), o.idempotencyHeader(), o.testable(),
                        o.deprecated(), pick, o.inputs(), o.outputs()));
            }
            long supportedCount = finalOps.stream().filter(o -> ImportReport.SUPPORTED.equals(o.disposition())).count();
            long simplifiedCount = finalOps.stream().filter(o -> ImportReport.SIMPLIFIED.equals(o.disposition())).count();
            long blockedCount = finalOps.stream().filter(o -> ImportReport.BLOCKED.equals(o.disposition())).count();
            long usable = supportedCount + simplifiedCount;
            if (usable > MAX_SELECTABLE) {
                issues.add(new Issue("info", "selection-limit", usable + " operations can be imported but one connection version "
                        + "holds at most " + MAX_SELECTABLE + ". Choose which to include; register the rest under another key."));
            }
            Auth auth = auth(schemes, finalOps, templates.keySet());
            Summary summary = new Summary(finalOps.size(), (int) supportedCount, (int) simplifiedCount, (int) blockedCount,
                    MAX_SELECTABLE, MAX_OPERATIONS);
            ImportReport report = new ImportReport(source, servers, auth, summary, finalOps, List.copyOf(issues),
                    suggestKey(source.title()), truncate(source.title(), 200));
            return new Analysis(report, templates);
        }

        // -------------------------------------------------- servers, security

        List<Server> servers() {
            List<Server> servers = new ArrayList<>();
            for (JsonNode server : root.path("servers")) {
                if (servers.size() >= 10) {
                    break;
                }
                String url = text(server.path("url"), null);
                if (url != null) {
                    servers.add(new Server(displayUrl(url), truncate(text(server.path("description"), null), 200)));
                }
            }
            return servers;
        }

        static String displayUrl(String url) {
            String cleaned = url.strip();
            try {
                URI uri = URI.create(cleaned.replace("{", "%7B").replace("}", "%7D"));
                if (uri.getHost() != null) {
                    String shown = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                            + (uri.getRawPath() == null ? "" : uri.getRawPath());
                    return truncate(shown.replace("%7B", "{").replace("%7D", "}"), 200);
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to the cleaned text
            }
            int cut = Math.min(cleaned.indexOf('?') < 0 ? cleaned.length() : cleaned.indexOf('?'),
                    cleaned.indexOf('@') < 0 ? cleaned.length() : cleaned.indexOf('@'));
            return truncate(cleaned.substring(0, cut), 200);
        }

        Map<String, Scheme> schemes() {
            Map<String, Scheme> schemes = new LinkedHashMap<>();
            JsonNode declared = root.path("components").path("securitySchemes");
            if (!declared.isObject()) {
                return schemes;
            }
            for (Map.Entry<String, JsonNode> entry : declared.properties()) {
                JsonNode scheme = entry.getValue();
                String type = text(scheme.path("type"), "unknown");
                String name = truncate(entry.getKey(), 80);
                switch (type.toLowerCase(Locale.ROOT)) {
                    case "http" -> {
                        String kind = text(scheme.path("scheme"), "").toLowerCase(Locale.ROOT);
                        if ("bearer".equals(kind)) {
                            schemes.put(entry.getKey(), new Scheme(name, "Bearer token", true,
                                    "A bearer token kept as a workspace secret (binding type bearerToken)."));
                        } else if ("basic".equals(kind)) {
                            schemes.put(entry.getKey(), new Scheme(name, "HTTP Basic", false,
                                    "Needs a platform decision: bindings do not support user name and password sign-in."));
                        } else {
                            schemes.put(entry.getKey(), new Scheme(name, "HTTP " + truncate(kind, 30), false,
                                    "Needs a platform decision: this HTTP sign-in scheme is not supported by bindings."));
                        }
                    }
                    case "apikey" -> {
                        String where = text(scheme.path("in"), "");
                        String header = text(scheme.path("name"), "");
                        if ("header".equals(where) && HEADER.matcher(header).matches()) {
                            schemes.put(entry.getKey(), new Scheme(name, "API key in header " + header, true,
                                    "An API key kept as a workspace secret, sent in the " + header
                                            + " header (binding type apiKeyHeader)."));
                        } else {
                            schemes.put(entry.getKey(), new Scheme(name, "API key in " + truncate(where, 20), false,
                                    "Needs a platform decision: only API keys sent in a header are supported by bindings."));
                        }
                    }
                    case "oauth2" -> schemes.put(entry.getKey(), new Scheme(name, "OAuth 2.0", false,
                            "Needs a platform decision: bindings cannot obtain or refresh OAuth 2.0 tokens yet. A pre-issued "
                                    + "long-lived token could be used as a bearer token only if the system owner agrees."));
                    case "openidconnect" -> schemes.put(entry.getKey(), new Scheme(name, "OpenID Connect", false,
                            "Needs a platform decision: bindings do not support OpenID Connect sign-in."));
                    case "mutualtls" -> schemes.put(entry.getKey(), new Scheme(name, "Mutual TLS", false,
                            "Needs a platform decision: bindings do not support client certificates."));
                    default -> schemes.put(entry.getKey(), new Scheme(name, truncate(type, 30), false,
                            "Needs a platform decision: this sign-in scheme is not recognised."));
                }
            }
            return schemes;
        }

        /** The binding auth suggestion all usable operations can share, or the reason a decision is needed. */
        Auth auth(Map<String, Scheme> schemes, List<Operation> operations, Collection<String> usableKeys) {
            List<Scheme> listed = new ArrayList<>(schemes.values());
            Set<String> common = null;
            boolean any = false;
            boolean undeclared = true;
            for (Operation operation : operations) {
                if (!usableKeys.contains(operation.key())) {
                    continue;
                }
                any = true;
                Set<String> options = operationAuth.getOrDefault(operation.key(), Set.of("none"));
                if (!options.equals(Set.of("none"))) {
                    undeclared = false;
                }
                if (common == null) {
                    common = new LinkedHashSet<>(options);
                } else {
                    common.retainAll(options);
                }
            }
            if (!any) {
                return new Auth("none", "none", null, "No operation can be imported, so no sign-in method is suggested.",
                        listed);
            }
            if (undeclared && listed.isEmpty()) {
                return new Auth("none", "none", null,
                        "The file declares no sign-in method. Confirm with the system owner before binding it without one.",
                        listed);
            }
            Set<String> choices = common == null ? Set.of() : common;
            String pick = null;
            for (String choice : choices) {
                if (!"none".equals(choice)) {
                    pick = choice;
                    break;
                }
            }
            if (pick == null && choices.contains("none")) {
                return new Auth("none", "none", null, "The selected operations declare no sign-in requirement.", listed);
            }
            if (pick == null) {
                return new Auth("needsPlatformDecision", null, null,
                        "The sign-in methods the file declares cannot be configured in a binding (or the operations do not share "
                                + "one). A platform decision is needed before this connection can be used; check each listed method below.",
                        listed);
            }
            if (pick.startsWith("apiKeyHeader:")) {
                String header = pick.substring("apiKeyHeader:".length());
                return new Auth("suggested", "apiKeyHeader", header,
                        "Suggested binding sign-in: API key sent in the " + header + " header. Store the key as a workspace secret.",
                        listed);
            }
            return new Auth("suggested", "bearerToken", null,
                    "Suggested binding sign-in: bearer token. Store the token as a workspace secret.", listed);
        }

        final Map<String, Set<String>> operationAuth = new HashMap<>();

        // -------------------------------------------------- references and schemas

        void tick() {
            if (++steps > STEPS_PER_OPERATION) {
                throw new ComplexityException("more than " + STEPS_PER_OPERATION + " schema steps");
            }
        }

        /** Follows {@code $ref} chains inside #/components only. */
        Resolved deref(JsonNode node, Set<String> active) {
            JsonNode current = node;
            List<String> chain = new ArrayList<>();
            for (int i = 0;; i++) {
                tick();
                if (current == null || !current.isObject() || !current.has("$ref")) {
                    return new Resolved(current, chain, null);
                }
                JsonNode ref = current.get("$ref");
                if (!ref.isString()) {
                    return new Resolved(null, chain, "a reference is not text");
                }
                String target = ref.asString();
                if (i >= MAX_REFERENCE_CHAIN) {
                    return new Resolved(null, chain, "a chain of references is too long");
                }
                if (active.contains(target) || chain.contains(target)) {
                    return new Resolved(null, chain, "it refers back to itself (" + truncate(target, 80) + ")");
                }
                if (++references > REFERENCE_BUDGET) {
                    throw new ComplexityException("more than " + REFERENCE_BUDGET + " references in the document");
                }
                JsonNode found = lookup(target);
                if (found == null) {
                    return new Resolved(null, chain, blockedReferences.containsKey(truncate(target, 200))
                            ? "it uses a reference Orqix does not follow (" + truncate(target, 80) + ")"
                            : "a reference cannot be found (" + truncate(target, 80) + ")");
                }
                chain.add(target);
                current = found;
            }
        }

        JsonNode lookup(String ref) {
            if (!ref.startsWith("#/components/")) {
                blockedReferences.merge(truncate(ref, 200), 1, Integer::sum);
                return null;
            }
            JsonNode node = root;
            for (String raw : ref.substring(2).split("/", -1)) {
                String token;
                try {
                    token = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8).replace("~1", "/")
                            .replace("~0", "~");
                } catch (IllegalArgumentException e) {
                    return null;
                }
                node = node.isObject() ? node.get(token) : node.isArray() && token.matches("\\d{1,4}")
                        ? node.get(Integer.parseInt(token)) : null;
                if (node == null) {
                    return null;
                }
            }
            return node;
        }

        static Set<String> plus(Set<String> active, List<String> refs) {
            if (refs.isEmpty()) {
                return active;
            }
            Set<String> next = new HashSet<>(active);
            next.addAll(refs);
            return next;
        }

        /** The object fields of a schema; oneOf/anyOf and non-objects fail. */
        Shape shape(JsonNode schema, Set<String> active, int depth) {
            tick();
            Resolved resolved = deref(schema, active);
            if (resolved.failed()) {
                return Shape.fail(resolved.error());
            }
            JsonNode s = resolved.node();
            if (s == null || !s.isObject()) {
                return Shape.fail("it is not described");
            }
            Set<String> inner = plus(active, resolved.refs());
            if (s.has("oneOf") || s.has("anyOf")) {
                return Shape.fail("it allows alternative shapes (oneOf / anyOf), which cannot be mapped");
            }
            String type = typeOf(s);
            if (type != null && !"object".equals(type)) {
                return Shape.fail("it is a " + type + ", not an object");
            }
            Shape out = new Shape();
            out.refs.addAll(resolved.refs());
            JsonNode all = s.get("allOf");
            if (all != null) {
                if (!all.isArray() || all.size() > 10 || depth >= 5) {
                    return Shape.fail("its allOf combination is too complex");
                }
                for (JsonNode member : all) {
                    Shape part = shape(member, inner, depth + 1);
                    if (part.error != null) {
                        return part;
                    }
                    out.properties.putAll(part.properties);
                    out.required.addAll(part.required);
                }
            }
            if (s.path("properties").isObject()) {
                for (Map.Entry<String, JsonNode> p : s.get("properties").properties()) {
                    out.properties.put(p.getKey(), p.getValue());
                }
            }
            if (s.path("required").isArray()) {
                for (JsonNode r : s.get("required")) {
                    if (r.isString()) {
                        out.required.add(r.asString());
                    }
                }
            }
            if (type == null && all == null && out.properties.isEmpty()) {
                return Shape.fail("it does not describe an object");
            }
            return out;
        }

        /** The JSON type of a schema without "null"; null when absent or ambiguous. */
        static String typeOf(JsonNode s) {
            JsonNode type = s.get("type");
            if (type == null) {
                return null;
            }
            if (type.isString()) {
                return type.asString();
            }
            if (type.isArray()) {
                String found = null;
                for (JsonNode t : type) {
                    if (t.isString() && !"null".equals(t.asString())) {
                        if (found != null) {
                            return "mixed";
                        }
                        found = t.asString();
                    }
                }
                return found;
            }
            return null;
        }

        static boolean nullable(JsonNode s) {
            if (s.path("nullable").asBoolean(false)) {
                return true;
            }
            if (s.path("type").isArray()) {
                for (JsonNode t : s.get("type")) {
                    if (t.isString() && "null".equals(t.asString())) {
                        return true;
                    }
                }
            }
            return false;
        }

        Classified classify(JsonNode schema, Set<String> active) {
            tick();
            Classified c = new Classified();
            Resolved resolved = deref(schema, active);
            if (resolved.failed()) {
                c.kind = Kind.ERROR;
                c.error = resolved.error();
                return c;
            }
            JsonNode s = resolved.node();
            c.refs = resolved.refs();
            if (s == null || !s.isObject()) {
                c.kind = Kind.UNKNOWN;
                c.error = "it is not described";
                return c;
            }
            Set<String> inner = plus(active, resolved.refs());
            if (s.has("oneOf") || s.has("anyOf")) {
                c.kind = Kind.ALTERNATIVES;
                c.error = "it allows alternative shapes (oneOf / anyOf)";
                return c;
            }
            if (s.path("allOf").isArray() && s.get("allOf").size() == 1 && !s.has("properties") && !s.has("type")) {
                Classified single = classify(s.get("allOf").get(0), inner);
                if (single.kind == Kind.SCALAR) {
                    if (!s.path("description").isMissingNode() && s.path("description").isString()) {
                        single.field.put("description", truncate(s.get("description").asString(), 500));
                    }
                    single.nullable |= nullable(s);
                }
                return single;
            }
            if (s.has("allOf")) {
                c.kind = Kind.OBJECT;
                c.node = s;
                c.refs = resolved.refs();
                return c;
            }
            String type = typeOf(s);
            if (type == null && s.path("enum").isArray() && s.get("enum").size() > 0) {
                boolean strings = true;
                for (JsonNode v : s.get("enum")) {
                    strings &= v.isString() || v.isNull();
                }
                type = strings ? "string" : null;
            }
            if (type == null && s.path("properties").isObject()) {
                type = "object";
            }
            if (type == null && s.has("items")) {
                type = "array";
            }
            c.node = s;
            c.nullable = nullable(s);
            if (type == null || "mixed".equals(type)) {
                c.kind = Kind.UNKNOWN;
                c.error = "its type is not stated";
                return c;
            }
            switch (type) {
                case "object" -> c.kind = Kind.OBJECT;
                case "array" -> c.kind = Kind.ARRAY;
                case "string", "number", "integer", "boolean" -> {
                    String format = text(s.path("format"), "");
                    if ("string".equals(type) && ("binary".equals(format) || "byte".equals(format))) {
                        c.kind = Kind.FILE;
                        c.error = "it is a file";
                        return c;
                    }
                    c.kind = Kind.SCALAR;
                    c.type = type;
                    c.field = scalarField(s, type, c.unenforced);
                }
                default -> {
                    c.kind = Kind.UNKNOWN;
                    c.error = "its type (" + truncate(type, 20) + ") is not supported";
                }
            }
            return c;
        }

        ObjectNode scalarField(JsonNode s, String type, Set<String> unenforced) {
            ObjectNode field = MAPPER.createObjectNode();
            field.put("type", type);
            if (s.path("description").isString() && !s.get("description").asString().isBlank()) {
                field.put("description", truncate(s.get("description").asString(), 500));
            }
            if (s.path("enum").isArray() && s.get("enum").size() > 0) {
                ArrayNode values = MAPPER.createArrayNode();
                Set<String> unique = new LinkedHashSet<>();
                boolean convertible = true;
                for (JsonNode v : s.get("enum")) {
                    if (v.isString()) {
                        unique.add(v.asString());
                    } else if (!v.isNull()) {
                        convertible = false;
                    }
                }
                if (convertible && !unique.isEmpty() && "string".equals(type)) {
                    unique.forEach(values::add);
                    field.set("enum", values);
                } else {
                    unenforced.add("list of allowed values");
                }
            }
            JsonNode maxLength = s.get("maxLength");
            if (maxLength != null && maxLength.isIntegralNumber() && "string".equals(type)) {
                int value = maxLength.asInt();
                if (value >= 1 && value <= 4000) {
                    field.put("maxLength", value);
                } else {
                    unenforced.add("maxLength");
                }
            }
            JsonNode pattern = s.get("pattern");
            if (pattern != null && pattern.isString() && "string".equals(type)) {
                String value = pattern.asString();
                if (value.length() <= 200 && safePattern(value)) {
                    field.put("pattern", value);
                } else {
                    unenforced.add("pattern");
                }
            }
            for (String key : UNENFORCED) {
                if (s.has(key)) {
                    unenforced.add(key);
                }
            }
            return field;
        }

        static boolean safePattern(String pattern) {
            if (NESTED_QUANTIFIER.matcher(pattern).find()) {
                return false;
            }
            try {
                Pattern.compile(pattern);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        // -------------------------------------------------- helpers

        static String text(JsonNode node, String fallback) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return fallback;
            }
            if (node.isString()) {
                String value = node.asString();
                return value.isBlank() ? fallback : value;
            }
            return node.isValueNode() ? node.asString() : fallback;
        }
    }

    // ------------------------------------------------------------------ one operation

    private static final class OperationRun {

        final Run run;
        final String sourcePath;
        final String method;
        final JsonNode pathItem;
        final JsonNode op;
        final Map<String, Scheme> schemes;
        final List<String> blockers = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> inputs = new ArrayList<>();
        final List<String> outputs = new ArrayList<>();
        String key;
        String summary;
        String effectClass;
        String effectReason;
        String idempotencyHeader;
        boolean deprecated;
        ObjectNode template;

        OperationRun(Run run, String sourcePath, String method, JsonNode pathItem, JsonNode op,
                Map<String, Scheme> schemes) {
            this.run = run;
            this.sourcePath = sourcePath;
            this.method = method;
            this.pathItem = pathItem;
            this.op = op;
            this.schemes = schemes;
        }

        void convert() {
            key = uniqueKey();
            summary = truncate(Run.text(op.path("summary"), Run.text(op.path("operationId"), method + " " + sourcePath)), 200);
            deprecated = op.path("deprecated").asBoolean(false);
            checkMethodAndPath();
            ObjectNode requestSchema = null;
            Set<String> active = Set.of();
            parameters();
            if (SUPPORTED_METHODS.contains(method)) {
                requestSchema = requestBody(active);
            }
            List<Integer> statuses = new ArrayList<>();
            ObjectNode responseSchema = responses(active, statuses);
            security();
            if (op.path("callbacks").isObject() && op.get("callbacks").size() > 0) {
                notes.add("Callbacks described for this operation are ignored; Orqix does not receive calls.");
            }
            if (!blockers.isEmpty()) {
                template = null;
                return;
            }
            suggestEffect();
            template = MAPPER.createObjectNode();
            template.put("label", truncate(summary, 200));
            String description = describe();
            if (description != null) {
                template.put("description", description);
            }
            template.put("method", method);
            template.put("path", sourcePath);
            if (!"GET".equals(method)) {
                template.set("requestSchema", requestSchema);
            }
            template.set("responseSchema", responseSchema);
            ArrayNode success = template.putArray("successStatuses");
            statuses.forEach(success::add);
            template.put("maxTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
            template.put("suggestedEffectClass", effectClass);
            if (idempotencyHeader != null) {
                template.put("idempotencyHeader", idempotencyHeader);
            }
        }

        String describe() {
            String summaryText = Run.text(op.path("summary"), null);
            String descriptionText = Run.text(op.path("description"), null);
            String joined = summaryText != null && descriptionText != null && !descriptionText.startsWith(summaryText)
                    ? summaryText + ". " + descriptionText : descriptionText != null ? descriptionText : summaryText;
            return joined == null ? null : truncate(joined.replaceAll("\\s+", " "), 1000);
        }

        String uniqueKey() {
            String base = baseKey();
            String candidate = base;
            int n = 2;
            while (run.usedKeys.containsKey(candidate)) {
                String suffix = String.valueOf(n++);
                candidate = (base.length() + suffix.length() > 64 ? base.substring(0, 64 - suffix.length()) : base) + suffix;
            }
            if (!candidate.equals(base)) {
                notes.add("Another operation also maps to the name " + base + "; this one is called " + candidate + ".");
            }
            run.usedKeys.put(candidate, method + " " + sourcePath);
            return candidate;
        }

        String baseKey() {
            String id = Run.text(op.path("operationId"), null);
            String raw;
            if (id != null) {
                raw = id;
            } else {
                StringBuilder sb = new StringBuilder(method.toLowerCase(Locale.ROOT));
                for (String segment : sourcePath.split("/")) {
                    if (segment.isEmpty()) {
                        continue;
                    }
                    sb.append(' ').append(segment.startsWith("{") ? "by " + segment : segment);
                }
                raw = sb.toString();
            }
            StringBuilder out = new StringBuilder();
            boolean up = false;
            for (char ch : raw.toCharArray()) {
                if (Character.isLetterOrDigit(ch) && ch < 128) {
                    out.append(up && out.length() > 0 ? Character.toUpperCase(ch) : out.length() == 0 ? Character.toLowerCase(ch) : ch);
                    up = false;
                } else {
                    up = true;
                }
            }
            String value = out.toString();
            if (value.isEmpty() || !Character.isLowerCase(value.charAt(0))) {
                value = "op" + (value.isEmpty() ? "" : Character.toUpperCase(value.charAt(0)) + value.substring(1));
            }
            return value.length() > 64 ? value.substring(0, 64) : value;
        }

        void checkMethodAndPath() {
            if (!SUPPORTED_METHODS.contains(method)) {
                blockers.add(switch (method) {
                    case "DELETE" -> "Removing records (DELETE) is not available in connections yet.";
                    case "HEAD", "OPTIONS", "TRACE" -> method + " calls are not available in connections.";
                    default -> method + " calls are not supported.";
                });
            }
            if (sourcePath.contains("{")) {
                blockers.add("The address contains a variable part (" + truncate(sourcePath, 80) + "). Connections call fixed "
                        + "addresses today, so an operation that puts an identifier in the address cannot be imported. "
                        + "Ask for an operation that takes the identifier in the request body, or raise a platform change.");
            } else if (!STATIC_PATH.matcher(sourcePath).matches()) {
                blockers.add("The address " + truncate(sourcePath, 80)
                        + " uses characters connections do not allow (letters, digits and . _ ~ - / only, 200 at most).");
            }
        }

        void parameters() {
            Map<String, JsonNode> merged = new LinkedHashMap<>();
            Set<String> ignored = new TreeSet<>();
            Set<String> paging = new TreeSet<>();
            for (JsonNode list : new JsonNode[] {pathItem.path("parameters"), op.path("parameters")}) {
                if (!list.isArray()) {
                    continue;
                }
                for (JsonNode raw : list) {
                    Resolved resolved = run.deref(raw, Set.of());
                    if (resolved.failed() || resolved.node() == null || !resolved.node().isObject()) {
                        blockers.add("A parameter cannot be read (" + (resolved.error() == null ? "not described" : resolved.error())
                                + ").");
                        continue;
                    }
                    JsonNode p = resolved.node();
                    merged.put(Run.text(p.path("in"), "?") + ":" + Run.text(p.path("name"), "?").toLowerCase(Locale.ROOT), p);
                }
            }
            for (JsonNode p : merged.values()) {
                String in = Run.text(p.path("in"), "?");
                String name = truncate(Run.text(p.path("name"), "?"), 64);
                boolean required = p.path("required").asBoolean(false) || "path".equals(in);
                if ("header".equals(in)) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (IGNORED_HEADERS.contains(lower)) {
                        continue;
                    }
                    if (lower.contains("idempotency") && HEADER.matcher(name).matches()) {
                        idempotencyHeader = name;
                        continue;
                    }
                }
                if (PAGING.contains(name.toLowerCase(Locale.ROOT))) {
                    paging.add(name);
                }
                if ("path".equals(in)) {
                    continue; // reported through the address check
                }
                if (required) {
                    blockers.add("It needs the " + in + " parameter " + name
                            + ", which connections cannot send (they send the request body only).");
                } else {
                    ignored.add(name);
                }
            }
            if (!ignored.isEmpty()) {
                notes.add("Optional parameters are not sent: " + String.join(", ", limit(ignored, 8))
                        + ". Connections send the request body only.");
            }
            if (!paging.isEmpty()) {
                notes.add("The API appears to page its results (" + String.join(", ", limit(paging, 4))
                        + "). Orqix reads the first page only.");
            }
        }

        static List<String> limit(Collection<String> values, int max) {
            List<String> list = new ArrayList<>(values);
            if (list.size() > max) {
                List<String> shortened = new ArrayList<>(list.subList(0, max));
                shortened.add("and " + (list.size() - max) + " more");
                return shortened;
            }
            return list;
        }

        // -------------------------------------------------- request body

        ObjectNode requestBody(Set<String> active) {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            schema.put("additionalProperties", false);
            JsonNode raw = op.get("requestBody");
            if (raw == null) {
                return schema;
            }
            Resolved body = run.deref(raw, active);
            if (body.failed() || body.node() == null || !body.node().isObject()) {
                blockers.add("The request body cannot be read (" + (body.error() == null ? "not described" : body.error()) + ").");
                return schema;
            }
            if ("GET".equals(method)) {
                blockers.add("A GET operation with a request body is not supported.");
                return schema;
            }
            JsonNode content = body.node().path("content");
            JsonNode json = jsonMedia(content);
            if (json == null) {
                String kinds = content.isObject() ? String.join(", ", limit(names(content), 3)) : "none described";
                if (content.isObject() && content.size() > 0) {
                    blockers.add("The request body is " + kinds + ". Only JSON request bodies can be sent "
                            + "(file uploads, forms and XML are not supported).");
                }
                return schema;
            }
            if (!json.has("schema")) {
                notes.add("The request body is not described, so no fields are sent.");
                return schema;
            }
            Set<String> inner = Run.plus(active, body.refs());
            Shape shape = run.shape(json.get("schema"), inner, 0);
            if (shape.error != null) {
                blockers.add("The request body cannot be turned into a flat form: " + shape.error + ".");
                return schema;
            }
            Set<String> requiredOut = new LinkedHashSet<>();
            Set<String> omitted = new TreeSet<>();
            Set<String> unenforced = new TreeSet<>();
            Set<String> activeInner = Run.plus(inner, shape.refs);
            for (Map.Entry<String, JsonNode> p : shape.properties.entrySet()) {
                run.tick();
                String name = p.getKey();
                boolean required = shape.required.contains(name);
                String problem = null;
                Classified c = null;
                if (!REQUEST_FIELD.matcher(name).matches()) {
                    problem = "its name cannot be sent (names start with a lower-case letter and contain letters and digits only)";
                } else if (properties.size() >= MAX_REQUEST_FIELDS) {
                    problem = "the request has more than " + MAX_REQUEST_FIELDS + " fields";
                } else {
                    c = run.classify(p.getValue(), activeInner);
                    if (c.kind != Kind.SCALAR) {
                        problem = c.kind == Kind.OBJECT ? "it is a nested object" : c.kind == Kind.ARRAY ? "it is a list"
                                : c.error != null ? c.error : "it is not a simple value";
                    }
                }
                if (problem != null) {
                    if (required) {
                        blockers.add("The required field " + truncate(name, 64) + " cannot be sent: " + problem + ".");
                    } else {
                        omitted.add(truncate(name, 64));
                    }
                    continue;
                }
                properties.set(name, c.field);
                unenforced.addAll(c.unenforced);
                inputs.add(name);
                if (required) {
                    requiredOut.add(name);
                }
            }
            if (!requiredOut.isEmpty()) {
                ArrayNode required = schema.putArray("required");
                requiredOut.forEach(required::add);
                // keep the schema key order readable: type, required, properties, additionalProperties
                ObjectNode ordered = MAPPER.createObjectNode();
                ordered.put("type", "object");
                ordered.set("required", required);
                ordered.set("properties", properties);
                ordered.put("additionalProperties", false);
                schema = ordered;
            }
            if (!omitted.isEmpty()) {
                notes.add("Optional request fields left out because connections send flat values only: "
                        + String.join(", ", limit(omitted, 8)) + ".");
            }
            if (!unenforced.isEmpty()) {
                notes.add("These limits in the file are not checked by Orqix (the remote service still enforces them): "
                        + String.join(", ", unenforced) + ".");
            }
            return schema;
        }

        static Set<String> names(JsonNode object) {
            Set<String> names = new LinkedHashSet<>();
            object.propertyNames().forEach(names::add);
            return names;
        }

        static boolean isJson(String mediaType) {
            String type = mediaType.toLowerCase(Locale.ROOT).split(";")[0].strip();
            return type.equals("application/json") || (type.startsWith("application/") && type.endsWith("+json"));
        }

        static JsonNode jsonMedia(JsonNode content) {
            if (!content.isObject()) {
                return null;
            }
            for (Map.Entry<String, JsonNode> entry : content.properties()) {
                if (isJson(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return null;
        }

        // -------------------------------------------------- responses

        ObjectNode responses(Set<String> active, List<Integer> statuses) {
            ObjectNode empty = MAPPER.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            JsonNode responses = op.path("responses");
            if (!responses.isObject()) {
                blockers.add("The operation declares no responses.");
                return empty;
            }
            ObjectNode chosen = null;
            boolean nonJson = false;
            String nonJsonKind = null;
            boolean described = false;
            for (Map.Entry<String, JsonNode> entry : responses.properties()) {
                String code = entry.getKey();
                int status;
                if (code.matches("2\\d\\d")) {
                    status = Integer.parseInt(code);
                } else if (code.equalsIgnoreCase("2XX")) {
                    status = 200;
                    notes.add("The file declares 2XX; status 200 is expected.");
                } else {
                    continue;
                }
                if (!statuses.contains(status)) {
                    statuses.add(status);
                }
                Resolved resolved = run.deref(entry.getValue(), active);
                if (resolved.failed() || resolved.node() == null || !resolved.node().isObject()) {
                    continue;
                }
                JsonNode content = resolved.node().path("content");
                if (!content.isObject() || content.isEmpty()) {
                    continue;
                }
                JsonNode json = jsonMedia(content);
                if (json == null) {
                    nonJson = true;
                    nonJsonKind = nonJsonKind == null ? String.join(", ", limit(names(content), 2)) : nonJsonKind;
                    continue;
                }
                if (chosen == null) {
                    described = true;
                    chosen = responseSchema(json, Run.plus(active, resolved.refs()));
                }
            }
            if (statuses.isEmpty()) {
                blockers.add("The operation declares no success response (2xx).");
                return empty;
            }
            if (chosen == null && nonJson) {
                blockers.add("The success response is " + nonJsonKind + ", not JSON (file downloads and XML are not supported).");
                return empty;
            }
            if (chosen == null) {
                if (!described && statuses.stream().noneMatch(s -> s == 204)) {
                    notes.add("The success response has no described body, so no result fields are available.");
                }
                return empty;
            }
            return chosen;
        }

        ObjectNode responseSchema(JsonNode media, Set<String> active) {
            ObjectNode empty = MAPPER.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            if (!media.has("schema")) {
                notes.add("The success response has no described body, so no result fields are available.");
                return empty;
            }
            Set<String> omitted = new TreeSet<>();
            Set<String> unenforced = new TreeSet<>();
            ObjectNode result = responseObject(media.get("schema"), active, 1, omitted, unenforced, "");
            if (result == null) {
                Shape why = run.shape(media.get("schema"), active, 0);
                notes.add("The success response is " + (why.error == null ? "not a plain object" : why.error)
                        + ", so its fields are not available to workflows.");
                return empty;
            }
            result.path("properties").propertyNames().forEach(outputs::add);
            if (!omitted.isEmpty()) {
                notes.add("Result fields not available to workflows (lists, alternatives, nullable, too deep or oddly named): "
                        + String.join(", ", limit(omitted, 8)) + ".");
            }
            return result;
        }

        ObjectNode responseObject(JsonNode schema, Set<String> active, int level, Set<String> omitted,
                Set<String> unenforced, String prefix) {
            Shape shape = run.shape(schema, active, 0);
            if (shape.error != null) {
                return null;
            }
            Set<String> inner = Run.plus(active, shape.refs);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            Set<String> requiredOut = new LinkedHashSet<>();
            for (Map.Entry<String, JsonNode> p : shape.properties.entrySet()) {
                run.tick();
                String name = p.getKey();
                String shown = truncate(prefix + name, 64);
                if (!RESPONSE_FIELD.matcher(name).matches() || properties.size() >= MAX_RESPONSE_FIELDS) {
                    omitted.add(shown);
                    continue;
                }
                Classified c = run.classify(p.getValue(), inner);
                if (c.kind == Kind.SCALAR && !c.nullable) {
                    properties.set(name, c.field);
                    unenforced.addAll(c.unenforced);
                } else if (c.kind == Kind.OBJECT && level < MAX_RESPONSE_DEPTH) {
                    ObjectNode nested = responseObject(p.getValue(), inner, level + 1, omitted, unenforced, name + ".");
                    if (nested == null || c.nullable) {
                        omitted.add(shown);
                        continue;
                    }
                    properties.set(name, nested);
                } else {
                    omitted.add(shown);
                    continue;
                }
                if (shape.required.contains(name)) {
                    requiredOut.add(name);
                }
            }
            if (!requiredOut.isEmpty()) {
                ArrayNode required = out.putArray("required");
                requiredOut.forEach(required::add);
            }
            out.set("properties", properties);
            return out;
        }

        // -------------------------------------------------- security and effect

        void security() {
            JsonNode requirements = op.has("security") ? op.get("security") : run.root.path("security");
            Set<String> options = new LinkedHashSet<>();
            if (!requirements.isArray() || requirements.isEmpty()) {
                options.add("none");
            } else {
                for (JsonNode requirement : requirements) {
                    if (!requirement.isObject() || requirement.isEmpty()) {
                        options.add("none");
                        continue;
                    }
                    if (requirement.size() > 1) {
                        continue; // several schemes together: not supported
                    }
                    String name = requirement.propertyNames().iterator().next();
                    Scheme scheme = schemes.get(name);
                    if (scheme == null || !scheme.supported()) {
                        continue;
                    }
                    if (scheme.type().startsWith("API key in header ")) {
                        options.add("apiKeyHeader:" + scheme.type().substring("API key in header ".length()));
                    } else {
                        options.add("bearerToken");
                    }
                }
            }
            run.operationAuth.put(key, options);
        }

        void suggestEffect() {
            if ("GET".equals(method)) {
                effectClass = "readOnly";
                effectReason = "Reads data; a GET call has no side effect by HTTP rules. Orqix may retry and test it freely.";
                idempotencyHeader = null;
            } else if (idempotencyHeader != null) {
                effectClass = "idempotentWithKey";
                effectReason = "The file declares the " + idempotencyHeader + " header. If the system really deduplicates on it, "
                        + "a retry cannot create a second record.";
            } else {
                effectClass = "nonIdempotent";
                effectReason = ("PUT".equals(method) || "PATCH".equals(method)
                        ? method + " is repeatable by HTTP rules, but nothing in the file proves it. "
                        : "A repeated call may do the work twice. ")
                        + "If a call is sent and the answer is lost, Orqix stops and asks an operator instead of retrying. "
                        + "Choose a different class only if the system owner confirms it.";
            }
        }

        Operation toOperation() {
            boolean blocked = !blockers.isEmpty();
            String disposition = blocked ? ImportReport.BLOCKED : notes.isEmpty() ? ImportReport.SUPPORTED : ImportReport.SIMPLIFIED;
            boolean testable = !blocked && "GET".equals(method);
            return new Operation(key, method, truncate(sourcePath, 300), summary, disposition, List.copyOf(blockers),
                    List.copyOf(notes), blocked ? null : effectClass, blocked ? null : effectReason,
                    blocked ? null : idempotencyHeader, testable, deprecated, false, List.copyOf(inputs),
                    List.copyOf(outputs));
        }
    }
}
