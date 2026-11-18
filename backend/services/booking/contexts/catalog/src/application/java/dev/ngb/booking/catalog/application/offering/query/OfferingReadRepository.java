package dev.ngb.booking.catalog.application.offering.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads offerings straight into the view; the aggregate is never rebuilt for a read. A page's requirements and
 * thresholds are read in one statement each for the whole page rather than one per offering.
 */
public interface OfferingReadRepository {

    Page<OfferingView> list(ListQuery query);

    Optional<OfferingView> findById(UUID id);
}
