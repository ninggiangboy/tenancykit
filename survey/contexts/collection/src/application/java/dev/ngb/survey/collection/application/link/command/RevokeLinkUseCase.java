package dev.ngb.survey.collection.application.link.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.collection.application.link.port.LinkRepository;
import dev.ngb.survey.collection.domain.link.LinkStatus;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import java.util.UUID;

/**
 * Revokes a link, which is the only way to stop one already handed out from opening the survey.
 *
 * <p>Revoking is idempotent: a link that is already revoked answers with its current state rather than failing, because
 * a repeated revoke is not a conflict a caller should have to handle.
 */
public class RevokeLinkUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final LinkRepository links;

    public RevokeLinkUseCase(UnitOfWork unitOfWork, TenantContext tenantContext, LinkRepository links) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.links = links;
    }

    public SurveyLink execute(UUID linkId) {
        return unitOfWork.transactional(() -> {
            UUID tenant = tenantContext.requireTenant().value();
            SurveyLink link = links.findById(linkId)
                    .orElseThrow(() -> new NotFoundException("No link exists here."));
            if (link.status() == LinkStatus.REVOKED) {
                return link;
            }
            links.revoke(linkId, tenant);
            return link.revoked();
        });
    }
}
