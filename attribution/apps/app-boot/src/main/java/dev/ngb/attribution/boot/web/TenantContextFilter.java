package dev.ngb.attribution.boot.web;

import dev.ngb.core.suite.web.SuiteHostResolver;
import dev.ngb.core.suite.web.SuiteTenantFilter;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Builds the request scope once, from the hostname alone, using the replicated {@code tenant_domains}.
 *
 * <p>The redirect and the collection endpoints still take their tenant from the host — only the one OAuth callback
 * reads it from a signed state, because a provider needs one exact redirect URI for every tenant.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantContextFilter extends SuiteTenantFilter {

    TenantContextFilter(SuiteHostResolver hosts, TenantContext tenantContext) {
        super(hosts, tenantContext);
    }

    /**
     * The OAuth callback is the one path whose tenant does not come from the host: it is served on the Platform host
     * and resolves its tenant from the signed {@code state}. The redirect and collection paths are not skipped — their
     * tenant is the host, and an unknown host must not resolve.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/attribution/t/oauth/");
    }
}
