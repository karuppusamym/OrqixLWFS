package com.orqix.compiler.expr;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.expr.ExprType.ListOf;
import com.orqix.compiler.expr.ExprType.Row;

/**
 * Static type checker of Orqix expressions (docs/build/S137). It types every node, records the request fields, step
 * outputs and local names used, and refuses mismatches before anything runs. Ported line by line to
 * {@code frontend/src/lib/expr/checker.ts}.
 */
final class Checker {

    /** Rounding modes {@code round(n, places, mode)} accepts, by their expression names. */
    static final List<String> ROUNDING = List.of("halfUp", "halfEven", "down", "up", "floor", "ceiling");

    /** Functions whose second value is evaluated once per list item, with {@code item} bound to it. */
    static final Set<String> ITEM_FUNCTIONS = Set.of("count", "sum", "average", "min", "max", "filter", "any", "all");

    private final Scope scope;
    final Map<Ast, ExprType> types = new IdentityHashMap<>();
    final Set<String> fieldRefs = new LinkedHashSet<>();
    final Set<String> locals = new LinkedHashSet<>();
    private final Deque<ExprType> items = new ArrayDeque<>();

    Checker(Scope scope) {
        this.scope = scope;
    }

    ExprType check(Ast node) throws ExpressionException {
        ExprType t = type(node);
        types.put(node, t);
        return t;
    }

    private static boolean loose(ExprType t) {
        return t == ExprType.NULL || t == ExprType.ANY;
    }

    private static boolean fits(ExprType actual, ExprType expected) {
        return loose(actual) || actual.equals(expected) || expected == ExprType.ANY;
    }

    private static boolean orderable(ExprType t) {
        return t == ExprType.NUMBER || t == ExprType.STRING || t == ExprType.DATE;
    }

    private static boolean comparable(ExprType a, ExprType b) {
        if (loose(a) || loose(b)) return !(a instanceof Row) && !(b instanceof Row);
        if (a instanceof ListOf la && b instanceof ListOf lb) return comparable(la.element(), lb.element());
        return a instanceof ExprType.Scalar && a.equals(b);
    }

    static ExprType join(ExprType a, ExprType b) {
        if (a == ExprType.NULL || a == ExprType.ANY) return b;
        if (b == ExprType.NULL || b == ExprType.ANY) return a;
        if (a.equals(b)) return a;
        if (a instanceof ListOf la && b instanceof ListOf lb) {
            ExprType e = join(la.element(), lb.element());
            return e == null ? null : new ListOf(e);
        }
        return null;
    }

    private ExprType type(Ast node) throws ExpressionException {
        return switch (node) {
            case Ast.Num n -> ExprType.NUMBER;
            case Ast.Str s -> ExprType.STRING;
            case Ast.Bool b -> ExprType.BOOLEAN;
            case Ast.Null n -> ExprType.NULL;
            case Ast.ListLit l -> {
                ExprType element = ExprType.ANY;
                for (Ast item : l.items()) {
                    ExprType t = check(item);
                    ExprType joined = join(element, t);
                    if (joined == null) {
                        throw new ExpressionException("EXPR_TYPE", item.pos(),
                                "A list holds values of one type: this is " + t.describe() + " after " + element.describe() + ".");
                    }
                    element = joined;
                }
                yield new ListOf(element);
            }
            case Ast.Ref r -> ref(r);
            case Ast.Member m -> {
                ExprType target = check(m.target());
                if (target instanceof Row row) {
                    ExprType f = row.fields().get(m.name());
                    if (f == null) {
                        throw new ExpressionException("EXPR_UNKNOWN_FIELD", m.pos(), "This row has no column named " + m.name() + ".");
                    }
                    yield f;
                }
                if (loose(target)) yield target;
                if (target instanceof ListOf l && l.element() instanceof Row) {
                    throw new ExpressionException("EXPR_TYPE", m.pos(),
                            "This is a list of rows: use item." + m.name() + " inside sum, count, filter, any or all.");
                }
                throw new ExpressionException("EXPR_TYPE", m.pos(), "Only a row has columns; this is " + target.describe() + ".");
            }
            case Ast.Neg n -> {
                ExprType t = check(n.operand());
                if (!fits(t, ExprType.NUMBER)) {
                    throw new ExpressionException("EXPR_TYPE", n.pos(), "Only a number can be negative; this is " + t.describe() + ".");
                }
                yield ExprType.NUMBER;
            }
            case Ast.Binary b -> binary(b);
            case Ast.If i -> {
                ExprType c = check(i.condition());
                if (!fits(c, ExprType.BOOLEAN)) {
                    throw new ExpressionException("EXPR_TYPE", i.condition().pos(),
                            "The condition after if must be true or false; this is " + c.describe() + ".");
                }
                ExprType a = check(i.then());
                ExprType e = check(i.otherwise());
                ExprType joined = join(a, e);
                if (joined == null) {
                    throw new ExpressionException("EXPR_TYPE", i.otherwise().pos(),
                            "Both results of if must have one type: " + a.describe() + " and " + e.describe() + ".");
                }
                yield joined;
            }
            case Ast.Call c -> call(c);
        };
    }

    private ExprType ref(Ast.Ref r) throws ExpressionException {
        switch (r.kind()) {
            case INPUT -> {
                ExprType t = scope.input(r.key());
                if (t == null) {
                    throw new ExpressionException("EXPR_UNKNOWN_FIELD", r.pos(), "There is no request field named " + r.key() + ".");
                }
                fieldRefs.add("/input/" + r.key());
                return t;
            }
            case STEP -> {
                ExprType t = scope.step(r.step(), r.key());
                if (t == null) {
                    throw new ExpressionException("EXPR_UNKNOWN_FIELD", r.pos(),
                            "Step " + r.step() + " has no output named " + r.key() + " available here.");
                }
                fieldRefs.add("/steps/" + r.step() + "/" + r.key());
                return t;
            }
            case ITEM -> {
                if (items.isEmpty()) {
                    throw new ExpressionException("EXPR_UNKNOWN_FIELD", r.pos(),
                            "item can only be used inside count, sum, average, min, max, filter, any and all.");
                }
                return items.peek();
            }
            default -> {
                ExprType t = scope.local(r.key());
                if (t == null) {
                    throw new ExpressionException("EXPR_UNKNOWN_FIELD", r.pos(),
                            "There is no field or earlier output named " + r.key() + "; request fields start with input.");
                }
                locals.add(r.key());
                return t;
            }
        }
    }

    private ExprType binary(Ast.Binary b) throws ExpressionException {
        ExprType l = check(b.left());
        ExprType r = check(b.right());
        switch (b.op()) {
            case ADD -> {
                if (fits(l, ExprType.NUMBER) && fits(r, ExprType.NUMBER)) return ExprType.NUMBER;
                if (fits(l, ExprType.STRING) && fits(r, ExprType.STRING)) return ExprType.STRING;
                throw new ExpressionException("EXPR_TYPE", b.pos(),
                        "+ adds two numbers or joins two texts; here it gets " + l.describe() + " and " + r.describe() + ".");
            }
            case SUB, MUL, DIV -> {
                if (fits(l, ExprType.NUMBER) && fits(r, ExprType.NUMBER)) return ExprType.NUMBER;
                throw new ExpressionException("EXPR_TYPE", b.pos(), b.op().symbol + " works on two numbers; here it gets "
                        + l.describe() + " and " + r.describe() + ". Use addDays or daysBetween for dates.");
            }
            case EQ, NE -> {
                if (comparable(l, r)) return ExprType.BOOLEAN;
                throw new ExpressionException("EXPR_TYPE", b.pos(),
                        b.op().symbol + " compares two values of one type; here it gets " + l.describe() + " and " + r.describe() + ".");
            }
            case LT, LE, GT, GE -> {
                boolean ok = loose(l) && (loose(r) || orderable(r)) || orderable(l) && (loose(r) || l.equals(r));
                if (ok) return ExprType.BOOLEAN;
                throw new ExpressionException("EXPR_TYPE", b.pos(), b.op().symbol
                        + " compares two numbers, two texts or two dates; here it gets " + l.describe() + " and " + r.describe() + ".");
            }
            case AND, OR -> {
                if (fits(l, ExprType.BOOLEAN) && fits(r, ExprType.BOOLEAN)) return ExprType.BOOLEAN;
                throw new ExpressionException("EXPR_TYPE", b.pos(), b.op().symbol
                        + " joins two true-or-false values; here it gets " + l.describe() + " and " + r.describe() + ".");
            }
            case IN -> {
                if (r instanceof ListOf list && comparable(l, list.element()) || loose(r) && !(l instanceof Row)) {
                    return ExprType.BOOLEAN;
                }
                throw new ExpressionException("EXPR_TYPE", b.pos(),
                        "in looks for a value in a list of the same type; here it gets " + l.describe() + " and " + r.describe() + ".");
            }
            default -> throw new IllegalStateException(b.op().name());
        }
    }

    private static ExpressionException arity(Ast.Call c, String spec) {
        return new ExpressionException("EXPR_ARGUMENTS", c.pos(), c.name() + " takes " + spec + ".");
    }

    private static void need(Ast.Call c, int index, ExprType actual, ExprType expected) throws ExpressionException {
        if (!fits(actual, expected)) {
            throw new ExpressionException("EXPR_TYPE", c.args().get(index).pos(),
                    c.name() + " needs " + expected.describe() + " here, not " + actual.describe() + ".");
        }
    }

    private void count(Ast.Call c, int min, int max, String spec) throws ExpressionException {
        int n = c.args().size();
        if (n < min || n > max) throw arity(c, spec);
    }

    /** Checks the list value and returns its element type. */
    private ExprType list(Ast.Call c) throws ExpressionException {
        return element(c, check(c.args().get(0)));
    }

    private static ExprType element(Ast.Call c, ExprType t) throws ExpressionException {
        if (t instanceof ListOf l) return l.element();
        if (loose(t)) return ExprType.ANY;
        throw new ExpressionException("EXPR_TYPE", c.args().get(0).pos(),
                c.name() + " needs a list here, not " + t.describe() + ".");
    }

    private ExprType perItem(Ast.Call c, ExprType element) throws ExpressionException {
        items.push(element);
        try {
            return check(c.args().get(1));
        } finally {
            items.pop();
        }
    }

    private ExprType call(Ast.Call c) throws ExpressionException {
        List<Ast> a = c.args();
        String n = c.name();
        switch (n) {
            case "count", "sum", "average", "filter", "any", "all" -> {
                boolean predicate = n.equals("filter") || n.equals("any") || n.equals("all");
                if (predicate) count(c, 2, 2, "a list and a condition");
                else count(c, 1, 2, "a list and, optionally, a value for each item");
                ExprType element = list(c);
                ExprType each = element;
                if (a.size() == 2) {
                    each = perItem(c, element);
                    ExprType expected = predicate || n.equals("count") ? ExprType.BOOLEAN : ExprType.NUMBER;
                    need(c, 1, each, expected);
                } else if (!n.equals("count") && !fits(element, ExprType.NUMBER)) {
                    throw new ExpressionException("EXPR_TYPE", a.get(0).pos(),
                            n + " needs a list of numbers here, not " + new ListOf(element).describe() + ".");
                }
                return switch (n) {
                    case "filter" -> types.get(a.get(0)) instanceof ListOf l ? l : new ListOf(element);
                    case "any", "all" -> ExprType.BOOLEAN;
                    default -> ExprType.NUMBER;
                };
            }
            case "min", "max" -> {
                count(c, 1, MAX_ARGS_SCALAR, "a list, a list and a value for each item, or 2 to 10 values");
                ExprType first = check(a.get(0));
                if (a.size() == 1 || first instanceof ListOf) {
                    if (a.size() > 2) throw arity(c, "a list and, optionally, a value for each item");
                    ExprType element = element(c, first);
                    ExprType each = a.size() == 2 ? perItem(c, element) : element;
                    if (!loose(each) && !orderable(each)) {
                        throw new ExpressionException("EXPR_TYPE", a.get(a.size() - 1).pos(),
                                n + " needs numbers, texts or dates, not " + each.describe() + ".");
                    }
                    return loose(each) ? ExprType.ANY : each;
                }
                ExprType result = first;
                for (int i = 1; i < a.size(); i++) {
                    ExprType t = check(a.get(i));
                    ExprType joined = join(result, t);
                    if (joined == null) {
                        throw new ExpressionException("EXPR_TYPE", a.get(i).pos(),
                                n + " compares values of one type: " + result.describe() + " and " + t.describe() + ".");
                    }
                    result = joined;
                }
                if (!loose(result) && !orderable(result)) {
                    throw new ExpressionException("EXPR_TYPE", a.get(0).pos(),
                            n + " needs numbers, texts or dates, not " + result.describe() + ".");
                }
                return result;
            }
            case "abs", "floor", "ceiling" -> {
                count(c, 1, 1, "one number");
                need(c, 0, check(a.get(0)), ExprType.NUMBER);
                return ExprType.NUMBER;
            }
            case "round" -> {
                count(c, 2, 3, "a number, the decimal places and, optionally, a rounding mode");
                need(c, 0, check(a.get(0)), ExprType.NUMBER);
                need(c, 1, check(a.get(1)), ExprType.NUMBER);
                if (a.size() == 3) {
                    check(a.get(2));
                    if (!(a.get(2) instanceof Ast.Str mode) || !ROUNDING.contains(mode.value())) {
                        throw new ExpressionException("EXPR_ARGUMENTS", a.get(2).pos(),
                                "The rounding mode must be one of \"" + String.join("\", \"", ROUNDING) + "\".");
                    }
                }
                return ExprType.NUMBER;
            }
            case "number" -> {
                count(c, 1, 1, "one text");
                need(c, 0, check(a.get(0)), ExprType.STRING);
                return ExprType.NUMBER;
            }
            case "length" -> {
                count(c, 1, 1, "one text");
                need(c, 0, check(a.get(0)), ExprType.STRING);
                return ExprType.NUMBER;
            }
            case "upper", "lower", "trim" -> {
                count(c, 1, 1, "one text");
                need(c, 0, check(a.get(0)), ExprType.STRING);
                return ExprType.STRING;
            }
            case "substring" -> {
                count(c, 2, 3, "a text, the first position (from 1) and, optionally, a length");
                need(c, 0, check(a.get(0)), ExprType.STRING);
                need(c, 1, check(a.get(1)), ExprType.NUMBER);
                if (a.size() == 3) need(c, 2, check(a.get(2)), ExprType.NUMBER);
                return ExprType.STRING;
            }
            case "contains", "startsWith", "endsWith" -> {
                count(c, 2, 2, "two texts");
                need(c, 0, check(a.get(0)), ExprType.STRING);
                need(c, 1, check(a.get(1)), ExprType.STRING);
                return ExprType.BOOLEAN;
            }
            case "replace" -> {
                count(c, 3, 3, "a text, the text to find and its replacement");
                for (int i = 0; i < 3; i++) need(c, i, check(a.get(i)), ExprType.STRING);
                return ExprType.STRING;
            }
            case "string" -> {
                count(c, 1, 1, "one value");
                ExprType t = check(a.get(0));
                if (!(t instanceof ExprType.Scalar)) {
                    throw new ExpressionException("EXPR_TYPE", a.get(0).pos(), "string needs a single value, not " + t.describe() + ".");
                }
                return ExprType.STRING;
            }
            case "date" -> {
                if (a.size() == 1) {
                    need(c, 0, check(a.get(0)), ExprType.STRING);
                } else if (a.size() == 3) {
                    for (int i = 0; i < 3; i++) need(c, i, check(a.get(i)), ExprType.NUMBER);
                } else {
                    throw arity(c, "a text like \"2026-03-31\" or a year, month and day");
                }
                return ExprType.DATE;
            }
            case "today" -> {
                count(c, 0, 0, "no values");
                return ExprType.DATE;
            }
            case "addDays", "addMonths", "addYears", "addWorkingDays" -> {
                count(c, 2, 2, "a date and a whole number");
                need(c, 0, check(a.get(0)), ExprType.DATE);
                need(c, 1, check(a.get(1)), ExprType.NUMBER);
                return ExprType.DATE;
            }
            case "daysBetween", "workingDaysBetween" -> {
                count(c, 2, 2, "two dates");
                need(c, 0, check(a.get(0)), ExprType.DATE);
                need(c, 1, check(a.get(1)), ExprType.DATE);
                return ExprType.NUMBER;
            }
            case "year", "month", "day", "weekday" -> {
                count(c, 1, 1, "one date");
                need(c, 0, check(a.get(0)), ExprType.DATE);
                return ExprType.NUMBER;
            }
            case "isWorkingDay" -> {
                count(c, 1, 1, "one date");
                need(c, 0, check(a.get(0)), ExprType.DATE);
                return ExprType.BOOLEAN;
            }
            case "coalesce" -> {
                count(c, 2, MAX_ARGS_SCALAR, "2 to 10 values");
                ExprType result = ExprType.NULL;
                for (Ast arg : a) {
                    ExprType t = check(arg);
                    ExprType joined = join(result, t);
                    if (joined == null) {
                        throw new ExpressionException("EXPR_TYPE", arg.pos(),
                                "coalesce needs values of one type: " + result.describe() + " and " + t.describe() + ".");
                    }
                    result = joined;
                }
                return result;
            }
            case "isEmpty" -> {
                count(c, 1, 1, "one value");
                check(a.get(0));
                return ExprType.BOOLEAN;
            }
            case "not" -> {
                count(c, 1, 1, "one true-or-false value");
                need(c, 0, check(a.get(0)), ExprType.BOOLEAN);
                return ExprType.BOOLEAN;
            }
            default -> throw new ExpressionException("EXPR_UNKNOWN_FUNCTION", c.pos(), "There is no function named " + n + ".");
        }
    }

    private static final int MAX_ARGS_SCALAR = Parser.MAX_ARGS;
}
