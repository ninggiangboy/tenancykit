package dev.ngb.attribution.links.adapter.campaign.persistence;

import static dev.ngb.attribution_jooq.Tables.CAMPAIGNS;

import dev.ngb.attribution.links.application.campaign.query.CampaignReadRepository;
import dev.ngb.attribution.links.application.campaign.query.CampaignView;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.CampaignStatus;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** The campaign listing and detail. A status filter and a keyset cursor, so a long list pages without an offset. */
public class JooqCampaignReadRepository implements CampaignReadRepository {

    private final DSLContext dsl;

    public JooqCampaignReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<CampaignView> list(String status, UUID afterId, int limit) {
        Condition where = DSL.noCondition();
        if (status != null && !status.isBlank()) {
            where = where.and(CAMPAIGNS.STATUS.eq(status.strip().toUpperCase(Locale.ROOT)));
        }
        if (afterId != null) {
            where = where.and(CAMPAIGNS.ID.lt(afterId));
        }
        return dsl.select(
                        CAMPAIGNS.ID, CAMPAIGNS.TENANT_ID, CAMPAIGNS.NAME, CAMPAIGNS.SLUG, CAMPAIGNS.CHANNEL,
                        CAMPAIGNS.STATUS, CAMPAIGNS.ATTRIBUTION_MODEL, CAMPAIGNS.WINDOW_DAYS, CAMPAIGNS.CURRENCY,
                        CAMPAIGNS.STARTS_ON, CAMPAIGNS.ENDS_ON, CAMPAIGNS.VERSION, CAMPAIGNS.CREATED_AT,
                        CAMPAIGNS.UPDATED_AT)
                .from(CAMPAIGNS)
                .where(where)
                .orderBy(CAMPAIGNS.ID.desc())
                .limit(limit)
                .fetch(this::toView);
    }

    @Override
    public Optional<CampaignView> find(UUID campaignId) {
        return dsl.select(
                        CAMPAIGNS.ID, CAMPAIGNS.TENANT_ID, CAMPAIGNS.NAME, CAMPAIGNS.SLUG, CAMPAIGNS.CHANNEL,
                        CAMPAIGNS.STATUS, CAMPAIGNS.ATTRIBUTION_MODEL, CAMPAIGNS.WINDOW_DAYS, CAMPAIGNS.CURRENCY,
                        CAMPAIGNS.STARTS_ON, CAMPAIGNS.ENDS_ON, CAMPAIGNS.VERSION, CAMPAIGNS.CREATED_AT,
                        CAMPAIGNS.UPDATED_AT)
                .from(CAMPAIGNS)
                .where(CAMPAIGNS.ID.eq(campaignId))
                .fetchOptional(this::toView);
    }

    private CampaignView toView(Record row) {
        return new CampaignView(
                row.get(CAMPAIGNS.ID),
                new dev.ngb.core.id.TenantId(row.get(CAMPAIGNS.TENANT_ID)),
                row.get(CAMPAIGNS.NAME),
                row.get(CAMPAIGNS.SLUG),
                row.get(CAMPAIGNS.CHANNEL),
                CampaignStatus.valueOf(row.get(CAMPAIGNS.STATUS)),
                AttributionModel.valueOf(row.get(CAMPAIGNS.ATTRIBUTION_MODEL)),
                row.get(CAMPAIGNS.WINDOW_DAYS),
                row.get(CAMPAIGNS.CURRENCY),
                row.get(CAMPAIGNS.STARTS_ON),
                row.get(CAMPAIGNS.ENDS_ON),
                row.get(CAMPAIGNS.VERSION),
                row.get(CAMPAIGNS.CREATED_AT),
                row.get(CAMPAIGNS.UPDATED_AT));
    }
}
