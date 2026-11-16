package dev.ngb.survey.insight.application.export.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The exports a Tenant reads: one by id, with a presigned download once it is done.
 *
 * <p>The URL is signed only when the export is finished and its object key exists, so a queued or failed export answers
 * its state rather than a link to nothing.
 */
public class ExportQueries {

    /** How long a download URL stays valid; long enough to start a large file, short enough to expire. */
    public static final Duration DOWNLOAD_LIFETIME = Duration.ofMinutes(15);

    private final UnitOfWork unitOfWork;
    private final ExportReadRepository exports;
    private final ExportDownloads downloads;

    public ExportQueries(UnitOfWork unitOfWork, ExportReadRepository exports, ExportDownloads downloads) {
        this.unitOfWork = unitOfWork;
        this.exports = exports;
        this.downloads = downloads;
    }

    public List<ExportView> ofSurvey(UUID surveyId, int limit) {
        return unitOfWork.transactional(() ->
                exports.ofSurvey(surveyId, limit).stream().map(this::viewOf).toList());
    }

    public ExportView get(UUID exportId) {
        return unitOfWork.transactional(() -> exports.find(exportId)
                .map(this::viewOf)
                .orElseThrow(() -> new NotFoundException("No export exists here.")));
    }

    private ExportView viewOf(ExportRecord export) {
        Optional<String> url = export.objectKey() == null || !"DONE".equals(export.status())
                ? Optional.empty()
                : downloads.presignGet(export.objectKey(), "survey-" + export.surveyId() + ".csv", DOWNLOAD_LIFETIME);
        return new ExportView(
                export.id(),
                export.surveyId(),
                export.status(),
                export.rows(),
                export.requestedBy(),
                export.createdAt(),
                export.finishedAt(),
                url.orElse(null));
    }

    /**
     * One export as the API returns it.
     *
     * @param downloadUrl the presigned GET, or null while the export is queued, failed or its object is gone
     */
    public record ExportView(
            UUID id,
            UUID surveyId,
            String status,
            Long rows,
            String requestedBy,
            Instant createdAt,
            Instant finishedAt,
            String downloadUrl) {}
}
