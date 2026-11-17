package dev.ngb.booking.catalog.application.session.command;

import dev.ngb.booking.catalog.api.session.SessionPlaces;
import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.application.session.service.SessionRules;
import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/**
 * Replaces a session's pools with the set the caller sent.
 *
 * <p>The stored set is replaced whole and the places follow it: a pool that grows gains the free places it gained, a
 * pool that shrinks gives free ones back, and a pool the caller dropped has its free places removed. A set that adds
 * up to more places than the session offers, or names a code twice, is refused rather than stored, and a pool that
 * would shrink below the places a booking holds or has booked is refused by the places themselves.
 */
public class ResizePoolsUseCase {

    /** The pool set the session should hold. */
    public record Command(List<Pool> pools) {}

    /** The session and the pools it now holds. */
    public record Result(Session session, List<Pool> pools) {}

    private final UnitOfWork unitOfWork;
    private final SessionRepository sessions;
    private final SessionPlaces places;

    public ResizePoolsUseCase(UnitOfWork unitOfWork, SessionRepository sessions, SessionPlaces places) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
        this.places = places;
    }

    public Result execute(UUID sessionId, Command command) {
        return unitOfWork.transactional(() -> {
            Session session = sessions
                    .lockSessionById(sessionId)
                    .orElseThrow(() -> new NotFoundException("No session exists here with that id."));
            List<Pool> before = poolsOf(session);
            List<Pool> pools = SessionRules.pools(command.pools(), session.capacity());
            sessions.replacePools(sessionId, pools);
            List<String> kept = pools.stream().map(pool -> pool.code().value()).toList();
            for (Pool pool : pools) {
                places.resizePlaces(sessionId, pool.code().value(), pool.capacity());
            }
            for (Pool pool : before) {
                if (!kept.contains(pool.code().value())) {
                    places.deletePlaces(sessionId, pool.code().value());
                }
            }
            return new Result(session, pools);
        });
    }

    /** The pools the session carries now, which is what the set the caller sent is compared against. */
    private List<Pool> poolsOf(Session session) {
        return session.isFromSeries() ? sessions.poolsOfSeries(session.seriesId().value()) : List.of();
    }
}
