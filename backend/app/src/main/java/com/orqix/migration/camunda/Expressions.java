package com.orqix.migration.camunda;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the small, explicit subset of Camunda condition expressions that canonical v0.1 can express (S11 section 2):
 * <ul>
 *   <li>JUEL {@code ${amount >= 5000}}, {@code ${status == 'new'}}, {@code ${approved}}, {@code ${!approved}},</li>
 *   <li>FEEL {@code = amount >= 5000}, {@code = status = "new"}, {@code = approved}, {@code = not(approved)}.</li>
 * </ul>
 * Everything else (logic operators, functions, property access, {@code >}, {@code <=}, {@code !=}) is reported as
 * unsupported with the reason, never approximated.
 */
public final class Expressions {

    /** Comparison operators of canonical conditions. */
    public enum Op {
        EQ("eq"), GTE("gte"), LT("lt");

        private final String canonical;

        Op(String canonical) {
            this.canonical = canonical;
        }

        public String canonical() {
            return canonical;
        }
    }

    /** Result of reading one expression. */
    public sealed interface Parsed permits Comparison, Unsupported {
    }

    /** {@code variable op literal}; the literal is a {@link BigDecimal}, {@link String} or {@link Boolean}. */
    public record Comparison(String variable, Op op, Object literal, boolean booleanShortcut) implements Parsed {

        public boolean isBoolean() {
            return literal instanceof Boolean;
        }
    }

    public record Unsupported(String reason) implements Parsed {
    }

    private static final Pattern TOKEN = Pattern.compile("\\s*(?:(?<num>-?\\d+(?:\\.\\d+)?)"
            + "|(?<str>'[^']*'|\"[^\"]*\")"
            + "|(?<id>[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)"
            + "|(?<op>>=|<=|==|!=|&&|\\|\\||[^\\s]))");

    private static final Pattern SAFE_STRING = Pattern.compile("^[A-Za-z0-9 _.,:/-]{0,100}$");

    private Expressions() {
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
        List<String> tokens = new ArrayList<>();
        List<Character> kinds = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(body);
        int position = 0;
        while (position < body.length() && !body.substring(position).isBlank()) {
            matcher.region(position, body.length());
            if (!matcher.lookingAt()) {
                break;
            }
            if (matcher.group("num") != null) {
                tokens.add(matcher.group("num"));
                kinds.add('n');
            } else if (matcher.group("str") != null) {
                String raw = matcher.group("str");
                tokens.add(raw.substring(1, raw.length() - 1));
                kinds.add('s');
            } else if (matcher.group("id") != null) {
                tokens.add(matcher.group("id"));
                kinds.add('i');
            } else {
                tokens.add(matcher.group("op"));
                kinds.add('o');
            }
            position = matcher.end();
        }
        if (tokens.isEmpty()) {
            return new Unsupported("It is not a simple comparison.");
        }
        return interpret(tokens, kinds, feel);
    }

    private static Parsed interpret(List<String> t, List<Character> k, boolean feel) {
        int n = t.size();
        // approved, !approved, not approved, not(approved)
        if (n == 1 && k.get(0) == 'i') {
            return bareVariable(t.get(0), true);
        }
        if (n == 2 && ("!".equals(t.get(0)) || "not".equals(t.get(0))) && k.get(1) == 'i') {
            return bareVariable(t.get(1), false);
        }
        if (n == 4 && "not".equals(t.get(0)) && "(".equals(t.get(1)) && k.get(2) == 'i' && ")".equals(t.get(3))) {
            return bareVariable(t.get(2), false);
        }
        if (n == 3 && k.get(0) == 'i' && (k.get(1) == 'o' || k.get(1) == 'i') && isLiteral(t.get(2), k.get(2))) {
            return comparison(t.get(0), t.get(1), t.get(2), k.get(2), feel);
        }
        if (n == 3 && isLiteral(t.get(0), k.get(0)) && k.get(2) == 'i') {
            return new Unsupported("The variable must be on the left of the comparison.");
        }
        for (int i = 0; i < n; i++) {
            String token = t.get(i);
            if (k.get(i) == 'o' && ("&&".equals(token) || "||".equals(token))
                    || k.get(i) == 'i' && ("and".equals(token) || "or".equals(token))) {
                return new Unsupported("It combines several conditions with '" + token + "'.");
            }
            if (k.get(i) == 'o' && "(".equals(token) || k.get(i) == 'i' && token.contains(".")) {
                return new Unsupported("It uses a function call or property access.");
            }
        }
        return new Unsupported("It is not a simple comparison of one variable with one value.");
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

    private static boolean isLiteral(String token, char kind) {
        return kind == 'n' || kind == 's' || kind == 'i' && ("true".equals(token) || "false".equals(token));
    }

    private static Parsed comparison(String variable, String operator, String literal, char literalKind, boolean feel) {
        if (variable.contains(".")) {
            return new Unsupported("It uses property access ('" + variable + "').");
        }
        Op op = switch (operator) {
            case ">=", "ge" -> Op.GTE;
            case "<", "lt" -> Op.LT;
            case "==", "eq" -> Op.EQ;
            case "=" -> feel ? Op.EQ : null;
            default -> null;
        };
        if (op == null) {
            return new Unsupported(switch (operator) {
                case ">", "gt" -> "The operator '>' has no exact equivalent; use '>=' with the intended limit.";
                case "<=", "le" -> "The operator '<=' has no exact equivalent; use '<' with the intended limit.";
                case "!=", "ne" -> "The operator '!=' is not supported; compare for equality instead.";
                default -> "The operator '" + operator + "' is not supported.";
            });
        }
        Object value;
        if (literalKind == 'n') {
            value = new BigDecimal(literal);
        } else if (literalKind == 's') {
            if (!SAFE_STRING.matcher(literal).matches()) {
                return new Unsupported("The text value '" + literal + "' has characters that canonical workflows do not "
                        + "allow (letters, digits, spaces and _ . , : / - only, at most 100 characters).");
            }
            value = literal;
        } else {
            value = Boolean.valueOf(literal);
        }
        if (op != Op.EQ && !(value instanceof BigDecimal)) {
            return new Unsupported("'" + operator + "' only works with numbers.");
        }
        return new Comparison(variable, op, value, false);
    }
}
