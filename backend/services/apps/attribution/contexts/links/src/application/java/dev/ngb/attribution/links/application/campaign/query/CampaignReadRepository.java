package dev.ngb.attribution.links.application.campaign.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The campaign read side. */
public interface CampaignReadRepository {

    /** @return one page of the campaigns the filter admits, in the order the query names */
    Page<CampaignView> list(ListQuery query);

    Optional<CampaignView> find(UUID campaignId);
}
