package dev.ngb.crm.customers.application.consent.port;

import dev.ngb.crm.customers.domain.consent.ConsentEvent;
import dev.ngb.crm.customers.domain.customer.CustomerId;
import java.util.Optional;

/**
 * How the write side appends consent records. The table behind it is append-only: there is no update and no delete,
 * because the history of an agreement is evidence.
 */
public interface ConsentEventRepository {

    /**
     * Appends a record, unless the customer's reference already has one.
     *
     * @return false when another caller appended the same reference first, which the caller answers by replaying it
     */
    boolean insertIfAbsent(ConsentEvent event);

    /** The record the customer's own reference already names, if it was appended before. */
    Optional<ConsentEvent> findByConsentRef(CustomerId customerId, String consentRef);
}
