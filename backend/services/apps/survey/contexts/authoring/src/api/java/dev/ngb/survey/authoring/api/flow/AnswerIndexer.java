package dev.ngb.survey.authoring.api.flow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one question a submit needs answered about a version: which typed rows its answers become, and which question
 * carries the respondent's identity.
 *
 * <p>It is authoring's own answer because only authoring knows a question's type and its options; {@code collection}
 * stores the rows without interpreting them. A row stores exactly one value column, decided by the type, so a query
 * never has to read the response's JSONB.
 */
public interface AnswerIndexer {

    /**
     * One typed answer row.
     *
     * @param questionId the stable question id the row answers
     * @param optionId the chosen option or matrix column, or null for a type that stores no choice
     * @param rowId the matrix row, or null for a question that is not a matrix
     */
    record AnswerRow(
            UUID questionId,
            UUID optionId,
            UUID rowId,
            BigDecimal numValue,
            String textValue,
            LocalDate dateValue) {}

    /**
     * The rows one submitted answer set produces, in question order.
     *
     * @param document the version document
     * @param answers the submitted answers, keyed by stable question id
     */
    List<AnswerRow> index(Map<String, Object> document, Map<String, Object> answers);

    /** The question the version marks {@code identity: true}, or empty when it marks none. */
    java.util.Optional<String> identityQuestion(Map<String, Object> document);

    /**
     * The limits one file question states.
     *
     * @param allowedMime the types its uploads may declare
     * @param maxBytes the largest object it accepts
     * @param maxFiles how many files one answer may reference
     */
    record FileLimit(List<String> allowedMime, long maxBytes, int maxFiles) {}

    /** The limits of one question, or empty when the version holds no file question with that id. */
    java.util.Optional<FileLimit> fileLimit(Map<String, Object> document, String questionId);
}
