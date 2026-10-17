package com.orqix.migration.camunda;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of reading a BPMN file: the source facts, the inventory (one {@link Item} per element or nested construct
 * with its disposition) and the plan the {@link Converter} needs (nodes, flows, recognised patterns). Created by
 * {@link Analyzer}; never mutated afterwards except for item targets while it is being built.
 */
public final class Analysis {

    /** What a designer has to provide before an item counts as resolved. */
    public enum Need {
        NONE, TASK, OUTCOME_GATEWAY, SERVICE, SERVICE_GROUP, CHILD, DECISION, FORM, ACKNOWLEDGE
    }

    /** One inventory entry. */
    public static final class Item {
        Report.Issue issue;
        final Need need;
        /** User task or service task id the item depends on (null for {@link Need#NONE} and {@link Need#ACKNOWLEDGE}). */
        final String subject;
        final int ordinal;
        final int sequence;
        /**
         * Shown instead of {@link #issue} once the designer's mapping resolved it: a mapped service call, child call or
         * decision converts, but its behaviour (retries, timeouts, results) can differ, so it stays a REVIEW item.
         */
        Report.Issue reviewAfterResolution;

        Item(Report.Issue issue, Need need, String subject, int ordinal, int sequence) {
            this.issue = issue;
            this.need = need;
            this.subject = subject;
            this.ordinal = ordinal;
            this.sequence = sequence;
        }

        public Report.Issue issue() {
            return issue;
        }

        public Need need() {
            return need;
        }

        public String subject() {
            return subject;
        }

        void target(String nodeId) {
            issue = issue.withTarget(nodeId);
            if (reviewAfterResolution != null) {
                reviewAfterResolution = reviewAfterResolution.withTarget(nodeId);
            }
        }

        public Report.Issue reviewAfterResolution() {
            return reviewAfterResolution;
        }
    }

    /** A top-level flow node of the selected process. */
    public static final class Node {
        final String id;
        final String name;
        final String type;
        final XElement element;
        final List<Flow> in = new ArrayList<>();
        final List<Flow> out = new ArrayList<>();
        /** Canonical node id when the node becomes a workflow node; null for removed gateways and blocked nodes. */
        String canonicalId;

        Node(String id, String name, String type, XElement element) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.element = element;
        }

        public String id() {
            return id;
        }

        public String type() {
            return type;
        }
    }

    /** A sequence flow between two top-level flow nodes. */
    public static final class Flow {
        final String id;
        final String name;
        final XElement element;
        final Node source;
        final Node target;
        /** Parsed condition (only for flows leaving an exclusive gateway); null when the flow has none. */
        Expressions.Parsed condition;
        boolean hasConditionElement;
        /** Canonical transition id when the flow becomes a transition. */
        String transitionId;

        Flow(String id, String name, XElement element, Node source, Node target) {
            this.id = id;
            this.name = name;
            this.element = element;
            this.source = source;
            this.target = target;
        }

        public String id() {
            return id;
        }
    }

    /** Where an approval's two outcomes come from. */
    public record Route(String approvedFlowId, String rejectedFlowId, String gatewayId, String variable, boolean inverted) {
    }

    /** Assignment of a user task: the Camunda values that need a workspace group. */
    public static final class UserTask {
        final Node node;
        /** groups | assignee | none | expression */
        final String assignmentKind;
        final List<String> mappingKeys;
        /** Recognised pattern (exclusive gateway with approved/rejected conditions), or null. */
        Route recognized;
        /** Gateway that follows the task and would carry its outcomes (null when the task has direct flows only). */
        Node outcomeGateway;
        /** Flows the designer may choose the approved and rejected route from. */
        final List<Flow> candidateFlows = new ArrayList<>();
        /** True when two outcomes can never be found (the task stays blocked). */
        boolean outcomesImpossible;
        /** Display-only due time in whole hours (Camunda 8 {@code zeebe:taskSchedule dueDate}); null when none. */
        Integer dueAfterHours;
        /** ISO duration of an interrupting boundary timer on this task (becomes the approval time limit); null when none. */
        String timeoutAfter;
        /** The flow that leaves the boundary timer (becomes the {@code timedOut} route). */
        Flow timeoutFlow;

        UserTask(Node node, String assignmentKind, List<String> mappingKeys) {
            this.node = node;
            this.assignmentKind = assignmentKind;
            this.mappingKeys = List.copyOf(mappingKeys);
        }

        public String id() {
            return node.id;
        }

        public List<String> mappingKeys() {
            return mappingKeys;
        }

        public boolean routeRecognised() {
            return recognized != null;
        }
    }

    /**
     * One outgoing path of a branch gateway: a single comparison ({@code group} null), a flat {@code all}/{@code any}
     * group, or the default path ({@code terms} empty). {@code sourceText} keeps the original condition.
     */
    public record BranchEntry(String flowId, String group, List<Expressions.Comparison> terms, boolean isDefault,
            String sourceText) {

        public BranchEntry {
            terms = List.copyOf(terms);
        }

        public boolean compound() {
            return group != null;
        }

        /** The one comparison of a scalar entry. */
        public Expressions.Comparison scalar() {
            return terms.get(0);
        }
    }

    /** An exclusive gateway that becomes a branch. */
    public static final class Branch {
        final Node gateway;
        /** Variable to value type (number | string | boolean), in order of first use. */
        final Map<String, String> variables;
        final List<BranchEntry> entries;
        /** Flow that became the default only because the other condition complements it; null otherwise. */
        String derivedDefaultFlowId;

        Branch(Node gateway, Map<String, String> variables, List<BranchEntry> entries) {
            this.gateway = gateway;
            this.variables = new LinkedHashMap<>(variables);
            this.entries = List.copyOf(entries);
        }

        /** First variable; a scalar branch has exactly one. */
        public String variable() {
            return variables.keySet().iterator().next();
        }

        public String valueType() {
            return variables.values().iterator().next();
        }

        public Map<String, String> variables() {
            return variables;
        }
    }

    /** A service task that needs a designer mapping. */
    public record Service(Node node, String reasonCode, String detail) {
    }

    /**
     * A call activity with multiInstanceLoopCharacteristics that can become a bounded for-each: the collection
     * variable, the element variable and whether the instances run one after another.
     */
    public record MultiInstance(String collectionVariable, String fieldKey, String elementVariable, boolean sequential) {
    }

    /** A business rule task that calls a DMN decision by a literal id; {@code resultVariable} and mapping may be null. */
    public record DecisionRef(String decisionId, String resultVariable, String resultMapping) {
    }

    /** A message catch event that becomes a waitForMessage step. */
    public record MessageCatch(String message, String correlationVariable) {
    }

    final String sourceSystem;
    final String sourceVersion;
    final String sourceHash;
    final String processId;
    final String processName;
    final List<Item> items = new ArrayList<>();
    final List<String> assumptions = new ArrayList<>();
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final Map<String, Flow> flows = new LinkedHashMap<>();
    final Map<String, UserTask> userTasks = new LinkedHashMap<>();
    final Map<String, Branch> branches = new LinkedHashMap<>();
    final Map<String, Service> services = new LinkedHashMap<>();
    final Map<String,List<String>> serviceGroups = new LinkedHashMap<>();
    final Map<String, MultiInstance> multiInstance = new LinkedHashMap<>();
    final Map<String, DecisionRef> decisions = new LinkedHashMap<>();
    /** Result variable name to the business rule task that sets it. */
    final Map<String, String> decisionResults = new LinkedHashMap<>();
    final Map<String, String> waits = new LinkedHashMap<>();
    /** Timer catch event id to the plain variable that holds the date it waits for. */
    final Map<String, String> waitDates = new LinkedHashMap<>();
    /** Message catch event id to the message name and the plain variable that correlates it. */
    final Map<String, MessageCatch> messageCatches = new LinkedHashMap<>();
    /** Boundary timer node id to the id of the user task whose time limit it becomes. */
    final Map<String, String> timeoutBoundaries = new LinkedHashMap<>();
    final Map<String, Report.ServiceHint> serviceHints = new LinkedHashMap<>();
    final Map<String, Report.CallHint> callHints = new LinkedHashMap<>();
    /** Element id (start event or user task) to its form link. */
    final Map<String, Report.FormHint> formHints = new LinkedHashMap<>();
    /** Element id to a form parsed from the BPMN itself (zeebe:userTaskForm). */
    final Map<String, FormImport.Model> embeddedForms = new LinkedHashMap<>();
    /** Element id to the form id of a linked form that needs a {@code formMappings} entry. */
    final Map<String, String> linkedForms = new LinkedHashMap<>();
    final Map<String, String> parallelJoins = new LinkedHashMap<>();
    final Set<String> children = new LinkedHashSet<>();
    /** Exclusive gateways with at most one outgoing flow: removed, their incoming flows go straight to the target. */
    final Set<String> mergeGateways = new LinkedHashSet<>();
    /** Gateways that would carry approval outcomes, by user task id (recognised or candidates). */
    final Map<String, String> outcomeGatewayOf = new LinkedHashMap<>();
    final List<String> startIds = new ArrayList<>();
    final List<String> endIds = new ArrayList<>();
    /** Source element id -> sanitized canonical id, for ids that had to change. */
    final Map<String, String> renamedIds = new LinkedHashMap<>();

    Analysis(String sourceSystem, String sourceVersion, String sourceHash, String processId, String processName) {
        this.sourceSystem = sourceSystem;
        this.sourceVersion = sourceVersion;
        this.sourceHash = sourceHash;
        this.processId = processId;
        this.processName = processName;
    }

    public String sourceSystem() {
        return sourceSystem;
    }

    public String sourceVersion() {
        return sourceVersion;
    }

    public String sourceHash() {
        return sourceHash;
    }

    public String processId() {
        return processId;
    }

    public String processName() {
        return processName;
    }

    public List<Item> items() {
        return items;
    }

    public List<String> assumptions() {
        return assumptions;
    }

    public Map<String, UserTask> userTasks() {
        return userTasks;
    }

    public Map<String, Service> services() {
        return services;
    }

    public Map<String, Branch> branches() {
        return branches;
    }

    public Map<String, DecisionRef> decisions() {
        return decisions;
    }

    public Map<String, MultiInstance> multiInstance() {
        return multiInstance;
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public Map<String, String> linkedForms() {
        return linkedForms;
    }

    public Map<String, FormImport.Model> embeddedForms() {
        return embeddedForms;
    }

    public Map<String, Report.FormHint> formHints() {
        return formHints;
    }

    public Report.MappingHints hints() {
        Map<String, Report.FormPreview> previews = new LinkedHashMap<>();
        embeddedForms.forEach((element, model) -> previews.put(element, model.preview()));
        return new Report.MappingHints(serviceHints, formHints, callHints, previews);
    }

    public Map<String, Flow> flows() {
        return flows;
    }
}
