package dev.ngb.billing.customer.api.lookup;

import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.core.error.NotFoundException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one customer-visibility rule the money path reads by: a Tenant System sees only the customers it mapped, and
 * every other caller — a null system — sees the whole tenant. A row a Tenant System cannot see is answered exactly
 * like a row that does not exist, with the error the reading context promises, so a leak cannot come from a copy of
 * this check that drifted.
 *
 * <p>It lives in the api package because the contexts that read a wallet, a reservation, a charge or a payment may
 * reach the customer context through that package alone.
 */
public record CustomerVisibility(CustomerLookup customers) {

    public CustomerVisibility {
        Objects.requireNonNull(customers, "customers");
    }

    /**
     * @param customerId the customer the row the caller asked for belongs to
     * @param tenantSystem the acting Tenant System, or null for a Tenant User
     * @param notFound the error the caller promises for a row it cannot see, normally the same one as for a row
     *     that does not exist
     * @throws NotFoundException from {@code notFound} when the acting Tenant System did not map the customer
     */
    public void requireVisible(CustomerId customerId, UUID tenantSystem, Supplier<NotFoundException> notFound) {
        if (tenantSystem != null && customers.findFor(customerId, tenantSystem).isEmpty()) {
            throw notFound.get();
        }
    }
}
