package dev.ngb.survey.collection.application.visit.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.domain.visit.Visit;
import java.time.Duration;
import java.util.List;

/**
 * Moves the visits that stopped to {@code ABANDONED}, which is what the funnel's fourth count is built from.
 *
 * <p>A visit is abandoned when it is still open or started and its last activity is older than the threshold. The run is
 * bounded so a backlog is several sweeps rather than one that holds a connection for minutes, and each visit's state is
 * compared before it moves, so a respondent who came back at the same moment is not overwritten.
 */
public class AbandonIdleVisitsUseCase {

    private final UnitOfWork unitOfWork;
    private final VisitRepository visits;
    private final Clock clock;

    public AbandonIdleVisitsUseCase(UnitOfWork unitOfWork, VisitRepository visits, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.visits = visits;
        this.clock = clock;
    }

    /**
     * @param threshold how long a visit may stay idle before it is abandoned
     * @param limit the most visits one sweep moves
     * @return how many visits moved
     */
    public int execute(Duration threshold, int limit) {
        return unitOfWork.transactional(() -> {
            List<Visit> idle = visits.idleSince(clock.now().minus(threshold), limit);
            int moved = 0;
            for (Visit visit : idle) {
                if (visit.idleSince(threshold, clock.now())
                        && visits.save(visit.abandoned(), visit.state().name())) {
                    moved++;
                }
            }
            return moved;
        });
    }
}
