package dev.ngb.survey.authoring.application.draft.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.application.draft.exception.DraftInvalidException;
import dev.ngb.survey.authoring.application.draft.port.DocumentVerifier;
import dev.ngb.survey.authoring.application.draft.port.DraftRepository;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.draft.DraftRules;
import dev.ngb.survey.authoring.domain.draft.SurveyDraft;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces a survey's draft, guarded by {@code If-Match} on the draft's tag.
 *
 * <p>The server assigns an id to every structural node that arrived without one and returns the stored document, so a
 * builder that never invents ids still keeps them across saves. Only the shape is enforced here: a draft in progress
 * may be incomplete — the translations and jumps are checked when the tenant validates or publishes.
 */
public class SaveDraftUseCase {

    private final UnitOfWork unitOfWork;
    private final SurveyRepository surveys;
    private final DraftRepository drafts;
    private final DocumentVerifier verifier;
    private final IdGenerator ids;
    private final Clock clock;

    public SaveDraftUseCase(
            UnitOfWork unitOfWork,
            SurveyRepository surveys,
            DraftRepository drafts,
            DocumentVerifier verifier,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
        this.drafts = drafts;
        this.verifier = verifier;
        this.ids = ids;
        this.clock = clock;
    }

    public SurveyDraft execute(
            UUID surveyId, Map<String, Object> document, ExpectedVersion expected, String actor) {
        return unitOfWork.transactional(() -> {
            surveys.findById(surveyId).orElseThrow(() -> new NotFoundException("No survey exists here."));
            SurveyDraft current = drafts.findBySurveyId(surveyId)
                    .orElseThrow(() -> new NotFoundException("No survey exists here."));
            expected.check(current.etag());

            Map<String, Object> stored = DraftRules.withIds(document, ids);
            List<ValidationError> problems = verifier.structuralProblems(stored);
            if (!problems.isEmpty()) {
                throw new DraftInvalidException(problems);
            }

            SurveyDraft saved = current.saved(stored, clock.now());
            if (!drafts.save(saved, current.etag(), actor)) {
                throw new PreconditionFailedException(null);
            }
            return saved;
        });
    }
}
