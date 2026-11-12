package dev.ngb.platform.tenancy.application.tenant.port;

import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import dev.ngb.platform.tenancy.domain.tenant.TenantCode;
import dev.ngb.core.id.TenantId;
import java.util.Optional;

/** Write side of tenants. Reads through a separate read port, never through this one. */
public interface TenantRepository {

    void add(Tenant tenant);

    void update(Tenant tenant);

    Optional<Tenant> findById(TenantId id);

    Optional<Tenant> findByCode(TenantCode code);
}
