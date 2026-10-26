package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;

/**
 * List-driven approval (S88, {@code config.perItem}): one task per item of a request list. The list must be a required,
 * unconditional request field, so it always has at least one item (every list field has {@code minItems >= 1}) and an
 * empty list can never reach the step; its maximum must not exceed {@link Node.PerItem#MAX_ITEMS}. Like several
 * approvers (S51) it cannot be combined with settings defined for exactly one task.
 */
final class PerItemRules {

    private PerItemRules() {
    }

    static void check(RuleContext rc) {
        for (Node node : rc.definition().nodes()) {
            if (!(node instanceof Node.Approval a) || rc.graph().isShadowed(node) || !a.listDriven()) {
                continue;
            }
            String path = a.configPath("perItem");
            Node.PerItem each = a.perItem();
            String key = each.listKey();
            FormSchema.Field list = rc.definition().inputSchema().properties().get(key);
            if (list == null || list.type() != FieldKind.ARRAY) {
                rc.issues().blocker("WF_PER_ITEM_LIST", path + "/itemsRef", a.id(), null, q(a)
                        + " opens one task per item, so choose a list from the request form.");
            } else if (!rc.definition().inputSchema().required().contains(key) || rc.types().conditional(key)) {
                rc.issues().blocker("WF_PER_ITEM_LIST", path + "/itemsRef", a.id(), null, q(list.title())
                        + " must be required and always asked for, so the step never meets an empty list.");
            } else {
                if (list.maxItems() == null || list.maxItems() > Node.PerItem.MAX_ITEMS) {
                    rc.issues().blocker("WF_PER_ITEM_BOUND", path + "/itemsRef", a.id(), null, q(list.title())
                            + " may hold more than " + Node.PerItem.MAX_ITEMS + " items. Set its maximum to "
                            + Node.PerItem.MAX_ITEMS + " or fewer.");
                }
                label(rc, a, list, path);
            }
            if (a.multiInstance()) {
                unsupported(rc, a, path, "a number of approvers");
            }
            if (a.timeoutAfter() != null) {
                unsupported(rc, a, path, "a time limit");
            }
            if (a.escalation() != null) {
                unsupported(rc, a, path, "reminders or escalation");
            }
            if (!a.correctable().isEmpty()) {
                unsupported(rc, a, path, "correction of request fields");
            }
        }
    }

    /** Rows show the chosen column; a list of single values shows the value itself and takes no column. */
    private static void label(RuleContext rc, Node.Approval a, FormSchema.Field list, String path) {
        String column = a.perItem().itemLabel();
        if (!list.rows()) {
            if (column != null) {
                rc.issues().blocker("WF_PER_ITEM_LABEL", path + "/itemLabel", a.id(), null, q(list.title())
                        + " is a list of single values, so each task shows the value itself. Remove the item label.");
            }
            return;
        }
        FormSchema.Field col = column == null ? null : list.items().rowFields().get(column);
        if (col == null) {
            rc.issues().blocker("WF_PER_ITEM_LABEL", path + "/itemLabel", a.id(), null, "Choose the column of "
                    + q(list.title()) + " that names each item on its task. Columns: "
                    + String.join(", ", list.items().rowFields().keySet()) + ".");
        } else if (col.type() == FieldKind.ARRAY || col.type() == FieldKind.ATTACHMENT || col.type() == FieldKind.OBJECT
                || col.type() == FieldKind.BOOLEAN) {
            rc.issues().blocker("WF_PER_ITEM_LABEL", path + "/itemLabel", a.id(), null, "The column " + q(col.title())
                    + " cannot name an item. Choose a text, number or date column.");
        }
    }

    private static void unsupported(RuleContext rc, Node.Approval a, String path, String setting) {
        rc.issues().blocker("WF_PER_ITEM_UNSUPPORTED", path, a.id(), null, q(a)
                + " opens one task per list item, so it cannot also have " + setting + ".");
    }
}
