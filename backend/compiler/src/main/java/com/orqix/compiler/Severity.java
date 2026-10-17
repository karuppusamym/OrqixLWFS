package com.orqix.compiler;

import java.util.Locale;

/** API representation is the lower-case name: "blocker" or "warning". */
public enum Severity {
    BLOCKER,
    WARNING;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
