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
        NONE, TASK, OUTCOME_GATEWAY, SERVICE, CHILD, ACKNOWLEDGE
    }

    /** One inventory entry. */
    public static final class Item {
        Report.Issue issue;
        final Need need;
        /** User task or service task id the item depends on (null for {@link Need#NONE} and {@link Need#ACKNOWLEDGE}). */
        final String subject;
        final int ordinal;
        final int sequence;

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

    /** One comparison of a branch gateway. */
    public record BranchEntry(String flowId, Expressions.Op op, Object literal, boolean isDefault) {
    }

    /** An exclusive gateway that becomes a branch. */
    public static final class Branch {
        final Node gateway;
        final String variable;
        /** number | string | boolean */
        final String valueType;
        final List<BranchEntry> entries;

        Branch(Node gateway, String variable, String valueType, List<BranchEntry> entries) {
            this.gateway = gateway;
            this.variable = variable;
            this.valueType = valueType;
            this.entries = List.copyOf(entries);
        }

        public String variable() {
            return variable;
        }

        public String valueType() {
            return valueType;
        }
    }

    /** A service task that needs a designer mapping. */
    public record Service(Node node, String reasonCode, String detail) {
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
    final Map<String, String> waits = new LinkedHashMap<>();
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

    public Map<String, Node> nodes() {
        return nodes;
    }

    public Map<String, Flow> flows() {
        return flows;
    }
}
