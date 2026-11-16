package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.TenantContext;
import java.util.UUID;

/**
 * The caller's own inbox. The tenant comes from the request scope and the user from the authenticated actor, never
 * from a parameter, so reading another user's notification is not expressible.
 *
 * <p>The read runs in a unit of work because row-level security reads the tenant of the open transaction: a query
 * outside one sees no row at all. Filtering, sorting and paging follow the list-query contract;
 * {@code filter[read_at][is_null]=true} with {@code page_info=full} answers the unread count the old response carried.
 */
public class ListNotificationsQuery {

    private final UnitOfWork unitOfWork;
    private final NotificationReadRepository repository;
    private final TenantContext tenantContext;
    private final CurrentActor currentActor;

    public ListNotificationsQuery(
            UnitOfWork unitOfWork,
            NotificationReadRepository repository,
            TenantContext tenantContext,
            CurrentActor currentActor) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.currentActor = currentActor;
    }

    public Page<NotificationView> list(ListQuery query) {
        TenantId tenantId = tenantContext.requireTenant();
        UUID userId = currentActor.get().id();
        return unitOfWork.transactional(() -> repository.list(query, tenantId, userId));
    }
}
