package dev.ngb.survey.insight.adapter.export.persistence;

import static dev.ngb.survey_jooq.Tables.EXPORTS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.survey.insight.application.export.query.ExportListing;
import dev.ngb.survey.insight.application.export.query.ExportReadRepository;
import dev.ngb.survey.insight.application.export.query.ExportRecord;
import dev.ngb.survey_jooq.tables.records.ExportsRecord;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The read side of the export ledger, sitting beside its write repository.
 *
 * <p>It selects the rows a Tenant reads into the read model and never claims or finishes one, so a read cannot drive the
 * worker's state.
 */
public class JooqExportReadRepository implements ExportReadRepository {

    /** The export fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(ExportListing.SCHEMA)
            .column("id", EXPORTS.ID)
            .column("survey_id", EXPORTS.SURVEY_ID)
            .column("status", EXPORTS.STATUS)
            .column("object_key", EXPORTS.OBJECT_KEY)
            .column("rows", EXPORTS.ROWS)
            .column("requested_by", EXPORTS.REQUESTED_BY)
            .column("created_at", EXPORTS.CREATED_AT)
            .column("finished_at", EXPORTS.FINISHED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the export listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqExportReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ExportRecord> find(UUID exportId) {
        return dsl.selectFrom(EXPORTS).where(EXPORTS.ID.eq(exportId)).fetchOptional(this::toRecord);
    }

    @Override
    public Page<ExportRecord> ofSurvey(ListQuery query, UUID surveyId) {
        return JooqLists.fetch(dsl, EXPORTS, EXPORTS.SURVEY_ID.eq(surveyId), BINDING, query, this::toRecord);
    }

    private ExportRecord toRecord(ExportsRecord row) {
        return new ExportRecord(
                row.getId(),
                row.getTenantId(),
                row.getSurveyId(),
                row.getStatus(),
                Filters.read(row.getFilter()),
                row.getObjectKey(),
                row.getRows(),
                row.getRequestedBy(),
                row.getCreatedAt(),
                row.getFinishedAt());
    }
}
