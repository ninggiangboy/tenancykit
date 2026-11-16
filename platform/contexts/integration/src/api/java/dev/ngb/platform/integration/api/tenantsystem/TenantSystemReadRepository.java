package dev.ngb.platform.integration.api.tenantsystem;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of tenant systems, including the replica stream every app consumes. */
public interface TenantSystemReadRepository {

    /** One page of the Tenant Systems the caller may see, filtered, sorted and paged by the list-query contract. */
    Page<TenantSystemView> list(ListQuery query);

    Optional<TenantSystemView> findById(UUID id);

    /** One snapshot page; a full page means the caller should ask again with the last id. */
    List<TenantSystemView> page(UUID after, int limit);
}
