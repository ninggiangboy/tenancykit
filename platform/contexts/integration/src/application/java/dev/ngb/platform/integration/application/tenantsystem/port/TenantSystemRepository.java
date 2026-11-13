package dev.ngb.platform.integration.application.tenantsystem.port;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import java.util.Optional;

/** Write side of tenant systems. A read, including the snapshot page, goes through the read port. */
public interface TenantSystemRepository {

    /** @throws ConflictException when the name is already used in the tenant */
    void add(TenantSystem system);

    void update(TenantSystem system);

    Optional<TenantSystem> findById(TenantSystemId id);

    /** Reads the tenant system under a row lock, so a version bump that follows cannot race another one. */
    Optional<TenantSystem> lockById(TenantSystemId id);
}
