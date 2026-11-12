package dev.ngb.platform.boot.catalog;

import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.integration.application.credential.port.TenantSystemScopes;

/**
 * Only a catalogued code with {@code grantable_to_tenant_system: true} may reach a Tenant System credential. The
 * permission catalog belongs to the access context, so this bridge lives in boot, next to the wiring.
 */
public class CatalogTenantSystemScopes implements TenantSystemScopes {

    private final PermissionCatalog catalog;

    public CatalogTenantSystemScopes(PermissionCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public boolean grantable(String code) {
        return catalog.find(code).map(permission -> permission.grantableToTenantSystem()).orElse(false);
    }
}
