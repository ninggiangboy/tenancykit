package dev.ngb.tenant.billing.adapter.client.feign;

import dev.ngb.core.feign.FeignFailures;
import dev.ngb.tenant.shared.error.UnauthorizedException;
import dev.ngb.tenant.shared.error.UpstreamUnavailableException;

/** The tenant's own error types for a refused credential and an unreachable Billing. */
public final class TenantFeignFailures implements FeignFailures {

    public static final TenantFeignFailures INSTANCE = new TenantFeignFailures();

    private TenantFeignFailures() {}

    @Override
    public RuntimeException unavailable(String message, Throwable cause) {
        return new UpstreamUnavailableException(message, cause);
    }

    @Override
    public RuntimeException refused(String message) {
        return new UnauthorizedException(message);
    }
}
