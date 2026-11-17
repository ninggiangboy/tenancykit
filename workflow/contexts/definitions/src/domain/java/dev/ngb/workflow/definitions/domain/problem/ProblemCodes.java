package dev.ngb.workflow.definitions.domain.problem;

/**
 * The stable codes a refused definition reports. A code is part of the API: a client branches on it, so it never
 * changes meaning and a message never replaces it.
 */
public final class ProblemCodes {

    /** The document is not an expression at all: an object with no known member, a shape no node accepts. */
    public static final String EXPRESSION_INVALID = "expression_invalid";

    /** The document names an operator the language does not define. */
    public static final String UNKNOWN_OPERATOR = "unknown_operator";

    /** An operator was called with an arity it does not accept. */
    public static final String ARITY_MISMATCH = "arity_mismatch";

    /** A reference path is not the closed dotted subset the contract allows. */
    public static final String INVALID_PATH = "invalid_path";

    /** A reference names a step or a variable the graph does not have. */
    public static final String DANGLING_REFERENCE = "dangling_reference";

    /** A reference reads the current item or index outside a for-each. */
    public static final String REFERENCE_OUTSIDE_ITERATION = "reference_outside_iteration";

    /** An expression, a graph or a version is past one of its budgets. */
    public static final String LIMIT_EXCEEDED = "limit_exceeded";

    private ProblemCodes() {}
}
