package dev.ngb.booking.catalog.application.session.command;

import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.application.session.service.SessionMaterializer;
import dev.ngb.booking.catalog.application.session.service.SessionRules;
import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.SeriesStatus;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code PATCH /session-series/{id}}: only the properties the caller sent change, and only if the series is still the
 * version the caller read.
 *
 * <p>Changing a series regenerates only its future sessions: the scheduled ones from now on are removed and
 * materialized again from the new rule, while a cancelled occurrence stays where it is and stays excluded. A session a
 * booking references is left alone and reported, which is nothing while no booking exists.
 */
public class PatchSessionSeriesUseCase {

    /** A change request: a null property is left as it is. */
    public record Change(List<String> rule, Integer durationMinutes, Integer capacity, SeriesStatus status) {}

    /** The series, the sessions it materialized and the future sessions it left alone. */
    public record Result(SessionSeries series, List<Session> sessions, List<UUID> untouchedSessions, List<Pool> pools) {}

    private final UnitOfWork unitOfWork;
    private final SessionRepository sessions;
    private final SessionMaterializer materializer;
    private final Clock clock;

    public PatchSessionSeriesUseCase(
            UnitOfWork unitOfWork, SessionRepository sessions, SessionMaterializer materializer, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
        this.materializer = materializer;
        this.clock = clock;
    }

    public Result execute(UUID seriesId, Change change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            SessionSeries series = sessions
                    .lockSeriesById(seriesId)
                    .orElseThrow(() -> new NotFoundException("No session series exists here with that id."));
            expected.check(series.version());
            List<String> rule = change.rule() == null ? series.rule() : SessionRules.lines(change.rule());
            int durationMinutes = change.durationMinutes() == null
                    ? series.durationMinutes()
                    : SessionRules.requiredPositive(change.durationMinutes(), "durationMinutes");
            int capacity = change.capacity() == null
                    ? series.capacity()
                    : SessionRules.requiredPositive(change.capacity(), "capacity");
            SeriesStatus status = change.status() == null ? series.status() : change.status();
            List<Pool> pools = materializer.poolsOf(seriesId, capacity);
            Instant now = clock.now();
            if (!series.change(rule, durationMinutes, capacity, status, now)) {
                return new Result(series, List.of(), List.of(), pools);
            }
            sessions.updateSeries(series);
            List<Session> materialized = List.of();
            if (series.isActive()) {
                sessions.deleteFutureScheduledSessions(seriesId, now);
                materialized = materializer.materialize(series, pools, now);
            }
            return new Result(series, materialized, List.of(), pools);
        });
    }
}
