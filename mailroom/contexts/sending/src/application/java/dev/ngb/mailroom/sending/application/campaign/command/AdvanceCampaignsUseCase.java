package dev.ngb.mailroom.sending.application.campaign.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRepository;
import dev.ngb.mailroom.sending.application.campaign.service.CampaignSnapshotter;
import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The scheduler's claim: due SCHEDULED campaigns move to SENDING and snapshot their audience. The CAS inside
 * {@code tryStartScheduled} is what makes two workers safe — only the winner snapshots.
 *
 * <p>Each campaign is started in its own transaction. A campaign that cannot be started — its segment was deleted
 * after it was scheduled, its audience is too large, a snapshot insert conflicted — is left for the operator and does
 * not roll back or block the tenant's other scheduled campaigns. A single wrapping transaction would re-run the same
 * failure every tick and hold every other campaign in the tenant behind it.
 */
public class AdvanceCampaignsUseCase {

    private static final System.Logger LOG = System.getLogger(AdvanceCampaignsUseCase.class.getName());

    private final UnitOfWork unitOfWork;
    private final CampaignRepository campaigns;
    private final CampaignSnapshotter snapshotter;
    private final Clock clock;

    public AdvanceCampaignsUseCase(
            UnitOfWork unitOfWork,
            CampaignRepository campaigns,
            CampaignSnapshotter snapshotter,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.campaigns = campaigns;
        this.snapshotter = snapshotter;
        this.clock = clock;
    }

    public int startDue(int limit) {
        Instant now = clock.now();
        List<UUID> due = unitOfWork.transactional(() -> campaigns.dueScheduled(now, limit));
        int started = 0;
        for (UUID campaignId : due) {
            if (startOne(campaignId, now)) {
                started++;
            }
        }
        return started;
    }

    private boolean startOne(UUID campaignId, Instant now) {
        try {
            return unitOfWork.transactional(() -> {
                if (!campaigns.tryStartScheduled(campaignId, now)) {
                    // Another worker claimed it, or it is no longer due. Either way it is not this worker's to start.
                    return false;
                }
                Campaign campaign = campaigns.findById(campaignId).orElseThrow();
                snapshotter.snapshot(campaign);
                return true;
            });
        } catch (RuntimeException failure) {
            // The campaign stays SCHEDULED — the start rolled back — so an operator can fix the cause and reschedule.
            // The id and the reason are logged; never an address or a body.
            LOG.log(System.Logger.Level.WARNING,
                    "could not start the scheduled campaign " + campaignId + ": " + failure.getMessage());
            return false;
        }
    }
}
