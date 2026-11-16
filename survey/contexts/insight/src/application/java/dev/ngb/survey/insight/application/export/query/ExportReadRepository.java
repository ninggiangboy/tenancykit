package dev.ngb.survey.insight.application.export.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of the export ledger, separate from its write side so a query never drives a write.
 *
 * <p>It selects the rows a Tenant reads, into a read model rather than the aggregate, so the query side names no domain
 * type; the claim and finish statements belong to the port the worker uses.
 */
public interface ExportReadRepository {

    Optional<ExportRecord> find(UUID exportId);

    Page<ExportRecord> ofSurvey(ListQuery query, UUID surveyId);
}
