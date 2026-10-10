package com.orqix.migration.dmn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of values a rule's input entry accepts, as exact set algebra: numbers are disjoint intervals (integers are
 * normalised to closed integer bounds), text and yes/no values are finite or co-finite sets. Used to prove that a DMN
 * table converts to the canonical single-comparison rows without changing which rule fires. Immutable.
 */
final class Region {

    /** A real or integer interval; a null bound is infinite. */
    record Interval(BigDecimal lo, boolean loIncl, BigDecimal hi, boolean hiIncl) {

        boolean isPoint() {
            return lo != null && hi != null && loIncl && hiIncl && lo.compareTo(hi) == 0;
        }
    }

    private final boolean numeric;
    private final boolean integer;
    private final List<Interval> intervals;
    /** Values (or, when {@code complement}, the excluded values); insertion ordered for deterministic output. */
    private final Set<Object> values;
    private final boolean complement;
    /** Finite universe of text/yes-no values (an enumerated input or yes/no), or null when unbounded. */
    private final Set<Object> universe;

    private Region(boolean numeric, boolean integer, List<Interval> intervals, Set<Object> values, boolean complement,
            Set<Object> universe) {
        this.numeric = numeric;
        this.integer = integer;
        this.intervals = intervals;
        this.values = values;
        this.complement = complement;
        this.universe = universe;
    }

    // ------------------------------------------------------------------------------------------------ factories

    static Region numbers(boolean integer, List<Interval> intervals) {
        return new Region(true, integer, normalise(integer, intervals), Set.of(), false, null);
    }

    static Region allNumbers(boolean integer) {
        return numbers(integer, List.of(new Interval(null, false, null, false)));
    }

    static Region noNumbers(boolean integer) {
        return numbers(integer, List.of());
    }

    static Region values(Set<Object> values, Set<Object> universe) {
        return new Region(false, false, List.of(), new LinkedHashSet<>(values), false, universe).canonical();
    }

    static Region allValues(Set<Object> universe) {
        return new Region(false, false, List.of(), new LinkedHashSet<>(), true, universe).canonical();
    }

    /** Same kind of region with nothing in it. */
    Region none() {
        return numeric ? noNumbers(integer) : values(Set.of(), universe);
    }

    /** Same kind of region with every value in it. */
    Region everything() {
        return numeric ? allNumbers(integer) : allValues(universe);
    }

    // ------------------------------------------------------------------------------------------------ queries

    boolean numeric() {
        return numeric;
    }

    boolean isInteger() {
        return integer;
    }

    List<Interval> intervals() {
        return intervals;
    }

    boolean isEmpty() {
        if (numeric) {
            return intervals.isEmpty();
        }
        return !complement && values.isEmpty();
    }

    /** True when every possible value is in the region. */
    boolean isEverything() {
        if (numeric) {
            return intervals.size() == 1 && intervals.get(0).lo() == null && intervals.get(0).hi() == null;
        }
        if (universe != null) {
            return !complement && values.containsAll(universe);
        }
        return complement && values.isEmpty();
    }

    /** Finite members of a value region (not a complement), in insertion order. */
    List<Object> members() {
        return new ArrayList<>(values);
    }

    boolean isComplement() {
        return complement;
    }

    /** The excluded values of a co-finite value region. */
    List<Object> excluded() {
        return new ArrayList<>(values);
    }

    boolean contains(Object value) {
        if (numeric) {
            BigDecimal n = (BigDecimal) value;
            for (Interval iv : intervals) {
                boolean aboveLo = iv.lo() == null || n.compareTo(iv.lo()) > 0 || n.compareTo(iv.lo()) == 0 && iv.loIncl();
                boolean belowHi = iv.hi() == null || n.compareTo(iv.hi()) < 0 || n.compareTo(iv.hi()) == 0 && iv.hiIncl();
                if (aboveLo && belowHi) {
                    return true;
                }
            }
            return false;
        }
        return complement != values.contains(value);
    }

    // ------------------------------------------------------------------------------------------------ algebra

    Region union(Region other) {
        if (numeric) {
            List<Interval> all = new ArrayList<>(intervals);
            all.addAll(other.intervals);
            return numbers(integer, all);
        }
        if (!complement && !other.complement) {
            Set<Object> merged = new LinkedHashSet<>(values);
            merged.addAll(other.values);
            return new Region(false, false, List.of(), merged, false, universe).canonical();
        }
        if (complement && other.complement) {
            Set<Object> common = new LinkedHashSet<>(values);
            common.retainAll(other.values);
            return new Region(false, false, List.of(), common, true, universe).canonical();
        }
        Region excluding = complement ? this : other;
        Region finite = complement ? other : this;
        Set<Object> rest = new LinkedHashSet<>(excluding.values);
        rest.removeAll(finite.values);
        return new Region(false, false, List.of(), rest, true, universe).canonical();
    }

    Region intersect(Region other) {
        if (numeric) {
            List<Interval> out = new ArrayList<>();
            for (Interval a : intervals) {
                for (Interval b : other.intervals) {
                    Interval both = intersect(a, b);
                    if (both != null) {
                        out.add(both);
                    }
                }
            }
            return numbers(integer, out);
        }
        if (!complement && !other.complement) {
            Set<Object> common = new LinkedHashSet<>(values);
            common.retainAll(other.values);
            return new Region(false, false, List.of(), common, false, universe).canonical();
        }
        if (complement && other.complement) {
            Set<Object> both = new LinkedHashSet<>(values);
            both.addAll(other.values);
            return new Region(false, false, List.of(), both, true, universe).canonical();
        }
        Region finite = complement ? other : this;
        Region excluding = complement ? this : other;
        Set<Object> rest = new LinkedHashSet<>(finite.values);
        rest.removeAll(excluding.values);
        return new Region(false, false, List.of(), rest, false, universe).canonical();
    }

    Region negate() {
        if (numeric) {
            List<Interval> gaps = new ArrayList<>();
            BigDecimal reach = null;
            boolean reachIncl = true; // "nothing covered yet": the first gap starts at minus infinity, inclusive
            boolean first = true;
            for (Interval iv : intervals) {
                if (first) {
                    if (iv.lo() != null) {
                        gaps.add(new Interval(null, false, iv.lo(), !iv.loIncl()));
                    }
                    first = false;
                } else {
                    gaps.add(new Interval(reach, !reachIncl, iv.lo(), !iv.loIncl()));
                }
                reach = iv.hi();
                reachIncl = iv.hiIncl();
                if (reach == null) {
                    return numbers(integer, gaps);
                }
            }
            if (first) {
                return allNumbers(integer);
            }
            gaps.add(new Interval(reach, !reachIncl, null, false));
            return numbers(integer, gaps);
        }
        return new Region(false, false, List.of(), values, !complement, universe).canonical();
    }

    Region minus(Region other) {
        return intersect(other.negate());
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** With a finite universe every region is stored as an explicit member set. */
    private Region canonical() {
        if (universe == null) {
            return this;
        }
        Set<Object> members = new LinkedHashSet<>();
        for (Object candidate : universe) {
            if (complement != values.contains(candidate)) {
                members.add(candidate);
            }
        }
        return new Region(false, false, List.of(), members, false, universe);
    }

    private static List<Interval> normalise(boolean integer, List<Interval> input) {
        List<Interval> items = new ArrayList<>();
        for (Interval iv : input) {
            Interval n = integer ? toIntegers(iv) : iv;
            if (n != null && !emptyReal(n)) {
                items.add(n);
            }
        }
        items.sort(Comparator.comparing(Interval::lo, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(iv -> !iv.loIncl()));
        List<Interval> merged = new ArrayList<>();
        for (Interval iv : items) {
            if (merged.isEmpty()) {
                merged.add(iv);
                continue;
            }
            Interval last = merged.get(merged.size() - 1);
            if (touches(integer, last, iv)) {
                merged.set(merged.size() - 1, join(last, iv));
            } else {
                merged.add(iv);
            }
        }
        return List.copyOf(merged);
    }

    private static boolean emptyReal(Interval iv) {
        if (iv.lo() == null || iv.hi() == null) {
            return false;
        }
        int c = iv.lo().compareTo(iv.hi());
        return c > 0 || c == 0 && !(iv.loIncl() && iv.hiIncl());
    }

    /** Closed integer bounds of an interval, or null when it holds no integer. */
    private static Interval toIntegers(Interval iv) {
        BigDecimal lo = iv.lo() == null ? null : iv.loIncl() ? iv.lo().setScale(0, RoundingMode.CEILING)
                : iv.lo().setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE);
        BigDecimal hi = iv.hi() == null ? null : iv.hiIncl() ? iv.hi().setScale(0, RoundingMode.FLOOR)
                : iv.hi().setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE);
        if (lo != null && hi != null && lo.compareTo(hi) > 0) {
            return null;
        }
        return new Interval(lo, lo != null, hi, hi != null);
    }

    /** True when {@code b} (which starts at or after {@code a}) overlaps or directly continues {@code a}. */
    private static boolean touches(boolean integer, Interval a, Interval b) {
        if (a.hi() == null) {
            return true;
        }
        if (b.lo() == null) {
            return true;
        }
        int c = b.lo().compareTo(a.hi());
        if (integer) {
            return b.lo().subtract(a.hi()).compareTo(BigDecimal.ONE) <= 0;
        }
        return c < 0 || c == 0 && (a.hiIncl() || b.loIncl());
    }

    private static Interval join(Interval a, Interval b) {
        BigDecimal hi;
        boolean hiIncl;
        if (a.hi() == null || b.hi() == null) {
            hi = null;
            hiIncl = false;
        } else {
            int c = a.hi().compareTo(b.hi());
            hi = c >= 0 ? a.hi() : b.hi();
            hiIncl = c == 0 ? a.hiIncl() || b.hiIncl() : c > 0 ? a.hiIncl() : b.hiIncl();
        }
        return new Interval(a.lo(), a.loIncl(), hi, hiIncl);
    }

    private static Interval intersect(Interval a, Interval b) {
        BigDecimal lo;
        boolean loIncl;
        if (a.lo() == null || b.lo() == null) {
            lo = a.lo() == null ? b.lo() : a.lo();
            loIncl = a.lo() == null ? b.loIncl() : a.loIncl();
        } else {
            int c = a.lo().compareTo(b.lo());
            lo = c >= 0 ? a.lo() : b.lo();
            loIncl = c == 0 ? a.loIncl() && b.loIncl() : c > 0 ? a.loIncl() : b.loIncl();
        }
        BigDecimal hi;
        boolean hiIncl;
        if (a.hi() == null || b.hi() == null) {
            hi = a.hi() == null ? b.hi() : a.hi();
            hiIncl = a.hi() == null ? b.hiIncl() : a.hiIncl();
        } else {
            int c = a.hi().compareTo(b.hi());
            hi = c <= 0 ? a.hi() : b.hi();
            hiIncl = c == 0 ? a.hiIncl() && b.hiIncl() : c < 0 ? a.hiIncl() : b.hiIncl();
        }
        Interval result = new Interval(lo, loIncl, hi, hiIncl);
        return emptyReal(result) ? null : result;
    }
}
