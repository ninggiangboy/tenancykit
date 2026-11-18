package dev.ngb.platform.tenancy.application.entitlement.query;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import dev.ngb.platform.tenancy.application.tenant.query.TenantReadRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of entitlements (Platform scope). */
public class EntitlementQueries {

    private final UnitOfWork unitOfWork;
    private final EntitlementReadRepository entitlements;
    private final TenantReadRepository tenants;
    private final AvailableApps apps;

    public EntitlementQueries(
            UnitOfWork unitOfWork, EntitlementReadRepository entitlements, TenantReadRepository tenants, AvailableApps apps) {
        this.unitOfWork = unitOfWork;
        this.entitlements = entitlements;
        this.tenants = tenants;
        this.apps = apps;
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

    /**
     * Every app of the suite with the Tenant's state of it, so a screen renders one switch per app instead of only the
     * ones the tenant has ever changed. An app with no row is disabled at version 0, which is exactly the absent-row
     * case a conditional update expects.
     */
    public List<AppEntitlementView> appsForTenant(TenantId tenantId) {
        return unitOfWork.transactional(() -> {
            tenants.findById(tenantId.value()).orElseThrow(() -> new TenantNotFoundException(tenantId.toString()));
            Map<String, EntitlementView> stored = new HashMap<>();
            for (EntitlementView view : entitlements.listByTenant(tenantId.value())) {
                stored.put(view.appCode(), view);
            }
            return apps.appCodes().stream().sorted().map(appCode -> {
                EntitlementView view = stored.get(appCode);
                return view == null
                        ? new AppEntitlementView(appCode, false, 0)
                        : new AppEntitlementView(appCode, view.enabled(), view.version());
            }).toList();
        });
    }
}
