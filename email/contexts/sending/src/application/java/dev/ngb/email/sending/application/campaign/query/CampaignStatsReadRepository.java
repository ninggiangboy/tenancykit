package dev.ngb.email.sending.application.campaign.query;

import java.util.UUID;

/** The campaign statistics read side. */
public interface CampaignStatsReadRepository {

    CampaignStatsView stats(UUID campaignId);
}
