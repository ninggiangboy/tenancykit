package dev.ngb.core.replica.web;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Builds the request scope once, from the hostname alone. A tenant id sent by the client in a header, path or body is
 * never consulted; an unknown host gets one generic answer. Health probes reach the pod by IP, so they run on the
 * platform scope.
 */
public class ReplicaTenantFilter extends OncePerRequestFilter {

    private final ReplicaHostResolver hosts;
    private final TenantContext tenantContext;

    public ReplicaTenantFilter(ReplicaHostResolver hosts, TenantContext tenantContext) {
        this.hosts = hosts;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
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
