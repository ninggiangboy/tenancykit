package dev.ngb.attribution.links.adapter.campaign.persistence;

import static dev.ngb.attribution_jooq.Tables.CAMPAIGNS;

import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** A campaign's attribution policy, read through the api the attribution context sees. */
public class JooqCampaignPolicyReader implements CampaignPolicyReader {

    private final DSLContext dsl;

    public JooqCampaignPolicyReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Policy> policy(UUID campaignId) {
        if (campaignId == null) {
            return Optional.empty();
        }
        return dsl.select(CAMPAIGNS.ID, CAMPAIGNS.ATTRIBUTION_MODEL, CAMPAIGNS.WINDOW_DAYS, CAMPAIGNS.CURRENCY)
                .from(CAMPAIGNS)
                .where(CAMPAIGNS.ID.eq(campaignId))
                .fetchOptional()
                .map(row -> new Policy(row.value1(), row.value2(), row.value3(), row.value4()));
    }
}
