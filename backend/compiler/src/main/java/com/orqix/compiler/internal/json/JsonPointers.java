package com.orqix.compiler.internal.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** RFC 6901 JSON Pointer helpers. */
public final class JsonPointers {

    private JsonPointers() {
    }

    /** @return the escaped reference token ({@code ~} as {@code ~0}, {@code /} as {@code ~1}) */
    public static String escape(String token) {
        if (token.indexOf('~') < 0 && token.indexOf('/') < 0) {
            return token;
        }
        return token.replace("~", "~0").replace("/", "~1");
    }

    /** @return pointer built from raw (unescaped) tokens; the empty list gives the root pointer "" */
    public static String of(Object... tokens) {
        StringBuilder sb = new StringBuilder();
        for (Object token : tokens) {
            sb.append('/').append(escape(String.valueOf(token)));
        }
        return sb.toString();
    }

    /** @return {@code base} with one more raw token appended */
    public static String append(String base, Object token) {
        return base + '/' + escape(String.valueOf(token));
    }

    /**
     * @return the unescaped reference tokens of a pointer
     * @throws IllegalArgumentException when the text is not a JSON Pointer
     */
    public static List<String> parse(String pointer) {
        if (pointer.isEmpty()) {
            return List.of();
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("A JSON Pointer must start with '/'");
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/", -1)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '~') {
                    char next = i + 1 < raw.length() ? raw.charAt(i + 1) : ' ';
                    if (next == '0') {
                        sb.append('~');
                    } else if (next == '1') {
                        sb.append('/');
                    } else {
                        throw new IllegalArgumentException("Invalid escape in JSON Pointer");
                    }
                    i++;
                } else {
                    sb.append(c);
                }
            }
            tokens.add(sb.toString());
        }
        return Collections.unmodifiableList(tokens);
    }

    /**
     * Orders pointers token by token; tokens that are both non-negative integers compare numerically, so
     * {@code /nodes/2} sorts before {@code /nodes/10}. A pointer sorts before its own children.
     */
    public static int compare(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        // Malformed pointers never come from the compiler itself; keep the order total by placing them last.
        List<String> ta = safeParse(a);
        List<String> tb = safeParse(b);
        if ((ta == null) != (tb == null)) {
            return ta == null ? 1 : -1;
        }
        if (ta == null) {
            return a.compareTo(b);
        }
        int n = Math.min(ta.size(), tb.size());
        for (int i = 0; i < n; i++) {
            int c = compareToken(ta.get(i), tb.get(i));
            if (c != 0) {
                return c;
            }
        }
        int c = Integer.compare(ta.size(), tb.size());
        return c != 0 ? c : a.compareTo(b);
    }

    private static List<String> safeParse(String pointer) {
        try {
            return parse(pointer);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int compareToken(String a, String b) {
        boolean na = isIndex(a);
        boolean nb = isIndex(b);
        if (na && nb) {
            int c = Integer.compare(a.length(), b.length());
            return c != 0 ? c : a.compareTo(b);
        }
        if (na != nb) {
            return na ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static boolean isIndex(String token) {
        if (token.isEmpty() || token.length() > 9) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return token.length() == 1 || token.charAt(0) != '0';
    }
}
