package dev.ngb.survey.collection.application.visit.query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One visit as the public endpoints read it: the facts they need and nothing that identifies the person.
 *
 * @param tenantId the tenant the visit's link belongs to, which is what a row-level check compares
 * @param versionNo the version the visit pinned, so a resume keeps the questions it opened with
 */
public record VisitView(
        UUID id,
        UUID tenantId,
        UUID surveyId,
        UUID linkId,
        int versionNo,
        String source,
        String state,
        UUID responseId,
        Instant openedAt,
        Instant lastActivityAt) {}
