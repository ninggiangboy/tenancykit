package dev.ngb.email.sending.adapter.campaign.persistence;

import static dev.ngb.email_jooq.Tables.CAMPAIGNS;

import dev.ngb.email.sending.application.campaign.query.CampaignReadRepository;
import dev.ngb.email.sending.application.campaign.query.CampaignView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** The campaign listing and detail. */
public class JooqCampaignReadRepository implements CampaignReadRepository {

    private final DSLContext dsl;

    public JooqCampaignReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<CampaignView> list(String status, UUID afterId, int limit) {
        Condition where = DSL.noCondition();
        if (status != null && !status.isBlank()) {
            where = where.and(CAMPAIGNS.STATUS.eq(status));
        }
        if (afterId != null) {
            where = where.and(CAMPAIGNS.ID.lt(afterId));
        }
        return dsl.select(
                        CAMPAIGNS.ID, CAMPAIGNS.NAME, CAMPAIGNS.STATUS, CAMPAIGNS.TEMPLATE_ID,
                        CAMPAIGNS.TEMPLATE_REVISION, CAMPAIGNS.TARGET_TYPE, CAMPAIGNS.TARGET_ID,
                        CAMPAIGNS.FROM_KEY, CAMPAIGNS.FROM_NAME, CAMPAIGNS.REPLY_TO, CAMPAIGNS.SCHEDULED_AT,
                        CAMPAIGNS.STARTED_AT, CAMPAIGNS.COMPLETED_AT, CAMPAIGNS.VERSION,
                        CAMPAIGNS.CREATED_AT, CAMPAIGNS.UPDATED_AT)
                .from(CAMPAIGNS)
                .where(where)
                .orderBy(CAMPAIGNS.ID.desc())
                .limit(limit)
                .fetch(this::toView);
    }

    @Override
    public Optional<CampaignView> find(UUID campaignId) {
        return dsl.select(
                        CAMPAIGNS.ID, CAMPAIGNS.NAME, CAMPAIGNS.STATUS, CAMPAIGNS.TEMPLATE_ID,
                        CAMPAIGNS.TEMPLATE_REVISION, CAMPAIGNS.TARGET_TYPE, CAMPAIGNS.TARGET_ID,
                        CAMPAIGNS.FROM_KEY, CAMPAIGNS.FROM_NAME, CAMPAIGNS.REPLY_TO, CAMPAIGNS.SCHEDULED_AT,
                        CAMPAIGNS.STARTED_AT, CAMPAIGNS.COMPLETED_AT, CAMPAIGNS.VERSION,
                        CAMPAIGNS.CREATED_AT, CAMPAIGNS.UPDATED_AT)
                .from(CAMPAIGNS)
                .where(CAMPAIGNS.ID.eq(campaignId))
                .fetchOptional(this::toView);
    }

    private CampaignView toView(Record row) {
        return new CampaignView(
                row.get(CAMPAIGNS.ID),
                row.get(CAMPAIGNS.NAME),
                row.get(CAMPAIGNS.STATUS),
                row.get(CAMPAIGNS.TEMPLATE_ID),
                row.get(CAMPAIGNS.TEMPLATE_REVISION),
                row.get(CAMPAIGNS.TARGET_TYPE),
                row.get(CAMPAIGNS.TARGET_ID),
                row.get(CAMPAIGNS.FROM_KEY),
                row.get(CAMPAIGNS.FROM_NAME),
                row.get(CAMPAIGNS.REPLY_TO),
                row.get(CAMPAIGNS.SCHEDULED_AT),
                row.get(CAMPAIGNS.STARTED_AT),
                row.get(CAMPAIGNS.COMPLETED_AT),
                row.get(CAMPAIGNS.VERSION),
                row.get(CAMPAIGNS.CREATED_AT),
                row.get(CAMPAIGNS.UPDATED_AT));
    }
}
