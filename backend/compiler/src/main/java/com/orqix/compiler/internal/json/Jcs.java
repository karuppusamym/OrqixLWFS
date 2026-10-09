package com.orqix.compiler.internal.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.erdtman.jcs.NumberToJSON;

import tools.jackson.databind.JsonNode;

/**
 * RFC 8785 JSON Canonicalization Scheme over a strictly parsed tree.
 * <p>
 * Member names are sorted by UTF-16 code units ({@link String#compareTo}), strings use the minimal JCS escaping, and
 * numbers are converted to the nearest IEEE 754 double and printed with the ECMAScript algorithm
 * ({@code org.erdtman.jcs.NumberToJSON}, verified against the RFC 8785 Appendix B vectors). The text parser of the
 * erdtman library is deliberately not used: it rejects the RFC's own sample string and accepts leading zeros.
 */
public final class Jcs {

    private static final HexFormat HEX = HexFormat.of();

    private Jcs() {
    }

    /** @return the JCS form of the node */
    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(sb, node);
        return sb.toString();
    }

    /** @return lower-case hex SHA-256 of the UTF-8 bytes of the JCS form */
    public static String sha256Hex(JsonNode node) {
        return sha256Hex(canonicalize(node));
    }

    /** @return lower-case hex SHA-256 of the UTF-8 bytes of the text */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** @return lower-case hex SHA-256 of the bytes */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** @return the ECMAScript (ES2015+) string form of the double nearest to the given decimal text */
    public static String number(String decimalText) {
        double value = Double.parseDouble(decimalText);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Number cannot be represented in canonical JSON: " + decimalText);
        }
        try {
            return NumberToJSON.serializeNumber(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Number cannot be represented in canonical JSON: " + decimalText, e);
        }
    }

    private static void write(StringBuilder sb, JsonNode node) {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : entries) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, element);
            }
            sb.append(']');
        } else if (node.isString()) {
            string(sb, node.stringValue());
        } else if (node.isNumber()) {
            sb.append(number(node.numberValue().toString()));
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNull()) {
            sb.append("null");
        } else {
            throw new IllegalArgumentException("Unsupported JSON node type " + node.getNodeType());
        }
    }

    /** @return the string as a JSON string literal with JCS escaping */
    public static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        string(sb, value);
        return sb.toString();
    }

    private static void string(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00").append(HEX.toHexDigits((byte) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
