package com.orqix.compiler;

import java.util.List;

/**
 * Deterministically ordered issues: blockers first, then by path, then by code (S4 section 1).
 *
 * @param semanticHash SHA-256 (JCS) of the definition without uiLayout; null when the document could not be parsed
 */
public record ValidationReport(String compilerVersion, List<ValidationIssue> issues, String semanticHash) {

    public ValidationReport {
        issues = List.copyOf(issues);
    }

    public boolean publishable() {
        return issues.stream().noneMatch(i -> i.severity() == Severity.BLOCKER);
    }

    public List<ValidationIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
    }
}
