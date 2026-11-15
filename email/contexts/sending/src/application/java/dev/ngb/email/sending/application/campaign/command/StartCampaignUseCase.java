package dev.ngb.email.sending.application.campaign.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.sending.application.campaign.port.CampaignRepository;
import dev.ngb.email.sending.application.campaign.service.CampaignSnapshotter;
import dev.ngb.email.sending.domain.campaign.Campaign;
import java.util.UUID;

/** Starts a campaign now: it enters SENDING and its audience is snapshotted once. */
public class StartCampaignUseCase {

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final CampaignSnapshotter snapshotter;
    private final Clock clock;

    public StartCampaignUseCase(
            UnitOfWork unitOfWork,
            CampaignRepository campaigns,
            CampaignSnapshotter snapshotter,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.snapshotter = snapshotter;
        this.clock = clock;
    }

    public Campaign execute(UUID campaignId, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Campaign current = campaigns.findById(campaignId)
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            expected.check(current.version());
            Campaign started = current.start(clock.now());
            if (!campaigns.update(started, current.version())) {
                throw new PreconditionFailedException(null);
            }
            snapshotter.snapshot(started);
            return started;
        });
    }
}
