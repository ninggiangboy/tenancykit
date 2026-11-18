package dev.ngb.attribution.links.adapter.campaign.persistence;

import static dev.ngb.attribution_jooq.Tables.CAMPAIGNS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.attribution.links.application.campaign.query.CampaignListing;
import dev.ngb.attribution.links.application.campaign.query.CampaignReadRepository;
import dev.ngb.attribution.links.application.campaign.query.CampaignView;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.CampaignStatus;
import dev.ngb.attribution_jooq.tables.records.CampaignsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The campaign listing and detail. The listing compiles the caller's {@link ListQuery} against the columns
 * {@link CampaignListing} whitelists, so a filter, an order and a page position are the contract's, not this class's.
 *
 * <p>Tenant isolation is the database's: every statement runs inside the unit of work's transaction, where row-level
 * security has bound the tenant, so a listing never widens past the caller's own campaigns.
 */
public class JooqCampaignReadRepository implements CampaignReadRepository {

    /** The campaign fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(CampaignListing.SCHEMA)
            .column("id", CAMPAIGNS.ID)
            .column("name", CAMPAIGNS.NAME)
            .column("slug", CAMPAIGNS.SLUG)
            .column("channel", CAMPAIGNS.CHANNEL)
            .column("status", CAMPAIGNS.STATUS)
            .column("attribution_model", CAMPAIGNS.ATTRIBUTION_MODEL)
            .column("currency", CAMPAIGNS.CURRENCY)
            .column("created_at", CAMPAIGNS.CREATED_AT)
            .column("updated_at", CAMPAIGNS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    public JooqCampaignReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** @return the binding of the campaign listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    @Override
    public Page<CampaignView> list(ListQuery query) {
        return JooqLists.fetch(dsl, CAMPAIGNS, noCondition(), BINDING, query, JooqCampaignReadRepository::toView);
    }

    @Override
    public Optional<CampaignView> find(UUID campaignId) {
        return dsl.selectFrom(CAMPAIGNS)
                .where(CAMPAIGNS.ID.eq(campaignId))
                .fetchOptional(JooqCampaignReadRepository::toView);
    }

    private static CampaignView toView(CampaignsRecord row) {
        return new CampaignView(
                row.getId(),
                new dev.ngb.core.id.TenantId(row.getTenantId()),
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
