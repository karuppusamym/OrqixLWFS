package com.orqix.runtime.evidencepack;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Format-neutral layout of a control evidence pack: a title, then sections that are either label/value fields or a
 * table. The PDF and DOCX renderers draw exactly this, so both formats carry the same facts as the JSON pack.
 */
public record EvidenceDocument(String title, String subtitle, List<Section> sections, String integrity) {

    public record Field(String label, String value) {
    }

    /** A section has fields or a table (columns non-null). {@code note} is an optional explanation under the heading. */
    public record Section(String heading, String note, List<Field> fields, List<String> columns,
            List<List<String>> rows, String emptyText) {

        public boolean isTable() {
            return columns != null;
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern INSTANT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z$");

    /** Label/value section from an object; {@code pairs} alternates JSON path (dots allowed) and label. */
    public static Section fields(String heading, String note, JsonNode source, String... pairs) {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            JsonNode value = at(source, pairs[i]);
            if (!value.isMissingNode() && !value.isNull()) {
                fields.add(new Field(pairs[i + 1], text(value)));
            }
        }
        return new Section(heading, note, List.copyOf(fields), null, null, null);
    }

    /** Table section from an array of objects; {@code pairs} alternates JSON path and column header. */
    public static Section table(String heading, String note, JsonNode items, String emptyText, String... pairs) {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i < pairs.length; i += 2) {
            columns.add(pairs[i]);
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode item : items) {
            List<String> row = new ArrayList<>();
            for (int i = 0; i < pairs.length; i += 2) {
                row.add(text(at(item, pairs[i])));
            }
            rows.add(List.copyOf(row));
        }
        return new Section(heading, note, null, List.copyOf(columns), List.copyOf(rows), emptyText);
    }

    static JsonNode at(JsonNode node, String path) {
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            current = current.path(part);
        }
        return current;
    }

    /** Auditor-readable text: subjects by name, times in UTC, booleans as Yes/No, lists joined. */
    public static String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "-";
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "Yes" : "No";
        }
        if (value.isObject()) {
            if (value.has("displayName")) {
                return value.path("displayName").asString();
            }
            List<String> parts = new ArrayList<>();
            value.properties().forEach(e -> parts.add(e.getKey() + ": " + text(e.getValue())));
            return parts.isEmpty() ? "-" : String.join("; ", parts);
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(v -> parts.add(text(v)));
            return parts.isEmpty() ? "-" : String.join(", ", parts);
        }
        String text = value.isString() ? value.asString() : value.toString();
        if (INSTANT.matcher(text).matches()) {
            try {
                return TIME.format(Instant.parse(text));
            } catch (RuntimeException ignored) {
                return text;
            }
        }
        return text.isEmpty() ? "-" : text;
    }
}
