package dev.ngb.attribution.links.application.campaign.command;

import dev.ngb.attribution.links.application.campaign.port.CampaignRepository;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Edits a campaign. A change to the model or the window is stored now; a later recompute derives its report from the
 * campaign's current values, so the caller's {@code If-Match} is the only concurrency guard needed here.
 */
public class UpdateCampaignUseCase {

    public record Change(
            String name,
            String channel,
            AttributionModel model,
            int windowDays,
            LocalDate startsOn,
            LocalDate endsOn) {}

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final Clock clock;

    public UpdateCampaignUseCase(UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    public Campaign execute(UUID campaignId, Change change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Campaign current = campaigns.findById(campaignId)
                    .orElseThrow(() -> new NotFoundException("No campaign exists here."));
            expected.check(current.version());
            Campaign updated = current.update(
                    change.name(), change.channel(), change.model(), change.windowDays(), change.startsOn(),
                    change.endsOn(), clock.now());
            if (!campaigns.update(updated, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
