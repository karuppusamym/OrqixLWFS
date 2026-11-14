package com.orqix.compiler.expr;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.orqix.compiler.BusinessCalendar;
import com.orqix.compiler.expr.ExprType.ListOf;
import com.orqix.compiler.expr.ExprType.Row;

import tools.jackson.databind.JsonNode;

/**
 * Evaluates a checked Orqix expression (docs/build/S137). Total and side-effect free: no loops, recursion or I/O; every
 * node evaluation costs one unit and a run stops with {@code EXPR_COST_LIMIT} after {@link #MAX_COST}. Numbers are
 * decimals: every arithmetic result is rounded to 34 significant digits, half to even ({@link MathContext#DECIMAL128}),
 * and must stay below 10^24. Division by zero, a missing value or a value of the wrong kind gives {@code null}, which
 * flows through arithmetic and makes ordering comparisons {@code null}; {@code if} takes the else branch on
 * {@code null}. Ported line by line to {@code frontend/src/lib/expr/evaluator.ts}.
 */
final class Evaluator {

    static final int MAX_COST = 100_000;
    static final int MAX_TEXT = 10_000;
    static final int MAX_LIST_ITEMS = 1_000;
    static final BigDecimal MAX_ABS = new BigDecimal("1E24");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,30}(\\.[0-9]{1,30})?");

    private final Map<Ast, ExprType> types;
    private final Values values;
    private final List<Object> items = new ArrayList<>();
    private int cost;

    Evaluator(Map<Ast, ExprType> types, Values values) {
        this.types = types;
        this.values = values;
    }

    private BusinessCalendar calendar() {
        BusinessCalendar c = values.calendar();
        return c == null ? Values.DEFAULT_CALENDAR : c;
    }

    // ---------------------------------------------------------------- conversions

    static Object fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isString()) return node.asString();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(fromJson(n)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.properties().forEach(e -> map.put(e.getKey(), fromJson(e.getValue())));
        return map;
    }

    static Object convert(Object raw, ExprType type, int pos) throws ExpressionException {
        Object v = raw instanceof JsonNode j ? fromJson(j) : raw;
        if (v == null) return null;
        if (v instanceof Number n && !(v instanceof BigDecimal)) v = new BigDecimal(n.toString());
        if (type == ExprType.NUMBER) return v instanceof BigDecimal ? v : null;
        if (type == ExprType.STRING) return v instanceof String ? v : null;
        if (type == ExprType.BOOLEAN) return v instanceof Boolean ? v : null;
        if (type == ExprType.DATE) return v instanceof LocalDate ? v : v instanceof String s ? parseDate(s) : null;
        if (type instanceof ListOf l) {
            if (!(v instanceof List<?> list)) return null;
            if (list.size() > MAX_LIST_ITEMS) {
                throw new ExpressionException("EXPR_LIST_LIMIT", pos, "A list can have at most " + MAX_LIST_ITEMS + " items.");
            }
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(convert(item, l.element(), pos));
            return out;
        }
        if (type instanceof Row r) {
            if (!(v instanceof Map<?, ?> map)) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            for (var f : r.fields().entrySet()) out.put(f.getKey(), convert(map.get(f.getKey()), f.getValue(), pos));
            return out;
        }
        return normalize(v, pos);
    }

    private static Object normalize(Object v, int pos) throws ExpressionException {
        if (v instanceof Number n && !(v instanceof BigDecimal)) return new BigDecimal(n.toString());
        if (v instanceof List<?> list) {
            if (list.size() > MAX_LIST_ITEMS) {
                throw new ExpressionException("EXPR_LIST_LIMIT", pos, "A list can have at most " + MAX_LIST_ITEMS + " items.");
            }
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(normalize(item, pos));
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : map.entrySet()) out.put(String.valueOf(e.getKey()), normalize(e.getValue(), pos));
            return out;
        }
        return v;
    }

    /** @return the date written YYYY-MM-DD (years 1 to 9999), or null */
    static LocalDate parseDate(String s) {
        if (!DATE.matcher(s).matches()) return null;
        return date(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)), Integer.parseInt(s.substring(8, 10)));
    }

    private static LocalDate date(long y, long m, long d) {
        if (y < 1 || y > 9999) return null;
        try {
            return LocalDate.of((int) y, (int) m, (int) d);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static LocalDate inRange(LocalDate d) {
        return d == null || d.getYear() < 1 || d.getYear() > 9999 ? null : d;
    }

    private static Integer whole(Object v) {
        if (!(v instanceof BigDecimal n)) return null;
        BigDecimal s = n.stripTrailingZeros();
        if (s.scale() > 0 || s.abs().compareTo(BigDecimal.valueOf(1_000_000_000)) > 0) return null;
        return s.intValueExact();
    }

    private static BigDecimal num(BigDecimal x, int pos) throws ExpressionException {
        BigDecimal r = x.round(MathContext.DECIMAL128);
        if (r.abs().compareTo(MAX_ABS) >= 0) {
            throw new ExpressionException("EXPR_NUMBER_RANGE", pos, "A result must stay below 10^24.");
        }
        return r;
    }

    private static String text(String s, int pos) throws ExpressionException {
        if (s.length() > MAX_TEXT && s.codePointCount(0, s.length()) > MAX_TEXT) {
            throw new ExpressionException("EXPR_TEXT_LIMIT", pos, "A text result can be at most " + MAX_TEXT + " characters long.");
        }
        return s;
    }

    /** Canonical text of a number: no exponent, no trailing zeros ({@code 12.50} is {@code 12.5}). */
    static String plain(BigDecimal n) {
        return n.signum() == 0 ? "0" : n.stripTrailingZeros().toPlainString();
    }

    static boolean equal(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!equal(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) return false;
            for (var e : x.entrySet()) if (!equal(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        return a.equals(b);
    }

    /** @return the sign of a - b for two numbers, texts (UTF-16 order) or dates; null otherwise */
    static Integer compare(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return Integer.signum(x.compareTo(y));
        if (a instanceof String x && b instanceof String y) return Integer.signum(x.compareTo(y));
        if (a instanceof LocalDate x && b instanceof LocalDate y) return Integer.signum(x.compareTo(y));
        return null;
    }

    private static Boolean bool(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    private static List<?> list(Object v) {
        return v == null ? List.of() : v instanceof List<?> l ? l : null;
    }

    // ---------------------------------------------------------------- evaluation

    Object eval(Ast node) throws ExpressionException {
        if (++cost > MAX_COST) {
            throw new ExpressionException("EXPR_COST_LIMIT", node.pos(),
                    "This expression does too much work for the data it was given.");
        }
        return switch (node) {
            case Ast.Num n -> n.value();
            case Ast.Str s -> s.value();
            case Ast.Bool b -> b.value();
            case Ast.Null n -> null;
            case Ast.ListLit l -> {
                List<Object> out = new ArrayList<>(l.items().size());
                for (Ast item : l.items()) out.add(eval(item));
                yield out;
            }
            case Ast.Ref r -> switch (r.kind()) {
                case INPUT -> convert(values.input(r.key()), types.get(r), r.pos());
                case STEP -> convert(values.step(r.step(), r.key()), types.get(r), r.pos());
                case LOCAL -> convert(values.local(r.key()), types.get(r), r.pos());
                case ITEM -> items.getLast();
            };
            case Ast.Member m -> eval(m.target()) instanceof Map<?, ?> row ? row.get(m.name()) : null;
            case Ast.Neg n -> eval(n.operand()) instanceof BigDecimal x ? num(x.negate(), n.pos()) : null;
            case Ast.Binary b -> binary(b);
            case Ast.If i -> Boolean.TRUE.equals(eval(i.condition())) ? eval(i.then()) : eval(i.otherwise());
            case Ast.Call c -> call(c);
        };
    }

    private Object binary(Ast.Binary b) throws ExpressionException {
        if (b.op() == Ast.Op.AND) {
            Boolean l = bool(eval(b.left()));
            if (Boolean.FALSE.equals(l)) return false;
            Boolean r = bool(eval(b.right()));
            if (Boolean.FALSE.equals(r)) return false;
            return l == null || r == null ? null : true;
        }
        if (b.op() == Ast.Op.OR) {
            Boolean l = bool(eval(b.left()));
            if (Boolean.TRUE.equals(l)) return true;
            Boolean r = bool(eval(b.right()));
            if (Boolean.TRUE.equals(r)) return true;
            return l == null || r == null ? null : false;
        }
        Object l = eval(b.left());
        Object r = eval(b.right());
        return switch (b.op()) {
            case ADD -> l instanceof BigDecimal x && r instanceof BigDecimal y ? num(x.add(y), b.pos())
                    : l instanceof String x && r instanceof String y ? text(x + y, b.pos()) : null;
            case SUB -> l instanceof BigDecimal x && r instanceof BigDecimal y ? num(x.subtract(y), b.pos()) : null;
            case MUL -> l instanceof BigDecimal x && r instanceof BigDecimal y ? num(x.multiply(y), b.pos()) : null;
            case DIV -> l instanceof BigDecimal x && r instanceof BigDecimal y && y.signum() != 0
                    ? num(x.divide(y, MathContext.DECIMAL128), b.pos()) : null;
            case EQ -> equal(l, r);
            case NE -> !equal(l, r);
            case LT, LE, GT, GE -> {
                Integer c = compare(l, r);
                yield c == null ? null : switch (b.op()) {
                    case LT -> c < 0;
                    case LE -> c <= 0;
                    case GT -> c > 0;
                    default -> c >= 0;
                };
            }
            case IN -> {
                if (l == null || !(r instanceof List<?> list)) yield null;
                for (Object item : list) if (equal(l, item)) yield true;
                yield false;
            }
            default -> throw new IllegalStateException(b.op().name());
        };
    }

    private Object each(Ast expression, Object item) throws ExpressionException {
        items.add(item);
        try {
            return eval(expression);
        } finally {
            items.removeLast();
        }
    }

    private Object call(Ast.Call c) throws ExpressionException {
        List<Ast> a = c.args();
        String n = c.name();
        switch (n) {
            case "count", "sum", "average", "filter", "any", "all" -> {
                List<?> list = list(eval(a.get(0)));
                if (list == null) return null;
                Ast per = a.size() == 2 ? a.get(1) : null;
                BigDecimal total = BigDecimal.ZERO;
                int counted = 0;
                List<Object> kept = new ArrayList<>();
                for (Object item : list) {
                    Object v = per == null ? item : each(per, item);
                    switch (n) {
                        case "count" -> {
                            if (per == null || Boolean.TRUE.equals(v)) counted++;
                        }
                        case "sum", "average" -> {
                            if (v instanceof BigDecimal x) {
                                total = num(total.add(x), c.pos());
                                counted++;
                            }
                        }
                        case "filter" -> {
                            if (Boolean.TRUE.equals(v)) kept.add(item);
                        }
                        case "any" -> {
                            if (Boolean.TRUE.equals(v)) return true;
                        }
                        default -> {
                            if (!Boolean.TRUE.equals(v)) return false;
                        }
                    }
                }
                return switch (n) {
                    case "count" -> BigDecimal.valueOf(counted);
                    case "sum" -> total;
                    case "average" -> counted == 0 ? null : num(total.divide(BigDecimal.valueOf(counted), MathContext.DECIMAL128), c.pos());
                    case "filter" -> kept;
                    case "any" -> false;
                    default -> true;
                };
            }
            case "min", "max" -> {
                boolean listMode = a.size() == 1 || types.get(a.get(0)) instanceof ListOf;
                List<Object> candidates = new ArrayList<>();
                if (listMode) {
                    List<?> list = list(eval(a.get(0)));
                    if (list == null) return null;
                    for (Object item : list) candidates.add(a.size() == 2 ? each(a.get(1), item) : item);
                } else {
                    for (Ast arg : a) candidates.add(eval(arg));
                }
                Object best = null;
                for (Object v : candidates) {
                    if (v == null) continue;
                    if (best == null) {
                        best = v;
                        continue;
                    }
                    Integer cmp = compare(v, best);
                    if (cmp != null && (n.equals("min") ? cmp < 0 : cmp > 0)) best = v;
                }
                return best;
            }
            case "coalesce" -> {
                for (Ast arg : a) {
                    Object v = eval(arg);
                    if (v != null) return v;
                }
                return null;
            }
            default -> {
                // Every other function evaluates all its values first, left to right.
            }
        }
        List<Object> v = new ArrayList<>(a.size());
        for (Ast arg : a) v.add(eval(arg));
        Object x = v.isEmpty() ? null : v.get(0);
        switch (n) {
            case "abs" -> {
                return x instanceof BigDecimal d ? num(d.abs(), c.pos()) : null;
            }
            case "floor" -> {
                return x instanceof BigDecimal d ? num(d.setScale(0, RoundingMode.FLOOR), c.pos()) : null;
            }
            case "ceiling" -> {
                return x instanceof BigDecimal d ? num(d.setScale(0, RoundingMode.CEILING), c.pos()) : null;
            }
            case "round" -> {
                Integer places = whole(v.get(1));
                if (!(x instanceof BigDecimal d) || places == null || places < 0 || places > 20) return null;
                String mode = v.size() == 3 ? (String) v.get(2) : "halfUp";
                return num(d.setScale(places, rounding(mode)), c.pos());
            }
            case "number" -> {
                return x instanceof String s && NUMBER.matcher(s).matches() ? num(new BigDecimal(s), c.pos()) : null;
            }
            case "length" -> {
                return x instanceof String s ? BigDecimal.valueOf(s.codePointCount(0, s.length())) : null;
            }
            case "upper" -> {
                return x instanceof String s ? text(s.toUpperCase(Locale.ROOT), c.pos()) : null;
            }
            case "lower" -> {
                return x instanceof String s ? text(s.toLowerCase(Locale.ROOT), c.pos()) : null;
            }
            case "trim" -> {
                return x instanceof String s ? trim(s) : null;
            }
            case "substring" -> {
                Integer start = whole(v.get(1));
                Integer length = v.size() == 3 ? whole(v.get(2)) : null;
                if (!(x instanceof String s) || start == null || start < 1 || v.size() == 3 && (length == null || length < 0)) {
                    return null;
                }
                int[] cps = s.codePoints().toArray();
                int from = Math.min(start - 1, cps.length);
                int to = length == null ? cps.length : (int) Math.min((long) from + length, cps.length);
                return new String(cps, from, to - from);
            }
            case "contains", "startsWith", "endsWith" -> {
                if (!(x instanceof String s) || !(v.get(1) instanceof String t)) return null;
                return n.equals("contains") ? s.contains(t) : n.equals("startsWith") ? s.startsWith(t) : s.endsWith(t);
            }
            case "replace" -> {
                if (!(x instanceof String s) || !(v.get(1) instanceof String find) || !(v.get(2) instanceof String with)) return null;
                return find.isEmpty() ? s : text(s.replace(find, with), c.pos());
            }
            case "string" -> {
                return x instanceof BigDecimal d ? plain(d) : x instanceof LocalDate d ? d.toString()
                        : x instanceof Boolean b ? b.toString() : x instanceof String s ? s : null;
            }
            case "date" -> {
                if (v.size() == 1) return x instanceof String s ? parseDate(s) : null;
                Integer y = whole(v.get(0)), m = whole(v.get(1)), d = whole(v.get(2));
                return y == null || m == null || d == null ? null : date(y, m, d);
            }
            case "today" -> {
                return inRange(LocalDate.ofInstant(values.now(), calendar().zone()));
            }
            case "addDays", "addMonths", "addYears", "addWorkingDays" -> {
                Integer k = whole(v.get(1));
                if (!(x instanceof LocalDate d) || k == null) return null;
                int bound = n.equals("addWorkingDays") ? 1000 : 1_000_000;
                if (Math.abs(k) > bound) return null;
                return inRange(switch (n) {
                    case "addDays" -> d.plusDays(k);
                    case "addMonths" -> d.plusMonths(k);
                    case "addYears" -> d.plusYears(k);
                    default -> calendar().plusWorkingDays(d, k);
                });
            }
            case "daysBetween", "workingDaysBetween" -> {
                if (!(x instanceof LocalDate from) || !(v.get(1) instanceof LocalDate to)) return null;
                if (n.equals("daysBetween")) return BigDecimal.valueOf(to.toEpochDay() - from.toEpochDay());
                Integer days = calendar().workingDaysBetween(from, to);
                return days == null ? null : BigDecimal.valueOf(days);
            }
            case "year", "month", "day", "weekday" -> {
                if (!(x instanceof LocalDate d)) return null;
                return BigDecimal.valueOf(switch (n) {
                    case "year" -> d.getYear();
                    case "month" -> d.getMonthValue();
                    case "day" -> d.getDayOfMonth();
                    default -> d.getDayOfWeek().getValue();
                });
            }
            case "isWorkingDay" -> {
                return x instanceof LocalDate d ? calendar().isWorkingDay(d) : null;
            }
            case "isEmpty" -> {
                return x == null || x instanceof String s && trim(s).isEmpty() || x instanceof List<?> l && l.isEmpty();
            }
            case "not" -> {
                Boolean b = bool(x);
                return b == null ? null : !b;
            }
            default -> throw new IllegalStateException("Unchecked function " + n);
        }
    }

    static RoundingMode rounding(String mode) {
        return switch (mode) {
            case "halfEven" -> RoundingMode.HALF_EVEN;
            case "down" -> RoundingMode.DOWN;
            case "up" -> RoundingMode.UP;
            case "floor" -> RoundingMode.FLOOR;
            case "ceiling" -> RoundingMode.CEILING;
            default -> RoundingMode.HALF_UP;
        };
    }

    /** Removes spaces, tabs and line breaks (only these, the same in Java and TypeScript) from both ends. */
    static String trim(String s) {
        int from = 0, to = s.length();
        while (from < to && " \t\n\r".indexOf(s.charAt(from)) >= 0) from++;
        while (to > from && " \t\n\r".indexOf(s.charAt(to - 1)) >= 0) to--;
        return s.substring(from, to);
    }
}
