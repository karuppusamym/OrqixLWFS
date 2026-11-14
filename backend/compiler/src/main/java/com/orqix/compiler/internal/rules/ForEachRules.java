package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.Map;

import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.StepForEach;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/**
 * "For each" on a call, external worker or AI task step (CG-E06, docs/build/S138). The step runs once per item of a
 * request list of at most {@link StepForEach#MAX_ITEMS} items. The reader already added the item inputs to the step's
 * input mapping ({@code /input/<list>/*}), so the step's own rules type-check them like any other input; these rules
 * check the policy itself.
 */
final class ForEachRules {

    private ForEachRules() {
    }

    static void check(RuleContext rc) {
        for (Map.Entry<String, StepForEach> e : rc.definition().execution().forEach().entrySet()) {
            Node n = rc.graph().node(e.getKey());
            if (n != null && !rc.graph().isShadowed(n)) {
                check(rc, n, e.getValue());
            }
        }
    }

    private static void check(RuleContext rc, Node n, StepForEach f) {
        String why = switch (n) {
            case Node.Http h when h.polls() -> "a call that keeps checking until done";
            case Node.Http h when h.undoHandler() || h.compensates() != null -> "an undo call";
            case Node.Http h -> null;
            case Node.ExternalTask e -> null;
            case Node.AiTask ai -> null;
            default -> n.kind().businessLabel();
        };
        if (why != null) {
            rc.issues().blocker("WF_FOR_EACH_KIND", f.path(), n.id(), null, q(n) + " is " + why + ", which cannot run"
                    + " for each item. Use \"for each\" on a call to a system, an external worker or an AI task.");
            return;
        }
        FormSchema.Field list = rc.definition().inputSchema().properties().get(f.itemsField());
        if (list == null || list.type() != FieldKind.ARRAY || list.items() == null) {
            rc.issues().blocker("WF_FOR_EACH_LIST", f.path() + "/itemsRef", n.id(), null, "Choose a list on the request"
                    + " form for " + q(n) + " to run for each item of." + (list == null ? " The form has no field "
                    + q(f.itemsField()) + "." : " " + q(list.title()) + " is not a list."));
            return;
        }
        if (list.maxItems() == null || list.maxItems() > f.maxItems()) {
            rc.issues().blocker("WF_FOR_EACH_BOUND", f.path() + "/maxItems", n.id(), null, q(list.title())
                    + (list.maxItems() == null ? " has no maximum number of items. Set one (at most "
                    + StepForEach.MAX_ITEMS + ") on the form." : " can hold " + list.maxItems() + " items, but "
                    + q(n) + " runs for at most " + f.maxItems() + ". Raise the limit to cover the list."));
        }
        if (f.itemInput() != null && list.rows()) {
            rc.issues().blocker("WF_FOR_EACH_ITEM", f.path() + "/itemInput", n.id(), null, q(list.title()) + " is a"
                    + " list of rows. Choose which column fills which input (item mapping) instead.");
        }
        if (!f.itemMapping().isEmpty() && !list.rows()) {
            rc.issues().blocker("WF_FOR_EACH_ITEM", f.path() + "/itemMapping", n.id(), null, q(list.title()) + " is a"
                    + " list of single values, so it has no columns. Choose the input that receives each item.");
        }
        Map<String, String> inputs = inputs(n);
        String listRef = f.itemsRef() + "/" + FieldTypes.ITEM;
        if (f.itemInput() != null && !listRef.equals(inputs.get(f.itemInput()))) {
            rc.issues().blocker("WF_FOR_EACH_INPUT", f.path() + "/itemInput", n.id(), null, "The input "
                    + q(f.itemInput()) + " is already filled by inputMapping, so it cannot also receive each item.");
        }
        f.itemMapping().forEach((input, column) -> {
            if (!(listRef + "/" + column).equals(inputs.get(input))) {
                rc.issues().blocker("WF_FOR_EACH_INPUT", JsonPointers.append(f.path() + "/itemMapping", input),
                        n.id(), null, "The input " + q(input) + " is already filled by inputMapping, so it cannot"
                                + " also come from each row.");
            }
        });
        if (StepForEach.AT_LEAST.equals(f.when()) != (f.count() != null)) {
            rc.issues().blocker("WF_FOR_EACH_COMPLETION", f.path() + "/completion", n.id(), null,
                    StepForEach.AT_LEAST.equals(f.when()) ? "Say how many items must succeed (count)."
                            : "A count is used only with \"atLeast\".");
        } else if (f.count() != null && f.count() > f.maxItems()) {
            rc.issues().blocker("WF_FOR_EACH_COMPLETION", f.path() + "/completion/count", n.id(), null, q(n)
                    + " runs for at most " + f.maxItems() + " items, so " + f.count() + " can never succeed.");
        }
        if (f.parallel() && !(n instanceof Node.Http)) {
            // The worker and AI job tables keep one open job per step occurrence: items run one at a time there.
            rc.issues().blocker("WF_FOR_EACH_PARALLEL", f.path() + "/mode", n.id(), null, q(n) + " ("
                    + n.kind().businessLabel() + ") runs its items one at a time. Only a call to a system can run"
                    + " items in parallel; remove mode \"parallel\".");
        }
        if (!f.parallel() && f.batchSize() != StepForEach.MAX_BATCH) {
            rc.issues().warning("WF_FOR_EACH_BATCH_UNUSED", f.path() + "/batchSize", n.id(), null,
                    "The batch size applies only when items run in parallel.");
        }
        for (Map.Entry<String, String> c : f.collect().entrySet()) {
            String path = JsonPointers.append(f.path() + "/collect", c.getKey());
            if (StepForEach.ITEM_COUNT.equals(c.getKey()) || StepForEach.COMPLETED_COUNT.equals(c.getKey())
                    || StepForEach.STOPPED_EARLY.equals(c.getKey()) || FailureRules.isFailureOutput(c.getKey())) {
                rc.issues().blocker("WF_FOR_EACH_COLLECT", path, n.id(), null, q(c.getKey())
                        + " is provided by every for-each step already. Choose another name.");
                continue;
            }
            var resolved = rc.types().resolve("/steps/" + n.id() + "/" + c.getKey());
            if (resolved instanceof Unknown u) {
                rc.issues().blocker("WF_FOR_EACH_COLLECT", path, n.id(), null, u.message());
            } else if (!(resolved instanceof Resolved) && !(resolved instanceof FieldTypes.Indeterminate)) {
                rc.issues().blocker("WF_FOR_EACH_COLLECT", path, n.id(), null, "Choose a result of " + q(n) + ".");
            }
        }
        // Per-item AI routes would disagree with each other: a for-each AI task continues on next, and each item's
        // status (answered, needsReview, lowConfidence, aiUnavailable) can be collected.
        if (n instanceof Node.AiTask) {
            for (Transition t : rc.leaving(n)) {
                if (t.on() != Route.NEXT && t.on() != Route.LIMIT_REACHED && !t.bounded()) {
                    rc.issues().blocker("WF_FOR_EACH_ROUTES", t.path() + "/on", n.id(), t.id(), q(n) + " runs for"
                            + " each item, so it continues on one next step. Collect each item's status and route on"
                            + " it with a choice after the step.");
                }
            }
        }
    }

    private static Map<String, String> inputs(Node n) {
        return switch (n) {
            case Node.Http h -> h.inputMapping();
            case Node.ExternalTask e -> e.inputMapping();
            case Node.AiTask ai -> ai.inputMapping();
            default -> Map.of();
        };
    }
}
