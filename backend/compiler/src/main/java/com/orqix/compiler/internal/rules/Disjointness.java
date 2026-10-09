package com.orqix.compiler.internal.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Enums.Operator;
import com.orqix.compiler.internal.model.Literal;
import com.orqix.compiler.internal.model.WorkflowDefinition.Condition;
import com.orqix.compiler.internal.rules.FieldTypes.FieldType;

/**
 * Set semantics of conditions over one field (S4 section 5.4): numbers are intervals with open/closed
 * {@link BigDecimal} endpoints, strings and booleans are single values.
 */
public final class Disjointness {

    /** A condition whose literal and operator were checked against the field type. */
    public record Typed(Condition condition, FieldType type) {
    }

    /**
     * Real interval; a null endpoint is infinite.
     */
    record Interval(BigDecimal lo, boolean loIncl, BigDecimal hi, boolean hiIncl) {
    }

    private Disjointness() {
    }

    /** @return true when some value satisfies both conditions */
    public static boolean overlap(Typed a, Typed b) {
        if (a.type().kind().numeric()) {
            return intersect(interval(a.condition()), interval(b.condition())) != null;
        }
        return a.condition().value().equals(b.condition().value());
    }

    /**
     * @return true when every value the field can hold satisfies at least one condition (so a default can never
     *         apply). Number domains respect the request form's minimum/maximum and integer fields.
     */
    public static boolean covers(List<Typed> conditions, FieldType type) {
        if (conditions.isEmpty()) {
            return false;
        }
        switch (type.kind()) {
            case STRING -> {
                if (type.domain() == null) {
                    return false;
                }
                Set<String> seen = new HashSet<>();
                conditions.forEach(c -> seen.add(((Literal.Str) c.condition().value()).string()));
                return seen.containsAll(type.domain());
            }
            case BOOLEAN -> {
                Set<Boolean> seen = new HashSet<>();
                conditions.forEach(c -> seen.add(((Literal.Bool) c.condition().value()).bool()));
                return seen.size() == 2;
            }
            default -> {
                Interval domain = new Interval(type.minimum(), true, type.maximum(), true);
                if (domain.lo() != null && domain.hi() != null && domain.lo().compareTo(domain.hi()) > 0) {
                    return true;
                }
                List<Interval> parts = new ArrayList<>();
                for (Typed c : conditions) {
                    Interval clipped = intersect(interval(c.condition()), domain);
                    if (clipped != null) {
                        parts.add(clipped);
                    }
                }
                return type.kind() == FieldKind.INTEGER ? coversIntegers(parts, domain) : coversReals(parts, domain);
            }
        }
    }

    static Interval interval(Condition c) {
        BigDecimal v = ((Literal.Num) c.value()).number();
        return switch (c.operator()) {
            case EQ -> new Interval(v, true, v, true);
            case GTE -> new Interval(v, true, null, false);
            case LT -> new Interval(null, false, v, false);
        };
    }

    /** @return the intersection, or null when it is empty */
    static Interval intersect(Interval a, Interval b) {
        BigDecimal lo;
        boolean loIncl;
        if (a.lo() == null || b.lo() == null) {
            lo = a.lo() == null ? b.lo() : a.lo();
            loIncl = a.lo() == null ? b.loIncl() : a.loIncl();
        } else {
            int c = a.lo().compareTo(b.lo());
            lo = c >= 0 ? a.lo() : b.lo();
            loIncl = c == 0 ? a.loIncl() && b.loIncl() : (c > 0 ? a.loIncl() : b.loIncl());
        }
        BigDecimal hi;
        boolean hiIncl;
        if (a.hi() == null || b.hi() == null) {
            hi = a.hi() == null ? b.hi() : a.hi();
            hiIncl = a.hi() == null ? b.hiIncl() : a.hiIncl();
        } else {
            int c = a.hi().compareTo(b.hi());
            hi = c <= 0 ? a.hi() : b.hi();
            hiIncl = c == 0 ? a.hiIncl() && b.hiIncl() : (c < 0 ? a.hiIncl() : b.hiIncl());
        }
        if (lo != null && hi != null) {
            int c = lo.compareTo(hi);
            if (c > 0 || (c == 0 && !(loIncl && hiIncl))) {
                return null;
            }
        }
        return new Interval(lo, loIncl, hi, hiIncl);
    }

    private static boolean coversReals(List<Interval> parts, Interval domain) {
        if (parts.isEmpty()) {
            return false;
        }
        parts.sort(Comparator.comparing(Interval::lo, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(i -> !i.loIncl()));
        Interval first = parts.get(0);
        boolean startsAtDomain = domain.lo() == null ? first.lo() == null
                : first.lo() != null && first.lo().compareTo(domain.lo()) == 0 && first.loIncl();
        if (!startsAtDomain) {
            return false;
        }
        BigDecimal reach = first.hi();
        boolean reachIncl = first.hiIncl();
        for (Interval iv : parts.subList(1, parts.size())) {
            if (reach == null) {
                return true;
            }
            int c = iv.lo() == null ? -1 : iv.lo().compareTo(reach);
            if (c > 0 || (c == 0 && !reachIncl && !iv.loIncl())) {
                return false;
            }
            if (iv.hi() == null) {
                reach = null;
            } else {
                int h = iv.hi().compareTo(reach);
                if (h > 0) {
                    reach = iv.hi();
                    reachIncl = iv.hiIncl();
                } else if (h == 0) {
                    reachIncl |= iv.hiIncl();
                }
            }
        }
        if (reach == null) {
            return domain.hi() == null;
        }
        if (domain.hi() == null) {
            return false;
        }
        int c = reach.compareTo(domain.hi());
        return c > 0 || (c == 0 && reachIncl);
    }

    private static boolean coversIntegers(List<Interval> parts, Interval domain) {
        BigDecimal[] d = integerRange(domain);
        if (d == null) {
            return true;
        }
        List<BigDecimal[]> ranges = new ArrayList<>();
        for (Interval iv : parts) {
            BigDecimal[] r = integerRange(iv);
            if (r != null) {
                ranges.add(r);
            }
        }
        if (ranges.isEmpty()) {
            return false;
        }
        ranges.sort((x, y) -> x[0] == null ? (y[0] == null ? 0 : -1) : (y[0] == null ? 1 : x[0].compareTo(y[0])));
        BigDecimal[] first = ranges.get(0);
        if (d[0] == null ? first[0] != null : first[0] == null || first[0].compareTo(d[0]) > 0) {
            return false;
        }
        BigDecimal reach = first[1];
        for (BigDecimal[] r : ranges.subList(1, ranges.size())) {
            if (reach == null) {
                return true;
            }
            if (r[0] != null && r[0].compareTo(reach.add(BigDecimal.ONE)) > 0) {
                return false;
            }
            if (r[1] == null || r[1].compareTo(reach) > 0) {
                reach = r[1];
            }
        }
        if (reach == null) {
            return true;
        }
        return d[1] != null && reach.compareTo(d[1]) >= 0;
    }

    /** @return {lo, hi} integer bounds (null = infinite), or null when the interval holds no integer */
    private static BigDecimal[] integerRange(Interval iv) {
        BigDecimal lo = null;
        if (iv.lo() != null) {
            lo = iv.loIncl() ? iv.lo().setScale(0, RoundingMode.CEILING)
                    : iv.lo().setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE);
        }
        BigDecimal hi = null;
        if (iv.hi() != null) {
            hi = iv.hiIncl() ? iv.hi().setScale(0, RoundingMode.FLOOR)
                    : iv.hi().setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE);
        }
        if (lo != null && hi != null && lo.compareTo(hi) > 0) {
            return null;
        }
        return new BigDecimal[] {lo, hi};
    }

    /** @return true for operators that order values (only valid on numbers) */
    public static boolean ordering(Operator op) {
        return op == Operator.GTE || op == Operator.LT;
    }
}
