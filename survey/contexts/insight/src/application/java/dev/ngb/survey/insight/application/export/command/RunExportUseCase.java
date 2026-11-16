package dev.ngb.survey.insight.application.export.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter;
import dev.ngb.survey.insight.application.export.port.ExportRepository;
import dev.ngb.survey.insight.application.export.port.ExportStore;
import dev.ngb.survey.insight.application.export.service.CsvExportWriter;
import dev.ngb.survey.insight.domain.export.Export;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Produces one queued export's CSV and marks it done, or failed when the file cannot be written.
 *
 * <p>It claims the row first, so two workers that tick at the same moment do not both stream the same survey; a claim
 * that loses leaves the export for the next tick. The write itself happens outside the row's transaction, because an S3
 * call cannot roll back.
 */
public class RunExportUseCase {

    private final UnitOfWork unitOfWork;
    private final ExportRepository exports;
    private final ExportStore store;
    private final ResponseReader responses;
    private final PublishedVersionReader versions;
    private final CsvExportWriter writer;
    private final Clock clock;

    public RunExportUseCase(
            UnitOfWork unitOfWork,
            ExportRepository exports,
            ExportStore store,
            ResponseReader responses,
            PublishedVersionReader versions,
            CsvExportWriter writer,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.exports = exports;
        this.store = store;
        this.responses = responses;
        this.versions = versions;
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * @param exportId the queued export to produce
     * @return true when this run produced it, false when another worker claimed it first
     */
    public boolean execute(UUID exportId) {
        boolean claimed = unitOfWork.transactional(() -> exports.claim(exportId));
        if (!claimed) {
            return false;
        }
        Export export = unitOfWork.transactional(() -> exports.findById(exportId))
                .orElseThrow(() -> new IllegalStateException("a claimed export must exist"));

        try {
            ResponseFilter filter = filterOf(export.filter());
            Map<UUID, String> labels = labelsOf(export.surveyId());
            String csv = unitOfWork.transactional(() -> writer.render(export.surveyId(), filter, labels));
            String objectKey = objectKey(export);
            store.put(objectKey, csv.getBytes(StandardCharsets.UTF_8), "text/csv; charset=utf-8");

            long rows = csv.lines().count() - 1;
            Export done = export.done(objectKey, Math.max(0, rows), clock.now());
            unitOfWork.transactional(() -> {
                exports.finish(done);
                return null;
            });
            return true;
        } catch (RuntimeException failure) {
            Export failed = export.failed(clock.now());
            unitOfWork.transactional(() -> {
                exports.finish(failed);
                return null;
            });
            throw failure;
        }
    }

    /** The stored filter, as the response reader narrows by. */
    private static ResponseFilter filterOf(Map<String, Object> filter) {
        return new ResponseFilter(
                integer(filter.get("version")),
                instant(filter.get("from")),
                instant(filter.get("to")),
                text(filter.get("source")),
                text(filter.get("external_ref")));
    }

    /** The default-locale label per question, from the survey's latest version, so the header reads for a person. */
    private Map<UUID, String> labelsOf(UUID surveyId) {
        Map<String, String> types = unitOfWork.transactional(() -> versions.questionTypes(surveyId));
        Map<UUID, String> labels = new LinkedHashMap<>();
        types.keySet().forEach(id -> {
            try {
                labels.put(UUID.fromString(id), id);
            } catch (RuntimeException notAnId) {
                // A malformed id cannot head a column, so it is skipped rather than breaking the export.
            }
        });
        return labels;
    }

    /** A key that names the tenant and the export, so a file cannot collide with another tenant's. */
    private static String objectKey(Export export) {
        return "tenant/" + export.tenantId() + "/survey/" + export.surveyId() + "/exports/" + export.id() + ".csv";
    }

    private static Integer integer(Object raw) {
        return raw instanceof Number number ? number.intValue() : null;
    }

    private static String text(Object raw) {
        return raw instanceof String value ? value : null;
    }

    private static Instant instant(Object raw) {
        if (!(raw instanceof String value) || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException notAnInstant) {
            try {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (RuntimeException notADate) {
                return null;
            }
        }
    }
}
