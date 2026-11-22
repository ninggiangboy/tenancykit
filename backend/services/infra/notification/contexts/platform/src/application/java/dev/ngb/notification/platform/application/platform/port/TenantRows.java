package dev.ngb.notification.platform.application.platform.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.platform.api.replica.TenantView;
import java.util.List;
import java.util.Optional;

/** The replica rows the tenant directory reads. Its implementation is a jOOQ adapter; this layer never sees one. */
public interface TenantRows {

    List<TenantView> activeTenants();

    Optional<TenantView> tenant(TenantId tenantId);

    Optional<Boolean> entitlementEnabled(TenantId tenantId, String appCode);
}
