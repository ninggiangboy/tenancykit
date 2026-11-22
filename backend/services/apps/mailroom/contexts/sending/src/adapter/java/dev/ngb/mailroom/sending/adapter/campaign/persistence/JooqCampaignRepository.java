package dev.ngb.mailroom.sending.adapter.campaign.persistence;

import static dev.ngb.mailroom_jooq.Tables.CAMPAIGNS;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.sending.application.campaign.port.CampaignRepository;
import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import dev.ngb.mailroom.sending.domain.campaign.CampaignStatus;
import dev.ngb.mailroom.sending.domain.campaign.CampaignTargetType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The campaign store, including the scheduler's atomic claim. */
public class JooqCampaignRepository implements CampaignRepository {

    private final DSLContext dsl;

    public JooqCampaignRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Campaign campaign) {
        dsl.insertInto(CAMPAIGNS)
                .set(CAMPAIGNS.ID, campaign.id())
                .set(CAMPAIGNS.TENANT_ID, campaign.tenantId().value())
                .set(CAMPAIGNS.NAME, campaign.name())
                .set(CAMPAIGNS.STATUS, campaign.status().name())
                .set(CAMPAIGNS.TEMPLATE_ID, campaign.templateId())
                .set(CAMPAIGNS.TEMPLATE_REVISION, campaign.templateRevision())
                .set(CAMPAIGNS.TARGET_TYPE, campaign.targetType().name())
                .set(CAMPAIGNS.TARGET_ID, campaign.targetId())
                .set(CAMPAIGNS.FROM_KEY, campaign.fromKey())
                .set(CAMPAIGNS.FROM_NAME, campaign.fromName())
                .set(CAMPAIGNS.REPLY_TO, campaign.replyTo())
                .set(CAMPAIGNS.SCHEDULED_AT, campaign.scheduledAt())
                .set(CAMPAIGNS.STARTED_AT, campaign.startedAt())
                .set(CAMPAIGNS.COMPLETED_AT, campaign.completedAt())
                .set(CAMPAIGNS.VERSION, campaign.version())
                .set(CAMPAIGNS.CREATED_AT, campaign.createdAt())
                .set(CAMPAIGNS.UPDATED_AT, campaign.updatedAt())
                .execute();
    }

    @Override
    public Optional<Campaign> findById(UUID campaignId) {
        return dsl.selectFrom(CAMPAIGNS).where(CAMPAIGNS.ID.eq(campaignId)).fetchOptional(this::toCampaign);
    }

    @Override
    public boolean update(Campaign campaign, long expectedVersion) {
        return dsl.update(CAMPAIGNS)
                        .set(CAMPAIGNS.NAME, campaign.name())
                        .set(CAMPAIGNS.STATUS, campaign.status().name())
                        .set(CAMPAIGNS.TEMPLATE_ID, campaign.templateId())
                        .set(CAMPAIGNS.TEMPLATE_REVISION, campaign.templateRevision())
                        .set(CAMPAIGNS.TARGET_TYPE, campaign.targetType().name())
                        .set(CAMPAIGNS.TARGET_ID, campaign.targetId())
                        .set(CAMPAIGNS.FROM_KEY, campaign.fromKey())
                        .set(CAMPAIGNS.FROM_NAME, campaign.fromName())
                        .set(CAMPAIGNS.REPLY_TO, campaign.replyTo())
                        .set(CAMPAIGNS.SCHEDULED_AT, campaign.scheduledAt())
                        .set(CAMPAIGNS.STARTED_AT, campaign.startedAt())
                        .set(CAMPAIGNS.COMPLETED_AT, campaign.completedAt())
                        .set(CAMPAIGNS.VERSION, campaign.version())
                        .set(CAMPAIGNS.UPDATED_AT, campaign.updatedAt())
                        .where(CAMPAIGNS.ID.eq(campaign.id()))
                        .and(CAMPAIGNS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public boolean tryStartScheduled(UUID campaignId, Instant now) {
        return dsl.update(CAMPAIGNS)
                        .set(CAMPAIGNS.STATUS, CampaignStatus.SENDING.name())
                        .set(CAMPAIGNS.STARTED_AT, now)
                        .set(CAMPAIGNS.VERSION, CAMPAIGNS.VERSION.plus(1))
                        .set(CAMPAIGNS.UPDATED_AT, now)
                        .where(CAMPAIGNS.ID.eq(campaignId))
                        .and(CAMPAIGNS.STATUS.eq(CampaignStatus.SCHEDULED.name()))
                        .execute()
                == 1;
    }

    @Override
    public List<UUID> dueScheduled(Instant now, int limit) {
        return dsl.select(CAMPAIGNS.ID)
                .from(CAMPAIGNS)
                .where(CAMPAIGNS.STATUS.eq(CampaignStatus.SCHEDULED.name()))
                .and(CAMPAIGNS.SCHEDULED_AT.le(now))
                .orderBy(CAMPAIGNS.SCHEDULED_AT.asc())
                .limit(limit)
                .fetch(CAMPAIGNS.ID);
    }

    @Override
    public List<UUID> sending() {
        return dsl.select(CAMPAIGNS.ID)
                .from(CAMPAIGNS)
                .where(CAMPAIGNS.STATUS.eq(CampaignStatus.SENDING.name()))
                .fetch(CAMPAIGNS.ID);
    }

    @Override
    public void markCompleted(UUID campaignId, Instant now) {
        dsl.update(CAMPAIGNS)
                .set(CAMPAIGNS.STATUS, CampaignStatus.COMPLETED.name())
                .set(CAMPAIGNS.COMPLETED_AT, now)
                .set(CAMPAIGNS.VERSION, CAMPAIGNS.VERSION.plus(1))
                .set(CAMPAIGNS.UPDATED_AT, now)
                .where(CAMPAIGNS.ID.eq(campaignId))
                .and(CAMPAIGNS.STATUS.eq(CampaignStatus.SENDING.name()))
                .execute();
    }

    private Campaign toCampaign(dev.ngb.mailroom_jooq.tables.records.CampaignsRecord row) {
        return new Campaign(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getName(),
                CampaignStatus.valueOf(row.getStatus()),
                row.getTemplateId(),
                row.getTemplateRevision(),
                CampaignTargetType.valueOf(row.getTargetType()),
                row.getTargetId(),
                row.getFromKey(),
                row.getFromName(),
                row.getReplyTo(),
                row.getScheduledAt(),
                row.getStartedAt(),
                row.getCompletedAt(),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
