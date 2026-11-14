package com.orqix.shared.tracing;

import java.util.Map;
import java.util.regex.Pattern;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * The current OpenTelemetry trace (CG-P06, docs/build/S150). Micrometer Tracing keeps its current span in the
 * OpenTelemetry context, so these static reads see the span of the HTTP request, engine job or scheduler run that is
 * executing on this thread. Every method answers {@code null} when tracing is off or nothing is being traced.
 */
public final class TraceIds {

    /** Process variable that carries the W3C traceparent of the request that started a run into its engine jobs. */
    public static final String PROCESS_VARIABLE = "orqix_traceparent";

    private static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private TraceIds() {
    }

    /** @return the 32-character trace id of the current span, or null */
    public static String current() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }

    /** @return the W3C traceparent of the current span ({@code 00-trace-span-flags}), or null */
    public static String traceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
    }

    /** @return true when the value is a well-formed version-00 traceparent */
    public static boolean isTraceparent(String value) {
        return value != null && TRACEPARENT.matcher(value).matches();
    }

    /**
     * @return the given variables plus {@link #PROCESS_VARIABLE} when a span is current; the map itself when not (it is
     *         never modified)
     */
    public static Map<String, Object> withTraceparent(Map<String, Object> variables) {
        String parent = traceparent();
        if (parent == null) {
            return variables;
        }
        Map<String, Object> copy = variables == null ? new java.util.HashMap<>() : new java.util.HashMap<>(variables);
        copy.put(PROCESS_VARIABLE, parent);
        return copy;
    }
}
