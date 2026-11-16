package dev.ngb.crm.customers.api.customer;

import java.util.UUID;

/**
 * The customers context's contract for a context that stores something about a customer: when the customer's personal
 * data is erased, the caller's part of it is erased with it, in the customer's erasure transaction.
 *
 * <p>It exists so an erasure reaches every table that can hold a person without the customers context reaching into
 * another context's data: the context that owns the rows is the one that clears them, and the wiring layer hands it to
 * the erasure. A context that keeps nothing about a customer implements nothing.
 */
public interface CustomerErasureParticipant {

    /**
     * Clears the personal data this context holds about the customer. The customer row itself is the caller's, and a
     * row that only marks that something happened — a typed, timed activity, an order, a consent record — stays.
     *
     * @param customerId crm's own id for the customer whose data was erased
     */
    void erase(UUID customerId);
}
