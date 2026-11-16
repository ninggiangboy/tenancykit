package dev.ngb.survey.insight.application.respondent.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The respondents list a Tenant imports into a mailroom group for a resend.
 *
 * <p>It is a list of arrivals rather than accounts: a respondent is a link, a visit and a response, never a record the
 * app reconciles with a mailroom receiver. A row carries only what the Tenant supplied or the response computed, never
 * an address or a token.
 *
 * @param state the visit's state: OPENED, STARTED, COMPLETED or ABANDONED
 */
public record RespondentView(
        String respondentIdentity,
        String externalRef,
        String source,
        String state,
        Instant lastActivityAt,
        UUID responseId) {

    /** The state a Tenant narrows the list by, or null for every one. */
    public enum State {
        COMPLETED,
        STARTED,
        ABANDONED;

        /** The state a query parameter names, or null when it names none. */
        public static State named(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return switch (value.toUpperCase(java.util.Locale.ROOT)) {
                case "COMPLETED" -> COMPLETED;
                case "STARTED", "OPENED" -> STARTED;
                case "ABANDONED" -> ABANDONED;
                default -> null;
            };
        }
    }

    /** The rows and the total the caller should say it saw. */
    public record Page(List<RespondentView> rows, long total) {}
}
