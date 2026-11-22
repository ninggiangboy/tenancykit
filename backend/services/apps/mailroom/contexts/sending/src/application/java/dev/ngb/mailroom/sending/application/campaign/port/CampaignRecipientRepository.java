package dev.ngb.mailroom.sending.application.campaign.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.sending.domain.campaign.RecipientState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The audience snapshot and its lease-based claim. */
public interface CampaignRecipientRepository {

    void snapshot(UUID campaignId, TenantId tenantId, List<UUID> receiverIds);

    /**
     * Claims up to {@code limit} pending rows for this worker, skipping rows another worker locked, and leases them.
     *
     * @return the receiver ids this worker now owns
     */
    List<UUID> claimPending(UUID campaignId, Instant now, Instant leaseUntil, int limit);

    void markPublished(UUID campaignId, UUID receiverId, UUID emailId);

    void markSkipped(UUID campaignId, UUID receiverId);

    /** True while any row is still pending, whether unclaimed or leased by another worker. */
    boolean hasPending(UUID campaignId);

    long count(UUID campaignId, RecipientState state);

    long countAll(UUID campaignId);

    /** Deletes the snapshot rows of campaigns that completed before the cut-off, at most {@code limit} rows. */
    int deleteCompletedBefore(Instant completedBefore, int limit);
}
