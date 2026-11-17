package dev.ngb.workflow.platform.application.platform.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.platform.api.replica.TenantDomains;
import dev.ngb.workflow.platform.application.platform.port.TenantRows;
import java.util.Optional;

/** Serves the Tenant's hostname from the replica, so an outbound call addresses the Tenant it belongs to. */
public class TenantDomainsService implements TenantDomains {

    private final TenantRows rows;

    public TenantDomainsService(TenantRows rows) {
        this.rows = rows;
    }

    @Override
    public Optional<String> hostname(TenantId tenantId) {
        return rows.hostname(tenantId);
    }
}
