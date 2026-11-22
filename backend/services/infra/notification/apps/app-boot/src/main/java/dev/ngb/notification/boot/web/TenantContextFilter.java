package dev.ngb.notification.boot.web;

import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.suite.web.SuiteTenantFilter;
import dev.ngb.core.tenancy.TenantContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Builds the request scope once, from the hostname alone, using the replicated {@code tenant_domains}. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantContextFilter extends SuiteTenantFilter {

    TenantContextFilter(SuiteHostResolver hosts, TenantContext tenantContext) {
        super(hosts, tenantContext);
    }
}
