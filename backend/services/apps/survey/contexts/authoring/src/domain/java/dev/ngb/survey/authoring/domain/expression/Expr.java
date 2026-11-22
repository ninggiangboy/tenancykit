package dev.ngb.survey.authoring.domain.expression;

import java.util.List;

/**
 * One expression tree: a literal, an array literal, an answer reference, a variable reference or an operation.
 *
 * <p>The tree is what the contract's JSON document describes, and it is the only shape the evaluator runs. A node is
 * counted once towards the 500-node budget whether it is an operation, a reference, an array or a scalar.
 */
public sealed interface Expr {

    /** A constant. */
    record Literal(Value value) implements Expr {}

    /** A list literal; each item is itself an expression. */
    record Array(List<Expr> items) implements Expr {}

    /** The submitted value of a question, or of one matrix cell when {@code rowId} is present. */
    record Answer(String questionId, String rowId) implements Expr {}

    /** The value of a computed variable declared earlier in the version. */
    record Var(String name) implements Expr {}

    /** An operator and its arguments. */
    record Op(Operator operator, List<Expr> args) implements Expr {}

    /** Every node in this tree, which is what the node budget counts. */
    default int nodes() {
        return switch (this) {
            case Literal ignored -> 1;
            case Array array -> 1 + array.items().stream().mapToInt(Expr::nodes).sum();
            case Answer ignored -> 1;
            case Var ignored -> 1;
            case Op op -> 1 + op.args().stream().mapToInt(Expr::nodes).sum();
        };
    }

    /** Nested operations on the longest path; a literal or a reference is depth zero. */
    default int depth() {
        return switch (this) {
            case Literal ignored -> 0;
            case Array array -> array.items().stream().mapToInt(Expr::depth).max().orElse(0);
            case Answer ignored -> 0;
            case Var ignored -> 0;
            case Op op -> 1 + op.args().stream().mapToInt(Expr::depth).max().orElse(0);
        };
    }
}
