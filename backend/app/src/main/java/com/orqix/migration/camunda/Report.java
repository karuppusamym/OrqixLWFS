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

    /**
     * What the designer needs to resolve a Camunda 8 service task. Facts only: the connector kind, a suggested operation
     * name, the original method and URL path as read-only provenance, header and query parameter names (never values),
     * and the request and result field names that can be derived. Secrets and full URLs are never copied.
     *
     * @param kind           {@code jobWorker}, {@code httpJson} or {@code unsupportedConnector}
     * @param method         HTTP method of an http-json connector, or null
     * @param urlHost        host of a literal URL (no credentials), or null when absent or computed
     * @param urlPath        path of a literal URL (no query), or null when absent or computed
     * @param urlComputed    true when the URL is an expression instead of a literal
     * @param authentication authentication type name only ({@code noAuth}, {@code bearer}, ...), or null
     * @param inputFields    request body name to the request field key read from a plain variable
     * @param resultFields   result name to a JSON Pointer into the response body derived from {@code resultExpression}
     * @param unmapped       source expressions that could not be derived (shown as text, never evaluated)
     * @param replacement    suggested replacement for an unsupported connector, or null
     */
    public record ServiceHint(String kind, String jobType, String suggestedOperation, String method, String urlHost,
            String urlPath, boolean urlComputed, String authentication, List<String> headerNames, List<String> queryNames,
            String resultVariable, java.util.Map<String, String> inputFields, java.util.Map<String, String> resultFields,
            List<String> unmapped, String replacement) {
        public ServiceHint {
            headerNames = headerNames == null ? List.of() : headerNames;
            queryNames = queryNames == null ? List.of() : queryNames;
            inputFields = inputFields == null ? java.util.Map.of() : inputFields;
            resultFields = resultFields == null ? java.util.Map.of() : resultFields;
            unmapped = unmapped == null ? List.of() : unmapped;
        }
    }

    /**
     * A form linked to a start event or user task. {@code state}: {@code embedded} (parsed from the BPMN),
     * {@code upload} (a {@code .form} file with this id is still needed) or {@code unsupported} (a custom form key).
     */
    public record FormHint(String target, String formId, String formKey, String binding, String state) {
    }

    /** Call activity facts: the called process, variable propagation and the mapping suggestions read from its ioMapping. */
    public record CallHint(String processId, String binding, boolean propagateAllChildVariables,
            boolean propagateAllParentVariables, java.util.Map<String, String> inputMapping,
            java.util.Map<String, String> resultMapping) {
        public CallHint {
            inputMapping = inputMapping == null ? java.util.Map.of() : inputMapping;
            resultMapping = resultMapping == null ? java.util.Map.of() : resultMapping;
        }
    }

    /** One component of an imported form, for preview. {@code widget} is the request form widget it becomes, or null. */
    public record FormField(String id, String key, String label, String component, String type, String widget, boolean required,
            List<String> options, String description, String disposition, String reasonCode) {
        public FormField {
            options = options == null ? List.of() : options;
        }
    }

    /** Preview of a Camunda form: the fields that become request fields and the components that do not. */
    public record FormPreview(String formId, List<FormField> fields, List<FormField> skipped) {
        public FormPreview {
            fields = fields == null ? List.of() : fields;
            skipped = skipped == null ? List.of() : skipped;
        }
    }

    /**
     * Structured hints that complement the issues (additive, openapi {@code MappingHints}). Keys are source element ids;
     * {@code formPreviews} holds the preview of every form parsed from this source (embedded forms by element id, or the
     * form file's own preview under its form id).
     */
    public record MappingHints(java.util.Map<String, ServiceHint> services, java.util.Map<String, FormHint> forms,
            java.util.Map<String, CallHint> calls, java.util.Map<String, FormPreview> formPreviews) {
        public static final MappingHints NONE = new MappingHints(null, null, null, null);

        public MappingHints {
            services = services == null ? java.util.Map.of() : services;
            forms = forms == null ? java.util.Map.of() : forms;
            calls = calls == null ? java.util.Map.of() : calls;
            formPreviews = formPreviews == null ? java.util.Map.of() : formPreviews;
        }
    }

    public record CompatibilityReport(String sourceSystem, String sourceVersion, String adapterVersion, String sourceHash,
            String processId, String processName, ElementCounts elementCounts, List<Issue> issues,
            List<String> assumptions, RequiredMappings requiredMappings, boolean publishable, List<TestCase> testCases,
            MappingHints mappingHints) {

        public CompatibilityReport {
            testCases = testCases == null ? List.of() : testCases;
            mappingHints = mappingHints == null ? MappingHints.NONE : mappingHints;
        }

        public CompatibilityReport(String sourceSystem, String sourceVersion, String adapterVersion, String sourceHash,
                String processId, String processName, ElementCounts elementCounts, List<Issue> issues,
                List<String> assumptions, RequiredMappings requiredMappings, boolean publishable, List<TestCase> testCases) {
            this(sourceSystem, sourceVersion, adapterVersion, sourceHash, processId, processName, elementCounts, issues,
                    assumptions, requiredMappings, publishable, testCases, MappingHints.NONE);
        }

        public CompatibilityReport(String sourceSystem, String sourceVersion, String adapterVersion, String sourceHash,
                String processId, String processName, ElementCounts elementCounts, List<Issue> issues,
                List<String> assumptions, RequiredMappings requiredMappings, boolean publishable) {
            this(sourceSystem, sourceVersion, adapterVersion, sourceHash, processId, processName, elementCounts, issues,
                    assumptions, requiredMappings, publishable, List.of(), MappingHints.NONE);
        }
    }
}
