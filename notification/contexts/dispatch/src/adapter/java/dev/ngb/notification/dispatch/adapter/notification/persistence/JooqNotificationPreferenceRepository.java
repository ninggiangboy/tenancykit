package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATION_PREFERENCES;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.port.NotificationPreferenceRepository;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/** Reads and writes one user's preference; the tenant comes from the caller and RLS is the second line. */
public class JooqNotificationPreferenceRepository implements NotificationPreferenceRepository {

    private final DSLContext dsl;

    public JooqNotificationPreferenceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public PreferenceView find(TenantId tenantId, UUID userId) {
        return dsl.select(NOTIFICATION_PREFERENCES.LOCALE, NOTIFICATION_PREFERENCES.MUTED_TYPES)
                .from(NOTIFICATION_PREFERENCES)
                .where(NOTIFICATION_PREFERENCES.TENANT_ID.eq(tenantId.value()))
                .and(NOTIFICATION_PREFERENCES.USER_ID.eq(userId))
                .fetchOptional(row -> new PreferenceView(
                        row.value1(), PreferenceView.parseTypes(row.value2())))
                .orElseGet(PreferenceView::none);
    }

    @Override
    public void save(TenantId tenantId, UUID userId, PreferenceView preference) {
        dsl.insertInto(NOTIFICATION_PREFERENCES)
                .set(NOTIFICATION_PREFERENCES.TENANT_ID, tenantId.value())
                .set(NOTIFICATION_PREFERENCES.USER_ID, userId)
                .set(NOTIFICATION_PREFERENCES.LOCALE, preference.locale())
                .set(NOTIFICATION_PREFERENCES.MUTED_TYPES, preference.mutedTypeNames())
                .set(NOTIFICATION_PREFERENCES.UPDATED_AT, Instant.now())
                .onConflict(NOTIFICATION_PREFERENCES.TENANT_ID, NOTIFICATION_PREFERENCES.USER_ID)
                .doUpdate()
                .set(NOTIFICATION_PREFERENCES.LOCALE, preference.locale())
                .set(NOTIFICATION_PREFERENCES.MUTED_TYPES, preference.mutedTypeNames())
                .set(NOTIFICATION_PREFERENCES.UPDATED_AT, Instant.now())
                .execute();
    }
}
