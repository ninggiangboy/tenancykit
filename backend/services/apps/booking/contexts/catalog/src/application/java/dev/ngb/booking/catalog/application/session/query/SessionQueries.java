package dev.ngb.booking.catalog.application.session.query;

import dev.ngb.booking.catalog.api.session.SessionPlaces;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the current tenant's sessions and session series for the GET handlers.
 *
 * <p>A session's pools are stored with the capacity they offer, but how many places are still free is a fact about
 * the places themselves, so it is read from the engine that owns them rather than carried in the pool row where it
 * would have to be kept in step.
 */
public class SessionQueries {

    private final UnitOfWork unitOfWork;
    private final SessionReadRepository sessions;
    private final SessionPlaces places;

    public SessionQueries(UnitOfWork unitOfWork, SessionReadRepository sessions, SessionPlaces places) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
        this.places = places;
    }

    public Page<SessionView> list(ListQuery query) {
        return unitOfWork.transactional(() -> sessions.list(query).map(this::withRemaining));
    }

    /** @throws NotFoundException when no session of this tenant has the id */
    public SessionView get(UUID sessionId) {
        return unitOfWork.transactional(() -> withRemaining(sessions
                .findById(sessionId)
                .orElseThrow(() -> new NotFoundException("No session exists here with that id."))));
    }

    /** A series' pools are the ones its sessions carry; the places of one occurrence are a session's own read. */
    public List<SessionSeriesView> series() {
        return unitOfWork.transactional(() -> sessions.series());
    }

    /** The same session, with each pool's remaining read from the places themselves. */
    private SessionView withRemaining(SessionView view) {
        List<PoolView> pools = new ArrayList<>(view.pools().size());
        for (PoolView pool : view.pools()) {
            pools.add(new PoolView(pool.code(), pool.capacity(), places.remaining(view.id(), pool.code())));
        }
        return new SessionView(
                view.id(),
                view.offeringId(),
                view.seriesId(),
                view.startsAt(),
                view.endsAt(),
                view.status(),
                view.capacity(),
                pools);
    }
}
