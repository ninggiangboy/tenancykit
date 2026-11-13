package dev.ngb.billing.boot.web;

import dev.ngb.core.suite.web.SuiteEntitlementFilter;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.core.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rejects a tenant that has not enabled Billing. It runs immediately after the hostname filter bound the scope, so the
 * entitlement is read for exactly the tenant the host resolved to. Health probes bypass it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AppEntitlementFilter extends SuiteEntitlementFilter {

    AppEntitlementFilter(
            SuiteEntitlements entitlements,
            @Value("${suite.app-code:billing}") String appCode,
            TenantContext tenantContext) {
        super(entitlements, appCode, tenantContext);
    }
}
