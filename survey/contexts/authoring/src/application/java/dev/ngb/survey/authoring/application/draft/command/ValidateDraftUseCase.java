package dev.ngb.survey.authoring.application.draft.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.application.draft.service.DraftValidationService;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.draft.DraftRules;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers every reason a document would not publish, without storing it. The same check runs again at publish, so a
 * draft that changed between the two calls cannot slip through.
 */
public class ValidateDraftUseCase {

    private final UnitOfWork unitOfWork;
    private final SurveyRepository surveys;
    private final DraftValidationService validation;
    private final IdGenerator ids;

    public ValidateDraftUseCase(
            UnitOfWork unitOfWork,
            SurveyRepository surveys,
            DraftValidationService validation,
            IdGenerator ids) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
        this.validation = validation;
        this.ids = ids;
    }

    public List<ValidationError> execute(UUID surveyId, Map<String, Object> document) {
        return unitOfWork.transactional(() -> {
            surveys.findById(surveyId).orElseThrow(() -> new NotFoundException("No survey exists here."));
            return validation.check(DraftRules.withIds(document, ids));
        });
    }
}
