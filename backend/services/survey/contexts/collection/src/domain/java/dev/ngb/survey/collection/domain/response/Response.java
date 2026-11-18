package dev.ngb.survey.collection.domain.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One respondent's answers within one visit, recorded against the version the visit pinned.
 *
 * <p>Two shapes matter. The submitted answers are kept verbatim, so a display or an export shows exactly what the
 * respondent sent even after an option is relabelled; the computed variables and score are the server's own, never a
 * client's, and the typed answer rows are what an aggregate reads. A personal link's reference is denormalised onto
 * the row as {@code personalLinkId}, because the partial unique index that makes single use hold needs it there rather
 * than behind a join.
 *
 * @param idempotencyKey the client's key for a submit, or null for an in-progress draft; a retry returns the first
 *     result rather than creating a second response
 * @param personalLinkId set only when the visit came from a personal link, which makes a second completion impossible
 */
public record Response(
        UUID id,
        UUID tenantId,
        UUID surveyId,
        UUID versionId,
        UUID linkId,
        UUID visitId,
        ResponseStatus status,
        String locale,
        Map<String, Object> answers,
        Map<String, Object> vars,
        BigDecimal score,
        String respondentIdentity,
        String externalRef,
        String source,
        String idempotencyKey,
        UUID personalLinkId,
        Instant startedAt,
        Instant submittedAt) {

    public Response {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(surveyId, "surveyId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(linkId, "linkId");
        Objects.requireNonNull(visitId, "visitId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(startedAt, "startedAt");
        answers = Map.copyOf(answers);
        vars = Map.copyOf(vars);
        if ((status == ResponseStatus.COMPLETED) != (submittedAt != null)) {
            throw new IllegalArgumentException("only a completed response carries a submission instant");
        }
    }

    /** The draft one visit starts with, before any answer is saved. */
    public static Response draft(
            UUID id,
            UUID tenantId,
            UUID surveyId,
            UUID versionId,
            UUID linkId,
            UUID visitId,
            String locale,
            String source,
            String externalRef,
            UUID personalLinkId,
            Instant now) {
        return new Response(
                id,
                tenantId,
                surveyId,
                versionId,
                linkId,
                visitId,
                ResponseStatus.IN_PROGRESS,
                locale,
                Map.of(),
                Map.of(),
                null,
                externalRef,
                externalRef,
                source,
                null,
                personalLinkId,
                now,
                null);
    }

    /** The same draft with answers replaced; the tag a completed response carries never changes. */
    public Response withAnswers(Map<String, Object> answers) {
        return new Response(
                id, tenantId, surveyId, versionId, linkId, visitId, status, locale, answers, vars, score,
                respondentIdentity, externalRef, source, idempotencyKey, personalLinkId, startedAt, submittedAt);
    }

    /**
     * The completed response: the answers, the server's own variables and score, the identity it resolved and the key
     * the submit carried.
     */
    public Response completed(
            Map<String, Object> answers,
            Map<String, Object> vars,
            BigDecimal score,
            String respondentIdentity,
            String idempotencyKey,
            Instant now) {
        return new Response(
                id, tenantId, surveyId, versionId, linkId, visitId, ResponseStatus.COMPLETED, locale, answers, vars,
                score, respondentIdentity, externalRef, source, idempotencyKey, personalLinkId, startedAt, now);
    }
}
