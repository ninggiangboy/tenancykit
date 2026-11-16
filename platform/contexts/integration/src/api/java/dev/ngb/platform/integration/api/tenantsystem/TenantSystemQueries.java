package dev.ngb.platform.integration.api.tenantsystem;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads tenant systems for the admin GET handlers. */
public class TenantSystemQueries {

    private final UnitOfWork unitOfWork;
    private final TenantSystemReadRepository systems;

    public TenantSystemQueries(UnitOfWork unitOfWork, TenantSystemReadRepository systems) {
        this.unitOfWork = unitOfWork;
        this.systems = systems;
    }

    /** One page of the Tenant Systems the caller may see, filtered, sorted and paged by the list-query contract. */
    public Page<TenantSystemView> list(ListQuery query) {
        return unitOfWork.transactional(() -> systems.list(query));
    }

    public TenantSystemView get(UUID id) {
        return unitOfWork.transactional(() -> systems.findById(id).orElseThrow(() -> new NotFoundException("tenant system not found")));
    }

    public List<TenantSystemView> page(UUID after, int limit) {
        return unitOfWork.transactional(() -> systems.page(after, limit));
    }
}
