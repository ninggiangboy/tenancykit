package dev.ngb.attribution.links.application.campaign.command;

import dev.ngb.attribution.links.application.campaign.port.CampaignRepository;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/**
 * Changes a campaign's currency. {@code measured} marks that spend or a conversion exists: the currency is then fixed
 * and the domain refuses the change, because re-labelling recorded money is worse than an explicit mistake.
 */
public class ChangeCampaignCurrencyUseCase {

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final Clock clock;

    public ChangeCampaignCurrencyUseCase(UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    public Campaign execute(UUID campaignId, String currency, boolean measured) {
        return unitOfWork.transactional(() -> {
            Campaign current = campaigns.findById(campaignId)
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            Campaign updated = current.changeCurrency(currency, measured, clock.now());
            if (!campaigns.update(updated, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
