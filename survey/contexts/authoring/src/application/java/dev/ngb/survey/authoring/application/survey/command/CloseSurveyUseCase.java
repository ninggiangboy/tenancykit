package dev.ngb.survey.authoring.application.survey.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.survey.Survey;
import java.util.UUID;

/**
 * Withdraws every version from the public surface. Closing a survey that is already closed changes nothing, so a
 * retried call is harmless.
 */
public class CloseSurveyUseCase {

    private final UnitOfWork unitOfWork;
    private final SurveyRepository surveys;
    private final Clock clock;

    public CloseSurveyUseCase(UnitOfWork unitOfWork, SurveyRepository surveys, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
        this.clock = clock;
    }

    public Survey execute(UUID surveyId) {
        return unitOfWork.transactional(() -> {
            Survey current = surveys.findById(surveyId)
                    .orElseThrow(() -> new NotFoundException("No survey exists here."));
            Survey closed = current.closed(clock.now());
            if (closed == current) {
                return current;
            }
            if (!surveys.update(closed, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return closed;
        });
    }
}
