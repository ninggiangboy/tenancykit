package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.core.id.TenantId;
import java.util.Optional;

/** The effective limits of one Tenant: its own row when it has one, the deployment defaults otherwise. */
public interface QuotaRepository {

    Optional<Limits> forTenant(TenantId tenantId);

    record Limits(int concurrentInstances, int activeDefinitions) {}
}
