package dev.ngb.mailroom.sending.adapter.campaign.persistence;

import static dev.ngb.mailroom_jooq.Tables.CAMPAIGNS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignListing;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignReadRepository;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignView;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** The campaign listing and detail. */
public class JooqCampaignReadRepository implements CampaignReadRepository {

    /** The campaign fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(CampaignListing.SCHEMA)
            .column("id", CAMPAIGNS.ID)
            .column("name", CAMPAIGNS.NAME)
            .column("status", CAMPAIGNS.STATUS)
            .column("template_id", CAMPAIGNS.TEMPLATE_ID)
            .column("template_revision", CAMPAIGNS.TEMPLATE_REVISION)
            .column("target_type", CAMPAIGNS.TARGET_TYPE)
            .column("target_id", CAMPAIGNS.TARGET_ID)
            .column("from_key", CAMPAIGNS.FROM_KEY)
            .column("from_name", CAMPAIGNS.FROM_NAME)
            .column("reply_to", CAMPAIGNS.REPLY_TO)
            .column("scheduled_at", CAMPAIGNS.SCHEDULED_AT)
            .column("started_at", CAMPAIGNS.STARTED_AT)
            .column("completed_at", CAMPAIGNS.COMPLETED_AT)
            .column("version", CAMPAIGNS.VERSION)
            .column("created_at", CAMPAIGNS.CREATED_AT)
            .column("updated_at", CAMPAIGNS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the campaign listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqCampaignReadRepository(DSLContext dsl) {
        this.dsl = dsl;
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

    private static CampaignView toView(dev.ngb.mailroom_jooq.tables.records.CampaignsRecord row) {
        return new CampaignView(
                row.getId(),
                row.getName(),
                row.getStatus(),
                row.getTemplateId(),
                row.getTemplateRevision(),
                row.getTargetType(),
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
