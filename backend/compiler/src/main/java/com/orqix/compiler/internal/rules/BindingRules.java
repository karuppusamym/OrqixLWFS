package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.CompileContext.GroupInfo;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Dependency;
import com.orqix.compiler.internal.rules.Connections.Pinned;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

import tools.jackson.databind.JsonNode;

/** Groups, connections, operations, mappings and policy against the workspace (S4 section 5.3). */
final class BindingRules {

    private BindingRules() {
    }

    static void check(RuleContext rc) {
        selfApproval(rc);
        groups(rc);
        dependencies(rc);
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.Http http) {
                http(rc, http);
            }
        }
    }

    private static void selfApproval(RuleContext rc) {
        if (!"allow".equals(rc.definition().selfApproval())) {
            return;
        }
        if (rc.workspace().selfApprovalAllowed()) {
            rc.issues().warning("WF_SELF_APPROVAL_ALLOWED", "/security/selfApproval", null, null, "Requesters may"
                    + " approve their own requests. Every such decision is flagged and audited.");
        } else {
            rc.issues().blocker("WF_SELF_APPROVAL_NOT_ALLOWED", "/security/selfApproval", null, null, "This"
                    + " workspace does not allow requesters to approve their own requests. Set self-approval to"
                    + " deny.");
        }
    }

    private static void groups(RuleContext rc) {
        Map<String, GroupInfo> groups = rc.workspace().groups();
        for (Node n : rc.definition().nodes()) {
            if (!(n instanceof Node.Approval a)) {
                continue;
            }
            String path = n.configPath("candidateGroupRef");
            GroupInfo group = groups.get(a.candidateGroupRef());
            if (group == null) {
                rc.issues().blocker("WF_GROUP_UNBOUND", path, n.id(), null, n.label() + " needs the group "
                        + q(a.candidateGroupRef()) + ", which does not exist in this workspace.");
            } else if (group.configuredMemberCount() <= 0) {
                rc.issues().warning("WF_GROUP_EMPTY", path, n.id(), null, "The group " + q(a.candidateGroupRef())
                        + " has no configured members. If its members come from the identity provider, make sure"
                        + " that group is not empty, or nobody can act on " + q(n) + ".");
            }
            for (String skill : a.requiredSkills()) {
                if (!groups.containsKey(skill)) rc.issues().blocker("WF_SKILL_UNBOUND", n.configPath("requiredSkills"),
                        n.id(), null, "Required skill group " + q(skill) + " does not exist in this workspace.");
            }
            if (a.calendar() != null) {
                try {
                    com.orqix.compiler.BusinessCalendar.read(a.calendar()).deadlineMinutes(java.time.Instant.EPOCH,
                            a.dueAfterMinutes() != null ? a.dueAfterMinutes() : a.dueAfterHours() == null ? 60 : a.dueAfterHours() * 60);
                } catch (IllegalArgumentException e) {
                    rc.issues().blocker("WF_CALENDAR_INVALID", n.configPath("calendar"), n.id(), null, e.getMessage());
                }
                if (a.dueAfterHours() == null && a.dueAfterMinutes() == null) rc.issues().blocker("WF_CALENDAR_DUE_REQUIRED", n.configPath("dueAfterHours"),
                        n.id(), null, "Set working hours for the calendar deadline.");
            }
            for (String ref : a.separateFrom()) {
                Node other = rc.graph().node(ref);
                String why = other == null ? "does not exist"
                        : !(other instanceof Node.Approval) ? "is not an approval step"
                        : other.id().equals(n.id()) ? "is this step itself"
                        : !rc.graph().dominates(other, n) ? "is not decided on every path to this step"
                        : null;
                if (why != null) rc.issues().blocker("WF_SEPARATION_INVALID", n.configPath("separateFrom"), n.id(), null,
                        n.label() + " separates duties from " + q(ref) + ", which " + why + ".");
            }
            correctable(rc, n, a);
            StepForms.check(rc, a);
            if (a.escalation() != null && a.dueAfterHours() == null && a.dueAfterMinutes() == null) rc.issues().blocker("WF_ESCALATION_DUE_REQUIRED",
                    n.configPath("escalation"), n.id(), null, "Recurring overdue notices require a due date.");
        }
    }

    /**
     * Fields an approver may correct (S42). Each must be a scalar request field that no step before this approval has
     * used, and the approval must not sit inside an open parallel section where another branch could be using it.
     */
    private static void correctable(RuleContext rc, Node n, Node.Approval a) {
        if (a.editableFields().isEmpty()) {
            return;
        }
        String path = n.configPath(a.correctable().isEmpty() ? "fieldAccess" : "correctable");
        var form = rc.definition().inputSchema();
        java.util.Set<String> before = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        queue.add(n);
        while (!queue.isEmpty()) {
            for (com.orqix.compiler.internal.model.WorkflowDefinition.Transition t : rc.graph().incoming(queue.poll())) {
                Node from = rc.graph().node(t.from());
                if (from != null && before.add(from.id())) {
                    queue.add(from);
                }
            }
        }
        for (String id : before) {
            Node earlier = rc.graph().node(id);
            if (earlier instanceof Node.ParallelSplit split && !before.contains(split.joinRef())) {
                rc.issues().blocker("WF_CORRECTION_INVALID", path, n.id(), null, n.label()
                        + " is inside a parallel section, where another branch could be using the request while it is corrected.");
                return;
            }
        }
        for (String key : a.editableFields()) {
            var field = form.properties().get(key);
            if (field == null) {
                rc.issues().blocker("WF_CORRECTION_INVALID", path, n.id(), null, n.label() + " lets the approver correct "
                        + q(key) + ", which is not a request field.");
                continue;
            }
            boolean scalar = switch (field.type()) {
                case STRING, NUMBER, INTEGER, BOOLEAN, DATE -> true;
                case ARRAY, OBJECT, ATTACHMENT -> false;
            };
            if (!scalar) {
                rc.issues().blocker("WF_CORRECTION_INVALID", path, n.id(), null, n.label() + " cannot let the approver correct "
                        + q(key) + ": only single-value fields (not lists, rows or files) can be corrected.");
                continue;
            }
            String ref = "/input/" + key;
            for (String id : before) {
                Node earlier = rc.graph().node(id);
                if (earlier != null && references(rc, earlier).contains(ref)) {
                    rc.issues().blocker("WF_CORRECTION_INVALID", path, n.id(), null, n.label() + " lets the approver correct "
                            + q(key) + ", but the earlier step " + q(earlier) + " already uses it.");
                    break;
                }
            }
        }
    }

    /** Request field references a step reads when it runs, including the conditions on the routes leaving it. */
    private static java.util.Set<String> references(RuleContext rc, Node node) {
        java.util.Set<String> refs = new java.util.HashSet<>();
        switch (node) {
            case Node.Http h -> refs.addAll(h.inputMapping().values());
            case Node.GenerateDocument g -> refs.addAll(g.inputMapping().values());
            case Node.ExternalTask e -> refs.addAll(e.inputMapping().values());
            case Node.SendAdapter a -> refs.addAll(a.inputMapping().values());
            case Node.StartWorkflow s -> {
                refs.addAll(s.inputMapping().values());
                if (s.forEach() != null) refs.add(s.forEach().itemsRef());
            }
            case Node.Wait w -> {
                if (w.untilField() != null) refs.add(w.untilField());
            }
            case Node.WaitForMessage m -> refs.add(m.correlationField());
            case Node.Decision d -> {
                var table = rc.types().table(d.decisionRef());
                if (table != null) {
                    table.rows().forEach(row -> conditionRefs(row.when(), refs));
                }
            }
            default -> {
            }
        }
        for (com.orqix.compiler.internal.model.WorkflowDefinition.Transition t : rc.leaving(node)) {
            if (t.condition() != null) {
                conditionRefs(t.condition(), refs);
            }
        }
        return refs;
    }

    private static void conditionRefs(com.orqix.compiler.internal.model.WorkflowDefinition.Condition c, java.util.Set<String> refs) {
        if (c.compound()) {
            c.terms().forEach(t -> conditionRefs(t, refs));
        } else if (c.fieldRef() != null) {
            refs.add(c.fieldRef());
        }
    }

    private static void dependencies(RuleContext rc) {
        Set<String> usedKeys = new HashSet<>();
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.Http http) {
                usedKeys.add(http.connectionRef());
            }
        }
        for (Dependency dup : rc.connections().duplicates()) {
            rc.issues().blocker("WF_CONNECTION_UNKNOWN", dup.path() + "/key", null, null, "The connection "
                    + q(dup.key()) + " is listed more than once. List each connection once, with the version to"
                    + " use.");
        }
        for (Pinned p : rc.connections().all().values()) {
            Dependency dep = p.dependency();
            String name = q(dep.key()) + " version " + dep.definitionVersion();
            switch (p.status()) {
                case NOT_REGISTERED -> rc.issues().blocker("WF_CONNECTION_UNKNOWN", dep.path() + "/definitionVersion",
                        null, null, "The connection " + name + " is not registered in this workspace.");
                case MALFORMED -> rc.issues().blocker("WF_CONNECTION_UNKNOWN", dep.path() + "/definitionVersion",
                        null, null, "The registered definition of connection " + name + " cannot be used because "
                                + p.problem() + ".");
                case OK -> {
                }
            }
            if (!usedKeys.contains(dep.key())) {
                rc.issues().warning("WF_DEPENDENCY_UNUSED", dep.path(), null, null, "The connection "
                        + q(dep.key()) + " is listed as a dependency, but no step uses it.");
            } else if (!rc.workspace().enabledConnectionBindings().contains(dep.key())) {
                rc.issues().blocker("WF_CONNECTION_UNBOUND", dep.path() + "/key", null, null, "The connection "
                        + q(dep.key()) + " is not set up in this workspace (no enabled binding). Ask an"
                        + " administrator to configure and enable it.");
            }
        }
    }

    private static void http(RuleContext rc, Node.Http n) {
        // Field references are checked even when the connection cannot be resolved.
        for (Map.Entry<String, String> e : n.inputMapping().entrySet()) {
            String path = JsonPointers.append(n.configPath("inputMapping"), e.getKey());
            Resolution res = rc.types().resolve(e.getValue());
            if (res instanceof Unknown u) {
                rc.issues().blocker("WF_FIELDREF_UNKNOWN", path, n.id(), null, u.message());
            } else {
                Conditions.availability(rc, e.getValue(), n, path, null);
            }
        }
        Pinned pinned = rc.connections().pinned(n.connectionRef());
        if (pinned == null) {
            rc.issues().blocker("WF_CONNECTION_UNDECLARED", n.configPath("connectionRef"), n.id(), null, n.label()
                    + " uses the connection " + q(n.connectionRef()) + ", which is not listed in the workflow's"
                    + " dependencies.");
            return;
        }
        if (pinned.status() != Connections.Status.OK) {
            return;
        }
        JsonNode operation = rc.connections().operation(n.connectionRef(), n.operation());
        if (operation == null) {
            rc.issues().blocker("WF_OPERATION_UNKNOWN", n.configPath("operation"), n.id(), null, "The connection "
                    + q(n.connectionRef()) + " version " + pinned.dependency().definitionVersion()
                    + " has no operation " + q(n.operation()) + ".");
            return;
        }
        String op = q(n.operation());
        Map<String, Connections.RequestField> request = Connections.requestFields(operation);
        for (Map.Entry<String, String> e : n.inputMapping().entrySet()) {
            String path = JsonPointers.append(n.configPath("inputMapping"), e.getKey());
            Connections.RequestField target = request.get(e.getKey());
            if (target == null) {
                rc.issues().blocker("WF_MAPPING_UNKNOWN_TARGET", path, n.id(), null, "The operation " + op
                        + " has no request field " + q(e.getKey()) + ".");
                continue;
            }
            if (rc.types().resolve(e.getValue()) instanceof Resolved r) {
                String problem = FieldTypes.incompatibility(r.type(), target.kind(), target.enumValues());
                if (problem != null) {
                    rc.issues().blocker("WF_MAPPING_TYPE", path, n.id(), null, "The request field "
                            + q(e.getKey()) + " " + problem + " (" + e.getValue() + ").");
                }
            }
        }
        for (String required : Connections.requiredRequestFields(operation)) {
            if (!n.inputMapping().containsKey(required)) {
                rc.issues().blocker("WF_MAPPING_MISSING_REQUIRED", n.configPath("inputMapping"), n.id(), null,
                        "The operation " + op + " requires " + q(required) + ", but " + n.label()
                                + " does not provide it.");
            }
        }
        for (Map.Entry<String, String> e : n.resultMapping().entrySet()) {
            String path = JsonPointers.append(n.configPath("resultMapping"), e.getKey());
            Connections.ResponseScalar scalar = Connections.responseScalar(operation, e.getValue());
            if (scalar == null) {
                rc.issues().blocker("WF_RESULT_POINTER_UNKNOWN", path, n.id(), null, "The response of " + op
                        + " has no single value at " + e.getValue() + ".");
            } else if (!scalar.allRequired()) {
                rc.issues().warning("WF_RESULT_OPTIONAL", path, n.id(), null, "The response of " + op
                        + " does not always contain " + e.getValue() + ". If it is missing, " + q(n)
                        + " stops and the request needs attention.");
            }
        }
        int max = operation.get("maxTimeoutSeconds").intValue();
        if (n.timeoutSeconds() > max) {
            rc.issues().blocker("WF_TIMEOUT_EXCEEDS_OPERATION", n.configPath("timeoutSeconds"), n.id(), null,
                    n.label() + " waits up to " + n.timeoutSeconds() + " seconds, but the operation " + op
                            + " allows at most " + max + ".");
        }
    }

    /** @return connection keys of http nodes, in canonical node order without repeats */
    static List<String> usedConnections(RuleContext rc) {
        return rc.definition().nodes().stream().filter(n -> n instanceof Node.Http)
                .map(n -> ((Node.Http) n).connectionRef()).distinct().toList();
    }
}
