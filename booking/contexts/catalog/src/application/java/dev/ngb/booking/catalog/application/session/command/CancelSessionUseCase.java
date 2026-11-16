package dev.ngb.booking.catalog.application.session.command;

import dev.ngb.booking.catalog.application.session.exception.SessionInUseException;
import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.application.session.service.SessionRules;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.UUID;

/**
 * Cancels one occurrence.
 *
 * <p>The row is kept and marked {@code CANCELLED}, so a booking that referenced it still explains itself. When the
 * occurrence came from a series, the series' start instant is appended to it as an {@code EXDATE} in the series' own
 * zone, which is what keeps the next expansion from recreating it. A session that still holds places a customer is
 * committed to is refused rather than cancelled under them.
 */
public class CancelSessionUseCase {

    private final UnitOfWork unitOfWork;
    private final SessionRepository sessions;
    private final Clock clock;

    public CancelSessionUseCase(UnitOfWork unitOfWork, SessionRepository sessions, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void execute(UUID sessionId) {
        unitOfWork.transactional(() -> {
            Session session = sessions
                    .lockSessionById(sessionId)
                    .orElseThrow(() -> new NotFoundException("No session exists here with that id."));
            if (sessions.hasHeldOrBookedPlaces(sessionId)) {
                throw new SessionInUseException();
            }
            if (session.isCancelled()) {
                return;
            }
            Instant now = clock.now();
            sessions.markCancelled(session.cancelled(now));
            if (session.isFromSeries()) {
                SessionSeries series = sessions
                        .lockSeriesById(session.seriesId().value())
                        .orElseThrow(() -> new NotFoundException("No session series exists here with that id."));
                if (series.exclude(SessionRules.exclusion(series.zone(), session.startsAt()), now)) {
                    sessions.updateSeries(series);
                }
            }
        });
    }
}
