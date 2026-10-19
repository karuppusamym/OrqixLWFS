package com.orqix.compiler;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-text placeholders of "Send through an adapter" steps (S73): {@code {{name}}} is replaced by the value mapped under
 * that name. No expressions, no nesting, and nothing else is interpolated. Shared by the compiler (validation) and the
 * runtime (rendering) so both read a text the same way.
 */
public final class AdapterMessages {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]{0,39})}}");

    private AdapterMessages() {
    }

    /** @return the placeholder names used in the text, sorted */
    public static Set<String> placeholders(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = TOKEN.matcher(text == null ? "" : text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** @return a business explanation when the text uses an unmapped name or a malformed placeholder, otherwise null */
    public static String problem(String text, Set<String> mapped) {
        if (text.contains("${")) {
            return "Expressions are not allowed; use {{name}} for a mapped value.";
        }
        String rest = TOKEN.matcher(text).replaceAll("");
        if (rest.contains("{{") || rest.contains("}}")) {
            return "A placeholder is not written as {{name}}.";
        }
        for (String name : placeholders(text)) {
            if (!mapped.contains(name)) {
                return "The placeholder {{" + name + "}} has no mapped value. Add " + name + " to the values this step uses.";
            }
        }
        return null;
    }

    /** Replaces each placeholder with its value (empty when absent; the compiler guarantees presence). */
    public static String render(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        return TOKEN.matcher(text).replaceAll(m -> Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
    }
}
