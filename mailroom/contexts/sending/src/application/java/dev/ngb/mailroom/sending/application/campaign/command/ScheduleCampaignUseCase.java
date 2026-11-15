package dev.ngb.mailroom.sending.application.campaign.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRepository;
import dev.ngb.mailroom.sending.application.campaign.service.CampaignSnapshotter;
import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import java.time.Instant;
import java.util.UUID;

/** Schedules a DRAFT campaign for a future time. */
public class ScheduleCampaignUseCase {

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final Clock clock;

    public ScheduleCampaignUseCase(UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    public Campaign execute(UUID campaignId, Instant scheduledAt, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Campaign current = campaigns.findById(campaignId)
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            expected.check(current.version());
            Campaign scheduled = current.schedule(scheduledAt, clock.now());
            if (!campaigns.update(scheduled, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return scheduled;
        });
    }
}
