package dev.ngb.booking.catalog.application.resource.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** Reads resources straight into the view; the aggregate is never rebuilt for a read. */
public interface ResourceReadRepository {

    Page<ResourceView> list(ListQuery query);

    Optional<ResourceView> findById(UUID id);
}
