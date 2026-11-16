package dev.ngb.attribution.boot.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.ngb.core.suite.web.SuiteEntitlements;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;

/**
 * Whether the current tenant may use attribution, answered on the public redirect path.
 *
 * <p>The entitlement is already checked by the filter for the authenticated surface, but the public paths skip that
 * filter on purpose: the redirect decides for itself and answers one constant {@code 404} when the Tenant has lost the
 * entitlement. Reading the replica on every click would put a database round trip back on the path the cache exists to
 * keep clean, so this is a port whose caching implementation is the one a deployment uses.
 */
@FunctionalInterface
public interface EntitlementGate {

    /** Whether the tenant bound to this request currently has the app enabled. */
    boolean enabled();

    /**
     * The caching implementation: per tenant, for the same short window as a link.
     *
     * <p>Removing a Tenant's entitlement is the platform-side abuse lever, so its effect is bounded by this window and
     * documented as such.
     */
    static EntitlementGate cached(SuiteEntitlements entitlements, TenantContext tenantContext, Duration ttl) {
        Cache<String, Boolean> cache =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
        return () -> {
            String tenant = tenantContext.requireTenant().value().toString();
            return cache.get(tenant, ignored -> entitlements.enabledFor("attribution"));
        };
    }
}
