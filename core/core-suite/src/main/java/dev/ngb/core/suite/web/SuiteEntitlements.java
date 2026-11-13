package dev.ngb.core.suite.web;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.replica.ReplicaTables;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Optional;
import org.jooq.DSLContext;

/** Reads an app's entitlement from the replica; an enabled row is what lets a tenant use the app at all. */
public class SuiteEntitlements {

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;

    public SuiteEntitlements(UnitOfWork unitOfWork, DSLContext dsl) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
    }

    /** @return false when the tenant has no enabled row for the app */
    public boolean enabledFor(TenantContext tenantContext, String appCode) {
        RequestScope scope = tenantContext.current();
        return unitOfWork.transactional(() -> dsl.fetchExists(
                dsl.selectOne()
                        .from(ReplicaTables.APP_ENTITLEMENTS)
                        .where(ReplicaTables.ENTITLEMENT_TENANT_ID.eq(scope.dataTenantId().value()))
                        .and(ReplicaTables.ENTITLEMENT_APP_CODE.eq(appCode))
                        .and(ReplicaTables.ENTITLEMENT_ENABLED.isTrue())));
    }

    /** Requires an enabled entitlement in the current scope; Platform scope is always allowed. */
    public void requireEnabled(TenantContext tenantContext, String appCode) {
        if (tenantContext.current() instanceof RequestScope.Tenant && !enabledFor(tenantContext, appCode)) {
            throw new NotFoundException("this tenant has not enabled " + appCode);
        }
    }
}
