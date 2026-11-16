package dev.ngb.crm.customers.application.consent.port;

import dev.ngb.crm.customers.domain.consent.ConsentEvent;
import dev.ngb.crm.customers.domain.customer.CustomerId;
import java.util.Optional;

/**
 * How the write side appends consent records. The table behind it is append-only for the application: there is no
 * update and no delete, because the history of an agreement is evidence. The one exception is the erasure, which
 * clears the two free-text columns a record carries and leaves the record itself — the topic, the state and the
 * instants — untouched.
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

    /**
     * Clears the free text of every record of one customer: the source and the evidence reference the Tenant sent can
     * hold the person's data, while the records themselves stay as the proof that the agreement existed.
     *
     * @return how many records lost their free text
     */
    int clearPersonalData(CustomerId customerId);
}
