package dev.ngb.survey.collection.application.response.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One typed answer row: the index every aggregate, filter, cross-tab and trend reads.
 *
 * <p>A question stores exactly one of the value columns, decided by its type: a choice or matrix cell stores the
 * option id (and the row id for a cell), a number its decimal, text and email its text, a date its date. The response's
 * JSONB stays the record a display shows; this is what a query can sum.
 *
 * @param optionId the chosen option, or the matrix column, or null for a type that stores no choice
 * @param rowId the matrix row, or null for a question that is not a matrix
 */
public record TypedAnswer(
        UUID responseId,
        UUID tenantId,
        UUID surveyId,
        UUID versionId,
        UUID questionId,
        UUID optionId,
        UUID rowId,
        BigDecimal numValue,
        String textValue,
        LocalDate dateValue,
        String source,
        Instant submittedAt) {}
