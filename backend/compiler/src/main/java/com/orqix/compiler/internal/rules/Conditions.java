package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.regex.Pattern;

import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.rules.FieldTypes.FieldType;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/** Checks shared by branch conditions, decision rows and mappings. */
final class Conditions {

    /** S4 section 3 literal safety. */
    static final Pattern SAFE_LITERAL = Pattern.compile("^[A-Za-z0-9 _.,:/-]{0,100}$");

    private Conditions() {
    }

    static boolean safe(String text) {
        return SAFE_LITERAL.matcher(text).matches();
    }

    /** Reports WF_LITERAL_UNSAFE for an unsafe string literal. */
    static void literalSafety(RuleContext rc, Literal literal, String path, String nodeId, String transitionId) {
        if (literal instanceof Literal.Str s && !safe(s.string())) {
            rc.issues().blocker("WF_LITERAL_UNSAFE", path, nodeId, transitionId, "This text contains characters"
                    + " that are not allowed here. Use at most 100 letters, digits, spaces and the characters"
                    + " . , : / - _");
        }
    }

    /**
     * Checks literal safety, field existence, operator and literal type of one condition.
     *
     * @param base JSON Pointer of the condition object
     * @return the typed condition, or null when it cannot take part in disjointness analysis
     */
    static Disjointness.Typed check(RuleContext rc, Condition c, String base, String nodeId, String transitionId) {
        literalSafety(rc, c.value(), base + "/value", nodeId, transitionId);
        Resolution resolution = rc.types().resolve(c.fieldRef());
        if (resolution instanceof Unknown u) {
            rc.issues().blocker("WF_FIELDREF_UNKNOWN", base + "/fieldRef", nodeId, transitionId, u.message());
            return null;
        }
        if (!(resolution instanceof Resolved r)) {
            return null;
        }
        FieldType type = r.type();
        if (Disjointness.ordering(c.operator()) && !type.kind().numeric()) {
            rc.issues().blocker("WF_OPERATOR_TYPE", base + "/operator", nodeId, transitionId, "The comparison "
                    + q(c.operator().phrase()) + " works only on numbers, but " + c.fieldRef() + " is "
                    + type.kind().description() + ".");
            return null;
        }
        if (!literalMatches(c.value(), type.kind())) {
            rc.issues().blocker("WF_LITERAL_TYPE", base + "/value", nodeId, transitionId, "The value must be "
                    + type.kind().description() + " to match " + c.fieldRef() + ".");
            return null;
        }
        if (c.value() instanceof Literal.Str s && type.domain() != null && !type.domain().contains(s.string())) {
            String shown = safe(s.string()) ? q(s.string()) : "This value";
            rc.issues().blocker("WF_LITERAL_NOT_IN_DOMAIN", base + "/value", nodeId, transitionId, shown
                    + " is not a value that " + c.fieldRef() + " can have. Possible values: "
                    + String.join(", ", type.domain()) + ".");
        }
        return new Disjointness.Typed(c, type);
    }

    static boolean literalMatches(Literal literal, FieldKind kind) {
        return switch (kind) {
            case STRING -> literal instanceof Literal.Str;
            case BOOLEAN -> literal instanceof Literal.Bool;
            case NUMBER -> literal instanceof Literal.Num;
            case INTEGER -> literal instanceof Literal.Num n && n.integral();
        };
    }

    /**
     * S4 section 4 rule 1: a step output used at node {@code usedAt} must come from another step that dominates it,
     * and must always be present.
     */
    static void availability(RuleContext rc, String fieldRef, Node usedAt, String path, String transitionId) {
        if (usedAt == null || !(rc.types().resolve(fieldRef) instanceof Resolved r) || r.producer() == null) {
            return;
        }
        Node producer = r.producer();
        if (producer == usedAt) {
            rc.issues().blocker("WF_FIELDREF_NOT_GUARANTEED", path, usedAt.id(), transitionId, q(usedAt)
                    + " cannot use its own result.");
            return;
        }
        Graph g = rc.graph();
        if (g.hasDominators() && g.isReachable(usedAt) && !g.dominates(producer, usedAt)) {
            rc.issues().blocker("WF_FIELDREF_NOT_GUARANTEED", path, usedAt.id(), transitionId, q(producer)
                    + " does not always happen before " + q(usedAt) + ", so its result cannot be used there.");
            return;
        }
        if (r.type().mayBeAbsent()) {
            rc.issues().blocker("WF_FIELDREF_NOT_GUARANTEED", path, usedAt.id(), transitionId, "The " + r.key()
                    + " of " + q(producer) + " is only recorded when the approver enters one, so it cannot be"
                    + " used at " + q(usedAt) + ".");
        }
    }
}
