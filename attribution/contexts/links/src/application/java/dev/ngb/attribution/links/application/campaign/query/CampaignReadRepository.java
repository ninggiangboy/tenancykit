package dev.ngb.attribution.links.application.campaign.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The campaign read side. */
public interface CampaignReadRepository {

    List<CampaignView> list(String status, UUID afterId, int limit);

    Optional<CampaignView> find(UUID campaignId);
}
