package dev.ngb.platform.access.api.user;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read side of users, the effective access they carry and the replica stream every app consumes. */
public interface UserReadRepository {

    /**
     * One page of users, filtered, sorted and paged by the list-query contract.
     *
     * @param tenantScope the only Tenant the page may contain, or null for a Platform administrator who reads them all
     */
    Page<UserView> list(ListQuery query, UUID tenantScope);

    Optional<UserView> findById(UUID id);

    Optional<UserView> findByKeycloakSubject(String subject);

    Set<String> effectivePermissions(UUID userId);

    List<UserAccessView> pageAccess(UUID after, int limit);
}
