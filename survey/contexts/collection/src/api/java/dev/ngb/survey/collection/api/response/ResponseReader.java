package dev.ngb.survey.collection.api.response;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What {@code insight} reads from {@code collection}: the completed responses an aggregate, a funnel, a respondent list
 * and an export are built from.
 *
 * <p>It is a narrow port on purpose. {@code insight} may not read {@code collection}'s tables, so every number it reports
 * comes through here, and deleting a response removes it from every report without insight keeping a copy.
 */
public interface ResponseReader {

    /**
     * One page of the response listing, the way the list-query contract asks for it.
     *
     * @param query the filter, ordering and position the caller sent, checked against {@link ResponseListing}
     */
    Page<ResponseRow> list(UUID surveyId, ListQuery query);

    /**
     * A batch of responses matching a filter, in submit order, which an export and a cross-tab walk page by page.
     *
     * <p>It is the scan a report runs, not the endpoint: the listing a caller reads is {@link #list}.
     *
     * @param filter the version, window, source and identity the Tenant narrowed by
     * @param offset how many matching rows to skip
     * @param limit the most rows to return
     */
    List<ResponseRow> responses(UUID surveyId, ResponseFilter filter, int offset, int limit);

    /** The number of responses matching a filter. */
    long count(UUID surveyId, ResponseFilter filter);

    /** One response by id, or empty when the tenant has none. */
    java.util.Optional<ResponseRow> response(UUID responseId);

    /** The typed answer rows of a response, which an export writes one column per question from. */
    List<AnswerRow> answers(UUID responseId);

    /**
     * The per-option counts of a survey's completed responses, grouped by question and option.
     *
     * @param filter the version, window, source and identity the Tenant narrowed by
     */
    List<OptionCount> optionCounts(UUID surveyId, ResponseFilter filter);

    /** The numeric values per question, which a mean, median or distribution needs. */
    List<NumericValue> numericValues(UUID surveyId, ResponseFilter filter);

    /** The per-cell counts of one matrix question. */
    List<CellCount> matrixCells(UUID surveyId, ResponseFilter filter, UUID questionId);

    /** The responses per day or week, in the survey's own time zone. */
    List<BucketCount> submittedByBucket(UUID surveyId, ResponseFilter filter, Bucket bucket, String timezone);

    /** The number of responses under a cap, which an NPS or a distribution also needs. */
    List<NpsValue> npsValues(UUID surveyId, ResponseFilter filter);

    /** The visits one funnel row is built from. */
    List<VisitRow> visits(UUID surveyId, Instant from, Instant to);

    /**
     * One page of the respondents list, the way the list-query contract asks for it.
     *
     * @param query the filter, ordering and position the caller sent, checked against {@link RespondentListing}
     */
    Page<RespondentRow> respondents(UUID surveyId, ListQuery query);

    /**
     * One respondent, as the listing returns it: the arrival and the identity of the response it produced, if any.
     * {@code externalRef} is the Tenant's own key for that person, which a resend joins a mailroom group on.
     */
    record RespondentRow(
            UUID id,
            UUID responseId,
            String respondentIdentity,
            String externalRef,
            String source,
            String state,
            Instant lastActivityAt) {}

    /** One response, as a report row. */
    record ResponseRow(
            UUID id,
            UUID versionId,
            String status,
            String locale,
            Map<String, Object> answers,
            Map<String, Object> vars,
            BigDecimal score,
            String respondentIdentity,
            String externalRef,
            String source,
            Instant startedAt,
            Instant submittedAt) {}

    /** One typed answer row of a response. */
    record AnswerRow(UUID questionId, UUID optionId, UUID rowId, BigDecimal numValue, String textValue, String dateValue) {}

    /** The counts of one option of one question. */
    record OptionCount(UUID questionId, UUID optionId, long count) {}

    /** One numeric answer, which a mean, median or distribution reads. */
    record NumericValue(UUID questionId, BigDecimal value) {}

    /** The count of one matrix row-and-column cell. */
    record CellCount(UUID questionId, UUID rowId, UUID columnId, long count) {}

    /** The count of one time bucket, whose key is the bucket's start as an ISO string. */
    record BucketCount(String bucket, long count) {}

    /** One NPS answer, whose bucket follows the 0–6 detractor, 7–8 passive, 9–10 promoter split. */
    record NpsValue(BigDecimal value) {}

    /** One visit, as the funnel reads it. */
    record VisitRow(
            UUID id,
            String source,
            String state,
            String country,
            String deviceClass,
            String trafficClass,
            UUID responseId,
            Instant openedAt,
            Instant lastActivityAt) {}

    /** The window, source and identity a report narrows by. */
    record ResponseFilter(Integer versionNo, Instant from, Instant to, String source, String externalRef) {

        /** No narrowing: every completed response the tenant has. */
        public static ResponseFilter all() {
            return new ResponseFilter(null, null, null, null, null);
        }
    }

    /** A time bucket for a trend. */
    enum Bucket {
        DAY,
        WEEK
    }
}
