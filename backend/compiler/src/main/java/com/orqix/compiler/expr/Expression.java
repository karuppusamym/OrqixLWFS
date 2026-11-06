package com.orqix.compiler.expr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A checked Orqix expression (docs/build/S137, doc 29 CG-X01): a total, side-effect-free, FEEL-like language for
 * Calculate steps, conditions, mappings, decision rows and form rules. {@link #compile} parses and type checks against a
 * {@link Scope}; {@link #evaluate} runs it on {@link Values}. The TypeScript engine in {@code frontend/src/lib/expr}
 * gives the same results, codes and offsets on {@code examples/fixtures/expression-cases.json}.
 */
public final class Expression {

    private final String source;
    private final Ast ast;
    private final ExprType type;
    private final Map<Ast, ExprType> types;
    private final Set<String> fieldRefs;
    private final Set<String> locals;

    private Expression(String source, Ast ast, ExprType type, Checker checker) {
        this.source = source;
        this.ast = ast;
        this.type = type;
        this.types = checker.types;
        this.fieldRefs = Set.copyOf(checker.fieldRefs);
        this.locals = Set.copyOf(checker.locals);
    }

    /** Parses and type checks; throws with the first problem's code, offset and business message. */
    public static Expression compile(String source, Scope scope) throws ExpressionException {
        Ast ast = Parser.parse(source);
        Checker checker = new Checker(scope);
        ExprType type = checker.check(ast);
        return new Expression(source, ast, type, checker);
    }

    public String source() {
        return source;
    }

    /** @return the static type of the result */
    public ExprType type() {
        return type;
    }

    /** @return the request fields and step outputs used, as field references ({@code /input/a}, {@code /steps/s/b}) */
    public Set<String> fieldRefs() {
        return fieldRefs;
    }

    /** @return the earlier outputs of the same step used by name */
    public Set<String> locals() {
        return locals;
    }

    /**
     * @return a {@link BigDecimal}, {@link String}, {@link Boolean}, {@link LocalDate}, list, row map or null
     * @throws ExpressionException only for the evaluation limits (cost, number range, text and list size)
     */
    public Object evaluate(Values values) throws ExpressionException {
        return new Evaluator(types, values).eval(ast);
    }

    /** @return the canonical JSON of an evaluated value: numbers exact without trailing zeros, dates YYYY-MM-DD */
    public static JsonNode toJson(Object value) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (value == null) return f.nullNode();
        if (value instanceof BigDecimal n) return f.numberNode(new BigDecimal(Evaluator.plain(n)));
        if (value instanceof String s) return f.stringNode(s);
        if (value instanceof Boolean b) return f.booleanNode(b);
        if (value instanceof LocalDate d) return f.stringNode(d.toString());
        if (value instanceof List<?> list) {
            ArrayNode out = f.arrayNode();
            list.forEach(item -> out.add(toJson(item)));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode out = f.objectNode();
            map.forEach((k, v) -> out.set(String.valueOf(k), toJson(v)));
            return out;
        }
        throw new IllegalArgumentException("Not an expression value: " + value.getClass().getName());
    }

    /** @return canonical text of a number: exact, without exponent or trailing zeros */
    public static String plain(BigDecimal number) {
        return Evaluator.plain(number);
    }

    /** @return the rounding mode named as in {@code round(n, places, mode)}; unknown names round half up */
    public static java.math.RoundingMode rounding(String mode) {
        return Evaluator.rounding(mode);
    }

    /** @return the expression names of the rounding modes: halfUp, halfEven, down, up, floor, ceiling */
    public static List<String> roundingModes() {
        return Checker.ROUNDING;
    }

    /** @return the date written YYYY-MM-DD, or null when it is not a real date in years 1 to 9999 */
    public static LocalDate parseDate(String text) {
        return Evaluator.parseDate(text);
    }

    /** @return the evaluation value of a JSON value: numbers become exact decimals, arrays lists, objects maps */
    public static Object fromJson(JsonNode node) {
        return Evaluator.fromJson(node);
    }

    @Override
    public String toString() {
        return source;
    }
}
