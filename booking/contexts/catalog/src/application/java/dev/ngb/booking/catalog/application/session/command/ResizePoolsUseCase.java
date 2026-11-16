package dev.ngb.booking.catalog.application.session.command;

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
 * <p>A pool that grows is stored as it is; the free places a pool gains are a later phase's job, because no place
 * exists yet. A set that adds up to more places than the session offers, or names a code twice, is refused rather than
 * stored.
 */
public class ResizePoolsUseCase {

    /** The pool set the session should hold. */
    public record Command(List<Pool> pools) {}

    /** The session and the pools it now holds. */
    public record Result(Session session, List<Pool> pools) {}

    private final UnitOfWork unitOfWork;
    private final SessionRepository sessions;

    public ResizePoolsUseCase(UnitOfWork unitOfWork, SessionRepository sessions) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
    }

    public Result execute(UUID sessionId, Command command) {
        return unitOfWork.transactional(() -> {
            Session session = sessions
                    .lockSessionById(sessionId)
                    .orElseThrow(() -> new NotFoundException("No session exists here with that id."));
            List<Pool> pools = SessionRules.pools(command.pools(), session.capacity());
            sessions.replacePools(sessionId, pools);
            return new Result(session, pools);
        });
    }
}
