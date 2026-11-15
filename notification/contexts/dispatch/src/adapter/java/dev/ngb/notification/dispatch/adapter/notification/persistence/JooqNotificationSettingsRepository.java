package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATION_SETTINGS;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.port.NotificationSettingsRepository;
import dev.ngb.notification.dispatch.application.notification.service.NotificationSettingsView;
import java.time.Instant;
import org.jooq.DSLContext;

/** Reads and writes the tenant's settings; an absent row means "everything enabled, locale en". */
public class JooqNotificationSettingsRepository implements NotificationSettingsRepository {

    private final DSLContext dsl;

    public JooqNotificationSettingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public NotificationSettingsView find(TenantId tenantId) {
        return dsl.select(NOTIFICATION_SETTINGS.DEFAULT_LOCALE, NOTIFICATION_SETTINGS.ENABLED_TYPES)
                .from(NOTIFICATION_SETTINGS)
                .where(NOTIFICATION_SETTINGS.TENANT_ID.eq(tenantId.value()))
                .fetchOptional(row -> new NotificationSettingsView(
                        row.value1(), NotificationSettingsView.parseTypes(row.value2())))
                .orElseGet(NotificationSettingsView::defaults);
    }

    @Override
    public void save(TenantId tenantId, NotificationSettingsView settings) {
        dsl.insertInto(NOTIFICATION_SETTINGS)
                .set(NOTIFICATION_SETTINGS.TENANT_ID, tenantId.value())
                .set(NOTIFICATION_SETTINGS.DEFAULT_LOCALE, settings.defaultLocale())
                .set(NOTIFICATION_SETTINGS.ENABLED_TYPES, settings.enabledTypeNames())
                .set(NOTIFICATION_SETTINGS.UPDATED_AT, Instant.now())
                .onConflict(NOTIFICATION_SETTINGS.TENANT_ID)
                .doUpdate()
                .set(NOTIFICATION_SETTINGS.DEFAULT_LOCALE, settings.defaultLocale())
                .set(NOTIFICATION_SETTINGS.ENABLED_TYPES, settings.enabledTypeNames())
                .set(NOTIFICATION_SETTINGS.UPDATED_AT, Instant.now())
                .execute();
    }
}
