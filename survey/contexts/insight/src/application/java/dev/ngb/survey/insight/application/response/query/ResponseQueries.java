package dev.ngb.survey.insight.application.response.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The responses a Tenant reads, and the one detail page it opens.
 *
 * <p>It is a read model over {@code collection}'s api, so a response that is deleted there disappears from here without
 * insight keeping a copy. The pagination is a plain window: a Tenant reads a page, not a cursor stream.
 */
public class ResponseQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;

    public ResponseQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
    }

    public ResponsePage page(UUID surveyId, ResponseFilter filter, int offset, int limit) {
        return unitOfWork.transactional(() -> new ResponsePage(
                responses.responses(surveyId, filter, offset, limit), responses.count(surveyId, filter)));
    }

    public ResponseReader.ResponseRow get(UUID responseId) {
        return unitOfWork.transactional(() -> responses.response(responseId)
                .orElseThrow(() -> new NotFoundException("No response exists here.")));
    }

    /** A page of responses and the total the caller should say it saw. */
    public record ResponsePage(List<ResponseReader.ResponseRow> rows, long total) {}

    /** The filter a query string names, defaulting to every completed response. */
    public static ResponseFilter filterOf(
            Integer version, Instant from, Instant to, String source, String externalRef) {
        return new ResponseFilter(version, from, to, source, externalRef);
    }
}
