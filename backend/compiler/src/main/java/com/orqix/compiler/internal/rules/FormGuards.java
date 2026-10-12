package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.util.ArrayList;
import java.util.List;

import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.model.WorkflowDefinition.InputUi;
import com.orqix.compiler.internal.model.WorkflowDefinition.Transition;

/**
 * S35: a request field that is asked for only in some cases ({@code visibleWhen}, a section condition or
 * {@code requiredWhen}) may be missing from the request. A step may use it only when every path to the step passes a
 * condition route that makes the field present: the route's condition contains the field's own condition (the same
 * comparisons, as an all-group or a single comparison), and the field is required whenever it is visible.
 */
final class FormGuards {

    private FormGuards() {
    }

    static void validateUi(RuleContext rc) {
        InputUi ui=rc.definition().inputUi();if(ui==null)return;
        ui.visibleWhen().forEach((key,c)->uiCondition(rc,key,c,"/inputUi/visibleWhen/"+key));
        ui.requiredWhen().forEach((key,c)->uiCondition(rc,key,c,"/inputUi/requiredWhen/"+key));
        var seen=new java.util.HashSet<String>();
        for(int i=0;i<ui.sections().size();i++) {
            var section=ui.sections().get(i);String path="/inputUi/layout/sections/"+i;
            for(String key:section.fields()) {
                if(!seen.add(key))rc.issues().blocker("WF_FORM_INVALID",path+"/fields",null,null,"A question can appear in only one section.");
                if(!rc.definition().inputSchema().properties().containsKey(key))rc.issues().blocker("WF_FORM_INVALID",path+"/fields",null,null,"Section question does not exist in the request form.");
            }
            if(section.condition()!=null)uiCondition(rc,null,section.condition(),path+"/visibleWhen");
        }
    }
    private static void uiCondition(RuleContext rc,String key,Condition condition,String path) {
        if(key!=null && !rc.definition().inputSchema().properties().containsKey(key))
            rc.issues().blocker("WF_FORM_INVALID",path,null,null,"Conditional question does not exist in the request form.");
        List<Condition> terms=condition.compound()?condition.terms():List.of(condition);
        for(Condition term:terms) {
            String ref=term.fieldRef();
            if(!ref.matches("^/input/[a-z][A-Za-z0-9]{0,63}$") || rc.types().conditional(ref.substring(7))) {
                rc.issues().blocker("WF_FORM_INVALID",path,null,null,"Form conditions require an unconditional scalar request question; derived and step values are unsupported.");
                continue;
            }
            Conditions.check(rc,term,path,null,null);
        }
    }

    /** Reports WF_FIELD_MAYBE_ABSENT when {@code fieldRef} is a conditional request field used without a guard. */
    static void check(RuleContext rc, String fieldRef, Node usedAt, String path, String transitionId) {
        if (!fieldRef.startsWith("/input/") || fieldRef.indexOf('/', "/input/".length()) >= 0) {
            return;
        }
        String key = fieldRef.substring("/input/".length());
        InputUi ui = rc.definition().inputUi();
        if (ui == null || !rc.types().conditional(key) || !rc.graph().isReachable(usedAt)) {
            return;
        }
        List<Condition> known = known(rc, usedAt);
        if (guarded(rc, ui, key, known)) {
            return;
        }
        String title = rc.definition().inputSchema().properties().get(key) == null ? key
                : rc.definition().inputSchema().properties().get(key).title();
        rc.issues().blocker("WF_FIELD_MAYBE_ABSENT", path, usedAt.id(), transitionId, q(title) + " is asked for only"
                + " in some cases, so it may be missing when " + q(usedAt) + " needs it. Use it only after a path"
                + " that checks the same condition as the form does, or ask for it in every request.");
    }

    /** The comparisons that hold on every path to {@code usedAt}: conditions of sole entries of its dominators. */
    private static List<Condition> known(RuleContext rc, Node usedAt) {
        List<Condition> known = new ArrayList<>();
        Graph g = rc.graph();
        if (!g.hasDominators()) {
            return known;
        }
        for (Node x : g.nodes()) {
            if (!g.dominates(x, usedAt)) {
                continue;
            }
            List<Transition> in = g.incoming(x);
            if (in.size() == 1 && in.get(0).condition() != null) {
                Condition c = in.get(0).condition();
                if (!c.compound()) {
                    known.add(c);
                } else if ("all".equals(c.group())) {
                    known.addAll(c.terms());
                }
            }
        }
        return known;
    }

    private static boolean guarded(RuleContext rc, InputUi ui, String key, List<Condition> known) {
        Condition own = ui.visibleWhen().get(key);
        if (own != null && !implied(own, known)) {
            return false;
        }
        for (InputUi.Section s : ui.sections()) {
            if (s.condition() != null && s.fields().contains(key) && !implied(s.condition(), known)) {
                return false;
            }
        }
        if (rc.definition().inputSchema().required().contains(key)) {
            return true;
        }
        Condition required = ui.requiredWhen().get(key);
        return required != null && implied(required, known);
    }

    /** True when the known comparisons make the condition hold (flat groups only; no arithmetic reasoning). */
    private static boolean implied(Condition c, List<Condition> known) {
        if (!c.compound()) {
            return known.stream().anyMatch(k -> same(k, c));
        }
        return "all".equals(c.group()) ? c.terms().stream().allMatch(t -> implied(t, known))
                : c.terms().stream().anyMatch(t -> implied(t, known));
    }

    private static boolean same(Condition a, Condition b) {
        Literal x = a.value();
        Literal y = b.value();
        return a.fieldRef().equals(b.fieldRef()) && a.operator() == b.operator() && x.equals(y);
    }
}
