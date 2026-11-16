package dev.ngb.survey.authoring.domain.expression;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The closed operator set of the expression language, with the arity each operator accepts.
 *
 * <p>There is no way to name an operator the contract does not list: a document naming another one fails validation
 * rather than reaching the evaluator, so a survey is data and never a program.
 */
public enum Operator {
    EQ("eq", 2, 2),
    NEQ("neq", 2, 2),
    GT("gt", 2, 2),
    GTE("gte", 2, 2),
    LT("lt", 2, 2),
    LTE("lte", 2, 2),
    IN("in", 2, 2),
    CONTAINS("contains", 2, 2),
    AND("and", 2, 8),
    OR("or", 2, 8),
    NOT("not", 1, 1),
    EXISTS("exists", 1, 1),
    EMPTY("empty", 1, 1),
    COUNT("count", 1, 1),
    SUM("sum", 1, 1),
    ADD("add", 2, 2),
    SUB("sub", 2, 2),
    MUL("mul", 2, 2),
    DIV("div", 2, 2),
    MIN("min", 2, 8),
    MAX("max", 2, 8),
    IF("if", 3, 3),
    LEN("len", 1, 1),
    LOWER("lower", 1, 1);

    private static final Map<String, Operator> BY_NAME =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Operator::wireName, Function.identity()));

    private final String wireName;
    private final int minArity;
    private final int maxArity;

    Operator(String wireName, int minArity, int maxArity) {
        this.wireName = wireName;
        this.minArity = minArity;
        this.maxArity = maxArity;
    }

    /** The name the contract spells, which is what a document carries. */
    public String wireName() {
        return wireName;
    }

    /** The operator a document names, or null when the language does not define it. */
    public static Operator named(String wireName) {
        return BY_NAME.get(wireName);
    }

    /** Whether this operator accepts a call with {@code arity} arguments. */
    public boolean accepts(int arity) {
        return arity >= minArity && arity <= maxArity;
    }

    /** The arity the contract states, phrased for an error message. */
    public String arityDescription() {
        return minArity == maxArity ? Integer.toString(minArity) : minArity + " to " + maxArity;
    }
}
