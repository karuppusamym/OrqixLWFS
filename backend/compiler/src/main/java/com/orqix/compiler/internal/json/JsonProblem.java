package com.orqix.compiler.internal.json;

/** A JSON text that cannot be accepted: syntax error, duplicate key, excessive nesting or an unrepresentable number. */
public final class JsonProblem extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        SYNTAX,
        DUPLICATE_KEY,
        TOO_DEEP,
        NUMBER_OUT_OF_RANGE
    }

    private final Kind kind;
    private final String pointer;
    private final int line;
    private final int column;

    public JsonProblem(Kind kind, String pointer, int line, int column, String message) {
        super(message);
        this.kind = kind;
        this.pointer = pointer;
        this.line = line;
        this.column = column;
    }

    public Kind kind() {
        return kind;
    }

    /** @return JSON Pointer of the offending member or container ("" for the document) */
    public String pointer() {
        return pointer;
    }

    /** @return 1-based line, or -1 when unknown */
    public int line() {
        return line;
    }

    /** @return 1-based column, or -1 when unknown */
    public int column() {
        return column;
    }
}
