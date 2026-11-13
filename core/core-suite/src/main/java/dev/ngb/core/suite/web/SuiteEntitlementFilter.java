package dev.ngb.core.suite.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects a tenant that has not enabled this app. It runs after {@link SuiteTenantFilter} bound the scope, so the
 * entitlement is read for exactly the tenant the hostname resolved to, and it answers with the same problem body as
 * that filter. Platform scope is always allowed; health probes bypass the check entirely.
 */
public class SuiteEntitlementFilter extends OncePerRequestFilter {

    private final SuiteEntitlements entitlements;
    private final String appCode;

    public SuiteEntitlementFilter(SuiteEntitlements entitlements, String appCode) {
        this.entitlements = entitlements;
        this.appCode = appCode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SuiteProblems.isHealthProbe(request) && !entitlements.enabledFor(appCode)) {
            SuiteProblems.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    SuiteProblems.APP_NOT_ENABLED,
                    "This tenant has not enabled " + appCode + ".");
            return;
        }
        chain.doFilter(request, response);
    }
}
