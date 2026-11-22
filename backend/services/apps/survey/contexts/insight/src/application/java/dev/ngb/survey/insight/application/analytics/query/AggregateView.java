package dev.ngb.survey.insight.application.analytics.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The answer to an aggregate request: one question's counts, mean, median, distribution or NPS.
 *
 * <p>A question is reported by its stable id and merged across versions, so a question that v1 and v2 both carry is one
 * row. A question whose type changed got a new id at publish, so it appears as its own question with a
 * {@code replaces} link rather than mixing two shapes into one.
 */
public record AggregateView(
        UUID surveyId,
        long responses,
        List<QuestionAggregate> questions,
        List<CrossTabView> crossTabs) {

    /**
     * One question's numbers.
     *
     * @param counts the chosen options, keyed by the option's stable value
     * @param mean the average of a numeric answer, or null
     * @param median the median of a numeric answer, or null
     * @param distribution the counts per numeric value or per NPS bucket
     * @param matrixCells the counts per matrix cell, keyed by row and column id
     * @param answered how many responses answered this question at all
     */
    public record QuestionAggregate(
            UUID questionId,
            String type,
            long answered,
            Map<String, Long> counts,
            BigDecimal mean,
            BigDecimal median,
            Map<String, Long> distribution,
            Map<String, Long> matrixCells) {}

    /**
     * One cross-tab: the counts and row percentages of two questions, or a question against source, country or a
     * device class.
     *
     * @param cells one entry per non-empty cell, keyed by a `row|column` pair
     */
    public record CrossTabView(String rowDimension, String columnDimension, long total, Map<String, Long> cells) {}
}
