package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATION_TEMPLATES;

import dev.ngb.notification.dispatch.application.notification.port.TemplateSource;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.shared.TemplateKey;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * The suite-wide override row, keyed by template and locale.
 *
 * <p>The table carries no row-level security: a template is the suite's content, so every scope reads the same row. A
 * missing row is not an error; the renderer falls back to the classpath default.
 */
public class JooqTemplateSource implements TemplateSource {

    private final DSLContext dsl;

    public JooqTemplateSource(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<TemplateVariant> find(TemplateKey key, String locale) {
        return dsl.select(
                        NOTIFICATION_TEMPLATES.SUBJECT,
                        NOTIFICATION_TEMPLATES.HTML,
                        NOTIFICATION_TEMPLATES.TEXT,
                        NOTIFICATION_TEMPLATES.TITLE,
                        NOTIFICATION_TEMPLATES.BODY,
                        NOTIFICATION_TEMPLATES.VERSION)
                .from(NOTIFICATION_TEMPLATES)
                .where(NOTIFICATION_TEMPLATES.TEMPLATE_KEY.eq(key.value()))
                .and(NOTIFICATION_TEMPLATES.LOCALE.eq(locale))
                .fetchOptional(row -> new TemplateVariant(
                        row.value1(), row.value2(), row.value3(), row.value4(), row.value5(), row.value6()));
    }
}
