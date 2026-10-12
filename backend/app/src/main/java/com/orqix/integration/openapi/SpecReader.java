package com.orqix.integration.openapi;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads an OpenAPI document from JSON or YAML text without executing or fetching anything (S36 section 3).
 *
 * <p>Limits: 2 MiB of UTF-8, 64 levels of nesting, 400,000 values, one YAML document, no duplicate keys. YAML is
 * checked event by event before it is read: anchors, aliases (the usual "billion laughs" vector), explicit tags and
 * merge keys are refused, so the document the rest of the import sees is a plain tree. Jackson's YAML reader never
 * builds Java objects from tags.
 */
public final class SpecReader {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_NODES = 400_000;

    private static final StreamReadConstraints CONSTRAINTS = StreamReadConstraints.builder()
            .maxNestingDepth(MAX_DEPTH).maxStringLength(MAX_BYTES).maxNumberLength(50).maxNameLength(1000)
            .maxDocumentLength(MAX_BYTES + 16).build();

    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(CONSTRAINTS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder().streamReadConstraints(CONSTRAINTS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

    private SpecReader() {
    }

    /** A document that cannot be read; {@code tooLarge} maps to 413, everything else to 422. */
    public static final class SpecException extends RuntimeException {
        private final boolean tooLarge;

        public SpecException(String message, boolean tooLarge) {
            super(message, null, false, false);
            this.tooLarge = tooLarge;
        }

        public boolean tooLarge() {
            return tooLarge;
        }
    }

    /** @param format {@code json} or {@code yaml} as actually used */
    public record Parsed(JsonNode document, String format) {
    }

    /**
     * @param formatHint {@code json}, {@code yaml} or null (decided by file extension, then by the first character)
     */
    public static Parsed read(String content, String fileName, String formatHint) {
        if (content == null || content.isBlank()) {
            throw new SpecException("The file is empty.", false);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new SpecException("The file is larger than 2 MiB.", true);
        }
        String text = content.charAt(0) == '﻿' ? content.substring(1) : content;
        String format = formatHint != null ? formatHint.toLowerCase(Locale.ROOT) : formatOf(fileName, text);
        JsonNode document;
        try {
            if ("json".equals(format)) {
                document = JSON.readTree(text);
            } else if ("yaml".equals(format)) {
                scanYaml(text);
                document = YAML.readTree(text);
            } else {
                throw new SpecException("The format must be json or yaml.", false);
            }
        } catch (SpecException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SpecException("The file is not valid " + ("json".equals(format) ? "JSON" : "YAML") + ": "
                    + brief(e.getMessage()), false);
        }
        if (document == null || !document.isObject()) {
            throw new SpecException("The file does not contain an OpenAPI document (a top-level object is expected).",
                    false);
        }
        if (countNodes(document) > MAX_NODES) {
            throw new SpecException("The document has more than " + MAX_NODES + " values.", true);
        }
        return new Parsed(document, format);
    }

    private static String formatOf(String fileName, String text) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return "json";
        }
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return "yaml";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' ? "json" : "yaml";
            }
        }
        return "yaml";
    }

    /** Refuses anchors, aliases, tags, several documents, excessive depth and excessive size, before any object is built. */
    private static void scanYaml(String text) {
        int depth = 0;
        int nodes = 0;
        int documents = 0;
        try {
            Iterable<Event> events = new Parse(LoadSettings.builder().build()).parseReader(new StringReader(text));
            for (Event event : events) {
                switch (event.getEventId()) {
                    case DocumentStart -> {
                        if (++documents > 1) {
                            throw new SpecException("The file contains more than one YAML document.", false);
                        }
                    }
                    case Alias -> throw new SpecException(
                            "YAML anchors and aliases (&name, *name, <<) are not accepted. Expand them before importing.",
                            false);
                    case MappingStart, SequenceStart, Scalar -> {
                        if (++nodes > MAX_NODES) {
                            throw new SpecException("The document has more than " + MAX_NODES + " values.", true);
                        }
                        if (event instanceof NodeEvent node && node.getAnchor().isPresent()) {
                            throw new SpecException(
                                    "YAML anchors and aliases (&name, *name, <<) are not accepted. Expand them before importing.",
                                    false);
                        }
                        String tag = event instanceof ScalarEvent scalar ? scalar.getTag().orElse(null)
                                : event instanceof CollectionStartEvent start ? start.getTag().orElse(null) : null;
                        if (tag != null) {
                            throw new SpecException("Explicit YAML tags (" + brief(tag) + ") are not accepted.", false);
                        }
                        if (event.getEventId() != Event.ID.Scalar && ++depth > MAX_DEPTH) {
                            throw new SpecException("The document is nested deeper than " + MAX_DEPTH + " levels.", true);
                        }
                        if (event instanceof ScalarEvent scalar && "<<".equals(scalar.getValue())) {
                            throw new SpecException("YAML merge keys (<<) are not accepted. Expand them before importing.",
                                    false);
                        }
                    }
                    case MappingEnd, SequenceEnd -> depth--;
                    default -> {
                    }
                }
            }
        } catch (SpecException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SpecException("The file is not valid YAML: " + brief(e.getMessage()), false);
        }
    }

    private static int countNodes(JsonNode node) {
        int count = 1;
        for (JsonNode child : node) {
            count += countNodes(child);
            if (count > MAX_NODES) {
                return count;
            }
        }
        return count;
    }

    private static String brief(String message) {
        if (message == null) {
            return "unreadable content";
        }
        String line = message.lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }
}
