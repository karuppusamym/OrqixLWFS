package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.orqix.compiler.CompileContext.WorkflowInfo;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.ModelReader;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.rules.FieldTypes.FieldType;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/**
 * "Start another workflow" (S12 section 4): the child exists and has an active release (which a publish pins), no call
 * cycles, bounded depth, and input and result mappings that fit the child's request and result forms.
 */
final class ChildRules {

    /** Longest chain of workflows starting workflows (a workflow, its child, its grandchild, ...): 3 levels below. */
    static final int MAX_CALL_DEPTH = 3;
    /** Output names that every child step provides itself. */
    static final Set<String> RESERVED_OUTPUTS = Set.of("outcome", "outcomeKind", "reference", "iterations", "limitReached", "allSucceeded");

    private ChildRules() {
    }

    static void check(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (rc.graph().isShadowed(n) || !(n instanceof Node.StartWorkflow s)) {
                continue;
            }
            check(rc, s);
        }
    }

    private static void check(RuleContext rc, Node.StartWorkflow n) {
        // Field references are checked even when the child cannot be resolved.
        for (Map.Entry<String, String> e : n.inputMapping().entrySet()) {
            String path = JsonPointers.append(n.configPath("inputMapping"), e.getKey());
            Resolution res = rc.types().resolve(e.getValue());
            if (res instanceof Unknown u) {
                rc.issues().blocker("WF_FIELDREF_UNKNOWN", path, n.id(), null, u.message());
            } else {
                Conditions.availability(rc, e.getValue(), n, path, null);
            }
        }
        String refPath = n.configPath("workflowRef");
        WorkflowInfo child = rc.workspace().workflows().get(n.workflowRef());
        if (child == null) {
            rc.issues().blocker("WF_CHILD_UNKNOWN", refPath, n.id(), null, q(n) + " starts the workflow "
                    + q(n.workflowRef()) + ", which does not exist in this workspace.");
            return;
        }
        if (!child.published()) {
            rc.issues().blocker("WF_CHILD_NOT_PUBLISHED", refPath, n.id(), null, q(n) + " starts "
                    + q(child.name()) + ", which has not been published yet. Publish it first.");
            return;
        }
        String self = rc.definition().workflowKey();
        if (n.repeat() != null && !child.outcomes().contains(n.repeat().untilOutcome())) {
            rc.issues().blocker("WF_REPEAT_OUTCOME_UNKNOWN", n.configPath("repeat") + "/untilOutcome", n.id(), null,
                    "Choose a completion outcome of " + q(child.name()) + ": " + String.join(", ", child.outcomes()) + ".");
        }
        if (n.workflowRef().equals(self)) {
            rc.issues().blocker("WF_CHILD_CYCLE", refPath, n.id(), null, q(n) + " starts this same workflow. A"
                    + " workflow cannot start itself.");
            return;
        }
        if (child.callClosure().contains(self)) {
            rc.issues().blocker("WF_CHILD_CYCLE", refPath, n.id(), null, q(child.name()) + " already starts this"
                    + " workflow, directly or through another workflow. Starting it here would create a loop.");
            return;
        }
        if (1 + child.callHeight() > MAX_CALL_DEPTH) {
            rc.issues().blocker("WF_CHILD_DEPTH", refPath, n.id(), null, q(child.name()) + " starts other workflows"
                    + " in turn. Workflows can start other workflows at most " + MAX_CALL_DEPTH + " levels deep.");
            return;
        }
        FormSchema in = ModelReader.readFormSchema("/inputSchema", StrictJson.parse(child.inputSchemaJson()));
        FormSchema out = ModelReader.readFormSchema("/outputSchema", StrictJson.parse(child.outputSchemaJson()));
        if (n.forEach() != null) {
            Node.ForEach each = n.forEach();
            String key = each.itemsRef().substring("/input/".length());
            FormSchema.Field source = rc.definition().inputSchema().properties().get(key);
            FormSchema.Field target = in.properties().get(each.itemInput());
            if (source == null || source.type() != FieldKind.ARRAY || !rc.definition().inputSchema().required().contains(key)) {
                rc.issues().blocker("WF_FOREACH_COLLECTION", n.configPath("forEach") + "/itemsRef", n.id(), null,
                        "Choose a required request collection with bounded scalar items.");
            } else if (source.maxItems() > each.maxItems()) {
                rc.issues().blocker("WF_FOREACH_BOUND", n.configPath("forEach") + "/maxItems", n.id(), null,
                        "The iteration limit must cover the collection's maximum item count (" + source.maxItems() + ").");
            } else if (target != null) {
                FormSchema.Field item = source.items();
                String problem = FieldTypes.incompatibility(new FieldType(item.type(), item.enumValues() == null ? null : new TreeSet<>(item.enumValues()), false, item.minimum(), item.maximum()),
                        target.type(), target.enumValues() == null ? null : new TreeSet<>(target.enumValues()));
                if (problem != null) rc.issues().blocker("WF_FOREACH_ITEM_TYPE", n.configPath("forEach") + "/itemInput", n.id(), null, problem);
            }
            if (target == null || n.inputMapping().containsKey(each.itemInput())) {
                rc.issues().blocker("WF_FOREACH_INPUT", n.configPath("forEach") + "/itemInput", n.id(), null,
                        "Choose an existing child request field which is not also supplied by inputMapping.");
            }
        }
        String name = q(child.name());
        for (Map.Entry<String, String> e : n.inputMapping().entrySet()) {
            String path = JsonPointers.append(n.configPath("inputMapping"), e.getKey());
            FormSchema.Field target = in.properties().get(e.getKey());
            if (target == null) {
                rc.issues().blocker("WF_CHILD_MAPPING_UNKNOWN_TARGET", path, n.id(), null, name
                        + " has no request field " + q(e.getKey()) + ". It asks for: "
                        + String.join(", ", in.properties().keySet()) + ".");
                continue;
            }
            if (rc.types().resolve(e.getValue()) instanceof Resolved r) {
                Set<String> targetEnum = target.enumValues() == null ? null : new TreeSet<>(target.enumValues());
                String problem = FieldTypes.incompatibility(r.type(), target.type(), targetEnum);
                if (problem != null) {
                    rc.issues().blocker("WF_CHILD_MAPPING_TYPE", path, n.id(), null, "The field " + q(target.title())
                            + " of " + name + " " + problem + " (" + e.getValue() + ").");
                }
            }
        }
        List<String> required = in.required();
        for (String key : required) {
            if (!n.inputMapping().containsKey(key) && in.properties().containsKey(key)
                    && (n.forEach() == null || !key.equals(n.forEach().itemInput()))) {
                rc.issues().blocker("WF_CHILD_MAPPING_MISSING_REQUIRED", n.configPath("inputMapping"), n.id(), null,
                        name + " requires " + q(in.properties().get(key).title()) + ", but " + q(n)
                                + " does not provide it.");
            }
        }
        for (Map.Entry<String, String> e : n.resultMapping().entrySet()) {
            String path = JsonPointers.append(n.configPath("resultMapping"), e.getKey());
            if (RESERVED_OUTPUTS.contains(e.getKey())) {
                rc.issues().blocker("WF_CHILD_RESULT_RESERVED", path, n.id(), null, q(e.getKey()) + " is provided"
                        + " by every \"Start another workflow\" step already. Choose another name.");
            }
            if (!out.properties().containsKey(e.getValue())) {
                rc.issues().blocker("WF_CHILD_RESULT_UNKNOWN", path, n.id(), null, name + " has no result field "
                        + q(e.getValue()) + (out.properties().isEmpty() ? " (it returns no result fields)."
                        : ". It returns: " + String.join(", ", out.properties().keySet()) + "."));
            }
        }
    }
}
