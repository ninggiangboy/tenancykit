package dev.ngb.billing.boot.web;

import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.suite.web.SuiteTenantFilter;
import dev.ngb.core.tenancy.TenantContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Builds the request scope once, from the hostname alone, using the replicated {@code tenant_domains}. Both a tenant
 * host and the platform host resolve; the platform host carries platform-scope users, whose permissions the actor
 * resolver reads from the replica as for any other user. An unknown host, or one of a deactivated tenant, gets one
 * generic 404.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantContextFilter extends SuiteTenantFilter {

    TenantContextFilter(SuiteHostResolver hosts, TenantContext tenantContext) {
        super(hosts, tenantContext);
    }
}
