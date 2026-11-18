package dev.ngb.platform.tenancy.application.tenant.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of tenant administration, filtered, sorted and paged by the list-query contract. A cursor page is ordered by id, which UUID v7 makes time-ordered. */
public interface TenantReadRepository {

    Page<TenantView> list(ListQuery query);

    /** Every Tenant including the reserved platform one; the broker relay runs for all of them. */
    List<TenantView> all();

    List<TenantView> page(UUID after, int limit);

    Optional<TenantView> findById(UUID id);

    List<TenantDomainView> pageDomains(UUID after, int limit);

    Optional<TenantDomainView> findDomainByHostname(String hostname);

    List<TenantDomainView> listDomains(UUID tenantId);

    /** Every hostname of an active Tenant, in order: the console hosts Keycloak must accept while signing in. */
    List<String> activeConsoleHosts();
}
