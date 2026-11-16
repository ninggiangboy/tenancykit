package dev.ngb.survey.collection.application.response.query;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One response as a Tenant or the respondent's own browser reads it.
 *
 * <p>The submitted answers are the record a display shows verbatim; the variables and the score are the server's own,
 * computed at submit, and are never a client's. No address, token or user agent is here: a response carries the facts
 * a report needs and nothing that identifies a person beyond the identity the Tenant itself supplied.
 */
public record ResponseView(
        UUID id,
        UUID surveyId,
        UUID visitId,
        UUID versionId,
        String status,
        String locale,
        Map<String, Object> answers,
        Map<String, Object> vars,
        java.math.BigDecimal score,
        String respondentIdentity,
        String externalRef,
        String source,
        Instant startedAt,
        Instant submittedAt) {}
