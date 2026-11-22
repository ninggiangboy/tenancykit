package dev.ngb.survey.insight.application.respondent.query;

import java.time.Instant;
import java.util.UUID;

/**
 * The respondents list a Tenant imports into a mailroom group for a resend.
 *
 * <p>It is a list of arrivals rather than accounts: a respondent is a link, a visit and a response, never a record the
 * app reconciles with a mailroom receiver. A row carries only what the Tenant supplied or the response computed, never
 * an address or a token.
 *
 * @param respondentIdentity the identity the response was submitted with, or null when the visit never finished
 * @param externalRef the Tenant's own key for that person, which a resend joins a mailroom group on
 * @param state the visit's state: OPENED, STARTED, COMPLETED or ABANDONED
 */
public record RespondentView(
        String respondentIdentity,
        String externalRef,
        String source,
        String state,
        Instant lastActivityAt,
        UUID responseId) {}
