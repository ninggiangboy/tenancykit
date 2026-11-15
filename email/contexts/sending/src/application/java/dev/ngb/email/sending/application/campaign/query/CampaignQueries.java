package dev.ngb.email.sending.application.campaign.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import java.util.List;
import java.util.UUID;

/** The campaign listing and detail endpoints, read inside a tenant-scoped transaction. */
public class CampaignQueries {

    public static final int DEFAULT_LIMIT = 50;

    private final UnitOfWork unitOfWork;
    private final CampaignReadRepository campaigns;

    public CampaignQueries(UnitOfWork unitOfWork, CampaignReadRepository campaigns) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
    }

    public List<CampaignView> list(String status, UUID after, Integer limit) {
        return unitOfWork.transactional(
                () -> campaigns.list(status, after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }

    public CampaignView get(UUID campaignId) {
        return unitOfWork.transactional(() -> campaigns.find(campaignId)
                .orElseThrow(() -> new NotFoundException("No campaign exists here.")));
    }
}
