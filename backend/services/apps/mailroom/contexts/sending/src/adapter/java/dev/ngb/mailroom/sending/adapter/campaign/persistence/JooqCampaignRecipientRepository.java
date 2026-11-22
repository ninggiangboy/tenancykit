package dev.ngb.mailroom.sending.adapter.campaign.persistence;

import static dev.ngb.mailroom_jooq.Tables.CAMPAIGN_RECIPIENTS;
import static dev.ngb.mailroom_jooq.Tables.CAMPAIGNS;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRecipientRepository;
import dev.ngb.mailroom.sending.domain.campaign.RecipientState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The snapshot and its lease-based claim. */
public class JooqCampaignRecipientRepository implements CampaignRecipientRepository {

    private final DSLContext dsl;

    public JooqCampaignRecipientRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void snapshot(UUID campaignId, TenantId tenantId, List<UUID> receiverIds) {
        for (UUID receiverId : receiverIds) {
            dsl.insertInto(CAMPAIGN_RECIPIENTS)
                    .set(CAMPAIGN_RECIPIENTS.TENANT_ID, tenantId.value())
                    .set(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID, campaignId)
                    .set(CAMPAIGN_RECIPIENTS.RECEIVER_ID, receiverId)
                    .set(CAMPAIGN_RECIPIENTS.STATE, RecipientState.PENDING.name())
                    .onConflict(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID, CAMPAIGN_RECIPIENTS.RECEIVER_ID)
                    .doNothing()
                    .execute();
        }
    }

    @Override
    public List<UUID> claimPending(UUID campaignId, Instant now, Instant leaseUntil, int limit) {
        // The select locks the rows for this transaction; a second worker's select skips them. The update then leases
        // exactly the rows this worker owns.
        List<UUID> claimed = dsl.select(CAMPAIGN_RECIPIENTS.RECEIVER_ID)
                .from(CAMPAIGN_RECIPIENTS)
                .where(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId))
                .and(CAMPAIGN_RECIPIENTS.STATE.eq(RecipientState.PENDING.name()))
                .and(CAMPAIGN_RECIPIENTS.CLAIMED_UNTIL.isNull()
                        .or(CAMPAIGN_RECIPIENTS.CLAIMED_UNTIL.lt(now)))
                .orderBy(CAMPAIGN_RECIPIENTS.RECEIVER_ID)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(CAMPAIGN_RECIPIENTS.RECEIVER_ID);
        if (claimed.isEmpty()) {
            return List.of();
        }
        dsl.update(CAMPAIGN_RECIPIENTS)
                .set(CAMPAIGN_RECIPIENTS.CLAIMED_UNTIL, leaseUntil)
                .where(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId))
                .and(CAMPAIGN_RECIPIENTS.RECEIVER_ID.in(claimed))
                .execute();
        return claimed;
    }

    @Override
    public void markPublished(UUID campaignId, UUID receiverId, UUID emailId) {
        dsl.update(CAMPAIGN_RECIPIENTS)
                .set(CAMPAIGN_RECIPIENTS.STATE, RecipientState.PUBLISHED.name())
                .set(CAMPAIGN_RECIPIENTS.EMAIL_ID, emailId)
                .set(CAMPAIGN_RECIPIENTS.CLAIMED_UNTIL, (Instant) null)
                .where(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId))
                .and(CAMPAIGN_RECIPIENTS.RECEIVER_ID.eq(receiverId))
                .execute();
    }

    @Override
    public void markSkipped(UUID campaignId, UUID receiverId) {
        dsl.update(CAMPAIGN_RECIPIENTS)
                .set(CAMPAIGN_RECIPIENTS.STATE, RecipientState.SKIPPED.name())
                .set(CAMPAIGN_RECIPIENTS.CLAIMED_UNTIL, (Instant) null)
                .where(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId))
                .and(CAMPAIGN_RECIPIENTS.RECEIVER_ID.eq(receiverId))
                .execute();
    }

    @Override
    public boolean hasPending(UUID campaignId) {
        return dsl.fetchExists(DSL.selectOne()
                .from(CAMPAIGN_RECIPIENTS)
                .where(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId))
                .and(CAMPAIGN_RECIPIENTS.STATE.eq(RecipientState.PENDING.name())));
    }

    @Override
    public long count(UUID campaignId, RecipientState state) {
        return dsl.fetchCount(
                CAMPAIGN_RECIPIENTS,
                CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId)
                        .and(CAMPAIGN_RECIPIENTS.STATE.eq(state.name())));
    }

    @Override
    public long countAll(UUID campaignId) {
        return dsl.fetchCount(CAMPAIGN_RECIPIENTS, CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId));
    }

    @Override
    public int deleteCompletedBefore(Instant completedBefore, int limit) {
        var completed = DSL.select(CAMPAIGNS.ID)
                .from(CAMPAIGNS)
                .where(CAMPAIGNS.STATUS.eq("COMPLETED"))
                .and(CAMPAIGNS.COMPLETED_AT.lt(completedBefore))
                .limit(limit);
        return dsl.deleteFrom(CAMPAIGN_RECIPIENTS)
                .where(CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.in(completed))
                .execute();
    }
}
