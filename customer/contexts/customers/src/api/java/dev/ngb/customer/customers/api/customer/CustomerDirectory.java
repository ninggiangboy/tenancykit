package dev.ngb.customer.customers.api.customer;

import java.util.Optional;
import java.util.UUID;

/**
 * The customers context's contract for another context that records something about a customer: resolve a reference,
 * and create a customer that did not exist yet.
 *
 * <p>Both methods read and write the customers context's own tables; nothing here exposes a domain type or a query.
 * A creation announces the customer exactly as {@code PUT /customers/{externalRef}} does — because it is the same
 * use case — so a customer that a first order brought into existence is delivered to the Tenant's endpoints too.
 */
public interface CustomerDirectory {

    /**
     * One customer by the reference the Tenant chose.
     *
     * @return the customer, or empty when this tenant has none with that reference
     */
    Optional<CustomerRef> findByExternalRef(String externalRef);

    /**
     * One customer by customer's own id, for a caller that stores the id and reports the reference.
     *
     * @return the customer, or empty when this tenant has none with that id
     */
    Optional<CustomerRef> findByCustomerId(UUID customerId);

    /**
     * Creates the customer with this reference and nothing else, in the caller's transaction, and announces it.
     *
     * <p>A reference that already exists is not changed: the stored customer is what the caller gets, which makes the
     * call safe to repeat. The creation is part of the caller's transaction, so a refused order leaves no customer
     * behind and an accepted one commits the customer, its announcement and the order together.
     *
     * @return the customer, or empty when the reference belongs to an erased customer and so may not be reused
     */
    Optional<CustomerRef> createIfAbsent(String externalRef);
}
