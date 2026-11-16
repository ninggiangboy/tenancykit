package dev.ngb.survey.collection.application.response.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** One response as a caller reads it, inside a tenant-scoped transaction. */
public class ResponseQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReadRepository responses;

    public ResponseQueries(UnitOfWork unitOfWork, ResponseReadRepository responses) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
    }

    public ResponseView get(UUID responseId) {
        return unitOfWork.transactional(() -> responses.findById(responseId)
                .orElseThrow(() -> new NotFoundException("No response exists here.")));
    }

    /** The response of one visit, inside the caller's tenant scope, for a browser resuming its own draft. */
    public java.util.Optional<ResponseView> ofVisit(UUID visitId) {
        return unitOfWork.transactional(() -> responses.findByVisitId(visitId));
    }
}
