package dev.ngb.platform.tenancy.application.tenant.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of tenant administration. A cursor page is ordered by id, which UUID v7 makes time-ordered. */
public interface TenantReadRepository {

    List<TenantView> list();

    /** Every Tenant including the reserved platform one; the broker relay runs for all of them. */
    List<TenantView> all();

    List<TenantView> page(UUID after, int limit);

    Optional<TenantView> findById(UUID id);

    List<TenantDomainView> pageDomains(UUID after, int limit);

    Optional<TenantDomainView> findDomainByHostname(String hostname);

    List<TenantDomainView> listDomains(UUID tenantId);
}
