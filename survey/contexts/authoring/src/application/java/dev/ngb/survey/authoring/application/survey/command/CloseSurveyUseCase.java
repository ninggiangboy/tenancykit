package dev.ngb.survey.authoring.application.survey.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.authoring.domain.version.SurveyLifecycleEvents;
import java.time.Instant;
import java.util.UUID;

/**
 * Withdraws every version from the public surface. Closing a survey that is already closed changes nothing, so a
 * retried call is harmless and announces nothing the second time.
 */
public class CloseSurveyUseCase {

    private final UnitOfWork unitOfWork;
    private final SurveyRepository surveys;
    private final EventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;

    public CloseSurveyUseCase(
            UnitOfWork unitOfWork,
            SurveyRepository surveys,
            EventPublisher events,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public Survey execute(UUID surveyId) {
        return unitOfWork.transactional(() -> {
            Survey current = surveys.findById(surveyId)
                    .orElseThrow(() -> new NotFoundException("No survey exists here."));
            Instant now = clock.now();
            Survey closed = current.closed(now);
            if (closed == current) {
                return current;
            }
            if (!surveys.update(closed, current.version())) {
                throw new PreconditionFailedException(null);
            }
            events.publish(new SurveyLifecycleEvents.SurveyClosed(
                    ids.newId(), current.tenantId(), surveyId, now));
            return closed;
        });
    }
}
