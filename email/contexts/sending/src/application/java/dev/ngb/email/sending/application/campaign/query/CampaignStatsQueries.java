package dev.ngb.email.sending.application.campaign.query;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads the campaign's statistics in one snapshot, so the counts cannot disagree with each other. */
public class CampaignStatsQueries {

    private final UnitOfWork unitOfWork;
    private final CampaignStatsReadRepository stats;

    public CampaignStatsQueries(UnitOfWork unitOfWork, CampaignStatsReadRepository stats) {
        this.unitOfWork = unitOfWork;
        this.stats = stats;
    }

    public CampaignStatsView stats(UUID campaignId) {
        return unitOfWork.transactional(() -> stats.stats(campaignId));
    }
}
