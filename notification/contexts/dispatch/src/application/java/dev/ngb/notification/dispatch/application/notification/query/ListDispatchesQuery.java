package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;

/**
 * The tenant's dispatch ledger, for a caller holding {@code notification:dispatch:read}.
 *
 * <p>The read runs in a unit of work because row-level security reads the tenant of the open transaction: a query
 * outside one sees no row at all. Filtering, sorting and paging follow the list-query contract.
 */
public class ListDispatchesQuery {

    private final UnitOfWork unitOfWork;
    private final DispatchReadRepository repository;
    private final TenantContext tenantContext;

    public ListDispatchesQuery(
            UnitOfWork unitOfWork, DispatchReadRepository repository, TenantContext tenantContext) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.tenantContext = tenantContext;
    }

    public Page<DispatchView> list(ListQuery query) {
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> repository.list(query, tenantId));
    }
}
