package dev.ngb.survey.authoring.domain.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.domain.expression.ExprParser;
import dev.ngb.survey.authoring.support.FlowDocuments;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The behaviours of the expression engine a vector cannot state: which parser message a caller sees, and that a
 * version document the schema accepts still fails when its expression budget is exceeded.
 *
 * <p>No operator's meaning is asserted here: every operator's behaviour comes from the contract's vectors, so there is
 * one source of truth for what a branch or a score means.
 */
class ExpressionBudgetTest {

    @Test
    void aDepthSeventeenExpressionFailsAtItsOwnPointer() {
        Map<String, Object> document = FlowDocuments.versionWith(FlowDocuments.nestedNot(17));

        assertThat(codes(FlowRunner.problems(document)))
                .containsExactly(new Problem("/computed/0/expr", ValidationCode.LIMIT_EXCEEDED));
    }

    @Test
    void aDepthSixteenExpressionIsAccepted() {
        Map<String, Object> document = FlowDocuments.versionWith(FlowDocuments.nestedNot(16));

        assertThat(FlowRunner.problems(document)).isEmpty();
    }

    @Test
    void anUnknownOperatorIsReportedAtTheOperatorMember() {
        Map<String, Object> document = FlowDocuments.versionWith(Map.of("op", "eval", "args", List.of(true)));

        assertThat(codes(FlowRunner.problems(document)))
                .containsExactly(new Problem("/computed/0/expr/op", ValidationCode.EXPRESSION_INVALID));
    }

    @Test
    void theParserNamesTheArityAnOperatorTakes() {
        ExprParser.Result result =
                ExprParser.parse(Map.of("op", "gt", "args", List.of(1)), "/computed/0/expr");

        assertThat(result.problems())
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.pointer()).isEqualTo("/computed/0/expr/args");
                    assertThat(problem.code()).isEqualTo(ValidationCode.EXPRESSION_INVALID);
                    assertThat(problem.message()).contains("2 arguments");
                });
    }

    @Test
    void aVersionOverTwoThousandNodesFailsAtTheDocumentRoot() {
        Map<String, Object> document = FlowDocuments.versionWithRepeated(
                FlowDocuments.expressionOfNodes(400), 6);

        assertThat(codes(FlowRunner.problems(document)))
                .containsExactly(new Problem("", ValidationCode.LIMIT_EXCEEDED));
    }

    @Test
    void aVersionOfExactlyTwoThousandNodesIsAccepted() {
        Map<String, Object> document = FlowDocuments.versionWithRepeated(
                FlowDocuments.expressionOfNodes(400), 5);

        assertThat(FlowRunner.problems(document)).isEmpty();
    }

    @Test
    void aVariableThatReadsItselfIsACycleAndOneThatReadsForwardIsDangling() {
        Map<String, Object> selfReferencing = FlowDocuments.versionWithComputed(List.of(
                Map.of("name", "a", "expr", Map.of("op", "add", "args", List.of(Map.of("var", "a"), 1)))));
        Map<String, Object> forward = FlowDocuments.versionWithComputed(List.of(
                Map.of("name", "a", "expr", Map.of("var", "b")),
                Map.of("name", "b", "expr", 1)));

        assertThat(codes(FlowRunner.problems(selfReferencing)))
                .containsExactly(new Problem("/computed/0/expr/args/0/var", ValidationCode.CYCLE));
        assertThat(codes(FlowRunner.problems(forward)))
                .containsExactly(new Problem("/computed/0/expr/var", ValidationCode.DANGLING_REFERENCE));
    }

    @Test
    void theLargestAllowedVersionFinishesWellInsideTheEvaluationBudget() {
        Map<String, Object> document = FlowDocuments.versionWithRepeated(
                FlowDocuments.expressionOfNodes(400), 5);
        Answers answers = FlowDocuments.answersFor(document, true);

        // Warm the JIT once, then measure: the budget is what a public submit costs, not what a cold start costs.
        FlowRunner.run(FlowPlan.compile(document), answers);

        long started = System.nanoTime();
        for (int run = 0; run < 20; run++) {
            FlowRunner.run(FlowPlan.compile(document), answers);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(millis)
                .as("twenty evaluations of the largest allowed version stay far under a submit's budget")
                .isLessThan(400);
    }

    private static List<Problem> codes(List<ValidationError> errors) {
        return errors.stream().map(Problem::of).toList();
    }

    private record Problem(String pointer, ValidationCode code) {
        static Problem of(ValidationError error) {
            return new Problem(error.pointer(), error.code());
        }
    }
}
