package com.orqix.integration;

/**
 * Internal marker for result mappings that may be missing in an answer (docs/build/S129): a "Keep checking until..."
 * step maps fields that often appear only once the work is done, such as a ticket number. The scheduler passes such
 * a pointer as {@code "?" + pointer}; a missing value is then left out of the result instead of making the answer
 * unusable. Authored pointers can never start with {@code ?} (schema pattern), so the marker cannot be confused.
 */
public final class ResultMappings {

    private static final String OPTIONAL = "?";

    private ResultMappings() {
    }

    public static String optional(String pointer) {
        return OPTIONAL + pointer;
    }

    public static boolean isOptional(String pointer) {
        return pointer.startsWith(OPTIONAL);
    }

    public static String pointer(String mapping) {
        return isOptional(mapping) ? mapping.substring(OPTIONAL.length()) : mapping;
    }
}
