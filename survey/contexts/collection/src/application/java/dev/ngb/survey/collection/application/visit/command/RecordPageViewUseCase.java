package dev.ngb.survey.collection.application.visit.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.domain.visit.Visit;
import java.util.UUID;

/**
 * Records one page view a respondent's browser reports.
 *
 * <p>Page views are bounded per visit because the client is what sends them: a visit holds at most
 * {@value #MAX_PAGE_VIEWS} events, so a malicious or broken client cannot grow the table without bound. The visit's
 * activity moves, which is what keeps the sweep from abandoning a respondent who is still reading.
 */
public class RecordPageViewUseCase {

    /** The most page views one visit keeps; a survey of 200 pages fits well inside it. */
    public static final int MAX_PAGE_VIEWS = 200;

    private final UnitOfWork unitOfWork;
    private final VisitRepository visits;
    private final IdGenerator ids;
    private final Clock clock;

    public RecordPageViewUseCase(UnitOfWork unitOfWork, VisitRepository visits, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.visits = visits;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @return false when the visit already holds its page views, which the endpoint answers as a refusal rather than
     *     an error
     * @throws NotFoundException when the visit does not exist
     */
    public boolean execute(UUID visitId, UUID pageId) {
        return unitOfWork.transactional(() -> {
            Visit visit = visits.findById(visitId)
                    .orElseThrow(() -> new NotFoundException("No visit exists here."));
            if (visits.eventCount(visitId) >= MAX_PAGE_VIEWS) {
                return false;
            }
            visits.recordPageView(ids.newId(), visit.tenantId(), visitId, pageId, clock.now());
            visits.save(visit.touched(clock.now()), visit.state().name());
            return true;
        });
    }
}
