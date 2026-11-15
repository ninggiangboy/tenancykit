package dev.ngb.notification.boot.web;

import dev.ngb.core.suite.web.SuiteEntitlementFilter;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.notification.boot.config.SuiteProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Rejects a tenant that has not enabled notification, immediately after the hostname filter bound the scope. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AppEntitlementFilter extends SuiteEntitlementFilter {

    AppEntitlementFilter(SuiteEntitlements entitlements, SuiteProperties suite) {
        super(entitlements, suite.appCode());
    }
}
