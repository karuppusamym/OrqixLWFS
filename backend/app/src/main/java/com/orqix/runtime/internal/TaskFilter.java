package com.orqix.runtime.internal;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import com.orqix.shared.problem.ApiException;

/** Search only the fields already exposed in an eligible task summary. */
public record TaskFilter(String q, String queue, String due, Instant at) {
    public static TaskFilter of(String q, String queue, String due, Instant at) {
        q = q == null ? "" : q.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        queue = queue == null ? "" : queue.strip();
        due = due == null || due.isBlank() ? "all" : due;
        if (q.length() > 200 || queue.length() > 63 || !List.of("all", "overdue", "soon", "none").contains(due))
            throw ApiException.malformed("Search is limited to 200 characters; queue to 63; due must be all, overdue, soon or none.");
        return new TaskFilter(q, queue, due, at);
    }
    public List<String> terms() { return q.isEmpty() ? List.of() : List.of(q.split(" ")); }
    public String binding() { return q.length() + ":" + q + queue.length() + ":" + queue + ":" + due; }
}
