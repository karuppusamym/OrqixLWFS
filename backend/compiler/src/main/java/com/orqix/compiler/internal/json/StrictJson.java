package com.orqix.compiler.internal.json;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Strict JSON reader shared by the compiler, the form validator, the decision evaluator and {@code CanonicalJson}.
 * <ul>
 * <li>Every non-integral number becomes a {@link BigDecimal} exactly as written (no trailing-zero stripping).</li>
 * <li>Duplicate member names are rejected with the JSON Pointer of the duplicate.</li>
 * <li>Numbers that are not finite IEEE 754 doubles are rejected (RFC 8785 / I-JSON), so hashing never fails later.</li>
 * <li>Nesting deeper than a limit is rejected; the root container counts as depth 1.</li>
 * </ul>
 */
public final class StrictJson {

    /** Stage 1 limit for workflow definitions (S4 section 1). */
    public static final int MAX_DEFINITION_BYTES = 256 * 1024;
    /** Stage 1 limit for workflow definitions (S4 section 1). */
    public static final int MAX_DEFINITION_DEPTH = 32;
    /** Limit for other documents (well below the Jackson default, keeps recursion shallow). */
    public static final int MAX_GENERAL_DEPTH = 256;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private StrictJson() {
    }

    /** @return the shared mapper (thread-safe); only used for writing and for creating parsers */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** Parses with {@link #MAX_GENERAL_DEPTH}. */
    public static JsonNode parse(String json) {
        return parse(json, MAX_GENERAL_DEPTH);
    }

    /**
     * @throws JsonProblem when the text is not acceptable
     */
    public static JsonNode parse(String json, int maxDepth) {
        if (json == null) {
            throw new JsonProblem(JsonProblem.Kind.SYNTAX, "", -1, -1, "No JSON text was supplied");
        }
        Deque<String> path = new ArrayDeque<>();
        try (JsonParser parser = MAPPER.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new JsonProblem(JsonProblem.Kind.SYNTAX, "", 1, 1, "The JSON text is empty");
            }
            JsonNode root = read(parser, path, 0, maxDepth);
            if (parser.nextToken() != null) {
                TokenStreamLocation loc = parser.currentLocation();
                throw new JsonProblem(JsonProblem.Kind.SYNTAX, "", loc.getLineNr(), loc.getColumnNr(),
                        "Unexpected content after the end of the JSON value");
            }
            return root;
        } catch (JsonProblem e) {
            throw e;
        } catch (JacksonException e) {
            TokenStreamLocation loc = e.getLocation();
            int line = loc == null ? -1 : loc.getLineNr();
            int column = loc == null ? -1 : loc.getColumnNr();
            throw new JsonProblem(JsonProblem.Kind.SYNTAX, pointer(path), line, column, e.getOriginalMessage());
        }
    }

    /** @return compact JSON text of a node (member order as stored) */
    public static String write(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    private static JsonNode read(JsonParser p, Deque<String> path, int depth, int maxDepth) {
        JsonToken token = p.currentToken();
        switch (token) {
            case START_OBJECT -> {
                checkDepth(p, path, depth + 1, maxDepth);
                ObjectNode object = NODES.objectNode();
                while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = p.currentName();
                    if (object.has(name)) {
                        TokenStreamLocation loc = p.currentLocation();
                        path.addLast(name);
                        throw new JsonProblem(JsonProblem.Kind.DUPLICATE_KEY, pointer(path), loc.getLineNr(),
                                loc.getColumnNr(), "Duplicate property \"" + name + "\"");
                    }
                    p.nextToken();
                    path.addLast(name);
                    object.set(name, read(p, path, depth + 1, maxDepth));
                    path.removeLast();
                }
                return object;
            }
            case START_ARRAY -> {
                checkDepth(p, path, depth + 1, maxDepth);
                ArrayNode array = NODES.arrayNode();
                int index = 0;
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    path.addLast(Integer.toString(index++));
                    array.add(read(p, path, depth + 1, maxDepth));
                    path.removeLast();
                }
                return array;
            }
            case VALUE_STRING -> {
                return StringNode.valueOf(p.getString());
            }
            case VALUE_NUMBER_INT -> {
                return switch (p.getNumberType()) {
                    case INT -> IntNode.valueOf(p.getIntValue());
                    case LONG -> LongNode.valueOf(p.getLongValue());
                    default -> {
                        var value = p.getBigIntegerValue();
                        checkFinite(p, path, value.toString());
                        yield BigIntegerNode.valueOf(value);
                    }
                };
            }
            case VALUE_NUMBER_FLOAT -> {
                BigDecimal value = p.getDecimalValue();
                checkFinite(p, path, value.toString());
                return DecimalNode.valueOf(value);
            }
            case VALUE_TRUE -> {
                return BooleanNode.TRUE;
            }
            case VALUE_FALSE -> {
                return BooleanNode.FALSE;
            }
            case VALUE_NULL -> {
                return NullNode.getInstance();
            }
            default -> {
                TokenStreamLocation loc = p.currentLocation();
                throw new JsonProblem(JsonProblem.Kind.SYNTAX, pointer(path), loc.getLineNr(), loc.getColumnNr(),
                        "Unexpected token " + token);
            }
        }
    }

    private static void checkDepth(JsonParser p, Deque<String> path, int depth, int maxDepth) {
        if (depth > maxDepth) {
            TokenStreamLocation loc = p.currentLocation();
            throw new JsonProblem(JsonProblem.Kind.TOO_DEEP, pointer(path), loc.getLineNr(), loc.getColumnNr(),
                    "Nesting deeper than " + maxDepth + " levels");
        }
    }

    private static void checkFinite(JsonParser p, Deque<String> path, String numberText) {
        if (!Double.isFinite(Double.parseDouble(numberText))) {
            TokenStreamLocation loc = p.currentLocation();
            throw new JsonProblem(JsonProblem.Kind.NUMBER_OUT_OF_RANGE, pointer(path), loc.getLineNr(),
                    loc.getColumnNr(), "Number out of range");
        }
    }

    private static String pointer(Deque<String> path) {
        StringBuilder sb = new StringBuilder();
        for (String token : path) {
            sb.append('/').append(JsonPointers.escape(token));
        }
        return sb.toString();
    }
}
