package dev.ngb.survey.insight.domain.export;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One CSV export a Tenant asked for, and the file the worker produced.
 *
 * <p>The filter is kept because the same request must be repeatable and a finished file must explain what it holds; it
 * carries the shape of the query, never a respondent's answer. A done export names its object and its row count, so a
 * client can show one without reading the file.
 */
public record Export(
        UUID id,
        UUID tenantId,
        UUID surveyId,
        ExportStatus status,
        Map<String, Object> filter,
        String objectKey,
        Long rows,
        String requestedBy,
        Instant createdAt,
        Instant finishedAt) {

    public Export {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(surveyId, "surveyId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        filter = Map.copyOf(filter);
        if (status == ExportStatus.DONE && (objectKey == null || rows == null)) {
            throw new IllegalArgumentException("a finished export names its object and its row count");
        }
    }

    /** The export a Tenant's request creates, queued for the worker. */
    public static Export queued(
            UUID id, UUID tenantId, UUID surveyId, Map<String, Object> filter, String requestedBy, Instant now) {
        return new Export(id, tenantId, surveyId, ExportStatus.QUEUED, filter, null, null, requestedBy, now, null);
    }

    /** The export after the worker produced its file. */
    public Export done(String objectKey, long rows, Instant now) {
        return new Export(
                id, tenantId, surveyId, ExportStatus.DONE, filter, objectKey, rows, requestedBy, createdAt, now);
    }

    /** The export after the worker could not produce it. */
    public Export failed(Instant now) {
        return new Export(
                id, tenantId, surveyId, ExportStatus.FAILED, filter, null, null, requestedBy, createdAt, now);
    }
}
