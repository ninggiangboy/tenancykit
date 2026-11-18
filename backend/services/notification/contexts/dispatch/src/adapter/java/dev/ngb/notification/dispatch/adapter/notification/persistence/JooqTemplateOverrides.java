package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATION_TEMPLATES;

import dev.ngb.notification.dispatch.application.notification.port.TemplateOverrideRepository;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.shared.TemplateKey;
import java.time.Instant;
import org.jooq.DSLContext;

/** Writes and deletes the suite-wide override; the read path is {@link JooqTemplateSource}. */
public class JooqTemplateOverrides implements TemplateOverrideRepository {

    private final DSLContext dsl;

    public JooqTemplateOverrides(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Inserts or replaces the whole variant and bumps its own revision, so the ledger records which content ran. */
    @Override
    public void save(TemplateKey key, String locale, TemplateVariant variant) {
        dsl.insertInto(NOTIFICATION_TEMPLATES)
                .set(NOTIFICATION_TEMPLATES.TEMPLATE_KEY, key.value())
                .set(NOTIFICATION_TEMPLATES.LOCALE, locale)
                .set(NOTIFICATION_TEMPLATES.SUBJECT, variant.subject())
                .set(NOTIFICATION_TEMPLATES.HTML, variant.html())
                .set(NOTIFICATION_TEMPLATES.TEXT, variant.text())
                .set(NOTIFICATION_TEMPLATES.TITLE, variant.title())
                .set(NOTIFICATION_TEMPLATES.BODY, variant.body())
                .set(NOTIFICATION_TEMPLATES.VERSION, 1)
                .set(NOTIFICATION_TEMPLATES.UPDATED_AT, Instant.now())
                .onConflict(NOTIFICATION_TEMPLATES.TEMPLATE_KEY, NOTIFICATION_TEMPLATES.LOCALE)
                .doUpdate()
                .set(NOTIFICATION_TEMPLATES.SUBJECT, variant.subject())
                .set(NOTIFICATION_TEMPLATES.HTML, variant.html())
                .set(NOTIFICATION_TEMPLATES.TEXT, variant.text())
                .set(NOTIFICATION_TEMPLATES.TITLE, variant.title())
                .set(NOTIFICATION_TEMPLATES.BODY, variant.body())
                .set(NOTIFICATION_TEMPLATES.VERSION, NOTIFICATION_TEMPLATES.VERSION.plus(1))
                .set(NOTIFICATION_TEMPLATES.UPDATED_AT, Instant.now())
                .execute();
    }

    @Override
    public boolean delete(TemplateKey key, String locale) {
        return dsl.deleteFrom(NOTIFICATION_TEMPLATES)
                        .where(NOTIFICATION_TEMPLATES.TEMPLATE_KEY.eq(key.value()))
                        .and(NOTIFICATION_TEMPLATES.LOCALE.eq(locale))
                        .execute()
                > 0;
    }
}
