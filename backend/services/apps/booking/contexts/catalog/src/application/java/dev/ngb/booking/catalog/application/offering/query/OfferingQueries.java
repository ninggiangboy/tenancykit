package dev.ngb.booking.catalog.application.offering.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads the current tenant's offerings for the GET handlers. */
public class OfferingQueries {

    private final UnitOfWork unitOfWork;
    private final OfferingReadRepository offerings;

    public OfferingQueries(UnitOfWork unitOfWork, OfferingReadRepository offerings) {
        this.unitOfWork = unitOfWork;
        this.offerings = offerings;
    }

    public Page<OfferingView> list(ListQuery query) {
        return unitOfWork.transactional(() -> offerings.list(query));
    }

    public OfferingView get(UUID offeringId) {
        return unitOfWork.transactional(() -> offerings
                .findById(offeringId)
                .orElseThrow(() -> new NotFoundException("No offering exists here with that id.")));
    }
}
