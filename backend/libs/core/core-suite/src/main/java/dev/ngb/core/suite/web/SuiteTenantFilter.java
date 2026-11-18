package dev.ngb.core.suite.web;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Builds the request scope once, from the hostname alone. A tenant id sent by the client in a header, path or body is
 * never consulted; an unknown host gets one generic answer, in the shape {@link SuiteProblems} writes for both suite
 * filters. Health probes reach the pod by IP, so they run on the platform scope.
 */
public class SuiteTenantFilter extends OncePerRequestFilter {

    private final SuiteHostResolver hosts;
    private final TenantContext tenantContext;

    public SuiteTenantFilter(SuiteHostResolver hosts, TenantContext tenantContext) {
        this.hosts = hosts;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RequestScope> scope = SuiteProblems.isHealthProbe(request)
                ? Optional.of(RequestScope.platform())
                : tenantContext.callAs(RequestScope.platform(), () -> hosts.resolve(request.getServerName()));
        if (scope.isEmpty()) {
            SuiteProblems.write(
                    response, HttpServletResponse.SC_NOT_FOUND, SuiteProblems.NOT_FOUND, "This hostname belongs to no tenant.");
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
