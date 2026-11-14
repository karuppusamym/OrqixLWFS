package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.List;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.model.Node;

/**
 * AI reviewer of an approval step (docs/build/S152): the skill exists, the pinned version is approved, it is a classify
 * or recommend skill offering the answers {@code approve} and {@code reject}, it passed its evaluation, the autonomy is
 * no higher than the version was promoted to, and the mapped inputs match its input contract. The first version refuses
 * steps where one AI answer cannot stand for the whole step: several approvers, a list of items, and fields the person
 * deciding must fill in.
 */
final class AiReviewerRules {

    private AiReviewerRules() {
    }

    /** @return the pinned version when it exists and is approved, else null */
    static CompileContext.AiSkillVersion pinned(CompileContext context, Node.AiReviewer reviewer) {
        CompileContext.AiSkillInfo skill = context.aiSkills().get(reviewer.skillKey());
        CompileContext.AiSkillVersion v = skill == null ? null : skill.versions().get(reviewer.skillVersion());
        return v != null && v.approved() ? v : null;
    }

    static void check(RuleContext rc) {
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.Approval a && a.aiReviewer() != null && !rc.graph().isShadowed(n)) {
                check(rc, a, a.aiReviewer());
            }
        }
    }

    private static void check(RuleContext rc, Node.Approval a, Node.AiReviewer reviewer) {
        String base = a.configPath("aiReviewer");
        if (a.multiInstance()) {
            unsupported(rc, a, base, "needs several people to approve");
        }
        if (a.listDriven()) {
            unsupported(rc, a, base, "opens one task per item of a list");
        }
        if (a.fieldAccess().containsValue("required")
                || a.capture() != null && !a.capture().required().isEmpty()) {
            unsupported(rc, a, base, "has fields the person deciding must fill in");
        }
        if (reviewer.reviewSamplePercent() != Node.AiTask.DEFAULT_REVIEW_PERCENT
                && !Node.AiTask.AUTO_WITH_REVIEW.equals(reviewer.autonomy())) {
            rc.issues().warning("WF_AI_REVIEW_SAMPLE_UNUSED", base + "/reviewSamplePercent", a.id(), null,
                    "The review sample applies only to autonomy \"autoWithReview\".");
        }
        CompileContext.AiSkillInfo skill = rc.workspace().aiSkills().get(reviewer.skillKey());
        if (skill == null) {
            rc.issues().blocker("WF_AI_REVIEWER_SKILL", base + "/skill/key", a.id(), null, "The AI reviewer of "
                    + q(a) + " uses the AI skill " + q(reviewer.skillKey()) + ", which this workspace does not have.");
            return;
        }
        CompileContext.AiSkillVersion v = pinned(rc.workspace(), reviewer);
        if (v == null) {
            rc.issues().blocker("WF_AI_SKILL_NOT_APPROVED", base + "/skill/version", a.id(), null, "The AI reviewer"
                    + " of " + q(a) + " needs an approved version of " + q(skill.name()) + " (version "
                    + reviewer.skillVersion() + " is not approved). A second person holding ai.approve-skill"
                    + " approves skill versions.");
            return;
        }
        if (!List.of("classify", "recommend").contains(v.mode())) {
            rc.issues().blocker("WF_AI_REVIEWER_SKILL", base + "/skill", a.id(), null, "An AI reviewer must be a"
                    + " classify or recommend skill; version " + v.version() + " of " + q(skill.name()) + " is a "
                    + v.mode() + " skill.");
            return;
        }
        List<String> answers = "classify".equals(v.mode())
                ? v.labels().stream().map(CompileContext.AiLabel::key).toList() : v.outcomes();
        List<String> missing = Node.AiReviewer.DECISIONS.stream().filter(d -> !answers.contains(d)).toList();
        if (!missing.isEmpty()) {
            rc.issues().blocker("WF_AI_REVIEWER_SKILL", base + "/skill", a.id(), null, "An AI reviewer answers"
                    + " approve or reject, but version " + v.version() + " of " + q(skill.name()) + " has no "
                    + ("classify".equals(v.mode()) ? "label " : "outcome ") + String.join(" or ", missing.stream()
                    .map(RuleContext::q).toList()) + ".");
        }
        if (v.passingEvaluationId() == null) {
            rc.issues().blocker("WF_AI_EVALUATION_REQUIRED", base + "/skill", a.id(), null, "Version "
                    + v.version() + " of " + q(skill.name()) + " has no passing evaluation run on the profile "
                    + q(v.modelProfile()) + " in the last 30 days. Run its evaluation suite before it reviews"
                    + " approvals.");
        }
        if (AiTaskRules.rankOf(reviewer.autonomy()) > AiTaskRules.rankOf(v.autonomyCeiling())) {
            rc.issues().blocker("WF_AI_AUTONOMY_NOT_PROMOTED", base + "/autonomy", a.id(), null, "Version "
                    + v.version() + " of " + q(skill.name()) + " is promoted up to \"" + v.autonomyCeiling()
                    + "\", not \"" + reviewer.autonomy() + "\". Promotion needs shadow evidence and an approved"
                    + " skill change.");
        }
        if (Node.AiTask.AUTO.equals(reviewer.autonomy()) && "classify".equals(v.mode())
                && v.labels().stream().filter(l -> Node.AiReviewer.DECISIONS.contains(l.key()))
                        .noneMatch(l -> "low".equals(l.risk()) && !l.adverse())) {
            rc.issues().warning("WF_AI_AUTO_NO_LOW_RISK", base + "/autonomy", a.id(), null, "Neither approve nor"
                    + " reject is a low-risk label of " + q(skill.name()) + ", so a person will decide every task;"
                    + " autonomy \"auto\" applies only to answers the workspace policy marks low-risk.");
        }
        AiTaskRules.inputs(rc, a, base + "/inputs", base + "/redaction", reviewer.inputs(), reviewer.redaction(), v,
                skill);
    }

    private static void unsupported(RuleContext rc, Node.Approval a, String path, String what) {
        rc.issues().blocker("WF_AI_REVIEWER_UNSUPPORTED", path, a.id(), null, q(a) + " " + what + ", so an AI"
                + " reviewer cannot decide it on its own. Remove the AI reviewer or that setting.");
    }
}
