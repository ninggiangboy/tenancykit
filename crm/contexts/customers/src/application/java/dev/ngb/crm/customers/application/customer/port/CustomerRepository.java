package dev.ngb.crm.customers.application.customer.port;

import dev.ngb.crm.customers.domain.customer.Customer;
import java.util.Optional;

/** How the write side stores customers. The Tenant's reference is the key; the id is crm's own. */
public interface CustomerRepository {

    /**
     * Inserts a customer the caller believes does not exist yet.
     *
     * @return false when another caller stored the same reference first, which the caller answers by reading that row
     *     back and applying its body to it — the race a concurrent first-time upsert of one reference loses
     */
    boolean insertIfAbsent(Customer customer);

    /** Stores the current state of a customer the caller holds a lock on, guarded by its version. */
    void update(Customer customer);

    Optional<Customer> findByExternalRef(String externalRef);

    /** The customer with its row locked until the transaction ends, so two callers of one reference change it in turn. */
    Optional<Customer> lockByExternalRef(String externalRef);
}
