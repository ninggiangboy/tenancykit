package dev.ngb.attribution.links.adapter.campaign.persistence;

import static dev.ngb.attribution_jooq.Tables.CAMPAIGNS;

import dev.ngb.attribution.links.application.campaign.port.CampaignRepository;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.attribution.links.domain.campaign.CampaignStatus;
import dev.ngb.core.id.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The campaign store: an append, a guarded update and the slug lookup a save needs. */
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
                .set(CAMPAIGNS.SLUG, campaign.slug())
                .set(CAMPAIGNS.CHANNEL, campaign.channel())
                .set(CAMPAIGNS.STATUS, campaign.status().name())
                .set(CAMPAIGNS.ATTRIBUTION_MODEL, campaign.model().name())
                .set(CAMPAIGNS.WINDOW_DAYS, campaign.windowDays())
                .set(CAMPAIGNS.CURRENCY, campaign.currency())
                .set(CAMPAIGNS.STARTS_ON, campaign.startsOn())
                .set(CAMPAIGNS.ENDS_ON, campaign.endsOn())
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
    public Optional<Campaign> findBySlug(String slug) {
        return dsl.selectFrom(CAMPAIGNS).where(CAMPAIGNS.SLUG.eq(slug)).fetchOptional(this::toCampaign);
    }

    @Override
    public boolean update(Campaign campaign, long expectedVersion) {
        return dsl.update(CAMPAIGNS)
                        .set(CAMPAIGNS.NAME, campaign.name())
                        .set(CAMPAIGNS.CHANNEL, campaign.channel())
                        .set(CAMPAIGNS.STATUS, campaign.status().name())
                        .set(CAMPAIGNS.ATTRIBUTION_MODEL, campaign.model().name())
                        .set(CAMPAIGNS.WINDOW_DAYS, campaign.windowDays())
                        .set(CAMPAIGNS.CURRENCY, campaign.currency())
                        .set(CAMPAIGNS.STARTS_ON, campaign.startsOn())
                        .set(CAMPAIGNS.ENDS_ON, campaign.endsOn())
                        .set(CAMPAIGNS.VERSION, campaign.version())
                        .set(CAMPAIGNS.UPDATED_AT, campaign.updatedAt())
                        .where(CAMPAIGNS.ID.eq(campaign.id()))
                        .and(CAMPAIGNS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public boolean archive(Campaign campaign) {
        return dsl.update(CAMPAIGNS)
                        .set(CAMPAIGNS.STATUS, CampaignStatus.ARCHIVED.name())
                        .set(CAMPAIGNS.VERSION, campaign.version())
                        .set(CAMPAIGNS.UPDATED_AT, campaign.updatedAt())
                        .where(CAMPAIGNS.ID.eq(campaign.id()))
                        .and(CAMPAIGNS.VERSION.eq(campaign.version() - 1))
                        .execute()
                == 1;
    }

    @Override
    public boolean slugTaken(String slug) {
        return dsl.fetchExists(DSL.selectOne().from(CAMPAIGNS).where(CAMPAIGNS.SLUG.eq(slug)));
    }

    private Campaign toCampaign(dev.ngb.attribution_jooq.tables.records.CampaignsRecord row) {
        return new Campaign(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getName(),
                row.getSlug(),
                row.getChannel(),
                CampaignStatus.valueOf(row.getStatus()),
                AttributionModel.valueOf(row.getAttributionModel()),
                row.getWindowDays(),
                row.getCurrency(),
                row.getStartsOn(),
                row.getEndsOn(),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
