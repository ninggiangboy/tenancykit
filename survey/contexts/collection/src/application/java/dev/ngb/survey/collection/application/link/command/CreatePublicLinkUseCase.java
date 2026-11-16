package dev.ngb.survey.collection.application.link.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.collection.application.link.exception.LinkRefusedException;
import dev.ngb.survey.collection.application.link.port.LinkRepository;
import dev.ngb.survey.collection.application.link.service.SurveyState;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues the one public link a Tenant names for a source.
 *
 * <p>The source is the report's identity, so a second link for the same source is refused rather than merged: two
 * links would split one campaign's visits into two rows a Tenant cannot tell apart.
 */
public class CreatePublicLinkUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final LinkRepository links;
    private final SurveyState surveyState;
    private final IdGenerator ids;
    private final Clock clock;

    public CreatePublicLinkUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            SurveyState surveyState,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.links = links;
        this.surveyState = surveyState;
        this.ids = ids;
        this.clock = clock;
    }

    public SurveyLink execute(UUID surveyId, String source) {
        return unitOfWork.transactional(() -> {
            TenantId tenant = tenantContext.requireTenant();
            surveyState.requireOpen(surveyId);
            if (links.publicSourceTaken(surveyId, source)) {
                throw new LinkRefusedException(
                        LinkRefusedException.Reason.SOURCE_TAKEN,
                        "a public link already names this source");
            }
            Instant now = clock.now();
            SurveyLink link = SurveyLink.publicLink(ids.newId(), tenant, surveyId, source, now);
            links.insert(link);
            return link;
        });
    }
}
