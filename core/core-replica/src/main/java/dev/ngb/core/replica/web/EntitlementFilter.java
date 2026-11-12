package dev.ngb.core.replica.web;

import dev.ngb.core.replica.ReplicaProperties;
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
 * Rejects a tenant that has not enabled this app. It runs after {@link ReplicaTenantFilter} bound the scope, so the
 * entitlement is read for exactly the tenant the hostname resolved to. Platform scope is always allowed.
 */
public class EntitlementFilter extends OncePerRequestFilter {

    private static final String PROBLEM_TYPE = "application/problem+json";

    private final ReplicaEntitlements entitlements;
    private final ReplicaProperties properties;
    private final TenantContext tenantContext;

    public EntitlementFilter(ReplicaEntitlements entitlements, ReplicaProperties properties, TenantContext tenantContext) {
        this.entitlements = entitlements;
        this.properties = properties;
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
                && !entitlements.enabledFor(tenantContext, properties.appCode())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(PROBLEM_TYPE);
            String body = "{\"type\":\"app-not-enabled\",\"title\":\"This tenant has not enabled "
                    + properties.appCode() + "\",\"status\":403}";
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
