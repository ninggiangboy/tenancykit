package dev.ngb.loyalty.boot.web;

import dev.ngb.core.suite.web.SuiteEntitlementFilter;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.loyalty.boot.config.SuiteProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rejects a tenant that has not enabled loyalty, immediately after the hostname filter bound the scope.
 *
 * <p>loyalty has no public path, so no request skips this filter: a Tenant without the entitlement sees nothing of the
 * app, including its webhook endpoints.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AppEntitlementFilter extends SuiteEntitlementFilter {

    AppEntitlementFilter(SuiteEntitlements entitlements, SuiteProperties suite) {
        super(entitlements, suite.appCode());
    }
}
