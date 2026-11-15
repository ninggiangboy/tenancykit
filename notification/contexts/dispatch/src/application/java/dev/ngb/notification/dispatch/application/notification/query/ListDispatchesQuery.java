package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;

/** The tenant's dispatch ledger, for a caller holding {@code notification:dispatch:read}. */
public class ListDispatchesQuery {

    public record Page(List<DispatchView> items, UUID nextBefore) {}

    private final DispatchReadRepository repository;
    private final TenantContext tenantContext;

    public ListDispatchesQuery(DispatchReadRepository repository, TenantContext tenantContext) {
        this.repository = repository;
        this.tenantContext = tenantContext;
    }

    public Page list(String status, UUID before, int limit) {
        TenantId tenantId = tenantContext.requireTenant();
        int capped = limit <= 0 ? ListNotificationsQuery.DEFAULT_LIMIT : Math.min(limit, ListNotificationsQuery.MAX_LIMIT);
        List<DispatchView> items = repository.list(tenantId, status, before, capped);
        UUID next = items.size() == capped ? items.getLast().id() : null;
        return new Page(items, next);
    }
}
