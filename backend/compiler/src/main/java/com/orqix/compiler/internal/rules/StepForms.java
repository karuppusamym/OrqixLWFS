package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.Map;

import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;

/**
 * Step forms (S123): an approval's {@code fieldAccess} names request fields, and its {@code capture} asks for new
 * single-value fields. Editable and required access go through the correction checks in {@link BindingRules}; data
 * flow of captured fields is checked where they are used ({@link Conditions#availability}).
 */
final class StepForms {

    private StepForms() {
    }

    static void check(RuleContext rc, Node.Approval a) {
        FormSchema request = rc.definition().inputSchema();
        for (Map.Entry<String, String> e : a.fieldAccess().entrySet()) {
            if (!request.properties().containsKey(e.getKey())) {
                rc.issues().blocker("WF_STEP_FORM_INVALID", a.configPath("fieldAccess") + "/" + e.getKey(), a.id(), null,
                        a.label() + " sets how " + q(e.getKey()) + " is shown, but the request form has no such question.");
            }
        }
        FormSchema capture = a.capture();
        if (capture == null) {
            return;
        }
        String path = a.configPath("capture");
        for (String key : capture.required()) {
            if (!capture.properties().containsKey(key)) {
                rc.issues().blocker("WF_STEP_FORM_INVALID", path + "/required", a.id(), null, a.label()
                        + " requires " + q(key) + ", which is not one of the fields it asks for.");
            }
        }
        for (FormSchema.Field field : capture.properties().values()) {
            String fieldPath = path + "/properties/" + field.key();
            if (FieldTypes.APPROVAL_OUTPUTS.contains(field.key()) || FieldTypes.PER_ITEM_OUTPUTS.contains(field.key())) {
                rc.issues().blocker("WF_STEP_FORM_INVALID", fieldPath, a.id(), null, a.label() + " cannot ask for a field"
                        + " named " + q(field.key()) + ": the step already records a value with that name.");
                continue;
            }
            boolean scalar = switch (field.type()) {
                case STRING, NUMBER, INTEGER, BOOLEAN, DATE -> true;
                case ARRAY, OBJECT, ATTACHMENT -> false;
            };
            if (!scalar) {
                rc.issues().blocker("WF_STEP_FORM_INVALID", fieldPath, a.id(), null, a.label() + " can ask only for"
                        + " single values (text, numbers, yes or no, dates), not lists, rows or files: " + q(field.title()) + ".");
            }
        }
    }
}
