package com.orqix.migration.dmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.migration.camunda.Report;

/**
 * Result of reading a DMN file: a disposition for every decision, table, column and rule (cells are reported with their
 * rule), the tables that can be converted, and the generated boundary cases that were run against them.
 */
public final class DmnAnalysis {

    /** Report item plus the decision it belongs to (null for file-level items). */
    record Item(Report.Issue issue, String decisionId) {
    }

    private final String sourceVersion;
    private final String sourceHash;
    private final String definitionsId;
    private final String definitionsName;
    private final List<Item> items = new ArrayList<>();
    private final List<String> assumptions = new ArrayList<>();
    private final Map<String, String> decisions = new LinkedHashMap<>();
    private final Map<String, DmnTable> tables = new LinkedHashMap<>();
    private final List<Report.TestCase> testCases = new ArrayList<>();

    DmnAnalysis(String sourceVersion, String sourceHash, String definitionsId, String definitionsName) {
        this.sourceVersion = sourceVersion;
        this.sourceHash = sourceHash;
        this.definitionsId = definitionsId;
        this.definitionsName = definitionsName;
    }

    List<Item> items() {
        return items;
    }

    List<String> assumptions() {
        return assumptions;
    }

    Map<String, String> decisions() {
        return decisions;
    }

    Map<String, DmnTable> tables() {
        return tables;
    }

    List<Report.TestCase> testCases() {
        return testCases;
    }

    /** Ids of every decision in the file, with their names. */
    public Map<String, String> decisionNames() {
        return Map.copyOf(decisions);
    }

    /** The convertible table of a decision, or null when the decision has none or cannot be converted. */
    public DmnTable table(String decisionId) {
        return tables.get(decisionId);
    }

    public String sourceHash() {
        return sourceHash;
    }

    /** Blocking items of one decision (and the file-level ones), for error messages. */
    public List<Report.Issue> blockersOf(String decisionId) {
        return items.stream().filter(i -> (i.decisionId() == null || i.decisionId().equals(decisionId))
                && Report.BLOCKER.equals(i.issue().severity())).map(Item::issue).toList();
    }

    /** The compatibility report of the analysis alone: nothing selected, nothing acknowledged. */
    public Report.CompatibilityReport report() {
        return report(null, false, false);
    }

    /**
     * @param selected       decisions being converted; items of other decisions are shown as before but marked not needed.
     *                       Null means every decision.
     * @param acknowledgeInfo   the designer accepts the info items (documentation, layout, unused columns)
     * @param acknowledgeReview the designer acknowledges the review items
     */
    public Report.CompatibilityReport report(Set<String> selected, boolean acknowledgeInfo, boolean acknowledgeReview) {
        List<Report.Issue> issues = new ArrayList<>();
        for (Item item : items) {
            Report.Issue issue = item.issue();
            boolean inScope = selected == null || item.decisionId() == null || selected.contains(item.decisionId());
            if (!inScope) {
                issue = issue.withResolution(Report.NOT_NEEDED);
            } else if (Report.INFO.equals(issue.severity()) && acknowledgeInfo) {
                issue = issue.withResolution(Report.ACKNOWLEDGED);
            } else if (Report.REVIEW.equals(issue.severity()) && acknowledgeReview) {
                issue = issue.withResolution(Report.ACKNOWLEDGED);
            }
            issues.add(issue);
        }
        boolean publishable = issues.stream().noneMatch(Report.Issue::blocksPublishing)
                && issues.stream().noneMatch(i -> Report.INFO.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus()));
        List<Report.TestCase> cases = selected == null ? testCases : testCases.stream()
                .filter(t -> selected.contains(t.decision())).toList();
        return new Report.CompatibilityReport("dmn", sourceVersion, DmnAnalyzer.ADAPTER_VERSION, sourceHash, definitionsId,
                definitionsName, Report.ElementCounts.of(issues), issues, List.copyOf(assumptions),
                new Report.RequiredMappings(List.of(), List.of(), List.of()), publishable, List.copyOf(cases));
    }
}
