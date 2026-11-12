package dev.ngb.platform.boot.catalog;

import dev.ngb.core.id.TenantId;
import dev.ngb.platform.integration.application.token.port.TenantCodes;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import dev.ngb.platform.tenancy.application.tenant.query.TenantView;
import java.util.Optional;

/**
 * Reads a Tenant's code through the tenancy read model, for the {@code tenant_code} claim of a Tenant System token.
 * Tenancy is another context, so the bridge lives in boot rather than in the integration context.
 */
public class TenantReadCodes implements TenantCodes {

    private final TenantQueries tenants;

    public TenantReadCodes(TenantQueries tenants) {
        this.tenants = tenants;
    }

    @Override
    public Optional<String> codeOf(TenantId tenantId) {
        return tenants.byId(tenantId.value()).map(TenantView::code);
    }
}
