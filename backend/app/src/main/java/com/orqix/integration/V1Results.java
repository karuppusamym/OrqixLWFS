package com.orqix.integration;

import java.math.BigDecimal;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.ConnectionContracts;
import com.orqix.integration.ConnectionDefinition.Operation;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Result mapping of v1 operations (S130): plain JSON pointers (array indexes allowed), {@code <list>/[*]} rows of the
 * item's scalar columns, {@code <list>/[*]/<column>} lists of single values, and {@code /@headers/<Name>} for
 * declared response headers. Lists keep at most {@link ConnectionContracts#MAX_ROWS} items; longer lists are cut and
 * reported in {@link Mapped#truncated()}.
 */
final class V1Results {

    /** A pointer that did not resolve; the message is the pointer (never a value). */
    static final class Unresolved extends RuntimeException {
        Unresolved(String pointer) {
            super(pointer, null, false, false);
        }
    }

    record Mapped(ObjectNode result, List<String> truncated) {
    }

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;[^,]*rel=\"?next\"?", Pattern.CASE_INSENSITIVE);

    private V1Results() {
    }

    static Mapped map(Operation operation, Map<String, String> mapping, JsonNode body, HttpHeaders headers, Json json) {
        return map(operation, mapping, body, headers, json, null);
    }

    /** @param asyncValues status location values of an accepted asynchronous answer (CG-W02), else null */
    static Mapped map(Operation operation, Map<String, String> mapping, JsonNode body, HttpHeaders headers, Json json,
            Map<String, JsonNode> asyncValues) {
        ObjectNode result = json.object();
        List<String> truncated = new ArrayList<>();
        JsonNode responseSchema = operation.responseSchema();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            // S129: "?" marks a result a check may not have yet; it is left out instead of failing the answer.
            boolean optional = ResultMappings.isOptional(entry.getValue());
            String pointer = ResultMappings.pointer(entry.getValue());
            ConnectionContracts.ResultPointer parsed = ConnectionContracts.parseResult(pointer);
            if (parsed == null) {
                throw new Unresolved(pointer);
            }
            if (parsed.async() != null) {
                JsonNode value = asyncValues == null ? null : asyncValues.get(parsed.async());
                if (value == null) {
                    if (optional) {
                        continue;
                    }
                    throw new Unresolved(pointer);
                }
                result.set(entry.getKey(), value);
                continue;
            }
            if (parsed.header() != null) {
                if (optional && (headers == null || headers.firstValue(parsed.header()).isEmpty())) {
                    continue;
                }
                result.set(entry.getKey(), header(operation, parsed.header(), headers, pointer));
                continue;
            }
            if (!parsed.projection()) {
                JsonNode value = body.at(pointer);
                if (optional && (value.isMissingNode() || value.isNull())) {
                    continue;
                }
                if (value.isMissingNode() || value.isNull() || value.isContainer()) {
                    throw new Unresolved(pointer);
                }
                result.set(entry.getKey(), value);
                continue;
            }
            JsonNode list = body.at(pointerOf(parsed.base()));
            if (!list.isArray()) {
                throw new Unresolved(pointer);
            }
            ConnectionContracts.SchemaAt listSchema = ConnectionContracts.schemaAt(responseSchema, parsed.base());
            JsonNode items = listSchema == null ? null : listSchema.schema().get("items");
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            boolean cut = false;
            for (JsonNode item : list) {
                if (out.size() >= ConnectionContracts.MAX_ROWS) {
                    cut = true;
                    break;
                }
                if (parsed.column().isEmpty()) {
                    ObjectNode row = JsonNodeFactory.instance.objectNode();
                    for (String column : ConnectionContracts.rowColumns(items).keySet()) {
                        JsonNode value = item.get(column);
                        if (value != null && !value.isNull() && !value.isContainer()) {
                            row.set(column, value);
                        }
                    }
                    out.add(row);
                } else {
                    JsonNode value = item.at(pointerOf(parsed.column()));
                    if (!value.isMissingNode() && !value.isNull() && !value.isContainer()) {
                        out.add(value);
                    }
                }
            }
            if (cut) {
                truncated.add(entry.getKey());
            }
            result.set(entry.getKey(), out);
        }
        return new Mapped(result, List.copyOf(truncated));
    }

    /** Appends the items of a later page's lists to the first page's result (pagination). */
    static void append(ObjectNode into, Mapped page, Map<String, String> mapping, List<String> truncated, int maxItems) {
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            ConnectionContracts.ResultPointer parsed = ConnectionContracts.parseResult(entry.getValue());
            if (parsed == null || !parsed.projection()) {
                continue;
            }
            ArrayNode target = (ArrayNode) into.get(entry.getKey());
            for (JsonNode item : page.result().get(entry.getKey())) {
                if (target.size() >= maxItems) {
                    if (!truncated.contains(entry.getKey())) {
                        truncated.add(entry.getKey());
                    }
                    break;
                }
                target.add(item);
            }
        }
    }

    /** @return the {@code rel="next"} target of a Link header, or null */
    static String nextLink(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        for (String value : headers.allValues("Link")) {
            Matcher m = NEXT_LINK.matcher(value);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    private static JsonNode header(Operation operation, String name, HttpHeaders headers, String pointer) {
        JsonNode declared = ConnectionContracts.responseHeader(operation.source(), name);
        String value = headers == null ? null : headers.firstValue(name).orElse(null);
        if (declared == null || value == null) {
            throw new Unresolved(pointer);
        }
        value = value.trim();
        try {
            return switch (declared.path("type").asString()) {
                case "integer" -> JsonNodeFactory.instance.numberNode(Long.parseLong(value));
                case "number" -> JsonNodeFactory.instance.numberNode(new BigDecimal(value));
                case "boolean" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        throw new NumberFormatException();
                    }
                    yield JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(value));
                }
                default -> JsonNodeFactory.instance.stringNode(value.length() > 4000 ? value.substring(0, 4000) : value);
            };
        } catch (NumberFormatException e) {
            throw new Unresolved(pointer);
        }
    }

    private static String pointerOf(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            sb.append('/').append(token.replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }
}
