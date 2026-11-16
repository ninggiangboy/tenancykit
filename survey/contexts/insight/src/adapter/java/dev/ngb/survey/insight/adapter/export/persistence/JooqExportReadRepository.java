package dev.ngb.survey.insight.adapter.export.persistence;

import static dev.ngb.survey_jooq.Tables.EXPORTS;

import dev.ngb.survey.insight.application.export.query.ExportReadRepository;
import dev.ngb.survey.insight.application.export.query.ExportRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The read side of the export ledger, sitting beside its write repository.
 *
 * <p>It selects the rows a Tenant reads into the read model and never claims or finishes one, so a read cannot drive the
 * worker's state.
 */
public class JooqExportReadRepository implements ExportReadRepository {

    private final DSLContext dsl;

    public JooqExportReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ExportRecord> find(UUID exportId) {
        return dsl.selectFrom(EXPORTS).where(EXPORTS.ID.eq(exportId)).fetchOptional(this::toRecord);
    }

    @Override
    public List<ExportRecord> ofSurvey(UUID surveyId, int limit) {
        return dsl.selectFrom(EXPORTS)
                .where(EXPORTS.SURVEY_ID.eq(surveyId))
                .orderBy(EXPORTS.CREATED_AT.desc())
                .limit(limit)
                .fetch(this::toRecord);
    }

    private ExportRecord toRecord(Record row) {
        return new ExportRecord(
                row.get(EXPORTS.ID),
                row.get(EXPORTS.TENANT_ID),
                row.get(EXPORTS.SURVEY_ID),
                row.get(EXPORTS.STATUS),
                Filters.read(row.get(EXPORTS.FILTER)),
                row.get(EXPORTS.OBJECT_KEY),
                row.get(EXPORTS.ROWS),
                row.get(EXPORTS.REQUESTED_BY),
                row.get(EXPORTS.CREATED_AT),
                row.get(EXPORTS.FINISHED_AT));
    }
}
