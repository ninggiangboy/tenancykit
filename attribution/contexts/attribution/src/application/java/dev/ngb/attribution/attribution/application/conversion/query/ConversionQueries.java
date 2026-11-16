package dev.ngb.attribution.attribution.application.conversion.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;

/** The conversion read side, opened in a unit of work so row-level security scopes it to the caller's tenant. */
public class ConversionQueries {

    private final UnitOfWork unitOfWork;
    private final ConversionReadRepository conversions;

    public ConversionQueries(UnitOfWork unitOfWork, ConversionReadRepository conversions) {
        this.unitOfWork = unitOfWork;
        this.conversions = conversions;
    }

    /** @param query the caller's filter, order and page position, already validated against the listing's schema */
    public Page<ConversionView> list(ListQuery query) {
        return unitOfWork.transactional(() -> conversions.list(query));
    }
}
