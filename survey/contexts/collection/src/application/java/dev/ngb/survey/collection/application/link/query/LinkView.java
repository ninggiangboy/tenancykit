package dev.ngb.survey.collection.application.link.query;

import java.time.Instant;
import java.util.UUID;

/**
 * One link as the API returns it: its identity, its source, and its URL rebuilt from the id.
 *
 * @param url the URL a Tenant hands out; readable at any time because it is derived, not stored
 * @param externalRef a personal link's recipient reference, or null for a public link
 */
public record LinkView(
        UUID id,
        UUID surveyId,
        String kind,
        String source,
        String externalRef,
        Instant expiresAt,
        String status,
        Instant createdAt,
        String url) {}
