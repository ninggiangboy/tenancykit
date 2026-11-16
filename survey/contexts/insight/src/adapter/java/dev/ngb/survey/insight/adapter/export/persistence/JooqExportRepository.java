package dev.ngb.survey.insight.adapter.export.persistence;

import static dev.ngb.survey_jooq.Tables.EXPORTS;

import dev.ngb.survey.insight.application.export.port.ExportRepository;
import dev.ngb.survey.insight.domain.export.Export;
import dev.ngb.survey.insight.domain.export.ExportStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The export ledger, which is the one table insight writes.
 *
 * <p>A claim is a conditional update, so two workers that tick at the same time cannot both stream the same survey: the
 * loser sees zero rows updated and leaves the export for the next tick.
 */
public class JooqExportRepository implements ExportRepository {

    private static final TypeReference<Map<String, Object>> FILTER = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqExportRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(Export export) {
        dsl.insertInto(EXPORTS)
                .set(EXPORTS.ID, export.id())
                .set(EXPORTS.TENANT_ID, export.tenantId())
                .set(EXPORTS.SURVEY_ID, export.surveyId())
                .set(EXPORTS.STATUS, export.status().name())
                .set(EXPORTS.FILTER, JSONB.valueOf(json.writeValueAsString(export.filter())))
                .set(EXPORTS.OBJECT_KEY, export.objectKey())
                .set(EXPORTS.ROWS, export.rows())
                .set(EXPORTS.REQUESTED_BY, export.requestedBy())
                .set(EXPORTS.CREATED_AT, export.createdAt())
                .set(EXPORTS.FINISHED_AT, export.finishedAt())
                .execute();
    }

    @Override
    public Optional<Export> findById(UUID exportId) {
        return dsl.selectFrom(EXPORTS)
                .where(EXPORTS.ID.eq(exportId))
                .fetchOptional(this::toExport);
    }

    @Override
    public boolean claim(UUID exportId) {
        return dsl.update(EXPORTS)
                .set(EXPORTS.STATUS, ExportStatus.RUNNING.name())
                .where(EXPORTS.ID.eq(exportId))
                .and(EXPORTS.STATUS.eq(ExportStatus.QUEUED.name()))
                .execute()
                > 0;
    }

    @Override
    public void finish(Export export) {
        dsl.update(EXPORTS)
                .set(EXPORTS.STATUS, export.status().name())
                .set(EXPORTS.OBJECT_KEY, export.objectKey())
                .set(EXPORTS.ROWS, export.rows())
                .set(EXPORTS.FINISHED_AT, export.finishedAt())
                .where(EXPORTS.ID.eq(export.id()))
                .execute();
    }

    @Override
    public List<Export> queued(int limit) {
        return dsl.selectFrom(EXPORTS)
                .where(EXPORTS.STATUS.eq(ExportStatus.QUEUED.name()))
                .orderBy(EXPORTS.CREATED_AT.asc())
                .limit(limit)
                .fetch(this::toExport);
    }

    private Export toExport(Record row) {
        return new Export(
                row.get(EXPORTS.ID),
                row.get(EXPORTS.TENANT_ID),
                row.get(EXPORTS.SURVEY_ID),
                ExportStatus.valueOf(row.get(EXPORTS.STATUS)),
                readFilter(row.get(EXPORTS.FILTER)),
                row.get(EXPORTS.OBJECT_KEY),
                row.get(EXPORTS.ROWS),
                row.get(EXPORTS.REQUESTED_BY),
                row.get(EXPORTS.CREATED_AT),
                row.get(EXPORTS.FINISHED_AT));
    }

    private Map<String, Object> readFilter(JSONB stored) {
        Map<String, Object> filter = stored == null ? null : json.readValue(stored.data(), FILTER);
        return filter == null ? Map.of() : filter;
    }
}
