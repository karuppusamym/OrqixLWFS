package com.orqix.migration.dmn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the literal FEEL unary tests of a DMN input entry and the literal output values of a DMN output entry. Only
 * literals are understood: {@code -}, numbers, text in double quotes, {@code true}/{@code false}, comparisons
 * ({@code >= 5000}), ranges ({@code [1000..5000)}), comma lists and {@code not(...)} around a list. Anything computed
 * (expressions, functions, variable references, dates, null) is rejected with a reason; nothing is evaluated.
 */
final class DmnEntries {

    /** An entry that cannot be converted; {@code code} is the report reason code. */
    static final class EntryProblem extends Exception {
        private final String code;

        EntryProblem(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final Pattern NUMBER_ONLY = Pattern.compile(NUMBER);
    private static final Pattern COMPARISON = Pattern.compile("^(>=|<=|>|<)\\s*(" + NUMBER + ")$");
    private static final Pattern RANGE = Pattern.compile("^([\\[(\\]])\\s*(" + NUMBER + ")\\s*\\.\\.\\s*(" + NUMBER + ")\\s*([\\])\\[])$");
    private static final Pattern NOT = Pattern.compile("^not\\s*\\((.*)\\)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("^\"([^\"]*)\"$");
    /** Same characters the canonical schema allows in text literals (compiler rule WF_LITERAL_UNSAFE). */
    private static final Pattern SAFE_STRING = Pattern.compile("^[A-Za-z0-9 _.,:/-]{0,100}$");

    private DmnEntries() {
    }

    /** True for an empty entry or {@code -}: the rule does not test this input. */
    static boolean isWildcard(String text) {
        return text == null || text.isBlank() || "-".equals(text.strip());
    }

    /**
     * @param type     number | integer | string | boolean
     * @param universe the allowed values of an enumerated or yes/no input, or null when unbounded
     */
    static Region parseInput(String text, String type, Set<Object> universe) throws EntryProblem {
        boolean numeric = type.equals("number") || type.equals("integer");
        boolean integer = type.equals("integer");
        Region empty = numeric ? Region.noNumbers(integer) : Region.values(Set.of(), universe);
        if (isWildcard(text)) {
            return empty.everything();
        }
        String body = text.strip();
        Matcher negation = NOT.matcher(body);
        boolean negate = negation.matches();
        if (negate) {
            body = negation.group(1).strip();
            if (isWildcard(body)) {
                throw new EntryProblem("DMN_ENTRY_NOT_SUPPORTED", "not() needs at least one value or range.");
            }
        }
        Region region = empty;
        for (String item : splitList(body, text)) {
            region = region.union(parseItem(item.strip(), type, universe, text));
        }
        return negate ? region.negate() : region;
    }

    private static Region parseItem(String item, String type, Set<Object> universe, String whole) throws EntryProblem {
        boolean integer = type.equals("integer");
        switch (type) {
            case "number", "integer" -> {
                Matcher comparison = COMPARISON.matcher(item);
                if (comparison.matches()) {
                    BigDecimal bound = number(comparison.group(2), integer, whole);
                    return Region.numbers(integer, List.of(switch (comparison.group(1)) {
                        case ">=" -> new Region.Interval(bound, true, null, false);
                        case ">" -> new Region.Interval(bound, false, null, false);
                        case "<=" -> new Region.Interval(null, false, bound, true);
                        default -> new Region.Interval(null, false, bound, false);
                    }));
                }
                Matcher range = RANGE.matcher(item);
                if (range.matches()) {
                    BigDecimal lo = number(range.group(2), integer, whole);
                    BigDecimal hi = number(range.group(3), integer, whole);
                    if (lo.compareTo(hi) > 0) {
                        throw new EntryProblem("DMN_ENTRY_NOT_SUPPORTED", "The range '" + item + "' ends below where it starts.");
                    }
                    boolean loClosed = "[".equals(range.group(1));
                    boolean hiClosed = "]".equals(range.group(4));
                    return Region.numbers(integer, List.of(new Region.Interval(lo, loClosed, hi, hiClosed)));
                }
                if (NUMBER_ONLY.matcher(item).matches()) {
                    BigDecimal value = number(item, integer, whole);
                    return Region.numbers(integer, List.of(new Region.Interval(value, true, value, true)));
                }
                throw unsupported(item, "a number, a comparison such as >= 5000, or a range such as [1000..5000)");
            }
            case "string" -> {
                Matcher quoted = QUOTED.matcher(item);
                if (!quoted.matches()) {
                    throw unsupported(item, "a text value in double quotes");
                }
                String value = quoted.group(1);
                if (!SAFE_STRING.matcher(value).matches()) {
                    throw new EntryProblem("DMN_LITERAL_NOT_ALLOWED", "The text value \"" + value + "\" has characters that "
                            + "canonical workflows do not allow (letters, digits, spaces and _ . , : / - only, at most 100 "
                            + "characters).");
                }
                if (universe != null && !universe.contains(value)) {
                    throw new EntryProblem("DMN_ENTRY_OUTSIDE_INPUT_VALUES", "The value \"" + value + "\" is not one of the "
                            + "allowed input values of this column.");
                }
                return Region.values(Set.of(value), universe);
            }
            case "boolean" -> {
                if ("true".equals(item) || "false".equals(item)) {
                    return Region.values(Set.of(Boolean.valueOf(item)), universe);
                }
                throw unsupported(item, "true or false");
            }
            default -> throw new EntryProblem("DMN_TYPE_NOT_SUPPORTED", "The input type '" + type + "' is not supported.");
        }
    }

    /** The literal output of one rule cell, typed by its column. */
    static Object parseOutput(String text, String type) throws EntryProblem {
        if (text == null || text.isBlank()) {
            throw new EntryProblem("DMN_OUTPUT_EMPTY", "The output entry is empty. A canonical rule must set every result.");
        }
        String value = text.strip();
        switch (type) {
            case "number", "integer" -> {
                if (!NUMBER_ONLY.matcher(value).matches()) {
                    throw new EntryProblem("DMN_OUTPUT_EXPRESSION_NOT_SUPPORTED", "The output '" + value + "' is not a number "
                            + "literal. Computed outputs are not converted.");
                }
                return number(value, type.equals("integer"), text);
            }
            case "string" -> {
                Matcher quoted = QUOTED.matcher(value);
                if (!quoted.matches()) {
                    throw new EntryProblem("DMN_OUTPUT_EXPRESSION_NOT_SUPPORTED", "The output '" + value + "' is not a text "
                            + "literal in double quotes. Computed outputs are not converted.");
                }
                if (!SAFE_STRING.matcher(quoted.group(1)).matches()) {
                    throw new EntryProblem("DMN_LITERAL_NOT_ALLOWED", "The text value \"" + quoted.group(1) + "\" has characters "
                            + "that canonical workflows do not allow (letters, digits, spaces and _ . , : / - only, at most "
                            + "100 characters).");
                }
                return quoted.group(1);
            }
            case "boolean" -> {
                if ("true".equals(value) || "false".equals(value)) {
                    return Boolean.valueOf(value);
                }
                throw new EntryProblem("DMN_OUTPUT_EXPRESSION_NOT_SUPPORTED", "The output '" + value + "' is not true or false.");
            }
            default -> throw new EntryProblem("DMN_TYPE_NOT_SUPPORTED", "The output type '" + type + "' is not supported.");
        }
    }

    /** The kind of literal an entry holds, used to infer a missing column type; null when it is not a plain literal. */
    static String inferKind(String text) {
        if (isWildcard(text)) {
            return null;
        }
        String value = text.strip();
        Matcher negation = NOT.matcher(value);
        if (negation.matches()) {
            value = negation.group(1).strip();
        }
        if (value.startsWith("\"")) {
            return "string";
        }
        if (value.startsWith("true") || value.startsWith("false")) {
            return "boolean";
        }
        if (!value.isEmpty() && (Character.isDigit(value.charAt(0)) || "-<>[]()".indexOf(value.charAt(0)) >= 0)) {
            return "number";
        }
        return null;
    }

    private static BigDecimal number(String literal, boolean integer, String whole) throws EntryProblem {
        BigDecimal value = new BigDecimal(literal);
        if (integer && value.stripTrailingZeros().scale() > 0) {
            throw new EntryProblem("DMN_LITERAL_TYPE_MISMATCH", "The value " + literal + " in '" + whole + "' is not a whole "
                    + "number but the input column is an integer.");
        }
        return value;
    }

    private static EntryProblem unsupported(String item, String expected) {
        String lower = item.toLowerCase(Locale.ROOT);
        String why = lower.contains("(") ? "It calls a function or groups an expression."
                : lower.contains("null") ? "It tests for null."
                : item.matches(".*[A-Za-z_].*") && !item.startsWith("\"") ? "It names a variable or uses an expression."
                : "It is not a literal test.";
        return new EntryProblem("DMN_ENTRY_NOT_SUPPORTED", "The entry '" + item + "' is not supported. " + why + " Expected "
                + expected + ".");
    }

    /** Splits a list on top-level commas, ignoring commas inside quotes, brackets and parentheses. */
    private static List<String> splitList(String body, String whole) throws EntryProblem {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (char c : body.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (!quoted) {
                if (c == '[' || c == '(') {
                    depth++;
                } else if (c == ']' || c == ')') {
                    depth--;
                }
                // A closing bracket may also open a range ("]1..5[" style); only a balanced split is accepted.
            }
            if (c == ',' && !quoted && depth == 0) {
                items.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        items.add(current.toString());
        if (quoted) {
            throw new EntryProblem("DMN_ENTRY_NOT_SUPPORTED", "The entry '" + whole + "' has an unterminated quote.");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String item : items) {
            if (item.isBlank()) {
                throw new EntryProblem("DMN_ENTRY_NOT_SUPPORTED", "The entry '" + whole + "' has an empty list item.");
            }
            seen.add(item);
        }
        return new ArrayList<>(seen);
    }
}
