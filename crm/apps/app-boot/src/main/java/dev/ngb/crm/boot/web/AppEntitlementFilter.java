package dev.ngb.crm.boot.web;

import dev.ngb.core.suite.web.SuiteEntitlementFilter;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.crm.boot.config.SuiteProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rejects a tenant that has not enabled crm, immediately after the hostname filter bound the scope.
 *
 * <p>crm has no public surface, so no path is exempt: an entitlement is checked before any endpoint of every runtime.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AppEntitlementFilter extends SuiteEntitlementFilter {

    AppEntitlementFilter(SuiteEntitlements entitlements, SuiteProperties suite) {
        super(entitlements, suite.appCode());
    }
}
