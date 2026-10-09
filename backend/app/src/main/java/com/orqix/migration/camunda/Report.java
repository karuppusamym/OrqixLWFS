package com.orqix.migration.camunda;

import java.util.List;

/**
 * Compatibility report records (openapi.yaml {@code CompatibilityReport} / {@code CompatibilityIssue}). Plain records so
 * they serialize straight to the API shape; {@code sourcePath} is an additive member (doc 05 "sourcePath").
 */
public final class Report {

    public static final String SUPPORTED = "supported";
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

        public boolean blocksPublishing() {
            return (BLOCKER.equals(severity) || ACTION.equals(severity)) && OPEN.equals(resolutionStatus);
        }
    }

    public record ElementCounts(int total, int supported, int info, int action, int blocker) {

        public static ElementCounts of(List<Issue> issues) {
            int supported = 0;
            int info = 0;
            int action = 0;
            int blocker = 0;
            for (Issue issue : issues) {
                switch (issue.severity()) {
                    case SUPPORTED -> supported++;
                    case INFO -> info++;
                    case ACTION -> action++;
                    default -> blocker++;
                }
            }
            return new ElementCounts(issues.size(), supported, info, action, blocker);
        }
    }

    public record RequiredMappings(List<String> groups, List<String> approvalTasks, List<String> serviceTasks) {
    }

    public record CompatibilityReport(String sourceSystem, String sourceVersion, String adapterVersion, String sourceHash,
            String processId, String processName, ElementCounts elementCounts, List<Issue> issues,
            List<String> assumptions, RequiredMappings requiredMappings, boolean publishable) {
    }
}
