package dev.ngb.booking.catalog.application.session.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads sessions and session series straight into their views; the aggregate is never rebuilt for a read. A page's
 * pools are read in one statement for the whole page rather than one per session.
 */
public interface SessionReadRepository {

    Page<SessionView> list(ListQuery query);

    Optional<SessionView> findById(UUID sessionId);

    List<SessionSeriesView> series();
}
