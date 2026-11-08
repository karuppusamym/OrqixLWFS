package com.orqix.compiler.expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lexer and recursive-descent parser of Orqix expressions (docs/build/S137). The TypeScript port in
 * {@code frontend/src/lib/expr/parser.ts} follows it line by line so both report the same codes at the same offsets.
 *
 * <pre>
 * expr    := or
 * or      := and ('or' and)*
 * and     := cmp ('and' cmp)*
 * cmp     := add (('=' | '!=' | '&lt;' | '&lt;=' | '&gt;' | '&gt;=') add | 'in' add)?
 * add     := mul (('+' | '-') mul)*
 * mul     := unary (('*' | '/') unary)*
 * unary   := '-' unary | postfix
 * postfix := primary ('.' name)*
 * primary := number | text | true | false | null | '(' expr ')' | '[' (expr (',' expr)*)? ']'
 *          | 'if' expr 'then' expr 'else' expr | name '(' (expr (',' expr)*)? ')'
 *          | 'input' '.' name | 'steps' '.' stepId '.' name | 'item' | name
 * </pre>
 */
final class Parser {

    static final int MAX_SOURCE = 2000;
    static final int MAX_NODES = 300;
    static final int MAX_DEPTH = 30;
    static final int MAX_LIST = 100;
    static final int MAX_ARGS = 10;
    static final int MAX_DIGITS = 30;

    private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "and", "or", "true", "false", "null", "in");

    enum Kind { NUM, STR, IDENT, SYM, EOF }

    record Token(Kind kind, String text, int pos, boolean spaceBefore) {
        String describe() {
            return kind == Kind.EOF ? "the end" : "\"" + text + "\"";
        }
    }

    private final List<Token> tokens;
    private int at;
    private int nodes;
    private int depth;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Ast parse(String source) throws ExpressionException {
        if (source == null || source.isBlank()) {
            throw new ExpressionException("EXPR_SYNTAX", 0, "Write an expression.");
        }
        if (source.length() > MAX_SOURCE) {
            throw new ExpressionException("EXPR_TOO_COMPLEX", 0,
                    "An expression can be at most " + MAX_SOURCE + " characters long.");
        }
        Parser p = new Parser(lex(source));
        Ast ast = p.expr();
        Token t = p.peek();
        if (t.kind() != Kind.EOF) {
            throw new ExpressionException("EXPR_SYNTAX", t.pos(), "Unexpected " + t.describe() + ".");
        }
        return ast;
    }

    // ---------------------------------------------------------------- lexer

    static List<Token> lex(String s) throws ExpressionException {
        List<Token> out = new ArrayList<>();
        int i = 0;
        boolean space = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                space = true;
                continue;
            }
            int start = i;
            if (c >= '0' && c <= '9') {
                while (i < s.length() && digit(s.charAt(i))) i++;
                if (i + 1 < s.length() && s.charAt(i) == '.' && digit(s.charAt(i + 1))) {
                    i++;
                    while (i < s.length() && digit(s.charAt(i))) i++;
                }
                String text = s.substring(start, i);
                if (text.replace(".", "").length() > MAX_DIGITS) {
                    throw new ExpressionException("EXPR_SYNTAX", start, "A number can have at most " + MAX_DIGITS + " digits.");
                }
                if (i < s.length() && identStart(s.charAt(i))) {
                    throw new ExpressionException("EXPR_SYNTAX", i, "Put a space or an operator after the number " + text + ".");
                }
                out.add(new Token(Kind.NUM, text, start, space));
            } else if (identStart(c)) {
                while (i < s.length() && identPart(s.charAt(i))) i++;
                out.add(new Token(Kind.IDENT, s.substring(start, i), start, space));
            } else if (c == '"') {
                StringBuilder b = new StringBuilder();
                i++;
                while (true) {
                    if (i >= s.length()) {
                        throw new ExpressionException("EXPR_SYNTAX", start, "This text is missing its closing quote.");
                    }
                    char d = s.charAt(i);
                    if (d == '"') {
                        i++;
                        break;
                    }
                    if (d == '\n' || d == '\r') {
                        throw new ExpressionException("EXPR_SYNTAX", i, "Text cannot run over several lines; write \\n for a new line.");
                    }
                    if (d == '\\') {
                        char e = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                        switch (e) {
                            case '"' -> b.append('"');
                            case '\\' -> b.append('\\');
                            case 'n' -> b.append('\n');
                            case 't' -> b.append('\t');
                            default -> throw new ExpressionException("EXPR_SYNTAX", i,
                                    "Only \\\", \\\\, \\n and \\t can follow a backslash.");
                        }
                        i += 2;
                        continue;
                    }
                    b.append(d);
                    i++;
                }
                out.add(new Token(Kind.STR, b.toString(), start, space));
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (two.equals("!=") || two.equals("<=") || two.equals(">=")) {
                    out.add(new Token(Kind.SYM, two, start, space));
                    i += 2;
                } else if (two.equals("==")) {
                    throw new ExpressionException("EXPR_SYNTAX", start, "Use a single = to compare.");
                } else if ("+-*/()[],.=<>".indexOf(c) >= 0) {
                    out.add(new Token(Kind.SYM, String.valueOf(c), start, space));
                    i++;
                } else {
                    throw new ExpressionException("EXPR_SYNTAX", start, "The character " + quoteChar(s, i) + " is not allowed here.");
                }
            }
            space = false;
        }
        out.add(new Token(Kind.EOF, "", s.length(), space));
        return out;
    }

    private static String quoteChar(String s, int i) {
        return "\"" + new String(Character.toChars(s.codePointAt(i))) + "\"";
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean identStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
    }

    private static boolean identPart(char c) {
        return identStart(c) || digit(c);
    }

    // ---------------------------------------------------------------- parser

    private Token peek() {
        return tokens.get(at);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(at + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token t = tokens.get(at);
        if (t.kind() != Kind.EOF) at++;
        return t;
    }

    private boolean sym(String text) {
        Token t = peek();
        return t.kind() == Kind.SYM && t.text().equals(text);
    }

    private boolean word(String text) {
        Token t = peek();
        return t.kind() == Kind.IDENT && t.text().equals(text);
    }

    private Token expectSym(String text) throws ExpressionException {
        if (!sym(text)) {
            Token t = peek();
            throw new ExpressionException("EXPR_SYNTAX", t.pos(), "Expected \"" + text + "\" but found " + t.describe() + ".");
        }
        return next();
    }

    private void expectWord(String text) throws ExpressionException {
        if (!word(text)) {
            Token t = peek();
            throw new ExpressionException("EXPR_SYNTAX", t.pos(), "Expected \"" + text + "\" but found " + t.describe() + ".");
        }
        next();
    }

    private String name() throws ExpressionException {
        Token t = peek();
        if (t.kind() != Kind.IDENT) {
            throw new ExpressionException("EXPR_SYNTAX", t.pos(), "Expected a name but found " + t.describe() + ".");
        }
        return next().text();
    }

    private <T extends Ast> T node(T ast) throws ExpressionException {
        if (++nodes > MAX_NODES) {
            throw new ExpressionException("EXPR_TOO_COMPLEX", ast.pos(),
                    "This expression is too complex: split it into several outputs.");
        }
        return ast;
    }

    private void enter(int pos) throws ExpressionException {
        if (++depth > MAX_DEPTH) {
            throw new ExpressionException("EXPR_TOO_COMPLEX", pos, "This expression is nested too deeply.");
        }
    }

    private Ast expr() throws ExpressionException {
        enter(peek().pos());
        Ast left = and();
        while (word("or")) {
            Token op = next();
            left = node(new Ast.Binary(op.pos(), Ast.Op.OR, left, and()));
        }
        depth--;
        return left;
    }

    private Ast and() throws ExpressionException {
        Ast left = comparison();
        while (word("and")) {
            Token op = next();
            left = node(new Ast.Binary(op.pos(), Ast.Op.AND, left, comparison()));
        }
        return left;
    }

    private Ast comparison() throws ExpressionException {
        Ast left = additive();
        Token t = peek();
        Ast.Op op = null;
        if (t.kind() == Kind.SYM) {
            op = switch (t.text()) {
                case "=" -> Ast.Op.EQ;
                case "!=" -> Ast.Op.NE;
                case "<" -> Ast.Op.LT;
                case "<=" -> Ast.Op.LE;
                case ">" -> Ast.Op.GT;
                case ">=" -> Ast.Op.GE;
                default -> null;
            };
        } else if (word("in")) {
            op = Ast.Op.IN;
        }
        if (op == null) {
            return left;
        }
        next();
        Ast result = node(new Ast.Binary(t.pos(), op, left, additive()));
        Token after = peek();
        if (after.kind() == Kind.SYM && Set.of("=", "!=", "<", "<=", ">", ">=").contains(after.text()) || word("in")) {
            throw new ExpressionException("EXPR_SYNTAX", after.pos(),
                    "Compare two values at a time; join comparisons with and.");
        }
        return result;
    }

    private Ast additive() throws ExpressionException {
        Ast left = multiplicative();
        while (sym("+") || sym("-")) {
            Token op = next();
            left = node(new Ast.Binary(op.pos(), op.text().equals("+") ? Ast.Op.ADD : Ast.Op.SUB, left, multiplicative()));
        }
        return left;
    }

    private Ast multiplicative() throws ExpressionException {
        Ast left = unary();
        while (sym("*") || sym("/")) {
            Token op = next();
            left = node(new Ast.Binary(op.pos(), op.text().equals("*") ? Ast.Op.MUL : Ast.Op.DIV, left, unary()));
        }
        return left;
    }

    private Ast unary() throws ExpressionException {
        if (sym("-")) {
            Token op = next();
            enter(op.pos());
            Ast operand = unary();
            depth--;
            return node(new Ast.Neg(op.pos(), operand));
        }
        return postfix();
    }

    private Ast postfix() throws ExpressionException {
        Ast target = primary();
        while (sym(".")) {
            Token dot = next();
            target = node(new Ast.Member(dot.pos(), target, name()));
        }
        return target;
    }

    private Ast primary() throws ExpressionException {
        Token t = peek();
        switch (t.kind()) {
            case NUM -> {
                next();
                return node(new Ast.Num(t.pos(), new BigDecimal(t.text())));
            }
            case STR -> {
                next();
                return node(new Ast.Str(t.pos(), t.text()));
            }
            case SYM -> {
                if (t.text().equals("(")) {
                    next();
                    Ast inner = expr();
                    expectSym(")");
                    return inner;
                }
                if (t.text().equals("[")) {
                    next();
                    List<Ast> items = new ArrayList<>();
                    if (!sym("]")) {
                        do {
                            if (items.size() == MAX_LIST) {
                                throw new ExpressionException("EXPR_TOO_COMPLEX", peek().pos(),
                                        "A list can have at most " + MAX_LIST + " values.");
                            }
                            items.add(expr());
                        } while (sym(",") && next() != null);
                    }
                    expectSym("]");
                    return node(new Ast.ListLit(t.pos(), List.copyOf(items)));
                }
                throw new ExpressionException("EXPR_SYNTAX", t.pos(), "Expected a value but found " + t.describe() + ".");
            }
            case EOF -> throw new ExpressionException("EXPR_SYNTAX", t.pos(), "The expression ends too early.");
            default -> {
                return word(t);
            }
        }
    }

    private Ast word(Token t) throws ExpressionException {
        String w = t.text();
        switch (w) {
            case "true", "false" -> {
                next();
                return node(new Ast.Bool(t.pos(), w.equals("true")));
            }
            case "null" -> {
                next();
                return node(new Ast.Null(t.pos()));
            }
            case "if" -> {
                next();
                Ast condition = expr();
                expectWord("then");
                Ast then = expr();
                expectWord("else");
                Ast otherwise = expr();
                return node(new Ast.If(t.pos(), condition, then, otherwise));
            }
            default -> {
            }
        }
        if (KEYWORDS.contains(w)) {
            throw new ExpressionException("EXPR_SYNTAX", t.pos(), "Expected a value but found " + t.describe() + ".");
        }
        next();
        if (sym("(")) {
            next();
            List<Ast> args = new ArrayList<>();
            if (!sym(")")) {
                do {
                    if (args.size() == MAX_ARGS) {
                        throw new ExpressionException("EXPR_ARGUMENTS", peek().pos(), w + " takes at most " + MAX_ARGS + " values.");
                    }
                    args.add(expr());
                } while (sym(",") && next() != null);
            }
            expectSym(")");
            return node(new Ast.Call(t.pos(), w, List.copyOf(args)));
        }
        return switch (w) {
            case "input" -> {
                expectSym(".");
                yield node(new Ast.Ref(t.pos(), Ast.RefKind.INPUT, null, name()));
            }
            case "steps" -> {
                expectSym(".");
                String step = stepId();
                expectSym(".");
                yield node(new Ast.Ref(t.pos(), Ast.RefKind.STEP, step, name()));
            }
            case "item" -> node(new Ast.Ref(t.pos(), Ast.RefKind.ITEM, null, null));
            default -> node(new Ast.Ref(t.pos(), Ast.RefKind.LOCAL, null, w));
        };
    }

    /** A step ID may contain hyphens ({@code check-credit}); they are part of it only when written without spaces. */
    private String stepId() throws ExpressionException {
        StringBuilder id = new StringBuilder(name());
        while (sym("-") && !peek().spaceBefore()) {
            Token part = peek(1);
            if (part.spaceBefore() || part.kind() != Kind.IDENT && part.kind() != Kind.NUM) {
                break;
            }
            next();
            next();
            id.append('-').append(part.text());
        }
        return id.toString();
    }
}
