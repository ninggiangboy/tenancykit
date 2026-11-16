package dev.ngb.platform.access.application.role.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** Read side of roles, filtered, sorted and paged by the list-query contract. */
public interface RoleReadRepository {

    /**
     * One page of roles.
     *
     * @param tenantScope the only Tenant the page may contain, or null for a Platform administrator who reads them all
     */
    Page<RoleView> list(ListQuery query, UUID tenantScope);

    Optional<RoleView> findById(UUID id);
}
