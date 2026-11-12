package dev.ngb.billing.boot.web;

import dev.ngb.core.replica.web.ReplicaEntitlements;
import dev.ngb.core.replica.web.ReplicaHostResolver;
import dev.ngb.core.replica.web.ReplicaTenantFilter;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Builds the request scope once, from the hostname alone, using the replicated {@code tenant_domains}. A tenant id
 * sent by the client in a header, path or body is never consulted; an unknown host gets one generic answer. Billing
 * no longer serves the platform hostname through this filter's resolution: the platform pseudo-tenant stays in the
 * replica for jobs, but the platform host resolves here only so a request can be refused with 404.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantContextFilter extends OncePerRequestFilter {

    private final ReplicaHostResolver hosts;
    private final TenantContext tenantContext;

    TenantContextFilter(ReplicaHostResolver hosts, TenantContext tenantContext) {
        this.hosts = hosts;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Health probes reach the pod by IP or an internal name, so they cannot be tied to a tenant hostname.
        Optional<RequestScope> scope = request.getRequestURI().startsWith("/actuator/health")
                ? Optional.of(RequestScope.platform())
                : tenantContext.callAs(RequestScope.platform(), () -> hosts.resolve(request.getServerName()));
        if (scope.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            response.getOutputStream().write("{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            tenantContext.callAs(scope.get(), () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
                return null;
            });
        } catch (FilterFailure failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) failure.getCause();
        }
    }

    private static final class FilterFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
