package dev.ngb.survey.authoring.domain.expression;

/**
 * The budgets the contract states: an expression is at most 500 nodes and 16 operations deep, an array literal holds
 * at most 100 items, one version holds at most 2,000 expression nodes, and a piped sentence is capped at 2,000
 * characters. They bound the work one submit can cause on a public endpoint.
 */
public final class Limits {

    /** Nested operation nodes on the longest path; a literal or a reference is depth zero. */
    public static final int DEPTH = 16;

    /** Expression nodes in one expression: operations, references, arrays and literals. */
    public static final int NODES = 500;

    /** Items in one array literal. */
    public static final int ARRAY_ITEMS = 100;

    /** Expression nodes in one version, summed over every expression it carries. */
    public static final int VERSION_NODES = 2000;

    /** Characters in one rendered piped sentence. */
    public static final int PIPED_CHARACTERS = 2000;

    private Limits() {}
}
