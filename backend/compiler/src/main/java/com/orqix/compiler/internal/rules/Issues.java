package com.orqix.compiler.internal.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.compiler.internal.json.JsonPointers;

/** Collects issues; {@link #sorted()} returns them de-duplicated in the order required by S4 section 1. */
public final class Issues {

    /**
     * Blockers first, then by path (token-wise, array indexes numerically), then by code; message and related IDs
     * break remaining ties so the order is total and independent of discovery order.
     */
    public static final Comparator<ValidationIssue> ORDER = Comparator
            .comparing((ValidationIssue i) -> i.severity() == Severity.BLOCKER ? 0 : 1)
            .thenComparing(ValidationIssue::path, JsonPointers::compare)
            .thenComparing(ValidationIssue::code)
            .thenComparing(ValidationIssue::message)
            .thenComparing(i -> Objects.toString(i.nodeId(), ""))
            .thenComparing(i -> Objects.toString(i.transitionId(), ""));

    private final List<ValidationIssue> issues = new ArrayList<>();

    public void blocker(String code, String path, String nodeId, String transitionId, String message) {
        issues.add(new ValidationIssue(code, Severity.BLOCKER, message, path, nodeId, transitionId));
    }

    public void warning(String code, String path, String nodeId, String transitionId, String message) {
        issues.add(new ValidationIssue(code, Severity.WARNING, message, path, nodeId, transitionId));
    }

    public void add(ValidationIssue issue) {
        issues.add(issue);
    }

    public boolean hasBlockers() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.BLOCKER);
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    /** @return de-duplicated issues in canonical order */
    public List<ValidationIssue> sorted() {
        List<ValidationIssue> list = new ArrayList<>(new LinkedHashSet<>(issues));
        list.sort(ORDER);
        return List.copyOf(list);
    }
}
