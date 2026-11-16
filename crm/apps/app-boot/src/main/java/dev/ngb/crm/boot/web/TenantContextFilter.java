package dev.ngb.crm.boot.web;

import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.suite.web.SuiteTenantFilter;
import dev.ngb.core.tenancy.TenantContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Builds the request scope once, from the hostname alone, using the replicated {@code tenant_domains}: a Tenant System
 * reaches crm on its Tenant's host, and the tenant is never read from a header, a path or a body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantContextFilter extends SuiteTenantFilter {

    TenantContextFilter(SuiteHostResolver hosts, TenantContext tenantContext) {
        super(hosts, tenantContext);
    }
}
