package dev.ngb.billing.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment configuration of the customer portal and the session it issues.
 *
 * @param enabled whether the portal and session endpoints are served at all
 * @param secureCookie whether the session cookie is marked {@code Secure}
 * @param sessionDefaultTtl the lifetime chosen when the caller names none
 * @param sessionMinTtl the shortest session the caller may ask for
 * @param sessionMaxTtl the longest session the caller may ask for
 */
@ConfigurationProperties(prefix = "billing.portal")
public record PortalProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean secureCookie,
        @DefaultValue("PT1H") Duration sessionDefaultTtl,
        @DefaultValue("PT1M") Duration sessionMinTtl,
        @DefaultValue("P1D") Duration sessionMaxTtl) {}
