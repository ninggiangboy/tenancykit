package dev.ngb.survey.authoring.application.survey.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.authoring.application.draft.port.DraftRepository;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.draft.DraftRules;
import dev.ngb.survey.authoring.domain.draft.SurveyDraft;
import dev.ngb.survey.authoring.domain.survey.Survey;
import java.time.Instant;

/** Creates a survey and the one empty draft a builder starts from, in one transaction. */
public class CreateSurveyUseCase {

    /** The name the tenant chose; anything else is filled in on the draft. */
    public record Command(String name) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SurveyRepository surveys;
    private final DraftRepository drafts;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateSurveyUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SurveyRepository surveys,
            DraftRepository drafts,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.surveys = surveys;
        this.drafts = drafts;
        this.ids = ids;
        this.clock = clock;
    }

    public Survey execute(Command command) {
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            Survey survey = Survey.create(ids.newId(), tenantId, command.name(), now);
            surveys.insert(survey);
            drafts.insert(SurveyDraft.initial(survey.id(), tenantId, DraftRules.empty(), now));
            return survey;
        });
    }
}
