package com.orqix.shared.web;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Correlation ID of the current request or job (S1 section 9). Stored in the MDC as {@code correlationId}. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private CorrelationId() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value != null ? value : UUID.randomUUID().toString();
    }

    public static String acceptOrGenerate(String candidate) {
        if (candidate != null && VALID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
