package com.orqix.migration.camunda;

import java.util.List;

/**
 * Compatibility report records (openapi.yaml {@code CompatibilityReport} / {@code CompatibilityIssue}). Plain records so
 * they serialize straight to the API shape; {@code sourcePath} is an additive member (doc 05 "sourcePath").
 * <p>
 * Severities follow Camunda's Diagram Converter vocabulary (docs/17-migration-workbench.md "Severity model"):
 * <ul>
 *   <li>{@link #SUPPORTED} (converter INFO, nothing to do): exact structural mapping, no behaviour to verify.</li>
 *   <li>{@link #INFO} (converter WARNING that may be ignored): nothing executable is lost, but source content is not
 *       imported (documentation, layout, settings, listeners). Needs an explicit acknowledgement.</li>
 *   <li>{@link #REVIEW} (converter REVIEW): converted, but behaviour could differ. Never blocks conversion or
 *       {@code publishable}; open until the designer acknowledges it, and kept in the report as evidence.</li>
 *   <li>{@link #ACTION} (converter WARNING with a decision): no direct equivalent; the designer must choose a mapping.</li>
 *   <li>{@link #BLOCKER} (converter TASK): manual rebuild; cannot be converted.</li>
 * </ul>
 */
public final class Report {

    public static final String SUPPORTED = "supported";
    public static final String REVIEW = "review";
    public static final String INFO = "info";
    public static final String ACTION = "action";
    public static final String BLOCKER = "blocker";

    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";
    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String NOT_NEEDED = "notNeeded";

    private Report() {
    }

    /** One finding about one source element (or one construct nested in it). */
    public record Issue(String sourceElementId, String sourceName, String sourceKind, String severity, String reasonCode,
            String explanation, String suggestedAction, String targetNodeId, String resolutionStatus, String sourcePath) {

        public Issue withResolution(String status) {
            return new Issue(sourceElementId, sourceName, sourceKind, severity, reasonCode, explanation, suggestedAction,
                    targetNodeId, status, sourcePath);
        }

        public Issue withTarget(String nodeId) {
            return new Issue(sourceElementId, sourceName, sourceKind, severity, reasonCode, explanation, suggestedAction,
                    nodeId, resolutionStatus, sourcePath);
        }

        /** Only open blockers and actions stop a conversion; review and info items never do by themselves. */
        public boolean blocksPublishing() {
            return (BLOCKER.equals(severity) || ACTION.equals(severity)) && OPEN.equals(resolutionStatus);
        }
    }

    public record ElementCounts(int total, int supported, int info, int action, int blocker, int review) {

        public ElementCounts(int total,int supported,int info,int action,int blocker) { this(total,supported,info,action,blocker,0); }
        public static ElementCounts of(List<Issue> issues) {
            int review = 0;
            int supported = 0;
            int info = 0;
            int action = 0;
            int blocker = 0;
            for (Issue issue : issues) {
                switch (issue.severity()) {
                    case SUPPORTED -> supported++;
                    case REVIEW -> review++;
                    case INFO -> info++;
                    case ACTION -> action++;
                    default -> blocker++;
                }
            }
            return new ElementCounts(issues.size(), supported, info, action, blocker, review);
        }
    }

    public record RequiredMappings(List<String> groups, List<String> approvalTasks, List<String> serviceTasks,
            List<String> childWorkflows, java.util.Map<String,List<String>> serviceGroups, List<String> decisionTasks) {
        public RequiredMappings {
            serviceGroups = serviceGroups == null ? java.util.Map.of() : serviceGroups;
            decisionTasks = decisionTasks == null ? List.of() : decisionTasks;
        }
        public RequiredMappings(List<String> groups,List<String> approvalTasks,List<String> serviceTasks,List<String> childWorkflows,java.util.Map<String,List<String>> serviceGroups) { this(groups,approvalTasks,serviceTasks,childWorkflows,serviceGroups,List.of()); }
        public RequiredMappings(List<String> groups,List<String> approvalTasks,List<String> serviceTasks,List<String> childWorkflows) { this(groups,approvalTasks,serviceTasks,childWorkflows,java.util.Map.of(),List.of()); }
        public RequiredMappings(List<String> groups, List<String> approvalTasks, List<String> serviceTasks) {
            this(groups, approvalTasks, serviceTasks, List.of(),java.util.Map.of(),List.of());
        }
    }

    /**
     * A generated behaviour check (docs/05 "testCases"): the source semantics evaluated independently, then the converted
     * canonical table evaluated by the compiler. {@code status} is {@code passed} or {@code failed}.
     */
    public record TestCase(String decision, String name, java.util.Map<String, Object> input, java.util.Map<String, Object> expected,
            java.util.Map<String, Object> actual, Integer matchedRow, String status) {
    }

    public record CompatibilityReport(String sourceSystem, String sourceVersion, String adapterVersion, String sourceHash,
            String processId, String processName, ElementCounts elementCounts, List<Issue> issues,
            List<String> assumptions, RequiredMappings requiredMappings, boolean publishable, List<TestCase> testCases) {

        public CompatibilityReport {
            testCases = testCases == null ? List.of() : testCases;
        }

        public CompatibilityReport(String sourceSystem, String sourceVersion, String adapterVersion, String sourceHash,
                String processId, String processName, ElementCounts elementCounts, List<Issue> issues,
                List<String> assumptions, RequiredMappings requiredMappings, boolean publishable) {
            this(sourceSystem, sourceVersion, adapterVersion, sourceHash, processId, processName, elementCounts, issues,
                    assumptions, requiredMappings, publishable, List.of());
        }
    }
}
