package com.orqix.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The text-answer pattern of a request form field ({@code pattern}, docs/build/S90-form-pattern-and-ai-assistant.md).
 *
 * <p>Safety: answers are matched by simulating a Thompson NFA (one pass over the answer, at most
 * {@link #MAX_STATES} active states per character), never by a backtracking engine, so matching is linear in the
 * answer length whatever the pattern. Patterns are at most {@link #MAX_PATTERN_LENGTH} characters, repeat counts at most
 * {@link #MAX_REPEAT}, the compiled automaton at most {@link #MAX_STATES} states, and answers longer than
 * {@link #MAX_INPUT_LENGTH} characters never match.
 *
 * <p>Semantics: a strict subset of ECMAScript regular expressions with the {@code u} flag, chosen so that the same
 * source means the same thing to this class, to the browser engine ({@code frontend/src/lib/form/pattern.ts}, a line
 * by line port) and to any JSON Schema validator: the pattern must start with {@code ^} and end with {@code $} (so
 * search and whole-answer matching agree), alternatives at the top level must be grouped, and only these constructs are
 * accepted: literal characters, {@code .}, classes {@code [...]} and {@code [^...]} with ranges, {@code \d \D \w \W \s
 * \S}, escaped syntax characters, groups {@code (...)} and {@code (?:...)}, alternation {@code |}, and the greedy
 * quantifiers {@code * + ? {n} {n,} {n,m}}. Everything else (backreferences, lookaround, lazy quantifiers, word
 * boundaries, Unicode property escapes, flags) is refused with a reason. Characters are Unicode code points;
 * {@code \d} is {@code [0-9]}, {@code \w} is {@code [A-Za-z0-9_]}, {@code \s} is the ECMAScript white space and line
 * terminator set, and {@code .} is any code point except line terminators. Matching is case-sensitive.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class FormPattern {

    public static final int MAX_PATTERN_LENGTH = 200;
    public static final int MAX_INPUT_LENGTH = 4000;
    public static final int MAX_REPEAT = 100;
    public static final int MAX_STATES = 2000;
    private static final int MAX_GROUP_DEPTH = 20;
    private static final int MAX_EXAMPLE_SETS = 2000;
    private static final int MAX_BUILD_STEPS = 50_000;

    /** A pattern that cannot be used; the message says why, in plain words. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ character sets

    /** One set of code points: sorted inclusive ranges, optionally negated. */
    private record Item(int[] ranges, boolean negated) {
        boolean matches(int cp) {
            boolean in = false;
            for (int i = 0; i < ranges.length; i += 2) {
                if (cp >= ranges[i] && cp <= ranges[i + 1]) {
                    in = true;
                    break;
                }
            }
            return in != negated;
        }
    }

    /** A character test: the union of items, optionally negated (a class {@code [^...]}). */
    private record Pred(Item[] items, boolean negated) {
        boolean matches(int cp) {
            boolean any = false;
            for (Item item : items) {
                if (item.matches(cp)) {
                    any = true;
                    break;
                }
            }
            return any != negated;
        }

        /** A readable member for examples, or -1 when the set is empty. */
        int representative() {
            if (!negated) {
                for (Item item : items) {
                    if (!item.negated && item.ranges.length > 0) {
                        return item.ranges[0];
                    }
                }
            }
            for (int c = 'a'; c <= 'z'; c++) {
                if (matches(c)) {
                    return c;
                }
            }
            for (int c = 'A'; c <= 'Z'; c++) {
                if (matches(c)) {
                    return c;
                }
            }
            for (int c = '0'; c <= '9'; c++) {
                if (matches(c)) {
                    return c;
                }
            }
            for (int c = 0x20; c <= 0x2FF; c++) {
                if (matches(c)) {
                    return c;
                }
            }
            return -1;
        }
    }

    private static final int[] DIGIT = {'0', '9'};
    private static final int[] WORD = {'0', '9', 'A', 'Z', '_', '_', 'a', 'z'};
    /** ECMAScript WhiteSpace and LineTerminator code points (the meaning of \s). */
    private static final int[] SPACE = {0x09, 0x0D, 0x20, 0x20, 0xA0, 0xA0, 0x1680, 0x1680, 0x2000, 0x200A,
            0x2028, 0x2029, 0x202F, 0x202F, 0x205F, 0x205F, 0x3000, 0x3000, 0xFEFF, 0xFEFF};
    private static final int[] LINE_TERMINATORS = {0x0A, 0x0A, 0x0D, 0x0D, 0x2028, 0x2029};
    private static final String SYNTAX = "^$\\.*+?()[]{}|/";

    private static Pred single(int cp) {
        return new Pred(new Item[] {new Item(new int[] {cp, cp}, false)}, false);
    }

    // ------------------------------------------------------------------ syntax tree

    private sealed interface Node permits Chars, Seq, Alt, Repeat {
    }

    private record Chars(Pred pred) implements Node {
    }

    private record Seq(List<Node> items) implements Node {
    }

    private record Alt(List<Node> branches) implements Node {
    }

    /** {@code max} is -1 for no upper bound. */
    private record Repeat(Node node, int min, int max) implements Node {
    }

    // ------------------------------------------------------------------ automaton

    private static final int CHAR = 0;
    private static final int SPLIT = 1;
    private static final int MATCH = 2;

    private final String source;
    private final int[] kind;
    private final int[] out1;
    private final int[] out2;
    private final Pred[] preds;
    private final int start;

    private FormPattern(String source, int[] kind, int[] out1, int[] out2, Pred[] preds, int start) {
        this.source = source;
        this.kind = kind;
        this.out1 = out1;
        this.out2 = out2;
        this.preds = preds;
        this.start = start;
    }

    /**
     * Compiles a pattern.
     *
     * @throws Invalid with the reason when the pattern is not in the supported subset, too long or too complex, or
     *                 when no answer can match it
     */
    public static FormPattern compile(String source) {
        if (source == null || source.isEmpty()) {
            throw new Invalid("The pattern is empty.");
        }
        int[] cps = source.codePoints().toArray();
        if (cps.length > MAX_PATTERN_LENGTH) {
            throw new Invalid("The pattern is longer than " + MAX_PATTERN_LENGTH + " characters.");
        }
        Parser parser = new Parser(cps);
        Node tree = parser.parsePattern();
        Builder builder = new Builder();
        int match = builder.state(MATCH, -1, -1, null);
        int start = builder.compile(tree, match);
        FormPattern pattern = new FormPattern(source, builder.kinds(), builder.outs1(), builder.outs2(),
                builder.preds(), start);
        String example = pattern.search();
        if (example == TOO_COMPLEX) {
            throw new Invalid("The pattern is too complex to check. Simplify it.");
        }
        if (example == null) {
            throw new Invalid("No answer can match this pattern.");
        }
        return pattern;
    }

    /** @return the reason the pattern cannot be used, or null when it compiles */
    public static String problem(String source) {
        try {
            compile(source);
            return null;
        } catch (Invalid e) {
            return e.getMessage();
        }
    }

    public String source() {
        return source;
    }

    /** True when the whole answer matches. Answers longer than {@link #MAX_INPUT_LENGTH} code points never match. */
    public boolean matches(String input) {
        if (input == null) {
            return false;
        }
        int length = input.codePointCount(0, input.length());
        if (length > MAX_INPUT_LENGTH) {
            return false;
        }
        int n = kind.length;
        int[] marks = new int[n];
        int[] current = new int[n];
        int[] next = new int[n];
        int[] stack = new int[n * 2 + 2];
        int generation = 1;
        int currentSize = add(current, 0, start, marks, generation, stack);
        int i = 0;
        while (i < input.length()) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            generation++;
            int nextSize = 0;
            for (int k = 0; k < currentSize; k++) {
                int s = current[k];
                if (kind[s] == CHAR && preds[s].matches(cp)) {
                    nextSize = add(next, nextSize, out1[s], marks, generation, stack);
                }
            }
            if (nextSize == 0) {
                return false;
            }
            int[] swap = current;
            current = next;
            next = swap;
            currentSize = nextSize;
        }
        for (int k = 0; k < currentSize; k++) {
            if (kind[current[k]] == MATCH) {
                return true;
            }
        }
        return false;
    }

    /** Adds the state and everything reachable from it without reading a character (iterative, cycle-safe). */
    private int add(int[] list, int size, int state, int[] marks, int generation, int[] stack) {
        int top = 0;
        stack[top++] = state;
        while (top > 0) {
            int s = stack[--top];
            if (marks[s] == generation) {
                continue;
            }
            marks[s] = generation;
            if (kind[s] == SPLIT) {
                stack[top++] = out2[s];
                stack[top++] = out1[s];
            } else {
                list[size++] = s;
            }
        }
        return size;
    }

    private int[] closure(int[] states) {
        int n = kind.length;
        int[] marks = new int[n];
        int[] list = new int[n];
        int[] stack = new int[n * 2 + 2];
        int size = 0;
        for (int s : states) {
            size = add(list, size, s, marks, 1, stack);
        }
        int[] result = Arrays.copyOf(list, size);
        Arrays.sort(result);
        return result;
    }

    /**
     * A shortest answer that matches, built from readable characters (for sample requests and the editor), or null
     * when no answer matches.
     */
    public String example() {
        String example = search();
        return example == TOO_COMPLEX ? null : example;
    }

    private static final String TOO_COMPLEX = new String("too complex");

    /** Breadth-first search over sets of states; {@link #TOO_COMPLEX} when the search limit was reached. */
    private String search() {
        record Entry(int[] states, String text) {
        }
        ArrayDeque<Entry> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        int[] first = closure(new int[] {start});
        queue.add(new Entry(first, ""));
        seen.add(Arrays.toString(first));
        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            for (int s : entry.states()) {
                if (kind[s] == MATCH) {
                    return entry.text();
                }
            }
            if (entry.text().codePointCount(0, entry.text().length()) >= MAX_INPUT_LENGTH) {
                continue;
            }
            TreeMap<Integer, Boolean> candidates = new TreeMap<>();
            for (int s : entry.states()) {
                if (kind[s] == CHAR) {
                    int c = preds[s].representative();
                    if (c >= 0) {
                        candidates.put(c, Boolean.TRUE);
                    }
                }
            }
            for (int c : candidates.keySet()) {
                List<Integer> targets = new ArrayList<>();
                for (int s : entry.states()) {
                    if (kind[s] == CHAR && preds[s].matches(c)) {
                        targets.add(out1[s]);
                    }
                }
                int[] next = closure(targets.stream().mapToInt(Integer::intValue).toArray());
                if (seen.size() >= MAX_EXAMPLE_SETS) {
                    return TOO_COMPLEX;
                }
                if (seen.add(Arrays.toString(next))) {
                    queue.add(new Entry(next, entry.text() + Character.toString(c)));
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final int[] cps;
        private final int end;
        private int pos;
        private int depth;
        private boolean topLevelAlternation;

        Parser(int[] cps) {
            this.cps = cps;
            this.end = cps.length - 1;
        }

        Node parsePattern() {
            if (cps[0] != '^' || cps.length < 2 || cps[cps.length - 1] != '$' || escapedAt(cps.length - 1)) {
                throw new Invalid("Start the pattern with ^ and end it with $, so that it applies to the whole answer.");
            }
            pos = 1;
            Node tree = parseAlt();
            if (pos != end) {
                throw new Invalid(cps[pos] == ')' ? "There is a ) without a matching (."
                        : "Unexpected " + show(cps[pos]) + ".");
            }
            if (topLevelAlternation) {
                throw new Invalid("Put alternatives in a group, for example ^(red|blue)$: at the top level, ^ and $ "
                        + "would only apply to the first and last alternative.");
            }
            return tree;
        }

        private boolean escapedAt(int index) {
            int slashes = 0;
            for (int i = index - 1; i >= 0 && cps[i] == '\\'; i--) {
                slashes++;
            }
            return slashes % 2 == 1;
        }

        private Node parseAlt() {
            List<Node> branches = new ArrayList<>();
            branches.add(parseSeq());
            while (pos < end && cps[pos] == '|') {
                if (depth == 0) {
                    topLevelAlternation = true;
                }
                pos++;
                branches.add(parseSeq());
            }
            return branches.size() == 1 ? branches.get(0) : new Alt(List.copyOf(branches));
        }

        private Node parseSeq() {
            List<Node> items = new ArrayList<>();
            while (pos < end && cps[pos] != '|' && cps[pos] != ')') {
                Node atom = parseAtom();
                if (pos < end && isQuantifierStart(cps[pos])) {
                    atom = parseQuantifier(atom);
                    if (pos < end && cps[pos] == '?') {
                        throw new Invalid("Lazy quantifiers (such as *? or +?) are not supported.");
                    }
                    if (pos < end && isQuantifierStart(cps[pos])) {
                        throw new Invalid("A repeat cannot follow another repeat (" + show(cps[pos]) + ").");
                    }
                }
                items.add(atom);
            }
            return items.size() == 1 ? items.get(0) : new Seq(List.copyOf(items));
        }

        private static boolean isQuantifierStart(int c) {
            return c == '*' || c == '+' || c == '?' || c == '{';
        }

        private Node parseQuantifier(Node atom) {
            int c = cps[pos++];
            switch (c) {
                case '*':
                    return new Repeat(atom, 0, -1);
                case '+':
                    return new Repeat(atom, 1, -1);
                case '?':
                    return new Repeat(atom, 0, 1);
                default:
                    break;
            }
            int min = number();
            int max = min;
            if (pos < end && cps[pos] == ',') {
                pos++;
                max = pos < end && cps[pos] == '}' ? -1 : number();
            }
            if (pos >= end || cps[pos] != '}') {
                throw badBrace();
            }
            pos++;
            if (min > MAX_REPEAT || max > MAX_REPEAT) {
                throw new Invalid("Repeat counts are limited to " + MAX_REPEAT + ".");
            }
            if (max != -1 && max < min) {
                throw new Invalid("In a repeat count {n,m}, n cannot be larger than m.");
            }
            return new Repeat(atom, min, max);
        }

        private int number() {
            int start = pos;
            long value = 0;
            while (pos < end && cps[pos] >= '0' && cps[pos] <= '9') {
                value = Math.min(value * 10 + (cps[pos] - '0'), Integer.MAX_VALUE);
                pos++;
            }
            if (pos == start) {
                throw badBrace();
            }
            return (int) value;
        }

        private static Invalid badBrace() {
            return new Invalid("Write repeat counts as {n}, {n,} or {n,m}; write a literal { as \\{.");
        }

        private Node parseAtom() {
            int c = cps[pos];
            switch (c) {
                case '(' -> {
                    pos++;
                    if (pos < end && cps[pos] == '?') {
                        if (pos + 1 < end && cps[pos + 1] == ':') {
                            pos += 2;
                        } else {
                            throw new Invalid("Lookarounds, named groups and inline flags (?...) are not supported;"
                                    + " use (...) or (?:...).");
                        }
                    }
                    if (++depth > MAX_GROUP_DEPTH) {
                        throw new Invalid("Groups are nested too deeply.");
                    }
                    Node inner = parseAlt();
                    depth--;
                    if (pos >= end || cps[pos] != ')') {
                        throw new Invalid("A group ( is not closed.");
                    }
                    pos++;
                    return inner;
                }
                case '[' -> {
                    pos++;
                    return new Chars(parseClass());
                }
                case '.' -> {
                    pos++;
                    return new Chars(new Pred(new Item[] {new Item(LINE_TERMINATORS, true)}, false));
                }
                case '\\' -> {
                    pos++;
                    Object escape = parseEscape(false);
                    return new Chars(escape instanceof Item item ? new Pred(new Item[] {item}, false)
                            : single((Integer) escape));
                }
                case '^', '$' -> throw new Invalid("^ and $ are only allowed at the very start and end of the pattern.");
                case '*', '+', '?', '{' -> throw new Invalid("Nothing to repeat before " + show(c) + ".");
                case ']', '}' -> throw new Invalid("Write a literal " + show(c) + " as \\" + Character.toString(c) + ".");
                default -> {
                    checkPrintable(c);
                    pos++;
                    return new Chars(single(c));
                }
            }
        }

        /** @return an {@link Item} for a class escape, or the code point (Integer) of a literal escape */
        private Object parseEscape(boolean inClass) {
            if (pos >= end) {
                throw new Invalid("The pattern ends with a backslash.");
            }
            int c = cps[pos++];
            return switch (c) {
                case 'd' -> new Item(DIGIT, false);
                case 'D' -> new Item(DIGIT, true);
                case 'w' -> new Item(WORD, false);
                case 'W' -> new Item(WORD, true);
                case 's' -> new Item(SPACE, false);
                case 'S' -> new Item(SPACE, true);
                default -> {
                    if (SYNTAX.indexOf(c) >= 0 || (inClass && c == '-')) {
                        yield c;
                    }
                    String hint = c >= '1' && c <= '9' ? " Backreferences are not supported."
                            : c == 'b' || c == 'B' ? " Word boundaries are not supported."
                            : c == 'p' || c == 'P' ? " Unicode property escapes are not supported."
                            : "";
                    throw new Invalid("\\" + Character.toString(c) + " is not supported." + hint
                            + " Use \\d \\D \\w \\W \\s \\S, or a backslash before one of ^ $ \\ . * + ? ( ) [ ] { } | /"
                            + (inClass ? " -" : "") + ".");
                }
            };
        }

        private Pred parseClass() {
            boolean negated = false;
            if (pos < end && cps[pos] == '^') {
                negated = true;
                pos++;
            }
            List<Item> items = new ArrayList<>();
            List<int[]> literalRanges = new ArrayList<>();
            boolean first = true;
            while (true) {
                if (pos >= end) {
                    throw new Invalid("A character class [ is not closed.");
                }
                int c = cps[pos];
                if (c == ']') {
                    if (first) {
                        throw new Invalid("A character class [] must list at least one character.");
                    }
                    pos++;
                    break;
                }
                Object low = classAtom(first);
                first = false;
                if (pos < end && cps[pos] == '-' && pos + 1 < end && cps[pos + 1] != ']') {
                    if (low instanceof Item) {
                        throw new Invalid("A range cannot start or end with \\d, \\w or \\s.");
                    }
                    pos++;
                    Object high = classAtom(false);
                    if (high instanceof Item) {
                        throw new Invalid("A range cannot start or end with \\d, \\w or \\s.");
                    }
                    int lo = (Integer) low;
                    int hi = (Integer) high;
                    if (hi < lo) {
                        throw new Invalid("The range " + show(lo) + "-" + show(hi) + " is out of order.");
                    }
                    literalRanges.add(new int[] {lo, hi});
                } else if (low instanceof Item item) {
                    items.add(item);
                } else {
                    int cp = (Integer) low;
                    literalRanges.add(new int[] {cp, cp});
                }
            }
            if (!literalRanges.isEmpty()) {
                literalRanges.sort((a, b) -> Integer.compare(a[0], b[0]));
                int[] flat = new int[literalRanges.size() * 2];
                for (int i = 0; i < literalRanges.size(); i++) {
                    flat[2 * i] = literalRanges.get(i)[0];
                    flat[2 * i + 1] = literalRanges.get(i)[1];
                }
                items.add(0, new Item(flat, false));
            }
            return new Pred(items.toArray(Item[]::new), negated);
        }

        private Object classAtom(boolean first) {
            int c = cps[pos];
            if (c == '\\') {
                pos++;
                return parseEscape(true);
            }
            if (c == '[') {
                throw new Invalid("Write a literal [ inside a class as \\[.");
            }
            if (c == '-' && !first && pos + 1 < end && cps[pos + 1] != ']') {
                throw new Invalid("Inside a class, put - first or last, or write it as \\-.");
            }
            checkPrintable(c);
            pos++;
            return c;
        }

        private static void checkPrintable(int c) {
            if (c < 0x20 || c == 0x7F) {
                throw new Invalid("Control characters are not allowed in a pattern; use \\s for white space.");
            }
        }
    }

    static String show(int c) {
        return "\"" + Character.toString(c) + "\"";
    }

    // ------------------------------------------------------------------ automaton builder

    private static final class Builder {
        private int[] kinds = new int[64];
        private int[] outs1 = new int[64];
        private int[] outs2 = new int[64];
        private Pred[] predicates = new Pred[64];
        private int size;
        private int steps;

        int state(int kind, int out1, int out2, Pred pred) {
            if (size >= MAX_STATES) {
                throw new Invalid("The pattern is too complex: repeat counts multiply. Use smaller repeat counts.");
            }
            if (size == kinds.length) {
                kinds = Arrays.copyOf(kinds, size * 2);
                outs1 = Arrays.copyOf(outs1, size * 2);
                outs2 = Arrays.copyOf(outs2, size * 2);
                predicates = Arrays.copyOf(predicates, size * 2);
            }
            kinds[size] = kind;
            outs1[size] = out1;
            outs2[size] = out2;
            predicates[size] = pred;
            return size++;
        }

        /** Builds the states for {@code node} followed by {@code next}; returns the entry state. */
        int compile(Node node, int next) {
            if (++steps > MAX_BUILD_STEPS) {
                throw new Invalid("The pattern is too complex: repeat counts multiply. Use smaller repeat counts.");
            }
            if (node instanceof Chars chars) {
                return state(CHAR, next, -1, chars.pred());
            }
            if (node instanceof Seq seq) {
                int entry = next;
                for (int i = seq.items().size() - 1; i >= 0; i--) {
                    entry = compile(seq.items().get(i), entry);
                }
                return entry;
            }
            if (node instanceof Alt alt) {
                int entry = compile(alt.branches().get(alt.branches().size() - 1), next);
                for (int i = alt.branches().size() - 2; i >= 0; i--) {
                    int branch = compile(alt.branches().get(i), next);
                    entry = state(SPLIT, branch, entry, null);
                }
                return entry;
            }
            Repeat repeat = (Repeat) node;
            int entry;
            if (repeat.max() == -1) {
                int loop = state(SPLIT, -1, next, null);
                outs1[loop] = compile(repeat.node(), loop);
                entry = loop;
            } else {
                entry = next;
                for (int i = 0; i < repeat.max() - repeat.min(); i++) {
                    int body = compile(repeat.node(), entry);
                    entry = state(SPLIT, body, next, null);
                }
            }
            for (int i = 0; i < repeat.min(); i++) {
                entry = compile(repeat.node(), entry);
            }
            return entry;
        }

        int[] kinds() {
            return Arrays.copyOf(kinds, size);
        }

        int[] outs1() {
            return Arrays.copyOf(outs1, size);
        }

        int[] outs2() {
            return Arrays.copyOf(outs2, size);
        }

        Pred[] preds() {
            return Arrays.copyOf(predicates, size);
        }
    }
}
