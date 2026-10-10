package com.orqix.compiler.internal.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.orqix.compiler.ConditionMatcher;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.rules.Disjointness.Typed;

/** Bounded satisfiability of flat AND/OR groups; at most 64 pairs of conjunctions per branch comparison. */
final class LogicalConditions {
    private LogicalConditions() { }

    static List<Typed> check(RuleContext rc, Condition c, String base, String nodeId, String transitionId) {
        List<Typed> typed = new ArrayList<>();
        List<Condition> terms = c.compound() ? c.terms() : List.of(c);
        boolean valid = true;
        for (int i = 0; i < terms.size(); i++) {
            Condition term = terms.get(i);
            String path = c.compound() ? base + "/" + c.group() + "/" + i : base;
            Typed value = Conditions.check(rc, term, path, nodeId, transitionId);
            Conditions.availability(rc, term.fieldRef(), nodeId == null ? null : rc.graph().node(nodeId), path + "/fieldRef", transitionId);
            if (value == null) valid = false; else typed.add(value);
        }
        return valid ? List.copyOf(typed) : null;
    }

    static boolean overlap(Condition a, List<Typed> at, Condition b, List<Typed> bt) {
        for (List<Typed> x : clauses(a, at)) for (List<Typed> y : clauses(b, bt)) {
            List<Typed> both = new ArrayList<>(x); both.addAll(y);
            if (satisfiable(both)) return true;
        }
        return false;
    }

    private static List<List<Typed>> clauses(Condition c, List<Typed> typed) {
        return "any".equals(c.group()) ? typed.stream().map(List::of).toList() : List.of(typed);
    }

    private static boolean satisfiable(List<Typed> terms) {
        Map<String, List<Typed>> fields = new LinkedHashMap<>();
        for (Typed term : terms) fields.computeIfAbsent(term.condition().fieldRef(), key -> new ArrayList<>()).add(term);
        for (List<Typed> field : fields.values()) if (!fieldSatisfiable(field)) return false;
        return true;
    }

    private static boolean fieldSatisfiable(List<Typed> terms) {
        var type = terms.get(0).type();
        if (type.kind().numeric()) {
            List<Disjointness.Interval> candidates = List.of(new Disjointness.Interval(type.minimum(), true, type.maximum(), true));
            for (Typed term : terms) {
                List<Disjointness.Interval> next = new ArrayList<>();
                for (var current : candidates) for (var part : Disjointness.intervals(term.condition())) {
                    var intersection = Disjointness.intersect(current, part);
                    if (intersection != null) next.add(intersection);
                }
                candidates = next;
            }
            return candidates.stream().anyMatch(iv -> type.kind() != FieldKind.INTEGER || integerExists(iv));
        }
        Set<?> domain = type.kind() == FieldKind.BOOLEAN ? Set.of(true, false) : type.domain();
        if (domain != null) return domain.stream().anyMatch(value -> acceptsAll(terms, value));
        var equal = terms.stream().filter(t -> t.condition().operator() == Operator.EQ).findFirst();
        return equal.isEmpty() || acceptsAll(terms, equal.get().condition().value().value());
    }

    private static boolean acceptsAll(List<Typed> terms, Object actual) {
        return terms.stream().allMatch(t -> ConditionMatcher.matches(t.condition().operator().json(), actual, t.condition().value().value()));
    }

    private static boolean integerExists(Disjointness.Interval iv) {
        BigDecimal lo = iv.lo() == null ? null : iv.loIncl() ? iv.lo().setScale(0, RoundingMode.CEILING) : iv.lo().setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE);
        BigDecimal hi = iv.hi() == null ? null : iv.hiIncl() ? iv.hi().setScale(0, RoundingMode.FLOOR) : iv.hi().setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE);
        return lo == null || hi == null || lo.compareTo(hi) <= 0;
    }
}
