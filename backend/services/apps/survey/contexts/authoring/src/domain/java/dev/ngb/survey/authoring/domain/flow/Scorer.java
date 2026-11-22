package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.expression.Evaluator;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Adds up a quiz score over the questions actually on the path.
 *
 * <p>Three things contribute: an option's score for a chosen choice (every chosen option of a multi-choice), a matrix
 * column's score for every answered row, the chosen value of a rating or an NPS answer, and each scoring rule whose
 * condition holds. A question behind a false {@code visible_if} or on a skipped page contributes nothing, so hiding a
 * question also removes its points. When the version disables quiz scoring the score is null, not zero.
 */
public final class Scorer {

    private Scorer() {}

    public static BigDecimal score(
            FlowPlan plan, Answers answers, Map<String, Boolean> visible, FlowEnvironment environment) {
        if (!plan.quizEnabled()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (FlowPlan.Page page : plan.pages()) {
            for (FlowPlan.Question question : page.questions()) {
                if (question.id() == null || !Boolean.TRUE.equals(visible.get(question.id()))) {
                    continue;
                }
                total = total.add(questionScore(question, answers));
            }
        }
        for (FlowPlan.Rule rule : plan.rules()) {
            if (rule.questionId() != null && !Boolean.TRUE.equals(visible.get(rule.questionId()))) {
                continue;
            }
            if (rule.when() != null && Evaluator.truthy(Evaluator.evaluate(rule.when(), environment))) {
                total = total.add(rule.points());
            }
        }
        return Value.number(total);
    }

    private static BigDecimal questionScore(FlowPlan.Question question, Answers answers) {
        BigDecimal total = BigDecimal.ZERO;
        if ("matrix".equals(question.type())) {
            // A matrix answer is a row-to-column map, so it scores per answered cell rather than as one value.
            for (Object columnId : answers.cells(question.id()).values()) {
                if (columnId instanceof String chosen) {
                    total = total.add(columnScore(question, chosen));
                }
            }
            return total;
        }
        Value answer = answers.value(question.id(), null);
        if (answer instanceof Value.Nil) {
            return total;
        }
        switch (question.type() == null ? "" : question.type()) {
            case "single_choice" -> total = total.add(optionScore(question, answer));
            case "multi_choice" -> {
                if (answer instanceof Value.Items items) {
                    for (Value member : items.values()) {
                        total = total.add(optionScore(question, member));
                    }
                }
            }
            case "rating", "nps" -> {
                if (answer instanceof Value.Num number) {
                    total = total.add(number.value());
                }
            }
            default -> {}
        }
        return total;
    }

    private static BigDecimal optionScore(FlowPlan.Question question, Value answer) {
        if (!(answer instanceof Value.Text chosen)) {
            return BigDecimal.ZERO;
        }
        for (FlowPlan.Option option : question.options()) {
            if (chosen.value().equals(option.value())) {
                return option.score();
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal columnScore(FlowPlan.Question question, String columnId) {
        for (FlowPlan.Column column : question.columns()) {
            if (columnId.equals(column.id())) {
                return column.score();
            }
        }
        return BigDecimal.ZERO;
    }

    /** Every question id of a version, in presentation order, which is what a scoped answer set keeps. */
    public static List<String> questionIds(FlowPlan plan) {
        return plan.pages().stream()
                .flatMap(page -> page.questions().stream())
                .map(FlowPlan.Question::id)
                .filter(id -> id != null)
                .toList();
    }
}
