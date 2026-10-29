package com.orqix.migration.pega.rules;

import java.util.*;

/**
 * One decoded clipboard page (docs/build/S112 "Page grammar"): properties hold text, a PageList of embedded pages or a
 * PropertyList of texts. Every value keeps the path it was read from, so findings point back to the exact source.
 */
public record Page(String path, Map<String, Value> fields) {
    public sealed interface Value permits Text, Pages, Texts { String path(); }
    public record Text(String path, String text) implements Value { }
    public record Pages(String path, List<Page> rows) implements Value { }
    public record Texts(String path, List<String> rows) implements Value { }

    /** Text of a property, or null when absent. A list where text is expected is a grammar error. */
    public String text(String name) {
        Value v = fields.get(name);
        if (v == null) return null;
        if (v instanceof Text t) return t.text();
        throw new Grammar(v.path(), "expected a single value");
    }

    public List<Page> pages(String name) {
        Value v = fields.get(name);
        if (v == null) return List.of();
        if (v instanceof Pages p) return p.rows();
        throw new Grammar(v.path(), "expected a PageList");
    }

    public List<String> texts(String name) {
        Value v = fields.get(name);
        if (v == null) return List.of();
        if (v instanceof Texts t) return t.rows();
        throw new Grammar(v.path(), "expected a PropertyList");
    }

    public String pathOf(String name) {
        Value v = fields.get(name);
        return v == null ? path + "/" + name : v.path();
    }

    /** Refuses properties outside the allowed set: an unknown property may carry behavior that would be lost. */
    public void allow(Set<String> allowed) {
        for (String name : fields.keySet()) if (!allowed.contains(name)) throw new Grammar(pathOf(name), "unsupported property " + name);
    }

    /** A source construct outside the supported grammar, with the path of the value that caused it. */
    public static final class Grammar extends RuntimeException {
        private final String sourcePath;
        public Grammar(String sourcePath, String message) { super(message); this.sourcePath = sourcePath; }
        public String sourcePath() { return sourcePath; }
    }
}
