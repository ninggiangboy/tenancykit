package dev.ngb.mailroom.sending.application.campaign.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The campaign read side. */
public interface CampaignReadRepository {

    Page<CampaignView> list(ListQuery query);

    Optional<CampaignView> find(UUID campaignId);
}
