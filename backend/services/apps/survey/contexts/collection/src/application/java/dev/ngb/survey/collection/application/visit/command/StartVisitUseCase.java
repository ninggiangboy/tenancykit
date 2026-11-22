package dev.ngb.survey.collection.application.visit.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.domain.visit.Arrival;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.collection.domain.visit.VisitToken;
import java.time.Instant;
import java.util.UUID;

/**
 * Creates the visit one opening of a link produces and hands the browser the opaque token it will identify itself with.
 *
 * <p>It is the write half of {@link OpenLinkUseCase}: the resolved link, the pinned version and the reduced arrival facts
 * are already decided, so this only records the visit and its token hash.
 */
public class StartVisitUseCase {

    private final UnitOfWork unitOfWork;
    private final VisitRepository visits;
    private final IdGenerator ids;
    private final Clock clock;

    public StartVisitUseCase(UnitOfWork unitOfWork, VisitRepository visits, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.visits = visits;
        this.ids = ids;
        this.clock = clock;
    }

    /** @return the issued token and the visit it identifies */
    public Started execute(Opened opened) {
        return unitOfWork.transactional(() -> {
            VisitToken.Issued token = VisitToken.issue();
            Instant now = clock.now();
            Visit visit = Visit.opened(
                    ids.newId(),
                    opened.tenantId(),
                    opened.surveyId(),
                    opened.linkId(),
                    opened.versionId(),
                    opened.versionNo(),
                    token.hash(),
                    opened.arrival(),
                    now);
            visits.insert(visit);
            return new Started(token.token(), visit);
        });
    }

    /** What the resolver found: the link's identity, the version it pinned and the facts the request contributed. */
    public record Opened(
            UUID tenantId, UUID surveyId, UUID linkId, UUID versionId, int versionNo, Arrival arrival) {}

    /** The token a browser holds and the visit it names. */
    public record Started(String visitToken, Visit visit) {}
}
