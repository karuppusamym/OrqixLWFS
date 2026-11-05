package com.orqix.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.internal.json.JsonPointers;

import tools.jackson.databind.JsonNode;

/**
 * Shared reading of connection definitions v1 (CG-C01, docs/build/S130-connection-definition-v1.md): the registration
 * rules CONN_V1_* that JSON Schema cannot express, the operation's inputs, result pointers with {@code [*]} row
 * projections and {@code /@headers/} response headers, and the bounds. Used by registration, the compiler and the
 * HTTP executor so all three read a definition the same way. Pure Java.
 */
public final class ConnectionContracts {

    public static final String V1 = "1.0";
    public static final int MAX_BODY_DEPTH = 5;
    public static final int MAX_BODY_LEAVES = 200;
    public static final int MAX_RESPONSE_DEPTH = 5;
    public static final int MAX_ROWS = 100;
    /** Pointer segment that addresses response headers instead of the body. */
    public static final String HEADERS_SEGMENT = "@headers";
    /** Pointer segment that projects every item of a list. */
    public static final String EVERY_ITEM = "[*]";

    /** Request headers a definition may never set or map (lower case). */
    public static final Set<String> FORBIDDEN_REQUEST_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "host", "content-type", "content-length", "connection", "keep-alive", "transfer-encoding", "te", "trailer",
            "upgrade", "expect", "via", "forwarded", "x-forwarded-for", "x-forwarded-host", "x-correlation-id",
            "x-request-id");
    /** Response headers a result may never read (lower case). */
    public static final Set<String> FORBIDDEN_RESPONSE_HEADERS = Set.of("set-cookie", "set-cookie2", "authorization",
            "proxy-authenticate", "www-authenticate", "connection", "keep-alive", "transfer-encoding", "te", "trailer",
            "upgrade");
    /** Status codes with a platform meaning that error mapping cannot change. */
    public static final Set<Integer> UNMAPPABLE_STATUSES = Set.of(401, 403, 408, 429);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern FIELD_KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");

    private ConnectionContracts() {
    }

    /** One broken rule; {@code pointer} is relative to the definition document. */
    public record Problem(String pointer, String code, String message) {
    }

    /** @return true for a definition with {@code schemaVersion "1.0"} */
    public static boolean isV1(JsonNode definition) {
        return definition != null && V1.equals(definition.path("schemaVersion").asString(""));
    }

    // ------------------------------------------------------------------------------------------------- inputs

    /**
     * Inputs of an operation that a workflow can map, by name: the requestSchema fields followed by every path
     * parameter that is not a const. Each value is the field declaration.
     */
    public static Map<String, JsonNode> inputs(JsonNode operation) {
        Map<String, JsonNode> inputs = new LinkedHashMap<>();
        JsonNode properties = operation.path("requestSchema").path("properties");
        for (String name : properties.propertyNames()) {
            inputs.put(name, properties.get(name));
        }
        JsonNode params = operation.path("pathParams");
        for (String name : params.propertyNames()) {
            if (!params.get(name).has("const")) {
                inputs.putIfAbsent(name, params.get(name));
            }
        }
        return inputs;
    }

    /** @return required inputs: requestSchema.required plus every non-const path parameter */
    public static Set<String> requiredInputs(JsonNode operation) {
        Set<String> required = new LinkedHashSet<>();
        operation.path("requestSchema").path("required").forEach(r -> required.add(r.asString()));
        JsonNode params = operation.path("pathParams");
        for (String name : params.propertyNames()) {
            if (!params.get(name).has("const")) {
                required.add(name);
            }
        }
        return required;
    }

    /** @return the {@code {name}} placeholders of a path or template, in order (raw text, possibly invalid names) */
    public static List<String> placeholders(String text) {
        List<String> names = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** requestSchema fields referenced by query, headers, the body template, binaryInput (not path parameters). */
    public static Set<String> referencedFields(JsonNode operation) {
        Set<String> used = new LinkedHashSet<>();
        JsonNode query = operation.path("query");
        for (String name : query.propertyNames()) {
            JsonNode q = query.get(name);
            if (q.has("input")) {
                used.add(q.get("input").asString());
            }
            if (q.has("template")) {
                used.addAll(placeholders(q.get("template").asString()));
            }
        }
        JsonNode headers = operation.path("headers");
        for (String name : headers.propertyNames()) {
            if (headers.get(name).has("input")) {
                used.add(headers.get(name).get("input").asString());
            }
        }
        if (operation.has("body")) {
            collectTemplateInputs(operation.get("body"), used);
        }
        if (operation.has("binaryInput")) {
            used.add(operation.get("binaryInput").asString());
        }
        return used;
    }

    /**
     * requestSchema fields that form the flat JSON body when there is no body template (v0 behavior): every field no
     * query parameter, header or binaryInput uses. Empty for GET and when a body template or a non-json content type
     * is declared.
     */
    public static List<String> flatBodyFields(JsonNode operation) {
        if ("GET".equals(operation.path("method").asString()) || operation.has("body")
                || !"json".equals(operation.path("contentType").asString("json"))) {
            return List.of();
        }
        Set<String> used = referencedFields(operation);
        List<String> fields = new ArrayList<>();
        for (String name : operation.path("requestSchema").path("properties").propertyNames()) {
            if (!used.contains(name)) {
                fields.add(name);
            }
        }
        return fields;
    }

    private static void collectTemplateInputs(JsonNode template, Set<String> into) {
        for (String member : template.propertyNames()) {
            collectValueInputs(template.get(member), into);
        }
    }

    private static void collectValueInputs(JsonNode value, Set<String> into) {
        if (value.has("input")) {
            into.add(value.get("input").asString());
        } else if (value.has("object")) {
            collectTemplateInputs(value.get("object"), into);
        } else if (value.has("array")) {
            value.get("array").forEach(v -> collectValueInputs(v, into));
        }
    }

    // ------------------------------------------------------------------------------------------- result pointers

    /**
     * A parsed result pointer.
     *
     * @param header     response header name for {@code /@headers/<Name>}, else null
     * @param base       tokens up to the list for a projection, or the whole pointer
     * @param projection true when the pointer contains {@code /[*]}
     * @param column     tokens after {@code /[*]} (empty: rows of every scalar column)
     */
    public record ResultPointer(String header, List<String> base, boolean projection, List<String> column) {
    }

    /** @return the parsed pointer, or null when it is not valid (bad escape, several [*], header with more tokens) */
    public static ResultPointer parseResult(String pointer) {
        List<String> tokens;
        try {
            tokens = JsonPointers.parse(pointer);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (tokens.isEmpty()) {
            return null;
        }
        if (HEADERS_SEGMENT.equals(tokens.get(0))) {
            return tokens.size() == 2 ? new ResultPointer(tokens.get(1), List.of(), false, List.of()) : null;
        }
        int star = tokens.indexOf(EVERY_ITEM);
        if (star < 0) {
            return new ResultPointer(null, tokens, false, List.of());
        }
        if (tokens.lastIndexOf(EVERY_ITEM) != star) {
            return null;
        }
        return new ResultPointer(null, List.copyOf(tokens.subList(0, star)), true,
                List.copyOf(tokens.subList(star + 1, tokens.size())));
    }

    /**
     * The schema reached by pointer tokens in a response schema; numeric tokens index arrays.
     *
     * @param allRequired false when any step is optional or an array index (the value may be absent)
     */
    public record SchemaAt(JsonNode schema, boolean allRequired) {
    }

    /** @return the schema at the tokens, or null when they do not resolve */
    public static SchemaAt schemaAt(JsonNode schema, List<String> tokens) {
        boolean allRequired = true;
        JsonNode current = schema;
        for (String token : tokens) {
            if (current == null) {
                return null;
            }
            String type = current.path("type").asString("");
            if ("object".equals(type)) {
                JsonNode next = current.path("properties").get(token);
                if (next == null) {
                    return null;
                }
                boolean required = false;
                for (JsonNode r : current.path("required")) {
                    required |= token.equals(r.asString());
                }
                allRequired &= required;
                current = next;
            } else if ("array".equals(type)) {
                if (!token.matches("^(0|[1-9][0-9]{0,5})$")) {
                    return null;
                }
                allRequired = false;
                current = current.get("items");
            } else {
                return null;
            }
        }
        return current == null ? null : new SchemaAt(current, allRequired);
    }

    /** @return true for a scalar field declaration (string, number, integer, boolean) */
    public static boolean isScalar(JsonNode schema) {
        return schema != null && Set.of("string", "number", "integer", "boolean").contains(schema.path("type").asString(""));
    }

    /** @return the scalar columns of a row item (object), by name; empty when the item is not an object */
    public static Map<String, JsonNode> rowColumns(JsonNode itemSchema) {
        Map<String, JsonNode> columns = new LinkedHashMap<>();
        if (itemSchema != null && "object".equals(itemSchema.path("type").asString(""))) {
            JsonNode properties = itemSchema.path("properties");
            for (String name : properties.propertyNames()) {
                if (isScalar(properties.get(name))) {
                    columns.put(name, properties.get(name));
                }
            }
        }
        return columns;
    }

    /** @return the header declaration ignoring case, or null */
    public static JsonNode responseHeader(JsonNode operation, String name) {
        JsonNode headers = operation.path("responseHeaders");
        for (String declared : headers.propertyNames()) {
            if (declared.equalsIgnoreCase(name)) {
                return headers.get(declared);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------- registration rules

    /** @return broken CONN_V1_* rules of a v1 definition that already matches the v1 schema; empty for v0 */
    public static List<Problem> check(JsonNode definition) {
        List<Problem> problems = new ArrayList<>();
        if (!isV1(definition)) {
            return problems;
        }
        JsonNode operations = definition.path("operations");
        for (String name : operations.propertyNames()) {
            checkOperation(operations, name, operations.get(name), "/operations/" + JsonPointers.escape(name), problems);
        }
        return problems;
    }

    private static void checkOperation(JsonNode operations, String name, JsonNode op, String at, List<Problem> out) {
        String method = op.path("method").asString();
        String effect = op.path("effectClass").asString();
        JsonNode fields = op.path("requestSchema").path("properties");

        // Path parameters.
        List<String> placeholders = placeholders(op.path("path").asString());
        Set<String> seen = new LinkedHashSet<>();
        JsonNode params = op.path("pathParams");
        for (String p : placeholders) {
            if (!seen.add(p)) {
                out.add(new Problem(at + "/path", "CONN_V1_PATH_PARAMS", "The path uses {" + p + "} twice."));
            } else if (!params.has(p)) {
                out.add(new Problem(at + "/path", "CONN_V1_PATH_PARAMS", "The path uses {" + p
                        + "}, which pathParams does not declare."));
            }
        }
        for (String p : params.propertyNames()) {
            JsonNode param = params.get(p);
            if (!seen.contains(p)) {
                out.add(new Problem(at + "/pathParams/" + p, "CONN_V1_PATH_PARAMS", "The path parameter " + p
                        + " does not appear in the path."));
            }
            if (!param.has("const") && fields.has(p)) {
                out.add(new Problem(at + "/pathParams/" + p, "CONN_V1_INPUT_CLASH", "The path parameter " + p
                        + " has the same name as a request field; inputs must have distinct names."));
            }
            if (param.has("const")) {
                String text = param.get("const").asString();
                if (text.isEmpty() || !safePathValue(text)) {
                    out.add(new Problem(at + "/pathParams/" + p + "/const", "CONN_V1_PATH_PARAMS",
                            "A constant path value cannot be empty or contain '/', '..' or control characters."));
                }
                if (param.has("pattern") || param.has("enum")) {
                    out.add(new Problem(at + "/pathParams/" + p, "CONN_V1_PATH_PARAMS",
                            "A constant path parameter takes no pattern or enum."));
                }
            } else if ("string".equals(param.path("type").asString()) && !param.has("pattern") && !param.has("enum")) {
                out.add(new Problem(at + "/pathParams/" + p, "CONN_V1_PATH_PARAMS", "The text path parameter " + p
                        + " needs a pattern or an enum, so a value cannot change the addressed resource."));
            }
        }

        // Query parameters.
        JsonNode query = op.path("query");
        for (String q : query.propertyNames()) {
            JsonNode param = query.get(q);
            String qat = at + "/query/" + JsonPointers.escape(q);
            if (param.has("input")) {
                requireField(fields, param.get("input").asString(), qat + "/input", out);
                attachmentOutsideBody(fields, param.get("input").asString(), qat + "/input", out);
            }
            if (param.has("template")) {
                String template = param.get("template").asString();
                String encoder = param.path("encoder").asString();
                for (String ph : placeholders(template)) {
                    if (!FIELD_KEY.matcher(ph).matches()) {
                        out.add(new Problem(qat + "/template", "CONN_V1_TEMPLATE", "The placeholder {" + ph
                                + "} is not a request field name."));
                        continue;
                    }
                    if (!requireField(fields, ph, qat + "/template", out)) {
                        continue;
                    }
                    JsonNode field = fields.get(ph);
                    attachmentOutsideBody(fields, ph, qat + "/template", out);
                    if ("servicenowEncodedQuery".equals(encoder) && !field.has("pattern") && !field.has("enum")
                            && !"integer".equals(field.path("type").asString())
                            && !"boolean".equals(field.path("type").asString())) {
                        out.add(new Problem(qat + "/template", "CONN_V1_TEMPLATE", "The request field " + ph
                                + " is used in an encoded query, so it needs a pattern or an enum that keeps query"
                                + " syntax out of its values."));
                    }
                }
                if (template.replaceAll("\\{[^{}]*\\}", "").matches(".*[{}].*")) {
                    out.add(new Problem(qat + "/template", "CONN_V1_TEMPLATE", "The template has an unbalanced brace."));
                }
            }
        }

        // Headers.
        JsonNode headers = op.path("headers");
        for (String h : headers.propertyNames()) {
            String lower = h.toLowerCase(Locale.ROOT);
            String hat = at + "/headers/" + h;
            if (FORBIDDEN_REQUEST_HEADERS.contains(lower) || lower.startsWith("proxy-") || lower.startsWith("x-orqix-")
                    || lower.startsWith("sec-")
                    || (op.has("idempotencyHeader") && lower.equalsIgnoreCase(op.get("idempotencyHeader").asString()))) {
                out.add(new Problem(hat, "CONN_V1_HEADER_FORBIDDEN", "The header " + h
                        + " is controlled by Orqix or carries credentials and cannot be declared."));
            }
            if (headers.get(h).has("input")) {
                requireField(fields, headers.get(h).get("input").asString(), hat + "/input", out);
                attachmentOutsideBody(fields, headers.get(h).get("input").asString(), hat + "/input", out);
            }
        }

        // Body.
        String contentType = op.path("contentType").asString("json");
        if (op.has("body")) {
            int[] leaves = {0};
            int depth = templateDepth(op.get("body"), fields, at + "/body", contentType, leaves, out);
            if (depth > MAX_BODY_DEPTH) {
                out.add(new Problem(at + "/body", "CONN_V1_BODY_SHAPE", "The body nests " + depth
                        + " levels; at most " + MAX_BODY_DEPTH + " are allowed."));
            }
            if (leaves[0] > MAX_BODY_LEAVES) {
                out.add(new Problem(at + "/body", "CONN_V1_BODY_SHAPE", "The body has " + leaves[0]
                        + " values; at most " + MAX_BODY_LEAVES + " are allowed."));
            }
            if (("form".equals(contentType) || "multipart".equals(contentType)) && depth > 1) {
                out.add(new Problem(at + "/body", "CONN_V1_BODY_SHAPE", "A " + contentType
                        + " body must be flat: only inputs and constants."));
            }
        }
        if ("binary".equals(contentType)) {
            if (!op.has("binaryInput") || op.has("body")) {
                out.add(new Problem(at, "CONN_V1_BODY_SHAPE",
                        "A binary body needs binaryInput (one file input) and no body template."));
            }
        } else if (op.has("binaryInput")) {
            out.add(new Problem(at + "/binaryInput", "CONN_V1_BODY_SHAPE", "binaryInput needs contentType binary."));
        }
        if ("multipart".equals(contentType) && !op.has("body")) {
            out.add(new Problem(at, "CONN_V1_BODY_SHAPE", "A multipart body needs a body template naming its parts."));
        }
        if ("form".equals(contentType) && !op.has("body")) {
            out.add(new Problem(at, "CONN_V1_BODY_SHAPE", "A form body needs a body template naming its fields."));
        }
        if (op.has("binaryInput")) {
            String input = op.get("binaryInput").asString();
            if (requireField(fields, input, at + "/binaryInput", out)
                    && !"attachment".equals(fields.get(input).path("format").asString())) {
                out.add(new Problem(at + "/binaryInput", "CONN_V1_BODY_SHAPE", "binaryInput must name a request field"
                        + " with format attachment."));
            }
        }
        if (!"multipart".equals(contentType) && !"binary".equals(contentType)) {
            for (String f : fields.propertyNames()) {
                if ("attachment".equals(fields.get(f).path("format").asString())) {
                    out.add(new Problem(at + "/requestSchema/properties/" + f, "CONN_V1_BODY_SHAPE", "The file input "
                            + f + " can only be sent in a multipart or binary body."));
                }
            }
        }
        // Every declared field must be sent somewhere.
        Set<String> used = referencedFields(op);
        used.addAll(flatBodyFields(op));
        for (String f : fields.propertyNames()) {
            if (!used.contains(f)) {
                out.add(new Problem(at + "/requestSchema/properties/" + f, "CONN_V1_INPUT_UNUSED", "The request field "
                        + f + " is never sent: reference it from the path, query, headers or body."));
            }
        }
        for (JsonNode r : op.path("requestSchema").path("required")) {
            if (!fields.has(r.asString())) {
                out.add(new Problem(at + "/requestSchema/required", "CONN_V1_INPUT_UNKNOWN", "The required field "
                        + r.asString() + " is not declared."));
            }
        }

        // Methods and effect classes.
        if ("DELETE".equals(method) && "readOnly".equals(effect)) {
            out.add(new Problem(at + "/effectClass", "CONN_V1_EFFECT_CLASS", "A DELETE changes data, so it cannot be"
                    + " readOnly; declare how a repeat behaves."));
        }
        if ("idempotentNatural".equals(effect) && !Set.of("PUT", "PATCH", "DELETE").contains(method)) {
            out.add(new Problem(at + "/effectClass", "CONN_V1_EFFECT_CLASS", "idempotentNatural is for PUT, PATCH or"
                    + " DELETE that set fixed values; a " + method + " cannot be declared naturally idempotent."));
        }
        if ("GET".equals(method) && !"readOnly".equals(effect)) {
            out.add(new Problem(at + "/effectClass", "CONN_V1_EFFECT_CLASS", "A GET must be readOnly."));
        }

        // Response.
        int depth = responseDepth(op.path("responseSchema"));
        if (depth > MAX_RESPONSE_DEPTH) {
            out.add(new Problem(at + "/responseSchema", "CONN_V1_RESPONSE_DEPTH", "The response nests " + depth
                    + " levels; at most " + MAX_RESPONSE_DEPTH + " are allowed."));
        }
        for (String h : op.path("responseHeaders").propertyNames()) {
            if (FORBIDDEN_RESPONSE_HEADERS.contains(h.toLowerCase(Locale.ROOT))) {
                out.add(new Problem(at + "/responseHeaders/" + h, "CONN_V1_HEADER_FORBIDDEN", "The response header " + h
                        + " cannot be mapped."));
            }
        }

        // Errors.
        Set<Integer> statuses = new LinkedHashSet<>();
        JsonNode errors = op.path("errors");
        for (int i = 0; i < errors.size(); i++) {
            int status = errors.get(i).path("status").asInt();
            if (UNMAPPABLE_STATUSES.contains(status)) {
                out.add(new Problem(at + "/errors/" + i + "/status", "CONN_V1_ERROR_STATUS", "HTTP " + status
                        + " keeps its platform meaning (sign-in, permission, timeout or rate limit) and cannot be"
                        + " mapped."));
            }
            if (!statuses.add(status)) {
                out.add(new Problem(at + "/errors/" + i + "/status", "CONN_V1_ERROR_STATUS", "HTTP " + status
                        + " is mapped twice."));
            }
        }

        // Pagination.
        if (op.has("pagination")) {
            JsonNode page = op.get("pagination");
            String pat = at + "/pagination";
            SchemaAt items = schemaAt(op.path("responseSchema"), tokens(page.path("itemsPointer").asString()));
            if (items == null || !"array".equals(items.schema().path("type").asString(""))) {
                out.add(new Problem(pat + "/itemsPointer", "CONN_V1_PAGINATION", "itemsPointer must point at a list in"
                        + " the response."));
            }
            String style = page.path("style").asString();
            if ("offset".equals(style)) {
                for (String param : List.of(page.path("offsetParam").asString(), page.path("limitParam").asString())) {
                    if (query.has(param)) {
                        out.add(new Problem(pat, "CONN_V1_PAGINATION", "The query parameter " + param
                                + " is set by pagination and cannot also be declared in query."));
                    }
                }
            }
            if ("cursor".equals(style) || "pageToken".equals(style)) {
                SchemaAt cursor = schemaAt(op.path("responseSchema"), tokens(page.path("cursorPointer").asString()));
                if (cursor == null || !isScalar(cursor.schema())) {
                    out.add(new Problem(pat + "/cursorPointer", "CONN_V1_PAGINATION", "cursorPointer must point at a"
                            + " single value in the response."));
                }
                if (query.has(page.path("cursorParam").asString())) {
                    out.add(new Problem(pat + "/cursorParam", "CONN_V1_PAGINATION", "The cursor parameter is set by"
                            + " pagination and cannot also be declared in query."));
                }
            }
        }

        // Async (declaration only).
        if (op.has("async")) {
            String status = op.get("async").path("statusOperation").asString();
            JsonNode target = operations.get(status);
            if (target == null || !"readOnly".equals(target.path("effectClass").asString())) {
                out.add(new Problem(at + "/async/statusOperation", "CONN_V1_ASYNC", "The status operation " + status
                        + " must be a readOnly operation of this connection."));
            }
        }

        // Reconcile (CG-C02).
        if (op.has("reconcile")) {
            checkReconcile(operations, name, op, at + "/reconcile", out);
        }
    }

    private static void checkReconcile(JsonNode operations, String name, JsonNode op, String at, List<Problem> out) {
        JsonNode rec = op.get("reconcile");
        JsonNode fields = op.path("requestSchema").path("properties");
        String keyField = rec.path("keyField").asString();
        JsonNode key = fields.get(keyField);
        if (key == null || !"string".equals(key.path("type").asString()) || key.has("enum")) {
            out.add(new Problem(at + "/keyField", "CONN_V1_RECONCILE", "keyField must name a text request field of"
                    + " this operation (Orqix fills it with the reconciliation key)."));
        } else if (key.has("maxLength") && key.get("maxLength").asInt() < 64) {
            out.add(new Problem(at + "/keyField", "CONN_V1_RECONCILE", "The key field must accept at least 64"
                    + " characters."));
        }
        String lookupName = rec.path("operation").asString();
        JsonNode lookup = operations.get(lookupName);
        if (lookup == null || lookupName.equals(name)) {
            out.add(new Problem(at + "/operation", "CONN_V1_RECONCILE", "The lookup operation " + lookupName
                    + " must be another operation of this connection."));
            return;
        }
        if (!"readOnly".equals(lookup.path("effectClass").asString()) || !"GET".equals(lookup.path("method").asString())
                || lookup.has("pagination")) {
            out.add(new Problem(at + "/operation", "CONN_V1_RECONCILE", "The lookup " + lookupName
                    + " must be a readOnly GET without pagination."));
        }
        String lookupField = rec.path("lookupField").asString(keyField);
        Map<String, JsonNode> lookupInputs = inputs(lookup);
        JsonNode lf = lookupInputs.get(lookupField);
        if (lf == null || !"string".equals(lf.path("type").asString())) {
            out.add(new Problem(at + (rec.has("lookupField") ? "/lookupField" : "/keyField"), "CONN_V1_RECONCILE",
                    "The lookup " + lookupName + " has no text input " + lookupField + " to receive the key."));
        }
        Set<String> otherRequired = new LinkedHashSet<>(requiredInputs(lookup));
        otherRequired.remove(lookupField);
        if (!otherRequired.isEmpty()) {
            out.add(new Problem(at + "/operation", "CONN_V1_RECONCILE", "The lookup " + lookupName
                    + " may only require the key; it also requires " + String.join(", ", otherRequired) + "."));
        }
        List<String> found = tokens(rec.path("found").asString());
        if (found == null || schemaAt(lookup.path("responseSchema"), found) == null) {
            out.add(new Problem(at + "/found", "CONN_V1_RECONCILE", "found must point into the response of "
                    + lookupName + "."));
        }
        if (rec.has("foundAs")) {
            List<String> as = tokens(rec.get("foundAs").asString());
            if (as == null || (!as.isEmpty() && schemaAt(op.path("responseSchema"), as) == null)) {
                out.add(new Problem(at + "/foundAs", "CONN_V1_RECONCILE", "foundAs must point into this operation's"
                        + " response."));
            }
        }
    }

    private static boolean requireField(JsonNode fields, String name, String at, List<Problem> out) {
        if (!fields.has(name)) {
            out.add(new Problem(at, "CONN_V1_INPUT_UNKNOWN", "The request field " + name + " is not declared in"
                    + " requestSchema."));
            return false;
        }
        return true;
    }

    private static void attachmentOutsideBody(JsonNode fields, String name, String at, List<Problem> out) {
        if (fields.has(name) && "attachment".equals(fields.get(name).path("format").asString())) {
            out.add(new Problem(at, "CONN_V1_BODY_SHAPE", "The file input " + name
                    + " can only be sent as a body part."));
        }
    }

    /** @return nesting depth of the template (1 = flat); counts leaves; checks input references */
    private static int templateDepth(JsonNode template, JsonNode fields, String at, String contentType, int[] leaves,
            List<Problem> out) {
        int deepest = 0;
        for (String member : template.propertyNames()) {
            deepest = Math.max(deepest, valueDepth(template.get(member), fields, at + "/" + JsonPointers.escape(member),
                    contentType, leaves, out));
        }
        return 1 + deepest;
    }

    /** @return 0 for a leaf, the template depth for an object, 1 + the deepest item for an array */
    private static int valueDepth(JsonNode value, JsonNode fields, String at, String contentType, int[] leaves,
            List<Problem> out) {
        if (value.has("input")) {
            leaves[0]++;
            String input = value.get("input").asString();
            requireField(fields, input, at + "/input", out);
            if ("json".equals(contentType) || "form".equals(contentType)) {
                attachmentOutsideBody(fields, input, at + "/input", out);
            }
            return 0;
        }
        if (value.has("const")) {
            leaves[0]++;
            return 0;
        }
        if (value.has("object")) {
            return templateDepth(value.get("object"), fields, at + "/object", contentType, leaves, out);
        }
        int deepest = 0;
        JsonNode array = value.path("array");
        for (int i = 0; i < array.size(); i++) {
            deepest = Math.max(deepest, valueDepth(array.get(i), fields, at + "/array/" + i, contentType, leaves, out));
        }
        return 1 + deepest;
    }

    /** @return levels of objects and arrays (a scalar is 0, a flat object 1) */
    static int responseDepth(JsonNode schema) {
        String type = schema.path("type").asString("");
        if ("object".equals(type)) {
            int deepest = 0;
            JsonNode properties = schema.path("properties");
            for (String name : properties.propertyNames()) {
                deepest = Math.max(deepest, responseDepth(properties.get(name)));
            }
            return 1 + deepest;
        }
        if ("array".equals(type)) {
            return 1 + responseDepth(schema.path("items"));
        }
        return 0;
    }

    /** @return pointer tokens, or null when the text is not a pointer */
    public static List<String> tokens(String pointer) {
        try {
            return JsonPointers.parse(pointer == null ? "" : pointer);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** @return true when the value can be a path segment: no '/', no '..' segment, no control characters */
    public static boolean safePathValue(String value) {
        if (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }
}
