package dev.ngb.mailroom.sending.application.campaign.port;

import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The write side of campaigns, including the two transitions the worker owns. */
public interface CampaignRepository {

    void insert(Campaign campaign);

    Optional<Campaign> findById(UUID campaignId);

    boolean update(Campaign campaign, long expectedVersion);

    /** The atomic scheduler claim: one worker moves a due campaign to SENDING. */
    boolean tryStartScheduled(UUID campaignId, Instant now);

    List<UUID> dueScheduled(Instant now, int limit);

    List<UUID> sending();

    void markCompleted(UUID campaignId, Instant now);
}
