package dev.ngb.crm.customers.api.customer;

import java.util.Objects;
import java.util.UUID;

/**
 * A customer as another context names it: crm's own opaque id, the reference the Tenant chose for it, and whether the
 * row is the anonymous marker an erasure left behind.
 *
 * <p>It carries no attribute of the person. A context that records something about a customer needs to point at the
 * customer, not to read it, so the phone, the email and the display name never leave the customers context.
 *
 * @param customerId crm's own id for the customer
 * @param externalRef the Tenant's reference for the customer, unique per tenant
 * @param erased whether the person's data was erased, in which case nothing may be recorded against the customer
 */
public record CustomerRef(UUID customerId, String externalRef, boolean erased) {

    public CustomerRef {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(externalRef, "externalRef");
    }
}
