package dev.ngb.workflow.definitions.domain.expression;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.definitions.domain.problem.ProblemCodes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The graph-level rules a definition adds on top of parsing: steps and variables must exist, item/index only inside a for-each. */
class ExprCheckerTest {

    private static final ExprChecker.Names NAMES =
            new ExprChecker.Names(Set.of("fetch", "send"), Set.of("total"), false);

    @Test
    void aStepAndAVariableOfTheGraphResolve() {
        Expr expression = parse(Map.of(
                "op", "add",
                "args", List.of(Map.of("step", "fetch", "path", "amount"), Map.of("var", "total"))));

        assertThat(ExprChecker.problems(expression, "/expr", NAMES)).isEmpty();
    }

    @Test
    void aStepTheGraphDoesNotHaveIsReportedAtItsPointer() {
        Expr expression = parse(Map.of("step", "missing"));

        assertThat(ExprChecker.problems(expression, "/expr", NAMES))
                .containsExactly(new Problem(
                        "/expr/step", ProblemCodes.DANGLING_REFERENCE, "no node of this graph carries this id"));
    }

    @Test
    void aVariableNoSetNodeDeclaresIsReportedAtItsPointer() {
        Expr expression = parse(Map.of("var", "missing"));

        assertThat(ExprChecker.problems(expression, "/expr", NAMES))
                .extracting(Problem::pointer, Problem::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/expr/var", ProblemCodes.DANGLING_REFERENCE));
    }

    @Test
    void theCurrentItemAndIndexAreAllowedInsideAForeach() {
        ExprChecker.Names insideIteration = new ExprChecker.Names(Set.of(), Set.of(), true);
        Expr expression = parse(List.of(Map.of("item", "sku"), Map.of("index", true)));

        assertThat(ExprChecker.problems(expression, "/expr", insideIteration)).isEmpty();
    }

    @Test
    void theCurrentItemIsReportedOutsideAForeach() {
        Expr expression = parse(Map.of("item", "sku"));

        assertThat(ExprChecker.problems(expression, "/expr", NAMES))
                .extracting(Problem::code)
                .containsExactly(ProblemCodes.REFERENCE_OUTSIDE_ITERATION);
    }

    @Test
    void everyReferenceOfANestedExpressionIsWalked() {
        Expr expression = parse(Map.of(
                "op", "or",
                "args", List.of(
                        Map.of("step", "missing"),
                        Map.of("op", "not", "args", List.of(Map.of("var", "missing"))))));

        assertThat(ExprChecker.problems(expression, "/expr", NAMES))
                .extracting(Problem::pointer)
                .containsExactly("/expr/args/0/step", "/expr/args/1/args/0/var");
    }

    private static Expr parse(Object document) {
        ExprParser.Result result = ExprParser.parse(document, "/expr");
        assertThat(result.problems()).isEmpty();
        return result.expression();
    }
}
