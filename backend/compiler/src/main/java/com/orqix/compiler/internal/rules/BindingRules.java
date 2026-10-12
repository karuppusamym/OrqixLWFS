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
                    com.orqix.compiler.BusinessCalendar.read(a.calendar()).deadline(java.time.Instant.EPOCH,
                            a.dueAfterHours() == null ? 1 : a.dueAfterHours());
                } catch (IllegalArgumentException e) {
                    rc.issues().blocker("WF_CALENDAR_INVALID", n.configPath("calendar"), n.id(), null, e.getMessage());
                }
                if (a.dueAfterHours() == null) rc.issues().blocker("WF_CALENDAR_DUE_REQUIRED", n.configPath("dueAfterHours"),
                        n.id(), null, "Set working hours for the calendar deadline.");
            }
            if (a.escalation() != null && a.dueAfterHours() == null) rc.issues().blocker("WF_ESCALATION_DUE_REQUIRED",
                    n.configPath("escalation"), n.id(), null, "Recurring overdue notices require a due date.");
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
