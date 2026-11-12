package dev.ngb.platform.tenancy.application.entitlement.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import java.util.Optional;

/** Write side of app entitlements. */
public interface AppEntitlementRepository {

    Optional<AppEntitlement> find(TenantId tenantId, String appCode);

    void upsert(AppEntitlement entitlement);
}
