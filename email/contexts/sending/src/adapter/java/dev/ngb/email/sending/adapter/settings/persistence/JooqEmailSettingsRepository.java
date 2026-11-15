package dev.ngb.email.sending.adapter.settings.persistence;

import static dev.ngb.email_jooq.Tables.EMAIL_SETTINGS;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.sending.application.email.port.EmailSettingsView;
import dev.ngb.email.sending.application.settings.port.EmailSettingsRepository;
import org.jooq.DSLContext;

/**
 * The settings row, overlaid on the configured defaults. A missing row and a null column both mean "the default", so
 * a new default reaches every tenant that never overrode it.
 */
public class JooqEmailSettingsRepository implements EmailSettingsRepository {

    private final DSLContext dsl;
    private final EmailSettingsView defaults;
    private final TenantContext tenantContext;
    private final Clock clock;

    public JooqEmailSettingsRepository(
            DSLContext dsl, EmailSettingsView defaults, TenantContext tenantContext, Clock clock) {
        this.dsl = dsl;
        this.defaults = defaults;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    public EmailSettingsView current() {
        return dsl.selectFrom(EMAIL_SETTINGS)
                .fetchOptional()
                .map(row -> new EmailSettingsView(
                        text(row.getFromKey(), defaults.fromKey()),
                        row.getFromName(),
                        row.getReplyTo(),
                        row.getBodyRetentionDays() == null
                                ? defaults.bodyRetentionDays()
                                : row.getBodyRetentionDays(),
                        row.getCampaignPublishPerMinute() == null
                                ? defaults.campaignPublishPerMinute()
                                : row.getCampaignPublishPerMinute(),
                        row.getVersion()))
                .orElse(defaults);
    }

    @Override
    public EmailSettingsView save(EmailSettingsView settings) {
        dsl.insertInto(EMAIL_SETTINGS)
                .set(EMAIL_SETTINGS.TENANT_ID, tenantContext.requireTenant().value())
                .set(EMAIL_SETTINGS.FROM_KEY, settings.fromKey())
                .set(EMAIL_SETTINGS.FROM_NAME, settings.fromName())
                .set(EMAIL_SETTINGS.REPLY_TO, settings.replyTo())
                .set(EMAIL_SETTINGS.BODY_RETENTION_DAYS, settings.bodyRetentionDays())
                .set(EMAIL_SETTINGS.CAMPAIGN_PUBLISH_PER_MINUTE, settings.campaignPublishPerMinute())
                .set(EMAIL_SETTINGS.VERSION, 1L)
                .set(EMAIL_SETTINGS.UPDATED_AT, clock.now())
                .onConflict(EMAIL_SETTINGS.TENANT_ID)
                .doUpdate()
                .set(EMAIL_SETTINGS.FROM_KEY, settings.fromKey())
                .set(EMAIL_SETTINGS.FROM_NAME, settings.fromName())
                .set(EMAIL_SETTINGS.REPLY_TO, settings.replyTo())
                .set(EMAIL_SETTINGS.BODY_RETENTION_DAYS, settings.bodyRetentionDays())
                .set(EMAIL_SETTINGS.CAMPAIGN_PUBLISH_PER_MINUTE, settings.campaignPublishPerMinute())
                .set(EMAIL_SETTINGS.VERSION, EMAIL_SETTINGS.VERSION.plus(1))
                .set(EMAIL_SETTINGS.UPDATED_AT, clock.now())
                .execute();
        return current();
    }

    private static String text(String stored, String fallback) {
        return stored == null || stored.isBlank() ? fallback : stored;
    }
}
