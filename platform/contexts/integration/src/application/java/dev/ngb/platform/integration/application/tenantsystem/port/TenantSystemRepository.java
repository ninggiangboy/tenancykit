package dev.ngb.platform.integration.application.tenantsystem.port;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import java.util.List;
import java.util.Optional;

public interface TenantSystemRepository {

    /** @throws ConflictException when the name is already used in the tenant */
    void add(TenantSystem system);

    void update(TenantSystem system);

    Optional<TenantSystem> findById(TenantSystemId id);

    /** One snapshot page, ordered by id, for the {@code tenant-systems} replica stream. */
    List<TenantSystem> page(java.util.UUID after, int limit);
}
