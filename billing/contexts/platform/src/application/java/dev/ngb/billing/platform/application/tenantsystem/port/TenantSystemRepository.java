package dev.ngb.billing.platform.application.tenantsystem.port;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.billing.platform.domain.tenantsystem.TenantSystem;
import java.util.Optional;

public interface TenantSystemRepository {

    /** @throws ConflictException when the name is already used in the tenant */
    void add(TenantSystem system);

    void update(TenantSystem system);

    Optional<TenantSystem> findById(TenantSystemId id);
}
