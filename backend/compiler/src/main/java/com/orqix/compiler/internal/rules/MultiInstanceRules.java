package com.orqix.compiler.internal.rules;

import com.orqix.compiler.internal.model.Node;

/**
 * "Several approvers" on an approval (S51). The step opens one task per required approver in the same candidate group
 * and each person can decide only one of them. It deliberately cannot be combined with settings whose meaning is
 * defined for exactly one task: a time limit, reminders/escalation and correction of request fields.
 */
final class MultiInstanceRules {

    private MultiInstanceRules() {
    }

    static void check(RuleContext rc) {
        for (Node node : rc.definition().nodes()) {
            if (!(node instanceof Node.Approval a) || rc.graph().isShadowed(node) || a.approvers() == null) {
                continue;
            }
            String path = a.configPath("multiInstance");
            if (a.approvers() < 2 || a.approvers() > 5) {
                rc.issues().blocker("WF_MULTI_COUNT", path + "/approvers", a.id(), null, a.label()
                        + " needs between 2 and 5 approvers.");
            }
            if (a.timeoutAfter() != null) {
                rc.issues().blocker("WF_MULTI_UNSUPPORTED", path, a.id(), null, a.label()
                        + " asks several people to approve, so it cannot also have a time limit.");
            }
            if (a.escalation() != null) {
                rc.issues().blocker("WF_MULTI_UNSUPPORTED", path, a.id(), null, a.label()
                        + " asks several people to approve, so it cannot also have reminders or escalation.");
            }
            if (!a.editableFields().isEmpty()) {
                rc.issues().blocker("WF_MULTI_UNSUPPORTED", path, a.id(), null, a.label()
                        + " asks several people to approve, so approvers cannot also correct request fields.");
            }
            if (a.capture() != null && !a.capture().properties().isEmpty()) {
                rc.issues().blocker("WF_MULTI_UNSUPPORTED", path, a.id(), null, a.label()
                        + " asks several people to approve, so it cannot also ask them to fill in fields.");
            }
        }
    }
}
