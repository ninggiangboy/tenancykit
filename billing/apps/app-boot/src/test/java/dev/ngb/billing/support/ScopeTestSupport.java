package dev.ngb.billing.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import org.springframework.stereotype.Component;

/**
 * Test helpers for running work in a scope. A tenant is seeded straight into the replica
 * ({@link ReplicaFixtures}); Billing no longer calls a tenant-creation use case.
 */
@Component
public class ScopeTestSupport {

    private final TenantContext tenantContext;
    private final ReplicaFixtures replica;

    ScopeTestSupport(TenantContext tenantContext, ReplicaFixtures replica) {
        this.tenantContext = tenantContext;
        this.replica = replica;
    }

    public <T> T inTenant(TenantId tenantId, java.util.function.Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenantId), work);
    }

    public void runInTenant(TenantId tenantId, Runnable work) {
        tenantContext.runAs(RequestScope.tenant(tenantId), work);
    }

    public void runOnPlatform(Runnable work) {
        tenantContext.runAs(RequestScope.platform(), work);
    }

    public <T> T onPlatform(java.util.function.Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    /** A fresh active, entitled tenant in the replica. */
    public ReplicaFixtures.TenantSeed newTenant() {
        return replica.tenant();
    }
}
