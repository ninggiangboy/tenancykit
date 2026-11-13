package dev.ngb.core.suite.web;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects a tenant that has not enabled this app. It runs after {@link SuiteTenantFilter} bound the scope, so the
 * entitlement is read for exactly the tenant the hostname resolved to. Platform scope is always allowed.
 */
public class SuiteEntitlementFilter extends OncePerRequestFilter {

    private static final String PROBLEM_TYPE = "application/problem+json";

    private final SuiteEntitlements entitlements;
    private final String appCode;
    private final TenantContext tenantContext;

    public SuiteEntitlementFilter(SuiteEntitlements entitlements, String appCode, TenantContext tenantContext) {
        this.entitlements = entitlements;
        this.appCode = appCode;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }
        if (tenantContext.current() instanceof RequestScope.Tenant
                && !entitlements.enabledFor(tenantContext, appCode)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(PROBLEM_TYPE);
            String body = "{\"type\":\"app-not-enabled\",\"title\":\"This tenant has not enabled "
                    + appCode + "\",\"status\":403}";
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
