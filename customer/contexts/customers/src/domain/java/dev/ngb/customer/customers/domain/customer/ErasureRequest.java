package dev.ngb.customer.customers.domain.customer;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * One erasure this tenant asked for, kept so a retried call replays instead of erasing a second time.
 *
 * <p>The record holds the hash of the reference the caller sent, never the reference: the reference is what ties a
 * customer to a person in the Tenant's own system, and cutting that tie is what the erasure was for. The hash is the
 * lookup key of a retry, and it cannot be turned back into the reference.
 */
public record ErasureRequest(TenantId tenantId, String refHash, CustomerId customerId, Instant erasedAt) {

    public ErasureRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(refHash, "refHash");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(erasedAt, "erasedAt");
    }
}
