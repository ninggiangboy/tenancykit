package dev.ngb.core.suite.web;

import dev.ngb.core.replica.ReplicaTables;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;

/**
 * Reads an app's entitlement from the replica; an enabled row is what lets a tenant use the app at all. The scope
 * comes from the {@link TenantContext} this class holds, so a caller reads it once and never passes it around.
 */
public class SuiteEntitlements {

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public SuiteEntitlements(UnitOfWork unitOfWork, DSLContext dsl, TenantContext tenantContext) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    /** @return whether the current scope may use the app; the Platform scope always may */
    public boolean enabledFor(String appCode) {
        RequestScope scope = tenantContext.current();
        if (scope instanceof RequestScope.Platform) {
            return true;
        }
        return unitOfWork.transactional(() -> dsl.fetchExists(
                dsl.selectOne()
                        .from(ReplicaTables.APP_ENTITLEMENTS)
                        .where(ReplicaTables.ENTITLEMENT_TENANT_ID.eq(scope.dataTenantId().value()))
                        .and(ReplicaTables.ENTITLEMENT_APP_CODE.eq(appCode))
                        .and(ReplicaTables.ENTITLEMENT_ENABLED.isTrue())));
    }
}
