package dev.ngb.mailroom.sending.application.campaign.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRepository;
import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import java.util.UUID;

/** Pause, resume and cancel. The domain refuses an illegal source state with a 409. */
public class ChangeCampaignStateUseCase {

    public enum Action {
        PAUSE,
        RESUME,
        CANCEL
    }

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final Clock clock;

    public ChangeCampaignStateUseCase(UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    public Campaign execute(UUID campaignId, Action action) {
        return unitOfWork.transactional(() -> {
            Campaign current = campaigns.findById(campaignId)
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            Campaign next = switch (action) {
                case PAUSE -> current.pause(clock.now());
                case RESUME -> current.resume(clock.now());
                case CANCEL -> current.cancel(clock.now());
            };
            if (!campaigns.update(next, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return next;
        });
    }
}
