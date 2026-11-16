package dev.ngb.booking.catalog.application.group.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads groups straight into the view; the aggregate is never rebuilt for a read. A page's members are read in one
 * statement for the whole page rather than one per group.
 */
public interface GroupReadRepository {

    List<GroupView> list();

    Optional<GroupView> findById(UUID id);
}
