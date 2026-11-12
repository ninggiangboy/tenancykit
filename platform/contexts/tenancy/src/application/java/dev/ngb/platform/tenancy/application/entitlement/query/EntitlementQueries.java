package dev.ngb.platform.tenancy.application.entitlement.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import java.util.List;
import java.util.UUID;

/** Read side of entitlements (Platform scope). */
public class EntitlementQueries {

    private final UnitOfWork unitOfWork;
    private final EntitlementReadRepository entitlements;

    public EntitlementQueries(UnitOfWork unitOfWork, EntitlementReadRepository entitlements) {
        this.unitOfWork = unitOfWork;
        this.entitlements = entitlements;
    }

    public List<EntitlementView> page(UUID after, int limit) {
        return unitOfWork.transactional(() -> entitlements.page(after, limit));
    }

    public List<EntitlementView> forTenant(UUID tenantId) {
        List<EntitlementView> found = unitOfWork.transactional(() -> entitlements.listByTenant(tenantId));
        if (found.isEmpty()) {
            throw new TenantNotFoundException(tenantId.toString());
        }
        return found;
    }
}
