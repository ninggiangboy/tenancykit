package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.UUID;

/**
 * The read side of the caller's inbox, filtered, sorted and paged by the list-query contract.
 *
 * <p>The scope is the tenant of the request and the user of the token; both are handed in, so reading another user's
 * inbox is not expressible.
 */
public interface NotificationReadRepository {

    Page<NotificationView> list(ListQuery query, TenantId tenantId, UUID userId);
}
