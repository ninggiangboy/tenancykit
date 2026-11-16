package dev.ngb.platform.access.api.user;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** The read side of users and their effective access. */
public class UserQueries {

    private final UnitOfWork unitOfWork;
    private final UserReadRepository users;

    public UserQueries(UnitOfWork unitOfWork, UserReadRepository users) {
        this.unitOfWork = unitOfWork;
        this.users = users;
    }

    /**
     * One page of users, filtered, sorted and paged by the list-query contract.
     *
     * @param tenantScope the only Tenant the page may contain, or null for a Platform administrator who reads them all
     */
    public Page<UserView> list(ListQuery query, UUID tenantScope) {
        return unitOfWork.transactional(() -> users.list(query, tenantScope));
    }

    public List<UserAccessView> pageAccess(UUID after, int limit) {
        return unitOfWork.transactional(() -> users.pageAccess(after, limit));
    }
}
