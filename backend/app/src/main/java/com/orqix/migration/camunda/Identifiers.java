package com.orqix.migration.camunda;

import java.util.Locale;
import java.util.Set;

/** Deterministic, bounded identifiers matching the canonical workflow schema. */
final class Identifiers {
    private Identifiers() {
    }

    static String sanitize(String source, boolean workflowKey) {
        String value = source == null ? "" : source;
        if (workflowKey) {
            value = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
        } else {
            value = value.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("_{2,}", "_");
        }
        value = value.replaceAll("^-+|-+$", "");
        if (value.isEmpty()) {
            value = "node";
        }
        if (!((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))) {
            value = "node-" + value;
        }
        value = value.substring(0, Math.min(64, value.length()));
        if (workflowKey) {
            value = value.replaceAll("-+$", "");
            if (value.length() == 1) {
                value += "0";
            }
        }
        return value;
    }

    static String unique(String base, Set<String> used) {
        String candidate = base;
        int index = 2;
        while (!used.add(candidate)) {
            String suffix = "-" + index++;
            candidate = base.substring(0, Math.min(base.length(), 64 - suffix.length())) + suffix;
        }
        return candidate;
    }
}
