package dev.ngb.survey.insight.application.respondent.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import java.util.UUID;

/**
 * The respondents list: one row per arrival, optionally narrowed to a state or a source.
 *
 * <p>A completed visit takes its identity from the response it produced; a visit that stopped before finishing has only
 * the personal link's reference, because a public link never names a person. That is why the list is what a Tenant uses
 * to decide who to chase, and not a claim about who the person is.
 *
 * <p>The listing is {@code collection}'s: it owns the visits and the responses the rows are built from.
 */
public class RespondentQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;

    public RespondentQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
    }

    public Page<RespondentView> page(UUID surveyId, ListQuery query) {
        return unitOfWork.transactional(() -> responses.respondents(surveyId, query).map(RespondentQueries::toView));
    }

    private static RespondentView toView(ResponseReader.RespondentRow row) {
        return new RespondentView(
                row.respondentIdentity(), null, row.source(), row.state(), row.lastActivityAt(), row.responseId());
    }
}
