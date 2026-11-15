package dev.ngb.mailroom.sending.adapter.campaign.persistence;

import static dev.ngb.mailroom_jooq.Tables.CAMPAIGN_RECIPIENTS;
import static dev.ngb.mailroom_jooq.Tables.EMAILS;
import static dev.ngb.mailroom_jooq.Tables.EMAIL_EVENTS;

import dev.ngb.mailroom.sending.application.campaign.query.CampaignStatsReadRepository;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignStatsView;
import dev.ngb.mailroom.sending.domain.campaign.RecipientState;
import dev.ngb.mailroom.sending.domain.email.EmailStatus;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Aggregates a campaign's counts live from the ledger and the event history. */
public class JooqCampaignStatsReadRepository implements CampaignStatsReadRepository {

    private final DSLContext dsl;

    public JooqCampaignStatsReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public CampaignStatsView stats(UUID campaignId) {
        return new CampaignStatsView(
                dsl.fetchCount(CAMPAIGN_RECIPIENTS, CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId)),
                dsl.fetchCount(
                        CAMPAIGN_RECIPIENTS,
                        CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId)
                                .and(CAMPAIGN_RECIPIENTS.STATE.eq(RecipientState.PUBLISHED.name()))),
                emails(campaignId, EmailStatus.SENT),
                emails(campaignId, EmailStatus.FAILED),
                dsl.fetchCount(
                        CAMPAIGN_RECIPIENTS,
                        CAMPAIGN_RECIPIENTS.CAMPAIGN_ID.eq(campaignId)
                                .and(CAMPAIGN_RECIPIENTS.STATE.eq(RecipientState.SKIPPED.name()))),
                distinctEmails(campaignId, "OPENED"),
                events(campaignId, "OPENED"),
                distinctEmails(campaignId, "CLICKED"),
                events(campaignId, "CLICKED"),
                distinctEmails(campaignId, "UNSUBSCRIBED"));
    }

    private long emails(UUID campaignId, EmailStatus status) {
        return dsl.fetchCount(
                EMAILS, EMAILS.CAMPAIGN_ID.eq(campaignId).and(EMAILS.STATUS.eq(status.name())));
    }

    private long events(UUID campaignId, String type) {
        return dsl.fetchCount(
                EMAIL_EVENTS,
                EMAIL_EVENTS.CAMPAIGN_ID.eq(campaignId).and(EMAIL_EVENTS.TYPE.eq(type)));
    }

    private long distinctEmails(UUID campaignId, String type) {
        return dsl.select(DSL.countDistinct(EMAIL_EVENTS.EMAIL_ID))
                .from(EMAIL_EVENTS)
                .where(EMAIL_EVENTS.CAMPAIGN_ID.eq(campaignId))
                .and(EMAIL_EVENTS.TYPE.eq(type))
                .fetchOne(0, Long.class);
    }
}
