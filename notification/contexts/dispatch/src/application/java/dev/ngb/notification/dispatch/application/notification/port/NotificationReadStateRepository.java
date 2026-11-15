package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/** Changes the read state of the caller's own rows; every statement also filters by user. */
public interface NotificationReadStateRepository {

    boolean exists(TenantId tenantId, UUID userId, UUID notificationId);

    int markRead(TenantId tenantId, UUID userId, UUID notificationId, Instant at);

    int markAllRead(TenantId tenantId, UUID userId, Instant at);
}
