package dev.ngb.workflow.definitions.domain.expression;

import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.definitions.domain.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves every reference an expression makes against the graph that carries it.
 *
 * <p>A {@code step} reference must name a node of the graph, a {@code var} reference must name a variable a
 * {@code set} node declares, and {@code item}/{@code index} are legal only inside a for-each. The trigger payload's
 * shape is not known when a definition is published, so an {@code input} path is checked for syntax by the parser and
 * never for existence.
 */
public final class ExprChecker {

    /**
     * What a reference may resolve to.
     *
     * @param stepIds every node id of the graph
     * @param variables every variable name a {@code set} node declares
     * @param insideIteration whether the expression is evaluated inside a for-each
     */
    public record Names(Set<String> stepIds, Set<String> variables, boolean insideIteration) {

        public Names {
            stepIds = Set.copyOf(stepIds);
            variables = Set.copyOf(variables);
        }

        /** A graph with nothing to reference, which is what a preview of an empty draft sees. */
        public static Names empty() {
            return new Names(Set.of(), Set.of(), false);
        }
    }

    private ExprChecker() {}

    /** Every reference this expression cannot resolve, by the pointer of the offending member. */
    public static List<Problem> problems(Expr expression, String pointer, Names names) {
        List<Problem> problems = new ArrayList<>();
        walk(expression, pointer, names, problems);
        return List.copyOf(problems);
    }

    private static void walk(Expr expression, String pointer, Names names, List<Problem> problems) {
        switch (expression) {
            case Expr.Literal ignored -> {}
            case Expr.Array array -> {
                for (int index = 0; index < array.items().size(); index++) {
                    walk(array.items().get(index), pointer + "/" + index, names, problems);
                }
            }
            case Expr.Input ignored -> {}
            case Expr.Step step -> {
                if (!names.stepIds().contains(step.nodeId())) {
                    problems.add(new Problem(
                            pointer + "/step",
                            ProblemCodes.DANGLING_REFERENCE,
                            "no node of this graph carries this id"));
                }
            }
            case Expr.Var variable -> {
                if (!names.variables().contains(variable.name())) {
                    problems.add(new Problem(
                            pointer + "/var",
                            ProblemCodes.DANGLING_REFERENCE,
                            "no set node of this graph declares this variable"));
                }
            }
            case Expr.Item ignored -> iteration(pointer, "item", names, problems);
            case Expr.Index ignored -> iteration(pointer, "index", names, problems);
            case Expr.Op op -> {
                for (int index = 0; index < op.args().size(); index++) {
                    walk(op.args().get(index), pointer + "/args/" + index, names, problems);
                }
            }
        }
    }

    private static void iteration(String pointer, String member, Names names, List<Problem> problems) {
        if (!names.insideIteration()) {
            problems.add(new Problem(
                    pointer + "/" + member,
                    ProblemCodes.REFERENCE_OUTSIDE_ITERATION,
                    "only an expression inside a for-each may read the current " + member));
        }
    }
}
