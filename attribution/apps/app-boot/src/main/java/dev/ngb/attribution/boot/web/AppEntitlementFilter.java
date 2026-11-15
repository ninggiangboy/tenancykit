package dev.ngb.attribution.boot.web;

import dev.ngb.core.suite.web.SuiteEntitlementFilter;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.attribution.boot.config.SuiteProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rejects a tenant that has not enabled attribution, immediately after the hostname filter bound the scope.
 *
 * <p>Losing the entitlement is the platform-side abuse lever: once the replica applies the event, every {@code /go/*}
 * of that tenant answers {@code 404} within the link cache's TTL on each instance.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AppEntitlementFilter extends SuiteEntitlementFilter {

    AppEntitlementFilter(SuiteEntitlements entitlements, SuiteProperties suite) {
        super(entitlements, suite.appCode());
    }

    /**
     * The public redirect and collection paths must still resolve when the entitlement filter would otherwise refuse
     * them: the redirect decides for itself and answers one constant {@code 404} for a tenant without the entitlement.
     * The OAuth callback is on the Platform host and reads its tenant from a signed state.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/go/") || uri.startsWith("/t/") || uri.startsWith("/attribution/t/");
    }
}
