package dev.ngb.customer.customers.domain.customer;

/**
 * A customer's lifecycle. {@code ERASED} is the anonymous marker a row becomes once the person's data was erased:
 * the row stays, because the orders and the consent history point at it, and it holds no personal data any more.
 */
public enum CustomerStatus {
    ACTIVE,
    ERASED
}
