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

    /** The document is not a graph at all, or a node lacks a member its type requires. */
    public static final String GRAPH_INVALID = "graph_invalid";

    /** A node names a type the graph language does not define. */
    public static final String UNKNOWN_NODE_TYPE = "unknown_node_type";

    /** Two nodes carry one id. */
    public static final String DUPLICATE_NODE = "duplicate_node";

    /** An edge names a node that does not exist. */
    public static final String MISSING_TARGET = "missing_target";

    /** A node cannot be reached from the trigger. */
    public static final String UNREACHABLE_NODE = "unreachable_node";

    /** A parallel node has no join, or names one that is not a join node. */
    public static final String PARALLEL_WITHOUT_JOIN = "parallel_without_join";

    /** A join node is not the join of any parallel. */
    public static final String JOIN_WITHOUT_PARALLEL = "join_without_parallel";

    /** A cycle that is not bounded by a for-each. */
    public static final String UNBOUNDED_CYCLE = "unbounded_cycle";

    /** A graph needs exactly one trigger, and the start must be it. */
    public static final String TRIGGER_INVALID = "trigger_invalid";

    /** A sub-workflow chain returns to a definition it already passed through. */
    public static final String SUBFLOW_CYCLE = "subflow_cycle";

    /** A compensation action is not an http node of this graph. */
    public static final String COMPENSATE_INVALID = "compensate_invalid";

    private ProblemCodes() {}
}
