package dev.ngb.mailroom.sending.application.campaign.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The campaign listing and detail endpoints, read inside a tenant-scoped transaction. */
public class CampaignQueries {

    private final UnitOfWork unitOfWork;
    private final CampaignReadRepository campaigns;

    public CampaignQueries(UnitOfWork unitOfWork, CampaignReadRepository campaigns) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
    }

    public Page<CampaignView> list(ListQuery query) {
        return unitOfWork.transactional(() -> campaigns.list(query));
    }

    public CampaignView get(UUID campaignId) {
        return unitOfWork.transactional(() -> campaigns.find(campaignId)
                .orElseThrow(() -> new NotFoundException("No campaign exists here.")));
    }
}
