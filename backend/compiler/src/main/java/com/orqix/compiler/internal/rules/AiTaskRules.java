package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Route;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/**
 * AI task rules (CG-A01/A02, docs/build/S134 section 3): the skill exists and the pinned version is approved, its mode
 * matches, the publish gate (a passing evaluation run on the pinned profile within 30 days) holds, the autonomy is
 * allowed for the mode and no higher than the version has been promoted to, the mapped inputs match the skill's input
 * contract, and the paths match the mode: one per label plus {@code lowConfidence} and {@code aiUnavailable} for
 * classify; {@code next}, {@code needsReview} and {@code aiUnavailable} for extract; {@code next} and
 * {@code aiUnavailable} otherwise; only {@code next} in shadow mode, which never routes.
 */
public final class AiTaskRules {

    /** Outputs present on every path out of an AI task. */
    public static final Set<String> ALWAYS = Set.of("status", "skillVersion");
    /** Values of {@code status}. */
    public static final Set<String> STATUSES = Set.of("decided", "lowConfidence", "needsReview", "unavailable",
            "shadow");
    private static final List<String> AUTONOMY_ORDER = List.of("shadow", "suggest", "autoWithReview", "auto");

    private AiTaskRules() {
    }

    /** @return the skill version a publish pins: the step's own pin when approved, else the latest approved, or null */
    public static CompileContext.AiSkillVersion pinned(CompileContext context, Node.AiTask ai) {
        CompileContext.AiSkillInfo skill = context.aiSkills().get(ai.skillRef());
        if (skill == null) {
            return null;
        }
        if (ai.skillVersion() != null) {
            CompileContext.AiSkillVersion v = skill.versions().get(ai.skillVersion());
            return v != null && v.approved() ? v : null;
        }
        return skill.latestApproved();
    }

    /** The routes a path out of this step may take (GraphRules reports the others). */
    static Set<Route> allowed(Node.AiTask ai) {
        if (ai.shadow()) {
            return Set.of(Route.NEXT);
        }
        return switch (ai.mode()) {
            case "classify" -> Set.of(Route.AI_LABEL, Route.LOW_CONFIDENCE, Route.NEEDS_REVIEW, Route.AI_UNAVAILABLE);
            case "extract" -> Set.of(Route.NEXT, Route.NEEDS_REVIEW, Route.AI_UNAVAILABLE);
            default -> Set.of(Route.NEXT, Route.AI_UNAVAILABLE);
        };
    }

    static void check(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.AiTask ai && !rc.graph().isShadowed(n)) {
                check(rc, ai);
            }
        }
    }

    private static void check(RuleContext rc, Node.AiTask ai) {
        routes(rc, ai);
        if (!ai.shadow() && !List.of("classify", "extract").contains(ai.mode()) && ai.routes()) {
            rc.issues().blocker("WF_AI_AUTONOMY_MODE", ai.configPath("autonomy"), ai.id(), null, q(ai) + " is a "
                    + ai.mode() + " task, which a person or a decision table always acts on. Use \"suggest\" or"
                    + " \"shadow\".");
        }
        if (ai.reviewSamplePercent() != Node.AiTask.DEFAULT_REVIEW_PERCENT
                && !Node.AiTask.AUTO_WITH_REVIEW.equals(ai.autonomy())) {
            rc.issues().warning("WF_AI_REVIEW_SAMPLE_UNUSED", ai.configPath("reviewSamplePercent"), ai.id(), null,
                    "The review sample applies only to autonomy \"autoWithReview\".");
        }
        CompileContext.AiSkillInfo skill = rc.workspace().aiSkills().get(ai.skillRef());
        if (skill == null) {
            rc.issues().blocker("WF_AI_SKILL_UNKNOWN", ai.configPath("skillRef"), ai.id(), null, q(ai)
                    + " uses the AI skill " + q(ai.skillRef()) + ", which this workspace does not have.");
            return;
        }
        CompileContext.AiSkillVersion v = pinned(rc.workspace(), ai);
        if (v == null) {
            rc.issues().blocker("WF_AI_SKILL_NOT_APPROVED", ai.configPath(ai.skillVersion() == null ? "skillRef"
                    : "skillVersion"), ai.id(), null, q(ai) + " needs an approved version of the AI skill "
                    + q(skill.name()) + (ai.skillVersion() == null ? "" : " (version " + ai.skillVersion()
                    + " is not approved)") + ". A second person holding ai.approve-skill approves skill versions.");
            return;
        }
        if (!v.mode().equals(ai.mode())) {
            rc.issues().blocker("WF_AI_SKILL_MODE", ai.configPath("mode"), ai.id(), null, q(ai) + " is set to "
                    + ai.mode() + ", but version " + v.version() + " of " + q(skill.name()) + " is a " + v.mode()
                    + " skill.");
        }
        if (!ai.shadow() && v.passingEvaluationId() == null) {
            rc.issues().blocker("WF_AI_EVALUATION_REQUIRED", ai.configPath("skillRef"), ai.id(), null, "Version "
                    + v.version() + " of " + q(skill.name()) + " has no passing evaluation run on the profile "
                    + q(v.modelProfile()) + " in the last 30 days. Run its evaluation suite, or use autonomy"
                    + " \"shadow\" to collect evidence first.");
        }
        if (rank(ai.autonomy()) > rank(v.autonomyCeiling())) {
            rc.issues().blocker("WF_AI_AUTONOMY_NOT_PROMOTED", ai.configPath("autonomy"), ai.id(), null, "Version "
                    + v.version() + " of " + q(skill.name()) + " is promoted up to \"" + v.autonomyCeiling()
                    + "\", not \"" + ai.autonomy() + "\". Promotion needs shadow evidence and an approved skill change.");
        }
        if (Node.AiTask.AUTO.equals(ai.autonomy()) && "classify".equals(v.mode())
                && v.labels().stream().noneMatch(l -> "low".equals(l.risk()) && !l.adverse())) {
            rc.issues().warning("WF_AI_AUTO_NO_LOW_RISK", ai.configPath("autonomy"), ai.id(), null, "None of the"
                    + " labels of " + q(skill.name()) + " is low-risk, so a person will decide every case; autonomy"
                    + " \"auto\" applies only to labels the workspace policy marks low-risk.");
        }
        inputs(rc, ai, v, skill);
        if ("classify".equals(v.mode()) && !ai.shadow()) {
            labels(rc, ai, v);
        }
    }

    private static void inputs(RuleContext rc, Node.AiTask ai, CompileContext.AiSkillVersion v,
            CompileContext.AiSkillInfo skill) {
        for (String required : v.requiredInputs()) {
            if (!ai.inputMapping().containsKey(required)) {
                rc.issues().blocker("WF_AI_INPUT_MISSING", ai.configPath("inputMapping"), ai.id(), null, q(ai)
                        + " must map the input " + q(required) + " of " + q(skill.name()) + ".");
            }
        }
        ai.inputMapping().forEach((name, ref) -> {
            String path = ai.configPath("inputMapping") + "/" + name;
            String type = v.inputs().get(name);
            if (type == null) {
                rc.issues().blocker("WF_AI_INPUT_UNKNOWN", path, ai.id(), null, q(skill.name())
                        + " has no input " + q(name) + ". Its inputs are: " + String.join(", ", new java.util.TreeSet<>(
                        v.inputs().keySet())) + ".");
                return;
            }
            var resolved = rc.types().resolve(ref);
            if (resolved instanceof Unknown u) {
                rc.issues().blocker("WF_AI_INPUT_UNKNOWN", path, ai.id(), null, u.message());
                return;
            }
            if (resolved instanceof Resolved r) {
                if (!compatible(type, r.type().kind())) {
                    rc.issues().blocker("WF_AI_INPUT_TYPE", path, ai.id(), null, "The input " + q(name) + " of "
                            + q(skill.name()) + " is " + FieldKind.fromJson(type, null).description()
                            + ", but the mapped value is " + r.type().kind().description() + ".");
                }
                if (r.type().mayBeAbsent() && v.requiredInputs().contains(name)) {
                    rc.issues().blocker("WF_AI_INPUT_OPTIONAL", path, ai.id(), null, "The input " + q(name)
                            + " is required by the skill, but this value may be missing when the step starts.");
                }
            }
            Conditions.availability(rc, ref, ai, path, null);
        });
        for (String name : ai.redaction().keySet()) {
            if (!ai.inputMapping().containsKey(name)) {
                rc.issues().blocker("WF_AI_REDACTION_UNKNOWN", ai.configPath("redaction") + "/" + name, ai.id(),
                        null, "Redaction is set for " + q(name) + ", which " + q(ai) + " does not send.");
            }
        }
    }

    private static boolean compatible(String skillType, FieldKind actual) {
        return switch (skillType) {
            case "string" -> actual == FieldKind.STRING || actual == FieldKind.DATE;
            case "number" -> actual.numeric();
            case "integer" -> actual == FieldKind.INTEGER;
            case "boolean" -> actual == FieldKind.BOOLEAN;
            case "date" -> actual == FieldKind.DATE;
            default -> false;
        };
    }

    private static void labels(RuleContext rc, Node.AiTask ai, CompileContext.AiSkillVersion v) {
        Set<String> declared = new java.util.LinkedHashSet<>();
        v.labels().forEach(l -> declared.add(l.key()));
        Map<String, Integer> seen = new HashMap<>();
        for (Transition t : rc.leaving(ai)) {
            if (t.on() != Route.AI_LABEL) {
                continue;
            }
            if (!declared.contains(t.aiLabel())) {
                rc.issues().blocker("WF_AI_LABEL_UNKNOWN", t.path() + "/aiLabel", ai.id(), t.id(), "The skill has no"
                        + " label " + q(t.aiLabel()) + ". Its labels are: " + String.join(", ", declared) + ".");
            } else {
                seen.merge(t.aiLabel(), 1, Integer::sum);
            }
        }
        if (!ai.routes()) {
            return;
        }
        for (String label : declared) {
            int count = seen.getOrDefault(label, 0);
            if (count != 1) {
                rc.issues().blocker("WF_AI_LABEL_ROUTE", ai.path(), ai.id(), null, q(ai) + (count == 0
                        ? " needs a path for the label " : " can have only one path for the label ") + q(label) + ".");
            }
        }
    }

    private static void routes(RuleContext rc, Node.AiTask ai) {
        Map<Route, Integer> counts = new HashMap<>();
        for (Transition t : rc.leaving(ai)) {
            counts.merge(t.on(), 1, Integer::sum);
        }
        if (ai.shadow()) {
            exactlyOne(rc, ai, counts, Route.NEXT, "a next step (shadow mode never changes the path)");
            return;
        }
        exactlyOne(rc, ai, counts, Route.AI_UNAVAILABLE, "a path for when AI is unavailable (a person must be able"
                + " to take over)");
        switch (ai.mode()) {
            case "classify" -> {
                exactlyOne(rc, ai, counts, Route.LOW_CONFIDENCE, "a path for when the confidence is too low");
                atMostOne(rc, ai, counts, Route.NEEDS_REVIEW, "path for when a person must review");
                if (!ai.routes() && counts.getOrDefault(Route.AI_LABEL, 0) > 0) {
                    for (Transition t : rc.leaving(ai)) {
                        if (t.on() == Route.AI_LABEL) {
                            rc.issues().blocker("WF_AI_LABEL_ROUTE_SUGGEST", t.path() + "/on", ai.id(), t.id(), "With"
                                    + " autonomy \"suggest\" a person decides, so " + q(ai) + " never takes a label"
                                    + " path. It continues on lowConfidence (or needsReview) with the suggestion.");
                        }
                    }
                }
            }
            case "extract" -> {
                exactlyOne(rc, ai, counts, Route.NEXT, "a next step");
                exactlyOne(rc, ai, counts, Route.NEEDS_REVIEW, "a path for when a person must check the values");
                if (!ai.routes()) {
                    rc.issues().warning("WF_AI_SUGGEST_ALWAYS_REVIEWS", ai.path(), ai.id(), null, "With autonomy"
                            + " \"suggest\", " + q(ai) + " always continues on needsReview.");
                }
            }
            default -> {
                exactlyOne(rc, ai, counts, Route.NEXT, "a next step");
                if ("recommend".equals(ai.mode())) {
                    for (Transition t : rc.leaving(ai)) {
                        Node to = rc.graph().node(t.to());
                        if (t.on() == Route.NEXT && to != null && !(to instanceof Node.Approval)) {
                            rc.issues().warning("WF_AI_RECOMMEND_TARGET", t.path() + "/to", ai.id(), t.id(), "A"
                                    + " recommendation is shown to the person who decides; " + q(ai)
                                    + " usually continues to an approval.");
                        }
                    }
                }
            }
        }
        BigDecimal min = ai.minConfidence();
        if (min.compareTo(Node.AiTask.DEFAULT_MIN_CONFIDENCE) != 0 && !List.of("classify", "extract").contains(ai.mode())) {
            rc.issues().warning("WF_AI_THRESHOLD_UNUSED", ai.configPath("minConfidence"), ai.id(), null,
                    "The confidence threshold applies to classify and extract tasks only.");
        }
    }

    private static void exactlyOne(RuleContext rc, Node.AiTask ai, Map<Route, Integer> counts, Route route,
            String what) {
        int count = counts.getOrDefault(route, 0);
        if (count == 0) {
            rc.issues().blocker("WF_AI_ROUTES", ai.path(), ai.id(), null, q(ai) + " needs " + what + ".");
        } else if (count > 1) {
            rc.issues().blocker("WF_AI_ROUTES", ai.path(), ai.id(), null, q(ai) + " can have only one "
                    + route.json() + " path.");
        }
    }

    private static void atMostOne(RuleContext rc, Node.AiTask ai, Map<Route, Integer> counts, Route route,
            String what) {
        if (counts.getOrDefault(route, 0) > 1) {
            rc.issues().blocker("WF_AI_ROUTES", ai.path(), ai.id(), null, q(ai) + " can have only one " + what + ".");
        }
    }

    private static int rank(String autonomy) {
        int i = AUTONOMY_ORDER.indexOf(autonomy == null ? "suggest" : autonomy);
        return i < 0 ? 1 : i;
    }

    /** True when the AI result key exists only when AI answered (absent on the aiUnavailable path). */
    static boolean answerOutput(String key) {
        return !ALWAYS.contains(key);
    }

    /**
     * @return true when {@code target} can be reached from a start while the AI task only continues on
     *         {@code aiUnavailable}
     */
    static boolean reachableThroughUnavailable(RuleContext rc, Node.AiTask producer, Node target) {
        Graph g = rc.graph();
        BitSet seen = new BitSet();
        Deque<Node> work = new ArrayDeque<>();
        for (Node s : g.starts()) {
            seen.set(s.index());
            work.add(s);
        }
        while (!work.isEmpty()) {
            Node n = work.poll();
            if (n == target) {
                return true;
            }
            for (Transition t : g.outgoing(n)) {
                if (n == producer && t.on() != Route.AI_UNAVAILABLE) {
                    continue;
                }
                Node to = g.node(t.to());
                if (to != null && !seen.get(to.index())) {
                    seen.set(to.index());
                    work.add(to);
                }
            }
        }
        return false;
    }
}
