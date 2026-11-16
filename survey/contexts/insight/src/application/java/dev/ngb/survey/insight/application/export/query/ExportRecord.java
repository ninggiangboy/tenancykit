package dev.ngb.survey.insight.application.export.query;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One export as a Tenant reads it: the row the ledger holds, shaped for its caller.
 *
 * <p>It is a read model, so it names no domain type: the query side maps the stored row into this and adds the
 * presigned URL, which a write never needs.
 */
public record ExportRecord(
        UUID id,
        UUID tenantId,
        UUID surveyId,
        String status,
        Map<String, Object> filter,
        String objectKey,
        Long rows,
        String requestedBy,
        Instant createdAt,
        Instant finishedAt) {}
