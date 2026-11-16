package dev.ngb.platform.access.application.role.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

public class RoleQueries {

    private final UnitOfWork unitOfWork;
    private final RoleReadRepository roles;

    public RoleQueries(UnitOfWork unitOfWork, RoleReadRepository roles) {
        this.unitOfWork = unitOfWork;
        this.roles = roles;
    }

    /**
     * One page of roles, filtered, sorted and paged by the list-query contract.
     *
     * @param tenantScope the only Tenant the page may contain, or null for a Platform administrator who reads them all
     */
    public Page<RoleView> list(ListQuery query, UUID tenantScope) {
        return unitOfWork.transactional(() -> roles.list(query, tenantScope));
    }
}
