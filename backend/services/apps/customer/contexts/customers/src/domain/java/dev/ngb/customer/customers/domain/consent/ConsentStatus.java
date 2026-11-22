package dev.ngb.customer.customers.domain.consent;

/**
 * What a consent record says about its topic. There are only two answers, and a change is always a new record: the
 * history of a customer's agreements is the sequence of these, never an edit of one of them.
 */
public enum ConsentStatus {

    /** The customer agreed to the topic. */
    GRANTED,

    /** The customer withdrew the agreement it had given. */
    REVOKED
}
