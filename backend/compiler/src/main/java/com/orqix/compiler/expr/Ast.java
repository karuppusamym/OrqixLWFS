package com.orqix.compiler.expr;

import java.math.BigDecimal;
import java.util.List;

/** Syntax tree of an Orqix expression. Every node keeps the source offset it starts at. */
sealed interface Ast {

    int pos();

    record Num(int pos, BigDecimal value) implements Ast {
    }

    record Str(int pos, String value) implements Ast {
    }

    record Bool(int pos, boolean value) implements Ast {
    }

    record Null(int pos) implements Ast {
    }

    record ListLit(int pos, List<Ast> items) implements Ast {
    }

    /** {@code input.key}, {@code steps.id.key}, {@code item} or a local name (an earlier output of the same step). */
    record Ref(int pos, RefKind kind, String step, String key) implements Ast {
    }

    enum RefKind { INPUT, STEP, ITEM, LOCAL }

    /** {@code target.name}: a column of a row. */
    record Member(int pos, Ast target, String name) implements Ast {
    }

    record Neg(int pos, Ast operand) implements Ast {
    }

    record Binary(int pos, Op op, Ast left, Ast right) implements Ast {
    }

    enum Op {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="), AND("and"),
        OR("or"), IN("in");

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    record If(int pos, Ast condition, Ast then, Ast otherwise) implements Ast {
    }

    record Call(int pos, String name, List<Ast> args) implements Ast {
    }
}
