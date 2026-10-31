package com.orqix.migration.camunda;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, bounded readers for the Camunda 8 ({@code zeebe:}) extension values: input/output mappings, task headers, FEEL
 * context literals, durations and URLs. Nothing here evaluates an expression; values that are not plain literals are reported
 * as text, never executed. URLs are reduced to host and path (no credentials, no query values) before they enter a report.
 */
final class Zeebe {

    static final String HTTP_JSON = "io.camunda:http-json:";
    private static final Pattern PLAIN_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern VARIABLE_PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern SECRET = Pattern.compile("\\{\\{\\s*secrets\\.[^}]*}}");
    private static final Pattern DURATION = Pattern.compile("P(?:[0-9]{1,3}D)?(?:T(?:[0-9]{1,4}H)?(?:[0-9]{1,5}M)?)?");
    private static final Pattern FEEL_DURATION = Pattern.compile("(?:=\\s*)?duration\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern NOW_PLUS = Pattern.compile("=\\s*now\\(\\)\\s*\\+\\s*duration\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern ISO_STRING = Pattern.compile("=?\\s*\"([^\"]*)\"");

    private Zeebe() {
    }

    /** Inputs (or outputs) of a {@code zeebe:ioMapping}: target name to source text, in document order. */
    static Map<String, String> mappings(XElement ioMapping, String kind) {
        Map<String, String> found = new LinkedHashMap<>();
        if (ioMapping == null) {
            return found;
        }
        for (XElement e : ioMapping.children(Namespaces.ZEEBE, kind)) {
            String target = e.attr("target");
            if (target != null && !target.isBlank()) {
                found.put(target.strip(), e.attr("source") == null ? "" : e.attr("source").strip());
            }
        }
        return found;
    }

    /** Header key to value of a {@code zeebe:taskHeaders}. */
    static Map<String, String> headers(XElement taskHeaders) {
        Map<String, String> found = new LinkedHashMap<>();
        if (taskHeaders != null) {
            for (XElement e : taskHeaders.children(Namespaces.ZEEBE, "header")) {
                if (e.attr("key") != null) {
                    found.put(e.attr("key").strip(), e.attr("value") == null ? "" : e.attr("value").strip());
                }
            }
        }
        return found;
    }

    /** The text of a literal value ({@code POST}, {@code ="POST"}), or null when the value is an expression. */
    static String literal(String source) {
        if (source == null) {
            return null;
        }
        String value = source.strip();
        Matcher quoted = ISO_STRING.matcher(value);
        if (quoted.matches()) {
            return quoted.group(1);
        }
        return value.startsWith("=") ? null : value;
    }

    /** {@code =amount} or {@code amount} as the variable name, or null when the value is anything else. */
    static String variable(String source) {
        if (source == null) {
            return null;
        }
        String value = source.strip();
        if (value.startsWith("=")) {
            value = value.substring(1).strip();
        }
        return PLAIN_VARIABLE.matcher(value).matches() && !List.of("true", "false", "null").contains(value) ? value : null;
    }

    /** A dotted variable path such as {@code response.body.id}, or null. */
    static String path(String source) {
        if (source == null) {
            return null;
        }
        String value = source.strip();
        if (value.startsWith("=")) {
            value = value.substring(1).strip();
        }
        return VARIABLE_PATH.matcher(value).matches() ? value : null;
    }

    /**
     * Entries of a FEEL context literal such as {@code ={a: x, "b-c": "text"}}: key to value text, or null when the text is not
     * a context literal. Splitting honours quotes and brackets; values are returned as written.
     */
    static List<String[]> context(String source) {
        if (source == null) {
            return null;
        }
        String value = source.strip();
        if (!value.startsWith("=")) {
            return null;
        }
        value = value.substring(1).strip();
        if (value.length() < 2 || value.charAt(0) != '{' || value.charAt(value.length() - 1) != '}') {
            return null;
        }
        String inner = value.substring(1, value.length() - 1);
        List<String[]> entries = new ArrayList<>();
        for (String part : splitTopLevel(inner, ',')) {
            if (part.isBlank()) {
                continue;
            }
            List<String> pair = splitTopLevel(part, ':');
            if (pair.size() < 2) {
                return null;
            }
            String key = pair.get(0).strip();
            if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            entries.add(new String[] {key, String.join(":", pair.subList(1, pair.size())).strip()});
        }
        return entries;
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '{' || c == '[' || c == '(') {
                    depth++;
                } else if (c == '}' || c == ']' || c == ')') {
                    depth--;
                } else if (c == separator && depth == 0) {
                    parts.add(text.substring(start, i));
                    start = i + 1;
                    if (separator == ':') {
                        parts.add(text.substring(start));
                        return parts;
                    }
                }
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** Normalised ISO duration of a literal ({@code PT48H}), a FEEL string ({@code ="PT48H"}) or {@code duration("PT48H")}. */
    static String duration(String source) {
        if (source == null) {
            return null;
        }
        String value = source.strip();
        Matcher feel = FEEL_DURATION.matcher(value);
        if (feel.matches()) {
            value = feel.group(1);
        } else {
            Matcher quoted = ISO_STRING.matcher(value);
            if (quoted.matches()) {
                value = quoted.group(1);
            }
        }
        value = value.strip();
        return DURATION.matcher(value).matches() && !value.equals("P") && !value.equals("PT") ? value : null;
    }

    /** True when the ISO duration (days, hours, minutes) is within the canonical bounds PT1M to P90D. */
    static boolean inBounds(String iso) {
        try {
            Duration d = Duration.parse(iso);
            return d.compareTo(Duration.ofMinutes(1)) >= 0 && d.compareTo(Duration.ofDays(90)) <= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Due time in whole hours (1 to 720) of {@code dueDate}: a plain ISO duration, {@code duration("PT48H")} or
     * {@code now() + duration("PT48H")}; null for absolute dates and every other expression.
     */
    static Integer dueHours(String source) {
        if (source == null) {
            return null;
        }
        String value = source.strip();
        Matcher now = NOW_PLUS.matcher(value);
        String iso = now.matches() ? now.group(1) : duration(value);
        if (iso == null || !DURATION.matcher(iso).matches() || iso.equals("P") || iso.equals("PT")) {
            return null;
        }
        try {
            long minutes = Duration.parse(iso).toMinutes();
            return minutes >= 60 && minutes <= 720L * 60 && minutes % 60 == 0 ? (int) (minutes / 60) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when a mapped due time had a day component, which {@link #dueHours} normalises to a fixed number of hours. */
    static boolean dueNormalised(String source) {
        if (source == null) {
            return false;
        }
        Matcher now = NOW_PLUS.matcher(source.strip());
        String iso = now.matches() ? now.group(1) : duration(source);
        return iso != null && iso.matches("P[0-9]+D.*");
    }

    /** {@code source-procurement-worker} as the operation name {@code sourceProcurementWorker}; null when nothing usable remains. */
    static String operationName(String jobType) {
        if (jobType == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : jobType.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = out.length() > 0;
            }
        }
        if (out.length() == 0 || !Character.isLetter(out.charAt(0))) {
            return null;
        }
        out.setCharAt(0, Character.toLowerCase(out.charAt(0)));
        return out.length() > 64 ? out.substring(0, 64) : out.toString();
    }

    /** Replaces secret placeholders ({@code {{secrets.NAME}}}) so that a report never carries a secret reference value. */
    static String mask(String text) {
        String masked = SECRET.matcher(text == null ? "" : text).replaceAll("{{secret}}");
        return masked.length() > 200 ? masked.substring(0, 200) + "..." : masked;
    }

    /** Host and path of a literal URL for provenance: no credentials, no query values; secret placeholders are masked. */
    static String[] hostAndPath(String url) {
        String cleaned = SECRET.matcher(url).replaceAll("secret");
        try {
            URI uri = URI.create(cleaned.strip());
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (host == null && uri.getScheme() == null) {
                return new String[] {null, path == null || path.isEmpty() ? cleaned.strip() : path};
            }
            return new String[] {host, path == null || path.isEmpty() ? "/" : path};
        } catch (IllegalArgumentException e) {
            return new String[] {null, null};
        }
    }

    /** Query parameter names of a literal URL (never their values). */
    static List<String> queryNames(String url) {
        int mark = url.indexOf('?');
        List<String> names = new ArrayList<>();
        if (mark >= 0) {
            for (String pair : url.substring(mark + 1).split("&")) {
                String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                if (!name.isBlank() && names.size() < 20) {
                    names.add(name.strip());
                }
            }
        }
        return names;
    }

    /** JSON Pointer ({@code /id}, {@code /order/id}) of {@code response.body.order.id}, or null when it is not a body path. */
    static String responsePointer(String source) {
        String path = path(source);
        if (path == null || !path.startsWith("response.body.") || path.length() <= "response.body.".length()) {
            return null;
        }
        return "/" + path.substring("response.body.".length()).replace('.', '/');
    }
}
