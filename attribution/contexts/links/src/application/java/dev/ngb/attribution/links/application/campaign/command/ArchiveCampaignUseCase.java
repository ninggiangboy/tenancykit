package dev.ngb.attribution.links.application.campaign.command;

import dev.ngb.attribution.links.application.campaign.port.CampaignRepository;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Archives a campaign. Archiving is terminal, so a lost race against another writer is a hard precondition failure. */
public class ArchiveCampaignUseCase {

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final Clock clock;

    public ArchiveCampaignUseCase(UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    public void execute(UUID campaignId) {
        unitOfWork.transactional(() -> {
            Campaign current = campaigns.findById(campaignId)
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            Campaign archived = current.archive(clock.now());
            if (!campaigns.archive(archived)) {
                throw new PreconditionFailedException(null);
            }
        });
    }
}
