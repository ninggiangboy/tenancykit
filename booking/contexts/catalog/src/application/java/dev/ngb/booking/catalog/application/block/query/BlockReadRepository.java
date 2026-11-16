package dev.ngb.booking.catalog.application.block.query;

import java.util.List;

/** Reads the current tenant's blocks straight into the view; the aggregate is never rebuilt for a read. */
public interface BlockReadRepository {

    List<BlockView> all();
}
