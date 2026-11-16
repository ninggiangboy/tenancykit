package dev.ngb.booking.catalog.application.session.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads the current tenant's sessions and session series for the GET handlers. */
public class SessionQueries {

    private final UnitOfWork unitOfWork;
    private final SessionReadRepository sessions;

    public SessionQueries(UnitOfWork unitOfWork, SessionReadRepository sessions) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
    }

    public Page<SessionView> list(ListQuery query) {
        return unitOfWork.transactional(() -> sessions.list(query));
    }

    /** @throws NotFoundException when no session of this tenant has the id */
    public SessionView get(UUID sessionId) {
        return unitOfWork.transactional(() -> sessions
                .findById(sessionId)
                .orElseThrow(() -> new NotFoundException("No session exists here with that id.")));
    }

    public List<SessionSeriesView> series() {
        return unitOfWork.transactional(() -> sessions.series());
    }
}
