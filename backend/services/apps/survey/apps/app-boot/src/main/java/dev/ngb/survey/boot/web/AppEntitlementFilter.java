package dev.ngb.survey.boot.web;

import dev.ngb.core.suite.web.SuiteEntitlementFilter;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.survey.boot.config.SuiteProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Rejects a tenant that has not enabled survey, immediately after the hostname filter bound the scope. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AppEntitlementFilter extends SuiteEntitlementFilter {

    AppEntitlementFilter(SuiteEntitlements entitlements, SuiteProperties suite) {
        super(entitlements, suite.appCode());
    }

    /** A respondent must still reach a survey from a tenant host the entitlement filter would otherwise refuse. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/p/") || uri.startsWith("/survey/p/");
    }
}
