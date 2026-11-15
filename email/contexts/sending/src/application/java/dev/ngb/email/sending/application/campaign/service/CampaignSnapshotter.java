package dev.ngb.email.sending.application.campaign.service;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.email.audience.api.audience.AudienceDirectory;
import dev.ngb.email.audience.api.audience.ReceiverSummary;
import dev.ngb.email.sending.application.campaign.port.CampaignRecipientRepository;
import dev.ngb.email.sending.domain.campaign.Campaign;
import dev.ngb.email.sending.domain.campaign.CampaignTargetType;
import java.util.List;
import java.util.UUID;

/**
 * Takes the audience snapshot once, in pages, when a campaign enters SENDING. A receiver added to the group later is
 * not mailed; one who unsubscribes after the snapshot is re-checked at publish time.
 */
public class CampaignSnapshotter {

    public static final int PAGE = 500;
    public static final int MAX_AUDIENCE = 100_000;

    private final AudienceDirectory audience;
    private final CampaignRecipientRepository recipients;

    public CampaignSnapshotter(AudienceDirectory audience, CampaignRecipientRepository recipients) {
        this.audience = audience;
        this.recipients = recipients;
    }

    public long snapshot(Campaign campaign) {
        UUID after = null;
        long total = 0;
        while (true) {
            List<ReceiverSummary> page = campaign.targetType() == CampaignTargetType.GROUP
                    ? audience.pageGroup(campaign.targetId(), after, PAGE)
                    : audience.pageSegment(campaign.targetId(), after, PAGE);
            if (page.isEmpty()) {
                return total;
            }
            recipients.snapshot(
                    campaign.id(), campaign.tenantId(), page.stream().map(ReceiverSummary::id).toList());
            total += page.size();
            if (total > MAX_AUDIENCE) {
                throw new BadRequestException(
                        "this audience is larger than the " + MAX_AUDIENCE + " recipients a campaign may carry");
            }
            if (page.size() < PAGE) {
                return total;
            }
            after = page.getLast().id();
        }
    }
}
