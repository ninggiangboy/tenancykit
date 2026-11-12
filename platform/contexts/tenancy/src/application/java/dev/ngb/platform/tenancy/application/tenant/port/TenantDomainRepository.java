package dev.ngb.platform.tenancy.application.tenant.port;

import dev.ngb.platform.tenancy.domain.tenant.Hostname;
import dev.ngb.platform.tenancy.domain.tenant.TenantDomain;
import java.util.Optional;

/** Write side of the hostname mappings. */
public interface TenantDomainRepository {

    void add(TenantDomain domain);

    Optional<TenantDomain> findByHostname(Hostname hostname);
}
