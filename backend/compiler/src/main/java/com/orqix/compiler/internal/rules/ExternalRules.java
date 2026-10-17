package com.orqix.compiler.internal.rules;

import java.util.HashSet;
import java.util.Set;

import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/**
 * "Work by an external worker" (S43). The worker receives only explicitly mapped scalar values that are always available
 * when the step starts, and must return exactly the declared fields; mappings obey normal graph availability.
 */
final class ExternalRules {

    private ExternalRules() {
    }

    static void check(RuleContext rc) {
        for (Node node : rc.definition().nodes()) {
            if (!(node instanceof Node.ExternalTask task) || rc.graph().isShadowed(node)) {
                continue;
            }
            Set<String> names = new HashSet<>();
            for (Node.PayloadField field : task.result()) {
                if (!names.add(field.name())) {
                    rc.issues().blocker("WF_EXTERNAL_RESULT", task.configPath("result"), task.id(), null, task.label()
                            + " declares the result field " + RuleContext.q(field.name()) + " more than once.");
                }
                if (field.type() == FieldKind.ARRAY || field.type() == FieldKind.OBJECT || field.type() == FieldKind.ATTACHMENT) {
                    rc.issues().blocker("WF_EXTERNAL_RESULT", task.configPath("result"), task.id(), null, task.label()
                            + " can only return single values (text, numbers, true/false, dates), not "
                            + RuleContext.q(field.name()) + ".");
                }
            }
            task.inputMapping().forEach((name, ref) -> {
                String path = task.configPath("inputMapping") + "/" + name;
                var resolved = rc.types().resolve(ref);
                if (resolved instanceof Unknown u) {
                    rc.issues().blocker("WF_EXTERNAL_INPUT_UNKNOWN", path, task.id(), null, u.message());
                }
                if (resolved instanceof Resolved r && (r.type().kind() == FieldKind.ARRAY || r.type().kind() == FieldKind.OBJECT)) {
                    rc.issues().blocker("WF_EXTERNAL_INPUT_TYPE", path, task.id(), null,
                            "A worker receives single values only; map a text, number, true/false or date field.");
                }
                if (resolved instanceof Resolved r && r.type().mayBeAbsent()) {
                    rc.issues().blocker("WF_EXTERNAL_INPUT_OPTIONAL", path, task.id(), null,
                            "This value may be missing when the step starts. Make it required or map one that is always present.");
                }
                Conditions.availability(rc, ref, task, path, null);
            });
        }
    }
}
