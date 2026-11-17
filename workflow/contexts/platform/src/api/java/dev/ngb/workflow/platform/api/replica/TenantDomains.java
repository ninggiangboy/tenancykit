package dev.ngb.workflow.platform.api.replica;

import dev.ngb.core.id.TenantId;
import java.util.Optional;

/**
 * The hostname an app call must address a Tenant by. An app builds its Tenant from the host alone, so calling one
 * without a domain would either fail or, worse, address another Tenant.
 */
public interface TenantDomains {

    /** The Tenant's primary hostname, or empty when the replica knows of no active domain. */
    Optional<String> hostname(TenantId tenantId);
}
