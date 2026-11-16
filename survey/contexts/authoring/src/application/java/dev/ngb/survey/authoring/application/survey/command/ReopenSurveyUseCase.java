package dev.ngb.survey.authoring.application.survey.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.survey.Survey;
import java.util.UUID;

/**
 * Opens a closed survey again. A survey closed before its first publish returns to {@code DRAFT}, because there is no
 * version to serve.
 */
public class ReopenSurveyUseCase {

    private final UnitOfWork unitOfWork;
    private final SurveyRepository surveys;
    private final Clock clock;

    public ReopenSurveyUseCase(UnitOfWork unitOfWork, SurveyRepository surveys, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
        this.clock = clock;
    }

    public Survey execute(UUID surveyId) {
        return unitOfWork.transactional(() -> {
            Survey current = surveys.findById(surveyId)
                    .orElseThrow(() -> new NotFoundException("No survey exists here."));
            Survey reopened = current.reopened(clock.now());
            if (reopened == current) {
                return current;
            }
            if (!surveys.update(reopened, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return reopened;
        });
    }
}
