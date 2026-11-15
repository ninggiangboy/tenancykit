package dev.ngb.mailroom.boot.web;

import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.suite.web.SuiteTenantFilter;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * The public tracking paths have no authenticated host scope: their tenant comes from the signed token, and a
     * mail client may reach the pod by a hostname the proxy did not bind to a tenant.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/t/") || uri.startsWith("/mailroom/t/");
    }
}
