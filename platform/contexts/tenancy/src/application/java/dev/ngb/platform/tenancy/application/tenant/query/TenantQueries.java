package dev.ngb.platform.tenancy.application.tenant.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Read side of tenant administration (Platform scope). */
public class TenantQueries {

    private final UnitOfWork unitOfWork;
    private final TenantReadRepository tenants;

    public TenantQueries(UnitOfWork unitOfWork, TenantReadRepository tenants) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
    }

    /** One page of the tenants, filtered, sorted and paged by the list-query contract. */
    public Page<TenantView> list(ListQuery query) {
        return unitOfWork.transactional(() -> tenants.list(query));
    }

    public List<TenantView> all() {
        return unitOfWork.transactional(tenants::all);
    }

    /** One snapshot page; a full page means the caller should ask again with the last id. */
    public List<TenantView> page(UUID after, int limit) {
        return unitOfWork.transactional(() -> tenants.page(after, limit));
    }

    public List<TenantDomainView> pageDomains(UUID after, int limit) {
        return unitOfWork.transactional(() -> tenants.pageDomains(after, limit));
    }

    public java.util.Optional<TenantView> byId(UUID id) {
        return unitOfWork.transactional(() -> tenants.findById(id));
    }
}
