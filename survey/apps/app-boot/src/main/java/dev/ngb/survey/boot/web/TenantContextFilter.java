package dev.ngb.survey.boot.web;

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
     * The public fill paths have no authenticated host scope: their tenant comes from the host, which may be one the
     * proxy did not bind to a tenant when a respondent is forwarded through a short link.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/p/") || uri.startsWith("/survey/p/");
    }
}
