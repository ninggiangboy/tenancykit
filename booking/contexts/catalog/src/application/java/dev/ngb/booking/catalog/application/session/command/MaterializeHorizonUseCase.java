package dev.ngb.booking.catalog.application.session.command;

import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.application.session.service.SessionMaterializer;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.List;

/**
 * Materializes every active series of one tenant to its horizon.
 *
 * <p>This is what the daily job runs, and what makes the horizon roll forward: a series is expanded from now, and the
 * pair of series and start instant means an occurrence that already exists is left as it is. The job runs inside one
 * tenant's scope, so a pass sees that tenant's series and no other's.
 */
public class MaterializeHorizonUseCase {

    /** How many series one pass expanded and how many sessions it inserted. */
    public record Result(int series, int sessions) {}

    private final UnitOfWork unitOfWork;
    private final SessionRepository sessions;
    private final SessionMaterializer materializer;
    private final Clock clock;

    public MaterializeHorizonUseCase(
            UnitOfWork unitOfWork, SessionRepository sessions, SessionMaterializer materializer, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
        this.materializer = materializer;
        this.clock = clock;
    }

    public Result execute() {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            List<SessionSeries> active = sessions.activeSeries();
            int inserted = 0;
            for (SessionSeries series : active) {
                List<Session> materialized = materializer.materialize(
                        series, materializer.poolsOf(series.id().value(), series.capacity()), now);
                inserted += materialized.size();
            }
            return new Result(active.size(), inserted);
        });
    }
}
