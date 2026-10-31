package com.orqix.migration.camunda;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the small, explicit subset of Camunda condition expressions that canonical v0.1 can express (S11 section 2).
 * The subset is exactly what {@code contracts/workflow-v0.schema.json} and the compiler accept for transition conditions:
 * <ul>
 *   <li>one comparison {@code variable op literal} with {@code eq ne gte gt lt lte}; ordering operators work on numbers
 *       only (the compiler rejects them on text and yes/no fields with {@code WF_OPERATOR_TYPE}),</li>
 *   <li>a bare boolean variable ({@code ${approved}}, {@code ${!approved}}, {@code = not(approved)}),</li>
 *   <li>a flat group of 2 to 8 such comparisons joined only by {@code and} or only by {@code or}
 *       ({@code all} / {@code any} in the canonical schema).</li>
 *   <li>FEEL only: {@code x between a and b}, {@code x in [a..b)} and {@code x in ("a", "b")} (a list of equalities), read
 *       as the equivalent flat {@code all} / {@code any} group; a literal on the left ({@code 5000 <= amount}) is turned
 *       around; {@code not(...)} over a flat group is inverted term by term (De Morgan); parenthesised groups of the same
 *       connective are flattened.</li>
 * </ul>
 * JUEL: {@code ${amount >= 5000 && status != 'new'}}, {@code ${a gt 1 and b le 3}}. FEEL: {@code = amount > 5000 and
 * status = "new"}. Everything else (mixed {@code and}/{@code or}, parentheses around groups, {@code not} over a
 * comparison, functions, property access, arithmetic, null) is reported as unsupported with the reason, never
 * approximated or rewritten. The source text is never evaluated.
 */
public final class Expressions {

    /** Comparison operators of canonical conditions. */
    public enum Op {
        EQ("eq", "=="), NE("ne", "!="), GTE("gte", ">="), GT("gt", ">"), LT("lt", "<"), LTE("lte", "<=");

        private final String canonical;
        private final String symbol;

        Op(String canonical, String symbol) {
            this.canonical = canonical;
            this.symbol = symbol;
        }

        public String canonical() {
            return canonical;
        }

        /** Readable operator used in report text. */
        public String symbol() {
            return symbol;
        }

        public boolean ordering() {
            return this != EQ && this != NE;
        }
    }

    /** Result of reading one expression. */
    private static final Pattern BARE_NEGATION = Pattern.compile("(?i)(?:not|!)\\s*\\(\\s*[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)");
    private static final Pattern REWRITE = Pattern.compile("(?i)(?:^|[^A-Za-z0-9_$])(?:not\\s*\\(|!\\s*\\(|between\\b)");

    /**
     * True when converting the condition text rewrites it rather than transcribing it: {@code not(...)} / {@code !(...)}
     * becomes the opposite operator (De Morgan over a group) and a FEEL {@code between} range becomes two comparisons. Such
     * a condition is a "converted but changed — verify" item ({@code CONDITION_REWRITTEN}).
     */
    public static boolean rewritten(String source) {
        // not(approved) of a bare yes/no variable is transcribed as "approved is no", not rewritten.
        return source != null && REWRITE.matcher(BARE_NEGATION.matcher(source).replaceAll(" ")).find();
    }

    public sealed interface Parsed permits Comparison, Logical, Unsupported {
    }

    /** {@code variable op literal}; the literal is a {@link BigDecimal}, {@link String} or {@link Boolean}. */
    public record Comparison(String variable, Op op, Object literal, boolean booleanShortcut) implements Parsed {

        public boolean isBoolean() {
            return literal instanceof Boolean;
        }

        /** The comparison that holds exactly when this one does not (for non-null values): {@code not(a >= 5)} is {@code a < 5}. */
        public Comparison negated() {
            Op inverse = switch (op) {
                case EQ -> Op.NE;
                case NE -> Op.EQ;
                case GTE -> Op.LT;
                case GT -> Op.LTE;
                case LT -> Op.GTE;
                case LTE -> Op.GT;
            };
            if (literal instanceof Boolean flag && (op == Op.EQ || op == Op.NE)) {
                return new Comparison(variable, Op.EQ, op == Op.EQ ? !flag : flag, false);
            }
            return new Comparison(variable, inverse, literal, false);
        }

        public String valueType() {
            return literal instanceof Boolean ? "boolean" : literal instanceof String ? "string" : "number";
        }

        /** Text such as {@code amount >= 5000} or {@code status != 'new'} for explanations. */
        public String describe() {
            Object shown = literal instanceof String text ? "'" + text + "'" : literal instanceof BigDecimal n ? n.toPlainString() : literal;
            return variable + " " + op.symbol() + " " + shown;
        }
    }

    /** Two to eight comparisons joined by one connective: {@code all} (and) or {@code any} (or). */
    public record Logical(String group, List<Comparison> terms) implements Parsed {

        public Logical {
            terms = List.copyOf(terms);
        }

        public String describe() {
            String joiner = "all".equals(group) ? " and " : " or ";
            return terms.stream().map(Comparison::describe).collect(java.util.stream.Collectors.joining(joiner));
        }
    }

    public record Unsupported(String reason) implements Parsed {
    }

    /** Most terms of one group (canonical schema {@code maxItems}). */
    public static final int MAX_TERMS = 8;

    private static final Pattern TOKEN = Pattern.compile("\\s*(?:(?<num>-?\\d+(?:\\.\\d+)?)"
            + "|(?<str>'[^']*'|\"[^\"]*\")"
            + "|(?<id>[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)"
            + "|(?<op>>=|<=|==|!=|&&|\\|\\||[^\\s]))");

    private static final java.util.Set<String> FEEL_KEYWORDS = java.util.Set.of("in", "between", "if", "then", "else", "for", "some",
            "every", "satisfies", "instance", "of", "function", "return");

    private static final Pattern SAFE_STRING = Pattern.compile("^[A-Za-z0-9 _.,:/-]{0,100}$");

    private Expressions() {
    }

    /** One lexical token: kind n(umber), s(tring), i(dentifier) or o(perator/punctuation). */
    private record Token(char kind, String text) {
        boolean is(char k, String t) {
            return kind == k && text.equals(t);
        }
    }

    /**
     * @param text     the {@code conditionExpression} text
     * @param language the {@code language} attribute (null for the default JUEL/FEEL)
     * @param resource the {@code camunda:resource} attribute (external script), or null
     */
    public static Parsed parse(String text, String language, String resource) {
        if (resource != null && !resource.isBlank()) {
            return new Unsupported("It loads the condition from an external script resource.");
        }
        if (language != null && !language.isBlank()) {
            String lang = language.toLowerCase(Locale.ROOT);
            if (!(lang.contains("juel") || lang.contains("feel"))) {
                return new Unsupported("It uses the script language '" + language + "'.");
            }
        }
        if (text == null || text.isBlank()) {
            return new Unsupported("The condition is empty.");
        }
        String body = text.strip();
        boolean feel = false;
        if ((body.startsWith("${") || body.startsWith("#{")) && body.endsWith("}")) {
            body = body.substring(2, body.length() - 1);
        } else if (body.startsWith("=")) {
            feel = true;
            body = body.substring(1);
        } else {
            return new Unsupported("It is not a ${...} or = expression.");
        }
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(body);
        int position = 0;
        while (position < body.length() && !body.substring(position).isBlank()) {
            matcher.region(position, body.length());
            if (!matcher.lookingAt()) {
                break;
            }
            if (matcher.group("num") != null) {
                tokens.add(new Token('n', matcher.group("num")));
            } else if (matcher.group("str") != null) {
                String raw = matcher.group("str");
                tokens.add(new Token('s', raw.substring(1, raw.length() - 1)));
            } else if (matcher.group("id") != null) {
                tokens.add(new Token('i', matcher.group("id")));
            } else {
                tokens.add(new Token('o', matcher.group("op")));
            }
            position = matcher.end();
        }
        if (tokens.isEmpty()) {
            return new Unsupported("It is not a simple comparison.");
        }
        return interpret(tokens, feel);
    }

    // ------------------------------------------------------------------------------------------------ grammar

    /** Splits the tokens at top-level and/or and reads every operand as one atom or one parenthesised group. */
    private static Parsed interpret(List<Token> tokens, boolean feel) {
        List<List<Token>> operands = new ArrayList<>();
        String connective = null;
        List<Token> current = new ArrayList<>();
        int depth = 0;
        boolean betweenOpen = false;
        for (Token t : tokens) {
            if (t.is('o', "(") || t.is('o', "[")) {
                depth++;
            } else if (t.is('o', ")") || t.is('o', "]")) {
                depth--;
                if (depth < 0) {
                    return new Unsupported("The parentheses do not match.");
                }
            }
            if (depth == 0 && feel && t.is('i', "between")) {
                betweenOpen = true;
            }
            String joined = depth == 0 ? connectiveOf(t, feel) : null;
            if (joined != null && betweenOpen && "and".equals(joined)) {
                // the 'and' of 'x between a and b' belongs to the range, not to the group
                betweenOpen = false;
                current.add(t);
                continue;
            }
            if (joined != null) {
                if (connective != null && !connective.equals(joined)) {
                    return new Unsupported("It mixes 'and' with 'or'. A canonical condition group uses one connective only.");
                }
                connective = joined;
                if (current.isEmpty()) {
                    return new Unsupported("It is not a simple comparison.");
                }
                operands.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        if (depth != 0) {
            return new Unsupported("The parentheses do not match.");
        }
        if (current.isEmpty()) {
            return new Unsupported("It is not a simple comparison.");
        }
        operands.add(current);
        if (operands.size() == 1) {
            return operand(operands.get(0), feel);
        }
        String group = "and".equals(connective) ? "all" : "any";
        List<Comparison> terms = new ArrayList<>();
        for (List<Token> operand : operands) {
            Parsed term = operand(operand, feel);
            if (term instanceof Unsupported u) {
                return u;
            }
            if (term instanceof Logical l) {
                if (!l.group().equals(group)) {
                    return new Unsupported("It nests a group of conditions inside parentheses. Only one flat group of 'and' or "
                            + "'or' conditions is supported.");
                }
                terms.addAll(l.terms());
            } else {
                terms.add((Comparison) term);
            }
        }
        if (terms.size() > MAX_TERMS) {
            return new Unsupported("It combines " + terms.size() + " conditions; at most " + MAX_TERMS + " are supported.");
        }
        return checked(new Logical(group, terms));
    }

    /** All terms on one variable must agree on the value type (the compiler types every field once). */
    private static Parsed checked(Logical logical) {
        for (Comparison a : logical.terms()) {
            for (Comparison b : logical.terms()) {
                if (a.variable().equals(b.variable()) && !a.valueType().equals(b.valueType())) {
                    return new Unsupported("The variable '" + a.variable() + "' is compared with values of different types.");
                }
            }
        }
        return logical;
    }

    /**
     * One operand of a group: a parenthesised sub-group of the same connective (flattened by the caller), not() over a
     * group (De Morgan: the connective and every operator are inverted), or an atom.
     */
    private static Parsed operand(List<Token> original, boolean feel) {
        List<Token> t = original;
        while (t.size() >= 3 && t.get(0).is('o', "(") && t.get(t.size() - 1).is('o', ")") && matching(t)) {
            t = t.subList(1, t.size() - 1);
        }
        int n = t.size();
        boolean grouped = t.stream().anyMatch(token -> connectiveOf(token, feel) != null)
                || t.stream().anyMatch(token -> feel && token.is('i', "between"));
        if (n >= 4 && (t.get(0).is('i', "not") || t.get(0).is('o', "!") && !feel) && t.get(1).is('o', "(") && t.get(n - 1).is('o', ")")
                && matching(t.subList(1, n))) {
            List<Token> inner = t.subList(2, n - 1);
            boolean innerCompound = inner.stream().anyMatch(token -> connectiveOf(token, feel) != null || feel && token.is('i', "between")
                    || feel && token.is('i', "in"));
            if (innerCompound) {
                Parsed parsedInner = interpret(inner, feel);
                if (parsedInner instanceof Logical l) {
                    List<Comparison> negated = l.terms().stream().map(Comparison::negated).toList();
                    return checked(new Logical("all".equals(l.group()) ? "any" : "all", negated));
                }
                if (parsedInner instanceof Comparison c && !c.booleanShortcut()) {
                    return c.negated();
                }
                return parsedInner instanceof Unsupported ? parsedInner : new Unsupported("It negates a comparison or a group with "
                        + "'not'. Only a yes/no variable can be negated; use the opposite operator instead.");
            }
        }
        if (grouped && original.size() == t.size() && t.stream().noneMatch(token -> token.is('o', "("))) {
            // a bare 'between' range only: handled by atom()
            return atom(t, feel);
        }
        if (grouped && t.size() != original.size()) {
            return interpret(t, feel);
        }
        return atom(t, feel);
    }

    /** {@code and} / {@code or} for a connective token, or null. {@code &&} and {@code ||} are JUEL only. */
    private static String connectiveOf(Token t, boolean feel) {
        if (t.kind() == 'i' && ("and".equals(t.text()) || "or".equals(t.text()))) {
            return t.text();
        }
        if (!feel && t.kind() == 'o' && ("&&".equals(t.text()) || "||".equals(t.text()))) {
            return "&&".equals(t.text()) ? "and" : "or";
        }
        return null;
    }

    /** One atomic operand: a bare (negated) boolean variable or one variable-versus-literal comparison. */
    private static Parsed atom(List<Token> original, boolean feel) {
        List<Token> t = original;
        // A single pair of parentheses around the whole operand.
        while (t.size() >= 3 && t.get(0).is('o', "(") && t.get(t.size() - 1).is('o', ")") && matching(t)) {
            t = t.subList(1, t.size() - 1);
        }
        int n = t.size();
        // not(<one comparison>) and !(<one comparison>): the operator is inverted (not over a group stays unsupported)
        if (n >= 4 && (t.get(0).is('i', "not") || t.get(0).is('o', "!") && !feel) && t.get(1).is('o', "(") && t.get(n - 1).is('o', ")")
                && matching(t.subList(1, n))) {
            List<Token> inner = t.subList(2, n - 1);
            if (inner.stream().noneMatch(token -> connectiveOf(token, feel) != null)) {
                Parsed parsedInner = atom(inner, feel);
                if (parsedInner instanceof Comparison c && !c.booleanShortcut()) {
                    return c.negated();
                }
                if (parsedInner instanceof Unsupported) {
                    return parsedInner;
                }
            }
        }
        // approved, !approved, not approved, not(approved)
        if (n == 1 && t.get(0).kind() == 'i') {
            return bareVariable(t.get(0).text(), true);
        }
        if (n == 2 && (t.get(0).is('o', "!") && !feel || t.get(0).is('i', "not")) && t.get(1).kind() == 'i') {
            return bareVariable(t.get(1).text(), false);
        }
        if (n == 4 && t.get(0).is('i', "not") && t.get(1).is('o', "(") && t.get(2).kind() == 'i' && t.get(3).is('o', ")")) {
            return bareVariable(t.get(2).text(), false);
        }
        if (n == 3 && t.get(0).kind() == 'i' && (t.get(1).kind() == 'o' || t.get(1).kind() == 'i') && isLiteral(t.get(2))) {
            return comparison(t.get(0).text(), t.get(1).text(), t.get(2), feel);
        }
        if (n == 3 && isLiteral(t.get(0)) && t.get(1).kind() == 'o' && t.get(2).kind() == 'i' && !isLiteral(t.get(2))
                && !t.get(2).is('i', "null") && !t.get(2).is('i', "not")) {
            // 5000 <= amount reads as amount >= 5000
            String flipped = switch (t.get(1).text()) {
                case ">=" -> "<=";
                case ">" -> "<";
                case "<" -> ">";
                case "<=" -> ">=";
                default -> t.get(1).text();
            };
            return comparison(t.get(2).text(), flipped, t.get(0), feel);
        }
        if (n == 3 && isLiteral(t.get(0)) && t.get(2).kind() == 'i') {
            return new Unsupported("The variable must be on the left of the comparison.");
        }
        if (feel && n == 5 && t.get(0).kind() == 'i' && t.get(1).is('i', "between") && t.get(3).is('i', "and")
                && t.get(2).kind() == 'n' && t.get(4).kind() == 'n') {
            // x between a and b: both bounds included
            return range(t.get(0).text(), t.get(2), true, t.get(4), true, feel);
        }
        if (feel && n == 8 && t.get(0).kind() == 'i' && t.get(1).is('i', "in") && "[(]".contains(t.get(2).text())
                && t.get(2).kind() == 'o' && t.get(3).kind() == 'n' && t.get(4).is('o', ".") && t.get(5).is('o', ".")
                && t.get(6).kind() == 'n' && t.get(7).kind() == 'o' && "])[".contains(t.get(7).text())) {
            // x in [a..b] closed, ( or ] open at the start, ) or [ open at the end
            return range(t.get(0).text(), t.get(3), "[".equals(t.get(2).text()), t.get(6), "]".equals(t.get(7).text()), feel);
        }
        if (feel && n >= 4 && t.get(0).kind() == 'i' && t.get(1).is('i', "in") && t.get(2).is('o', "(") && t.get(n - 1).is('o', ")")) {
            // x in ("a", "b"): equality with each listed literal, any of them
            List<Token> items = t.subList(3, n - 1);
            String shape = "The list after 'in' must hold plain text, number or yes/no values separated by commas.";
            if (items.isEmpty() || items.size() % 2 == 0) {
                return new Unsupported(shape);
            }
            List<Comparison> options = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Token item = items.get(i);
                if (i % 2 == 1 ? !item.is('o', ",") : !isLiteral(item)) {
                    return new Unsupported(shape);
                }
                if (i % 2 == 0) {
                    Parsed one = comparison(t.get(0).text(), "==", item, feel);
                    if (one instanceof Unsupported u) {
                        return u;
                    }
                    options.add((Comparison) one);
                }
            }
            if (options.size() > MAX_TERMS) {
                return new Unsupported("The list after 'in' has " + options.size() + " values; at most " + MAX_TERMS + " are supported.");
            }
            return options.size() == 1 ? options.get(0) : checked(new Logical("any", options));
        }
        if (n == 3 && t.get(0).kind() == 'i' && t.get(2).is('i', "null") && (t.get(1).kind() == 'o' || t.get(1).kind() == 'i')) {
            return new Unsupported("It tests whether '" + t.get(0).text() + "' is null. Workflow conditions compare a field with a text, "
                    + "number or yes/no value, and a field a condition reads is always provided, so a null check has no equivalent.");
        }
        for (int i = 0; i < n; i++) {
            Token token = t.get(i);
            if (token.kind() == 'i' && i + 1 < n && t.get(i + 1).is('o', "(") && !"not".equals(token.text())) {
                return new Unsupported("It calls the function '" + token.text() + "', which is not supported (function call).");
            }
            if (token.kind() == 'i' && FEEL_KEYWORDS.contains(token.text())) {
                return new Unsupported("It uses the FEEL construct '" + token.text() + "', which is not supported.");
            }
            if (token.is('o', "[") || token.is('o', "{")) {
                return new Unsupported("It uses a list, range or context literal, which is not supported.");
            }
        }
        for (Token token : t) {
            if (connectiveOf(token, feel) != null) {
                return new Unsupported("It nests a group of conditions inside parentheses. Only one flat group of 'and' or 'or' "
                        + "conditions is supported.");
            }
            if (token.is('i', "not") || token.is('o', "!") && !feel) {
                return new Unsupported("It negates a comparison or a group with 'not'. Only a yes/no variable can be negated; "
                        + "use the opposite operator instead.");
            }
            if (token.is('o', "(") || token.kind() == 'i' && token.text().contains(".")) {
                return new Unsupported("It uses a function call, property access or grouping parentheses.");
            }
            if (token.kind() == 'o' && "+-*/%?:".contains(token.text()) || token.is('i', "div") || token.is('i', "mod")) {
                return new Unsupported("It uses arithmetic or a conditional operator.");
            }
        }
        return new Unsupported("It is not a simple comparison of one variable with one value.");
    }

    /** True when the first and the last parenthesis of the token list belong together. */
    private static boolean matching(List<Token> t) {
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).is('o', "(")) {
                depth++;
            } else if (t.get(i).is('o', ")")) {
                depth--;
                if (depth == 0 && i < t.size() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static Parsed bareVariable(String variable, boolean expected) {
        if (variable.contains(".")) {
            return new Unsupported("It uses property access ('" + variable + "').");
        }
        if ("true".equals(variable) || "false".equals(variable) || "null".equals(variable)) {
            return new Unsupported("It is a constant, not a comparison of a variable.");
        }
        return new Comparison(variable, Op.EQ, expected, true);
    }

    private static Parsed range(String variable, Token lo, boolean loClosed, Token hi, boolean hiClosed, boolean feel) {
        if (new BigDecimal(lo.text()).compareTo(new BigDecimal(hi.text())) > 0) {
            return new Unsupported("The range " + lo.text() + ".." + hi.text() + " ends below where it starts.");
        }
        Parsed low = comparison(variable, loClosed ? ">=" : ">", lo, feel);
        Parsed high = comparison(variable, hiClosed ? "<=" : "<", hi, feel);
        if (low instanceof Unsupported) {
            return low;
        }
        if (high instanceof Unsupported) {
            return high;
        }
        return new Logical("all", List.of((Comparison) low, (Comparison) high));
    }

    private static boolean isLiteral(Token token) {
        return token.kind() == 'n' || token.kind() == 's' || token.kind() == 'i' && ("true".equals(token.text()) || "false".equals(token.text()));
    }

    private static Parsed comparison(String variable, String operator, Token literal, boolean feel) {
        if (variable.contains(".")) {
            return new Unsupported("It uses property access ('" + variable + "').");
        }
        Op op = switch (operator) {
            case ">=", "ge" -> Op.GTE;
            case ">", "gt" -> Op.GT;
            case "<", "lt" -> Op.LT;
            case "<=", "le" -> Op.LTE;
            case "==", "eq" -> Op.EQ;
            case "!=", "ne" -> Op.NE;
            case "=" -> feel ? Op.EQ : null;
            default -> null;
        };
        if (op == null) {
            return new Unsupported("The operator '" + operator + "' is not supported.");
        }
        Object value;
        if (literal.kind() == 'n') {
            value = new BigDecimal(literal.text());
        } else if (literal.kind() == 's') {
            if (!SAFE_STRING.matcher(literal.text()).matches()) {
                return new Unsupported("The text value '" + literal.text() + "' has characters that canonical workflows do not "
                        + "allow (letters, digits, spaces and _ . , : / - only, at most 100 characters).");
            }
            value = literal.text();
        } else {
            value = Boolean.valueOf(literal.text());
        }
        if (op.ordering() && !(value instanceof BigDecimal)) {
            return new Unsupported("'" + operator + "' only works with numbers.");
        }
        return new Comparison(variable, op, value, false);
    }
}
