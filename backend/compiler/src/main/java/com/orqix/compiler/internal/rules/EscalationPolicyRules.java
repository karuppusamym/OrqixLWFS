package com.orqix.compiler.internal.rules;

import com.orqix.compiler.CompileContext;

import tools.jackson.databind.JsonNode;

/**
 * CG-T04 (docs/build/S141): escalation policies on approval steps. The schema bounds the shape (one to five levels,
 * 1-2160 hours, three actions); these rules check what the schema cannot: strictly increasing times, a group for
 * notify and reassign (and none for alertOperators), groups that exist in the workspace, and no reassignment to the
 * queue the task already is in. List-driven and multi-approver steps are refused because a level acts on one task.
 */
public final class EscalationPolicyRules {

    private EscalationPolicyRules() {
    }

    public static void validate(JsonNode root, CompileContext context, Issues issues) {
        JsonNode nodes = root.path("nodes");
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode n = nodes.get(i);
            JsonNode c = n.path("config");
            if (!"approval".equals(n.path("type").asString()) || !c.has("escalationPolicy")) {
                continue;
            }
            String id = n.path("id").asString();
            String base = "/nodes/" + i + "/config/escalationPolicy";
            if (c.has("perItem") || c.has("multiInstance")) {
                issues.blocker("WF_ESCALATION_POLICY_STEP", base, id, null, "Escalation policies apply to steps with"
                        + " one task. Remove the policy or decide the step with one approver.");
            }
            String queue = c.path("candidateGroupRef").asString("");
            int previous = 0;
            JsonNode levels = c.path("escalationPolicy").path("levels");
            for (int l = 0; l < levels.size(); l++) {
                JsonNode level = levels.get(l);
                String path = base + "/levels/" + l;
                int after = level.path("afterHours").asInt(0);
                if (after <= previous) {
                    issues.blocker("WF_ESCALATION_POLICY_ORDER", path + "/afterHours", id, null, "Level " + (l + 1)
                            + " must come later than level " + l + " (" + previous + " hours).");
                }
                previous = Math.max(previous, after);
                String action = level.path("action").asString("");
                String group = level.path("groupRef").asString(null);
                if ("alertOperators".equals(action)) {
                    if (group != null) {
                        issues.blocker("WF_ESCALATION_POLICY_GROUP", path + "/groupRef", id, null, "Alerting operators"
                                + " goes to the workspace's operators; remove the group.");
                    }
                    continue;
                }
                if (group == null) {
                    issues.blocker("WF_ESCALATION_POLICY_GROUP", path + "/groupRef", id, null, "Choose the supervisor"
                            + " group for level " + (l + 1) + ".");
                    continue;
                }
                if (!context.groups().containsKey(group)) {
                    issues.blocker("WF_ESCALATION_POLICY_GROUP_UNBOUND", path + "/groupRef", id, null, "The group \""
                            + group + "\" does not exist in this workspace.");
                } else if ("reassign".equals(action) && group.equals(queue)) {
                    issues.warning("WF_ESCALATION_POLICY_SAME_QUEUE", path + "/groupRef", id, null, "Level " + (l + 1)
                            + " moves the task to the queue it is already in; it only returns the task to the queue.");
                }
            }
        }
    }
}
