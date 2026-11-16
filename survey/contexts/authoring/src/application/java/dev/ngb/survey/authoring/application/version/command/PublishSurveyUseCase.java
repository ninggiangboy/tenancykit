package dev.ngb.survey.authoring.application.version.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.application.draft.exception.DraftInvalidException;
import dev.ngb.survey.authoring.application.draft.port.DraftRepository;
import dev.ngb.survey.authoring.application.draft.service.DraftValidationService;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.application.version.port.VersionRepository;
import dev.ngb.survey.authoring.domain.draft.DraftRules;
import dev.ngb.survey.authoring.domain.draft.SurveyDraft;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.authoring.domain.version.QuestionLineage;
import dev.ngb.survey.authoring.domain.version.QuestionRetyping;
import dev.ngb.survey.authoring.domain.version.SurveyVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes the draft as the next immutable version, guarded by {@code If-Match} on the draft.
 *
 * <p>In one transaction: validate the document, compare its question types against the latest version — a question
 * whose type changed is given a new id and a lineage row — insert the version, write the retyped document back to the
 * draft and move the survey's pointer. The draft's tag moves with it, so a second publish needs a fresh read and a
 * double submit cannot publish the same document twice by accident.
 */
public class PublishSurveyUseCase {

    private final UnitOfWork unitOfWork;
    private final SurveyRepository surveys;
    private final DraftRepository drafts;
    private final VersionRepository versions;
    private final DraftValidationService validation;
    private final IdGenerator ids;
    private final Clock clock;

    public PublishSurveyUseCase(
            UnitOfWork unitOfWork,
            SurveyRepository surveys,
            DraftRepository drafts,
            VersionRepository versions,
            DraftValidationService validation,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
        this.drafts = drafts;
        this.versions = versions;
        this.validation = validation;
        this.ids = ids;
        this.clock = clock;
    }

    public SurveyVersion execute(UUID surveyId, ExpectedVersion expected, String actor) {
        return unitOfWork.transactional(() -> {
            Survey survey = surveys.findById(surveyId)
                    .orElseThrow(() -> new NotFoundException("No survey exists here."));
            SurveyDraft draft = drafts.findBySurveyId(surveyId)
                    .orElseThrow(() -> new NotFoundException("No survey exists here."));
            expected.check(draft.etag());

            Map<String, Object> document = DraftRules.withIds(draft.document(), ids);
            List<ValidationError> errors = validation.check(document);
            if (!errors.isEmpty()) {
                throw new DraftInvalidException(errors);
            }

            SurveyVersion latest = versions.latest(surveyId).orElse(null);
            int versionNo = latest == null ? 1 : latest.versionNo() + 1;
            QuestionRetyping.Result retyped =
                    QuestionRetyping.apply(latest == null ? null : latest.document(), document, ids);

            Instant now = clock.now();
            SurveyVersion version = new SurveyVersion(
                    ids.newId(), survey.tenantId(), surveyId, versionNo, retyped.document(), now);
            List<QuestionLineage> lineage = retyped.changes().stream()
                    .map(change -> new QuestionLineage(
                            change.questionId(), survey.tenantId(), surveyId, change.replaces(), versionNo))
                    .toList();
            versions.insert(version, lineage, actor);

            SurveyDraft rewritten = draft.saved(retyped.document(), now);
            if (!drafts.save(rewritten, draft.etag(), actor)) {
                throw new PreconditionFailedException(null);
            }
            Survey published = survey.published(version.id(), now);
            if (!surveys.update(published, survey.version())) {
                throw new PreconditionFailedException(null);
            }
            return version;
        });
    }
}
