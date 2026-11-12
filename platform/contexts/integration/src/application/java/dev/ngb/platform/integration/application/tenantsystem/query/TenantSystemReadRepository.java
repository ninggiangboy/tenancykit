package dev.ngb.platform.integration.application.tenantsystem.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of tenant systems, including the replica stream every app consumes. */
public interface TenantSystemReadRepository {

    List<TenantSystemView> list();

    Optional<TenantSystemView> findById(UUID id);

    /** One snapshot page; a full page means the caller should ask again with the last id. */
    List<TenantSystemView> page(UUID after, int limit);
}
