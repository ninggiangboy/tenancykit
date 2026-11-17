package dev.ngb.customer.customers.application.customer.port;

import dev.ngb.customer.customers.domain.customer.Customer;
import java.util.Optional;
import java.util.UUID;

/** How the write side stores customers. The Tenant's reference is the key; the id is customer's own. */
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

    /**
     * Stores a customer the caller erased: every attribute, the field values and the tags are cleared, the status and
     * the reference become the anonymous marker, and the version the caller holds rose with it.
     */
    void erase(Customer customer);

    Optional<Customer> findByExternalRef(String externalRef);

    /** The customer with customer's own id, for the contexts that store the id and report the reference. */
    Optional<Customer> findById(UUID id);

    /** The customer with its row locked until the transaction ends, so two callers of one reference change it in turn. */
    Optional<Customer> lockByExternalRef(String externalRef);
}
