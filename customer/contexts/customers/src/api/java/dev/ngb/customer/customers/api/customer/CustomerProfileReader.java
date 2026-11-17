package dev.ngb.customer.customers.api.customer;

import java.util.Optional;

/**
 * The customers context's contract for a context that shows one customer whole: its attributes, its field values, its
 * tags and the current state of its consents.
 *
 * <p>It is a read of one customer by the reference the Tenant chose, and it writes nothing, so a caller cannot change
 * a customer by reading it.
 */
public interface CustomerProfileReader {

    /**
     * One customer with its field values, its tags and the current status of every consent topic it has a record for.
     *
     * @return the customer, or empty when this tenant has none with that reference
     */
    Optional<CustomerProfile> findByExternalRef(String externalRef);
}
