package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import java.util.List;
import java.util.UUID;

/** The read side of the caller's inbox. Cursor paging is the UUID v7 id alone: it is time-ordered, so it is the sort key. */
public interface NotificationReadRepository {

    List<NotificationView> list(
            TenantId tenantId, UUID userId, boolean unreadOnly, UUID before, int limit);

    long unreadCount(TenantId tenantId, UUID userId);
}
