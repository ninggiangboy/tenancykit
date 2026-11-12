package dev.ngb.platform.tenancy.application.entitlement.query;

import java.util.List;
import java.util.UUID;

/** Read side of entitlements; the cursor is the tenant id, and a tenant has at most one page's worth of apps. */
public interface EntitlementReadRepository {

    List<EntitlementView> page(UUID afterTenantId, int limit);

    List<EntitlementView> listByTenant(UUID tenantId);
}
