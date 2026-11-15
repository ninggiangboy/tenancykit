package dev.ngb.notification.dispatch.adapter.notification.persistence;

import static dev.ngb.notification_jooq.Tables.NOTIFICATIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.port.NotificationReadStateRepository;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/** Read-state changes; every statement also filters by user, so another user's row is simply not found. */
public class JooqNotificationReadStateRepository implements NotificationReadStateRepository {

    private final DSLContext dsl;

    public JooqNotificationReadStateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean exists(TenantId tenantId, UUID userId, UUID notificationId) {
        return dsl.fetchExists(
                NOTIFICATIONS,
                NOTIFICATIONS.TENANT_ID.eq(tenantId.value())
                        .and(NOTIFICATIONS.USER_ID.eq(userId))
                        .and(NOTIFICATIONS.ID.eq(notificationId)));
    }

    @Override
    public int markRead(TenantId tenantId, UUID userId, UUID notificationId, Instant at) {
        return dsl.update(NOTIFICATIONS)
                .set(NOTIFICATIONS.READ_AT, at)
                .where(NOTIFICATIONS.TENANT_ID.eq(tenantId.value()))
                .and(NOTIFICATIONS.USER_ID.eq(userId))
                .and(NOTIFICATIONS.ID.eq(notificationId))
                .and(NOTIFICATIONS.READ_AT.isNull())
                .execute();
    }

    @Override
    public int markAllRead(TenantId tenantId, UUID userId, Instant at) {
        return dsl.update(NOTIFICATIONS)
                .set(NOTIFICATIONS.READ_AT, at)
                .where(NOTIFICATIONS.TENANT_ID.eq(tenantId.value()))
                .and(NOTIFICATIONS.USER_ID.eq(userId))
                .and(NOTIFICATIONS.READ_AT.isNull())
                .execute();
    }
}
