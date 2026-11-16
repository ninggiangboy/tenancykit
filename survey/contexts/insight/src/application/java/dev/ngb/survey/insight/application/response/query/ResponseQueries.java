package dev.ngb.survey.insight.application.response.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import java.util.UUID;

/**
 * The responses a Tenant reads, and the one detail page it opens.
 *
 * <p>It is a read model over {@code collection}'s api, so a response that is deleted there disappears from here without
 * insight keeping a copy. The listing itself is {@code collection}'s: it owns the rows and binds the columns.
 */
public class ResponseQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;

    public ResponseQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
    }

    public Page<ResponseReader.ResponseRow> page(UUID surveyId, ListQuery query) {
        return unitOfWork.transactional(() -> responses.list(surveyId, query));
    }

    public ResponseReader.ResponseRow get(UUID responseId) {
        return unitOfWork.transactional(() -> responses.response(responseId)
                .orElseThrow(() -> new NotFoundException("No response exists here.")));
    }
}
