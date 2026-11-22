package dev.ngb.survey.insight.application.analytics.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.AnswerRow;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A cross-tab: the count of each combination of two choice-like questions, joined over the same responses.
 *
 * <p>It reads the typed rows of two questions and joins them per response, so a pair is counted only when one response
 * answered both. The cell cap keeps a wide survey from producing an unbounded grid, which is the v1 bound on cost.
 */
public class CrossTabQueries {

    /** The most cells one cross-tab may hold, so a wide survey cannot produce an unbounded grid. */
    public static final int MAX_CELLS = 50 * 50;

    /** How many responses one page reads while building a cross-tab. */
    private static final int PAGE_SIZE = 1000;

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;

    public CrossTabQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
    }

    /**
     * @param rowQuestionId the question whose values form the rows
     * @param columnQuestionId the question whose values form the columns
     * @return the counts, keyed by {@code rowId|columnId}; a cell is present only when at least one response hit it
     */
    public AggregateView.CrossTabView crossTab(
            UUID surveyId,
            ResponseFilter filter,
            UUID rowQuestionId,
            UUID columnQuestionId) {
        return unitOfWork.transactional(() -> {
            Map<String, Long> cells = new LinkedHashMap<>();
            long total = 0;
            int offset = 0;
            while (cells.size() <= MAX_CELLS) {
                var page = responses.responses(surveyId, filter, offset, PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }
                for (ResponseRow response : page) {
                    String row = valueOf(response, rowQuestionId);
                    String column = valueOf(response, columnQuestionId);
                    if (row == null || column == null) {
                        continue;
                    }
                    cells.merge(row + "|" + column, 1L, Long::sum);
                    total++;
                }
                if (page.size() < PAGE_SIZE) {
                    break;
                }
                offset += PAGE_SIZE;
            }
            return new AggregateView.CrossTabView(
                    rowQuestionId.toString(),
                    columnQuestionId.toString(),
                    total,
                    Map.copyOf(cells.size() > MAX_CELLS ? Map.of() : cells));
        });
    }

    /** The first value a response holds for one question, as a count key. */
    private String valueOf(ResponseRow response, UUID questionId) {
        for (AnswerRow answer : responses.answers(response.id())) {
            if (!questionId.equals(answer.questionId())) {
                continue;
            }
            if (answer.optionId() != null) {
                return answer.optionId().toString();
            }
            if (answer.numValue() != null) {
                return answer.numValue().stripTrailingZeros().toPlainString();
            }
            if (answer.textValue() != null) {
                return answer.textValue();
            }
        }
        return null;
    }
}
