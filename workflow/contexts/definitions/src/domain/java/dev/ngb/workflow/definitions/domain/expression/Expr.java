package dev.ngb.workflow.definitions.domain.expression;

import java.util.List;

/**
 * One expression tree: a literal, an array literal, a reference or an operation.
 *
 * <p>The tree is what the contract's JSON document describes, and the only shape the evaluator runs. A node is
 * counted once towards the 500-node budget whether it is an operation, a reference, an array or a scalar.
 */
public sealed interface Expr {

    /** A constant. */
    record Literal(Value value) implements Expr {}

    /** A list literal; each item is itself an expression. */
    record Array(List<Expr> items) implements Expr {}

    /** A value of the trigger or manual payload at a closed dotted path. */
    record Input(String path) implements Expr {}

    /** A finished step's output at a closed dotted path. */
    record Step(String nodeId, String path) implements Expr {}

    /** A workflow variable declared by a {@code set} node. */
    record Var(String name) implements Expr {}

    /** The current item of an enclosing for-each, or a member of it. */
    record Item(String path) implements Expr {}

    /** The index of the current for-each iteration. */
    record Index() implements Expr {}

    /** An operator and its arguments. */
    record Op(Operator operator, List<Expr> args) implements Expr {}

    /** Every node in this tree, which is what the node budget counts. */
    default int nodes() {
        return switch (this) {
            case Literal ignored -> 1;
            case Array array -> 1 + array.items().stream().mapToInt(Expr::nodes).sum();
            case Input ignored -> 1;
            case Step ignored -> 1;
            case Var ignored -> 1;
            case Item ignored -> 1;
            case Index ignored -> 1;
            case Op op -> 1 + op.args().stream().mapToInt(Expr::nodes).sum();
        };
    }

    /** Nested operations on the longest path; a literal or a reference is depth zero. */
    default int depth() {
        return switch (this) {
            case Literal ignored -> 0;
            case Array array -> array.items().stream().mapToInt(Expr::depth).max().orElse(0);
            case Input ignored -> 0;
            case Step ignored -> 0;
            case Var ignored -> 0;
            case Item ignored -> 0;
            case Index ignored -> 0;
            case Op op -> 1 + op.args().stream().mapToInt(Expr::depth).max().orElse(0);
        };
    }
}
